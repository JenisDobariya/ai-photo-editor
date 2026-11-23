package com.photo_editor.my_photo_editor.ui.textlens

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * One recognised word, in flat reading order.
 * [box] is in bitmap pixels of [TextLensUiState.bitmap]; [cornerPoints] (also bitmap px) are only
 * kept for reference since rotated text is drawn with its axis-aligned [box].
 */
data class Word(
    val index: Int,
    val text: String,
    val box: RectF,
    val cornerPoints: List<PointF>?,
    val lineId: Int,
    val blockId: Int
)

/** A phone number, email address or link found in the photo, offered as an action chip. */
data class SmartItem(val kind: Kind, val value: String) {
    enum class Kind { PHONE, EMAIL, URL }
}

enum class LensStatus { LOADING_PHOTO, READING, READY, NO_TEXT, ERROR }

data class TextLensUiState(
    val status: LensStatus = LensStatus.LOADING_PHOTO,
    /** The photo the words were read from (null until decoded). */
    val bitmap: Bitmap? = null,
    val words: List<Word> = emptyList(),
    val fullText: String = "",
    val smartItems: List<SmartItem> = emptyList(),
    /** Shown for NO_TEXT / ERROR. */
    val message: String? = null,
    val canRetry: Boolean = false
)

/** Inclusive reading-order word range. */
data class TextSelection(val start: Int, val end: Int) {
    val count: Int get() = end - start + 1
}

sealed class TextLensEvent {
    data class Message(val text: String) : TextLensEvent()
    data class Share(val text: String) : TextLensEvent()
}

/** "Copy text" screen: reads every word in a photo on the phone (ML Kit) and tracks the selection. */
class TextLensViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TextLensUiState())
    val state: StateFlow<TextLensUiState> = _state.asStateFlow()

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val _events = MutableSharedFlow<TextLensEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TextLensEvent> = _events.asSharedFlow()

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var loadedUri: Uri? = null
    private var job: Job? = null
    private var bitmap: Bitmap? = null
    /** The OCR task still reading [bitmap]; the bitmap must not be recycled before it finishes. */
    private var pendingTask: Task<Text>? = null

    fun start(uri: Uri) {
        if (uri == loadedUri) return
        loadedUri = uri
        load(uri)
    }

    fun retry() {
        loadedUri?.let { load(it) }
    }

    private fun load(uri: Uri) {
        job?.cancel()
        _selection.value = null
        job = viewModelScope.launch {
            var bmp = bitmap
            if (bmp == null || bmp.isRecycled) {
                _state.value = TextLensUiState(status = LensStatus.LOADING_PHOTO)
                bmp = withContext(Dispatchers.IO) {
                    BitmapLoader.decodeUpright(getApplication(), uri, MAX_DIMENSION)
                }
                if (bmp == null) {
                    _state.value = TextLensUiState(status = LensStatus.ERROR, message = "Couldn't open this photo")
                    return@launch
                }
                bitmap = bmp
            }
            _state.value = TextLensUiState(status = LensStatus.READING, bitmap = bmp)
            _state.value = try {
                val task = recognizer.process(InputImage.fromBitmap(bmp, 0))
                pendingTask = task
                val result = task.await()
                val parsed = withContext(Dispatchers.Default) { parse(result) }
                AnalyticsManager.logEvent("TextLens_words_${if (parsed.words.isEmpty()) "none" else "found"}")
                if (parsed.words.isEmpty()) {
                    TextLensUiState(status = LensStatus.NO_TEXT, bitmap = bmp, message = "No text found in this photo")
                } else {
                    TextLensUiState(
                        status = LensStatus.READY,
                        bitmap = bmp,
                        words = parsed.words,
                        fullText = parsed.fullText,
                        smartItems = parsed.smartItems
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MlKitException) {
                if (e.errorCode == MlKitException.UNAVAILABLE) {
                    TextLensUiState(
                        status = LensStatus.ERROR, bitmap = bmp, canRetry = true,
                        message = "Text reader is still downloading. Try again in a minute."
                    )
                } else {
                    TextLensUiState(status = LensStatus.ERROR, bitmap = bmp, canRetry = true, message = "Couldn't read text from this photo")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TextLensUiState(status = LensStatus.ERROR, bitmap = bmp, canRetry = true, message = "Couldn't read text from this photo")
            }
        }
    }

    // ---- Selection ----

    /** From the view; (-1, -1) clears. */
    fun select(start: Int, end: Int) {
        val count = _state.value.words.size
        _selection.value = if (start < 0 || end < start || end >= count) null else TextSelection(start, end)
    }

    fun selectAll() {
        val count = _state.value.words.size
        if (count > 0) _selection.value = TextSelection(0, count - 1)
    }

    fun clearSelection() {
        _selection.value = null
    }

    fun selectedText(): String? {
        val sel = _selection.value ?: return null
        val words = _state.value.words
        if (sel.end >= words.size) return null
        return joinWords(words, sel.start, sel.end)
    }

    fun copySelection() {
        val text = selectedText() ?: return
        AnalyticsManager.logEvent("TextLens_copy")
        copyToClipboard(text)
    }

    fun copyAll() {
        val text = _state.value.fullText.takeIf { it.isNotEmpty() } ?: return
        AnalyticsManager.logEvent("TextLens_copy_all")
        copyToClipboard(text)
    }

    fun shareSelection() {
        val text = selectedText() ?: return
        AnalyticsManager.logEvent("TextLens_share")
        _events.tryEmit(TextLensEvent.Share(text))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Text from photo", text))
        // Android 13+ shows its own clipboard confirmation; don't double up.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _events.tryEmit(TextLensEvent.Message("Copied"))
        }
    }

    // ---- Parsing ----

    private class Parsed(val words: List<Word>, val fullText: String, val smartItems: List<SmartItem>)

    /** Flattens ML Kit blocks → lines → elements into reading-ordered words (ML Kit's own order). */
    private fun parse(text: Text): Parsed {
        val words = ArrayList<Word>()
        var lineId = 0
        text.textBlocks.forEachIndexed { blockId, block ->
            for (line in block.lines) {
                var added = false
                for (element in line.elements) {
                    val value = element.text.trim()
                    val box = element.boundingBox ?: continue
                    if (value.isEmpty() || box.isEmpty) continue
                    words += Word(
                        index = words.size,
                        text = value,
                        box = RectF(box),
                        cornerPoints = element.cornerPoints?.map { PointF(it.x.toFloat(), it.y.toFloat()) },
                        lineId = lineId,
                        blockId = blockId
                    )
                    added = true
                }
                if (added) lineId++
            }
        }
        val fullText = if (words.isEmpty()) "" else joinWords(words, 0, words.lastIndex)
        return Parsed(words, fullText, detectSmartItems(fullText))
    }

    /** Words in a line are joined by spaces, lines by a newline, blocks by a blank line. */
    private fun joinWords(words: List<Word>, start: Int, end: Int): String {
        val sb = StringBuilder()
        for (i in start..end) {
            val word = words[i]
            if (i > start) {
                val prev = words[i - 1]
                sb.append(
                    when {
                        prev.blockId != word.blockId -> "\n\n"
                        prev.lineId != word.lineId -> "\n"
                        else -> " "
                    }
                )
            }
            sb.append(word.text)
        }
        return sb.toString()
    }

    private fun detectSmartItems(text: String): List<SmartItem> {
        if (text.isEmpty()) return emptyList()
        val items = LinkedHashSet<SmartItem>()

        val emailRanges = ArrayList<IntRange>()
        val emails = Patterns.EMAIL_ADDRESS.matcher(text)
        while (emails.find()) {
            items += SmartItem(SmartItem.Kind.EMAIL, emails.group())
            emailRanges += emails.start() until emails.end()
        }

        val urls = Patterns.WEB_URL.matcher(text)
        while (urls.find()) {
            // WEB_URL also matches the domain part of an email address.
            val s = urls.start()
            val e = urls.end()
            if (emailRanges.any { s <= it.last && e > it.first }) continue
            val url = urls.group().trimEnd('.', ',', ';', ':', ')', '!', '?')
            if (url.length >= 4 && url.contains('.') && looksLikeLink(url)) items += SmartItem(SmartItem.Kind.URL, url)
        }

        val phones = Patterns.PHONE.matcher(text)
        while (phones.find()) {
            val value = phones.group().trim()
            // PHONE is loose (dates, prices…); real numbers have 7–15 digits.
            if (value.count { it.isDigit() } in 7..15) items += SmartItem(SmartItem.Kind.PHONE, value)
        }
        return items.take(MAX_SMART_ITEMS)
    }

    override fun onCleared() {
        super.onCleared()
        val bmp = bitmap
        bitmap = null
        val task = pendingTask
        // ML Kit may still be reading the pixels; recycle only once it's done with them.
        if (task != null && !task.isComplete) {
            task.addOnCompleteListener { bmp?.recycle() }
        } else {
            bmp?.recycle()
        }
        recognizer.close()
    }

    /**
     * OCR text like "S.P.sGae" also matches WEB_URL. Only offer "Open" for things that are
     * clearly links: a scheme, "www.", or a host ending in a common real domain.
     */
    private fun looksLikeLink(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) return true
        val host = lower.substringBefore('/').substringBefore('?').substringBefore(':')
        val tld = host.substringAfterLast('.', "")
        return tld in COMMON_TLDS && host.substringBeforeLast('.').length >= 2
    }

    private companion object {
        val COMMON_TLDS = setOf(
            "com", "in", "org", "net", "io", "co", "app", "dev", "ai", "info", "biz", "edu",
            "gov", "me", "tv", "uk", "us", "ae", "au", "ca", "de", "fr", "jp", "shop", "store", "site", "online"
        )
        /** Long edge: sharp enough for small print, small enough to stay fast and memory-safe. */
        const val MAX_DIMENSION = 2400
        const val MAX_SMART_ITEMS = 6
    }
}
