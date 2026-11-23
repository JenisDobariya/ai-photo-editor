package com.photo_editor.my_photo_editor

import android.content.ActivityNotFoundException
import com.photo_editor.my_photo_editor.utils.appColor
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photo_editor.my_photo_editor.databinding.ActivityTextLensBinding
import com.photo_editor.my_photo_editor.ui.textlens.LensStatus
import com.photo_editor.my_photo_editor.ui.textlens.SmartItem
import com.photo_editor.my_photo_editor.ui.textlens.TextLensEvent
import com.photo_editor.my_photo_editor.ui.textlens.TextLensUiState
import com.photo_editor.my_photo_editor.ui.textlens.TextLensViewModel
import com.photo_editor.my_photo_editor.ui.textlens.TextSelection
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.launch

/**
 * "Copy text" (Google-Lens style): the photo full screen with every recognised word highlighted;
 * tap or drag over words to select exactly the text you want, then copy or share it.
 * Text recognition runs on the phone (ML Kit); nothing is uploaded.
 */
class TextLensActivity : BaseActivity() {

    companion object {
        /** Same key the rest of the app uses for a photo uri string. */
        private const val EXTRA_IMAGE_URI = "IMAGE_URI"

        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, TextLensActivity::class.java).putExtra(EXTRA_IMAGE_URI, uri.toString())
    }

    override fun getScreenTag(): String = "TextLens"

    // Photos look best on dark: this screen stays dark in every theme.
    override val alwaysDark = true

    private lateinit var binding: ActivityTextLensBinding
    private val viewModel: TextLensViewModel by viewModels()

    private var renderedChips: List<SmartItem>? = null
    private var renderedFullText: String? = null
    private var allTextShown = false

    private val closeAllTextOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showAllText(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextLensBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupInsets()
        setupUi()
        observe()
        viewModel.start(Uri.parse(uriString))
    }

    private fun setupInsets() {
        val panelPadding = binding.bottomPanel.paddingBottom
        val sheetPadding = binding.allTextPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomPanel) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = panelPadding + nav.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.allTextPanel) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = sheetPadding + nav.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.bottomPanel)
        ViewCompat.requestApplyInsets(binding.allTextPanel)

        // Fit the photo between the header and the bottom bar (both overlay the photo view).
        val updateFit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val view = binding.selectionView
            val top = (binding.topBar.bottom - view.top).coerceAtLeast(0)
            val bottom = (view.bottom - binding.bottomPanel.top).coerceAtLeast(0)
            view.setFitInsets(top.toFloat(), bottom.toFloat())
        }
        binding.topBar.addOnLayoutChangeListener(updateFit)
        binding.bottomPanel.addOnLayoutChangeListener(updateFit)
    }

    private fun setupUi() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("TextLens_btnBack_click")
            finish()
        }
        onBackPressedDispatcher.addCallback(this, closeAllTextOnBack)

        binding.selectionView.onSelectionChanged = { start, end -> viewModel.select(start, end) }

        binding.btnCopy.setOnClickListener { viewModel.copySelection() }
        binding.btnShare.setOnClickListener { viewModel.shareSelection() }
        binding.btnSelectAll.setOnClickListener {
            AnalyticsManager.logEvent("TextLens_select_all")
            viewModel.selectAll()
        }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
        binding.btnAllText.setOnClickListener {
            AnalyticsManager.logEvent("TextLens_all_text")
            showAllText(!allTextShown)
        }
        binding.btnCopyAll.setOnClickListener { viewModel.copyAll() }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.selection.collect { renderSelection(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    private fun render(state: TextLensUiState) {
        val b = binding
        b.selectionView.setImage(state.bitmap)
        b.selectionView.setWords(state.words)
        val busy = state.status == LensStatus.LOADING_PHOTO || state.status == LensStatus.READING
        b.selectionView.setDimLevel(if (busy) 0.5f else 0.3f)

        // Centre card: loading spinner, or the no-text / error message.
        b.centerCard.isVisible = state.status != LensStatus.READY
        b.progress.isVisible = busy
        when (state.status) {
            LensStatus.LOADING_PHOTO, LensStatus.READING -> {
                b.tvCenterTitle.text = "Reading text…"
                b.tvCenterMessage.isVisible = false
            }
            LensStatus.NO_TEXT -> {
                b.tvCenterTitle.text = state.message ?: "No text found in this photo"
                b.tvCenterMessage.text = "Try a sharper photo with clear, printed text."
                b.tvCenterMessage.isVisible = true
            }
            LensStatus.ERROR -> {
                b.tvCenterTitle.text = state.message ?: "Couldn't read text from this photo"
                b.tvCenterMessage.isVisible = false
            }
            LensStatus.READY -> Unit
        }
        b.btnRetry.isVisible = state.status == LensStatus.ERROR && state.canRetry

        b.btnAllText.isEnabled = state.status == LensStatus.READY
        b.btnAllText.alpha = if (b.btnAllText.isEnabled) 1f else 0.4f
        if (state.status != LensStatus.READY) showAllText(false)
        if (renderedFullText != state.fullText) {
            renderedFullText = state.fullText
            b.tvAllText.text = state.fullText
        }

        renderChips(state.smartItems)
        renderBottomBar(state, viewModel.selection.value)
    }

    private fun renderSelection(selection: TextSelection?) {
        binding.selectionView.setSelection(selection?.start ?: -1, selection?.end ?: -1)
        renderBottomBar(viewModel.state.value, selection)
    }

    private fun renderBottomBar(state: TextLensUiState, selection: TextSelection?) {
        val b = binding
        val ready = state.status == LensStatus.READY
        val count = if (ready) selection?.count ?: 0 else 0
        val selected = count > 0
        val allSelected = selected && count == state.words.size

        b.tvStatus.text = when {
            selected -> if (count == 1) "1 word selected" else "$count words selected"
            ready -> "Tap or drag over text to select"
            state.status == LensStatus.NO_TEXT -> "Nothing to select"
            state.status == LensStatus.ERROR -> "Couldn't read text"
            else -> "Tap or drag over text to select"
        }
        b.tvStatus.setTextColor(
            this.appColor(if (selected) R.color.lumina_text_primary else R.color.lumina_text_secondary)
        )
        b.btnSelectAll.isVisible = ready && !allSelected
        b.btnShare.isVisible = selected
        b.btnCopy.isVisible = selected
    }

    /** Call / Email / Open link chips for numbers, addresses and links found in the photo. */
    private fun renderChips(items: List<SmartItem>) {
        if (items == renderedChips) return
        renderedChips = items
        val row = binding.chipsRow
        row.removeAllViews()
        binding.chipsScroll.isVisible = items.isNotEmpty()
        val density = resources.displayMetrics.density
        items.forEachIndexed { i, item ->
            val label = when (item.kind) {
                SmartItem.Kind.PHONE -> "Call ${item.value}"
                SmartItem.Kind.EMAIL -> "Email ${item.value}"
                SmartItem.Kind.URL -> "Open ${item.value}"
            }
            val chip = TextView(this).apply {
                text = label
                setTextColor(context.appColor(R.color.lumina_text_primary))
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (240 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setBackgroundResource(R.drawable.bg_chip_unselected)
                foreground = ContextCompat.getDrawable(context, resolveSelectableBackground())
                isClickable = true
                isFocusable = true
                setOnClickListener { openSmartItem(item) }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * density).toInt())
            if (i > 0) params.marginStart = (8 * density).toInt()
            row.addView(chip, params)
        }
    }

    private fun resolveSelectableBackground(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun openSmartItem(item: SmartItem) {
        AnalyticsManager.logEvent("TextLens_chip_${item.kind.name.lowercase()}")
        val intent = when (item.kind) {
            SmartItem.Kind.PHONE ->
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + item.value.filter { it.isDigit() || it == '+' }))
            SmartItem.Kind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + item.value))
            SmartItem.Kind.URL -> {
                val url = if (item.value.contains("://")) item.value else "https://${item.value}"
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAllText(show: Boolean) {
        if (show == allTextShown) return
        allTextShown = show
        closeAllTextOnBack.isEnabled = show
        binding.btnAllText.text = if (show) "Done" else "All text"
        val panel = binding.allTextPanel
        val offset = 48 * resources.displayMetrics.density
        panel.animate().cancel()
        if (show) {
            if (!panel.isVisible) {
                panel.alpha = 0f
                panel.translationY = offset
                panel.isVisible = true
            }
            panel.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            panel.animate().alpha(0f).translationY(offset).setDuration(180)
                .withEndAction { if (!allTextShown) panel.isVisible = false }
                .start()
        }
    }

    private fun handleEvent(event: TextLensEvent) {
        when (event) {
            is TextLensEvent.Message -> Toast.makeText(this, event.text, Toast.LENGTH_SHORT).show()
            is TextLensEvent.Share -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, event.text)
                startActivity(Intent.createChooser(send, "Share text"))
            }
        }
    }

    override fun onDestroy() {
        // The ViewModel owns (and recycles) the bitmap; make sure the view never draws it after that.
        if (::binding.isInitialized && !isChangingConfigurations) binding.selectionView.setImage(null)
        super.onDestroy()
    }
}
