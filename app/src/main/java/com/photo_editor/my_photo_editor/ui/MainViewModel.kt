package com.photo_editor.my_photo_editor.ui

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.model.AlbumSort
import com.photo_editor.my_photo_editor.model.GroupBy
import com.photo_editor.my_photo_editor.model.LibraryStats
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaFilter
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.Memory
import com.photo_editor.my_photo_editor.model.SortOrder
import com.photo_editor.my_photo_editor.model.ThingCollection
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import com.photo_editor.my_photo_editor.utils.GalleryPreferences
import com.photo_editor.my_photo_editor.utils.SmartLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val contentResolver = application.contentResolver
    private val prefs = GalleryPreferences(application)
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Debounce refreshes to avoid spamming the UI
            refreshJob?.cancel()
            refreshJob = viewModelScope.launch {
                delay(500) // Wait for 500ms of quiet before refreshing
                loadMedia()
            }
        }
    }

    init {
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun onCleared() {
        super.onCleared()
        contentResolver.unregisterContentObserver(observer)
    }

    private val _allMedia = MutableStateFlow<List<MediaFile>>(emptyList())
    val allMedia: StateFlow<List<MediaFile>> = _allMedia.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ------------------------------------------------------------------ view options

    private val _filter = MutableStateFlow(MediaFilter.ALL)
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(prefs.sortOrder)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _groupBy = MutableStateFlow(prefs.groupBy)
    val groupBy: StateFlow<GroupBy> = _groupBy.asStateFlow()

    private val _gridColumns = MutableStateFlow(prefs.gridColumns)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _albumSort = MutableStateFlow(prefs.albumSort)
    val albumSort: StateFlow<AlbumSort> = _albumSort.asStateFlow()

    private val _showMemories = MutableStateFlow(prefs.showMemories)
    val showMemories: StateFlow<Boolean> = _showMemories.asStateFlow()

    fun setFilter(filter: MediaFilter) { _filter.value = filter }
    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSortOrder(order: SortOrder) { prefs.sortOrder = order; _sortOrder.value = order }
    fun setGroupBy(groupBy: GroupBy) { prefs.groupBy = groupBy; _groupBy.value = groupBy }
    fun setAlbumSort(sort: AlbumSort) { prefs.albumSort = sort; _albumSort.value = sort }
    fun setShowMemories(show: Boolean) { prefs.showMemories = show; _showMemories.value = show }
    fun setGridColumns(columns: Int) {
        val c = columns.coerceIn(GalleryPreferences.MIN_COLUMNS, GalleryPreferences.MAX_COLUMNS)
        prefs.gridColumns = c
        _gridColumns.value = c
    }

    // ------------------------------------------------------------------ derived data

    private val favoriteUris = db.favoriteDao().getAllFavorites()
        .map { list -> list.map { it.uri }.toSet() }

    private val hiddenUris = db.hiddenDao().getAllHidden()
        .map { list -> list.map { it.uri }.toSet() }

    /** uri → on-device labels ("dog|grass|pet"), filled in the background by the smart index. */
    private val labelIndex: StateFlow<Map<String, String>> = SmartIndexRepository.observe(application)
        .map { list ->
            val map = HashMap<String, String>(list.size)
            list.forEach { if (!it.labels.isNullOrEmpty()) map[it.uri] = it.labels }
            map as Map<String, String>
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Progress of the background photo analysis (for "Analysing photos…" hints). */
    val indexProgress: StateFlow<SmartIndexRepository.Progress> = SmartIndexRepository.progress

    /**
     * The library as the user should see it: hidden (Locked Folder) items removed and
     * favorite flags kept in sync with the database, so changes made in the viewer
     * show up instantly without a full reload.
     */
    val libraryMedia: StateFlow<List<MediaFile>> =
        combine(_allMedia, favoriteUris, hiddenUris) { media, favorites, hidden ->
            media.asSequence()
                .filterNot { hidden.contains(it.uri.toString()) }
                .map { file ->
                    val fav = favorites.contains(file.uri.toString())
                    if (file.isFavorite == fav) file else file.copy(isFavorite = fav)
                }
                .toList()
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Library after applying the active filter chip, search text and sort order. */
    val visibleMedia: StateFlow<List<MediaFile>> =
        combine(
            libraryMedia,
            _filter,
            _searchQuery.debounce { if (it.isEmpty()) 0L else 250L },
            _sortOrder,
            labelIndex
        ) { media, filter, query, sort, labels ->
            val filtered = media.asSequence().filter { file ->
                when (filter) {
                    MediaFilter.ALL -> true
                    MediaFilter.PHOTOS -> file.type == MediaType.IMAGE
                    MediaFilter.VIDEOS -> file.type == MediaType.VIDEO
                    MediaFilter.FAVORITES -> file.isFavorite
                    MediaFilter.SCREENSHOTS -> file.isScreenshot
                }
            }.let { seq -> if (query.isBlank()) seq else seq.filter(searchMatcher(query, labels)) }
                .toList()
            sortMedia(filtered, sort)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * "Things" albums (Dogs, Food, Beach…) from on-device labels. A photo counts for its three
     * strongest labels; only labels with enough photos become an album, so results stay clean.
     */
    val things: StateFlow<List<ThingCollection>> =
        combine(libraryMedia, labelIndex) { media, labels ->
            if (labels.isEmpty()) return@combine emptyList()
            val byLabel = LinkedHashMap<String, MutableList<MediaFile>>()
            media.forEach { file ->
                if (file.type != MediaType.IMAGE) return@forEach
                SmartLabels.split(labels[file.uri.toString()]).take(3).forEach { label ->
                    if (SmartLabels.isAlbumWorthy(label)) byLabel.getOrPut(label) { mutableListOf() }.add(file)
                }
            }
            byLabel.entries
                .filter { it.value.size >= MIN_THING_PHOTOS }
                .sortedByDescending { it.value.size }
                .take(MAX_THINGS)
                .map { (label, files) ->
                    ThingCollection(
                        label = label,
                        title = SmartLabels.title(label),
                        cover = files.first().uri, // library is newest first
                        uris = files.map { it.uri }
                    )
                }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** "On this day" memories: photos taken around today's date in previous years. */
    val memories: StateFlow<List<Memory>> = libraryMedia
        .map { buildMemories(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val libraryStats: StateFlow<LibraryStats> = libraryMedia
        .map { media ->
            LibraryStats(
                photoCount = media.count { it.type == MediaType.IMAGE },
                videoCount = media.count { it.type == MediaType.VIDEO },
                albumCount = media.map { it.album }.distinct().size,
                favoriteCount = media.count { it.isFavorite },
                totalBytes = media.sumOf { it.size }
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryStats())

    val isFilterActive: Boolean
        get() = _filter.value != MediaFilter.ALL || _searchQuery.value.isNotBlank() ||
                _sortOrder.value != SortOrder.DATE_DESC

    private fun sortMedia(list: List<MediaFile>, sort: SortOrder): List<MediaFile> = when (sort) {
        SortOrder.DATE_DESC -> list // already newest first from loadMedia()
        SortOrder.DATE_ASC -> list.asReversed().toList()
        SortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
        SortOrder.SIZE_DESC -> list.sortedByDescending { it.size }
    }

    /**
     * Matches file name, album, type keywords ("video", "photo", "screenshot", "favorite")
     * and the formatted date ("march", "2023", "12 jan").
     */
    private fun searchMatcher(rawQuery: String, labels: Map<String, String>): (MediaFile) -> Boolean {
        val terms = rawQuery.lowercase(Locale.getDefault()).split(" ").filter { it.isNotBlank() }
        val dateFormat = SimpleDateFormat("d MMMM yyyy EEEE", Locale.getDefault())
        val dateCache = HashMap<Long, String>()
        return { file ->
            val day = file.dateAdded / 86_400L
            val dateText = dateCache.getOrPut(day) {
                dateFormat.format(Date(file.dateAdded * 1000L)).lowercase(Locale.getDefault())
            }
            terms.all { term ->
                if (file.name.lowercase(Locale.getDefault()).contains(term) ||
                    file.album.lowercase(Locale.getDefault()).contains(term) ||
                    dateText.contains(term)
                ) return@all true
                // A file-name-like term ("vid_20251228", "img_0777.mov") only matches names,
                // albums and dates — never the "videos"/"photos" shortcuts or smart labels.
                if (term.any { it.isDigit() || it == '_' || it == '.' }) return@all false
                (isKeyword(term, VIDEO_WORDS) && file.type == MediaType.VIDEO) ||
                        (isKeyword(term, PHOTO_WORDS) && file.type == MediaType.IMAGE) ||
                        (isKeyword(term, SCREENSHOT_WORDS) && file.isScreenshot) ||
                        (isKeyword(term, FAVORITE_WORDS) && file.isFavorite) ||
                        SmartLabels.matches(term, labels[file.uri.toString()])
            }
        }
    }

    /** The term is one of [words] or the start of one while typing ("vid", "vide" → "videos"). */
    private fun isKeyword(term: String, words: List<String>) =
        term.length >= 3 && words.any { it.startsWith(term) }

    private fun buildMemories(media: List<MediaFile>): List<Memory> {
        if (media.isEmpty()) return emptyList()
        val today = Calendar.getInstance()
        val currentYear = today.get(Calendar.YEAR)
        val todayDoy = today.get(Calendar.DAY_OF_YEAR)
        val cal = Calendar.getInstance()
        val byYear = HashMap<Int, MutableList<MediaFile>>()
        media.forEach { file ->
            if (file.type != MediaType.IMAGE) return@forEach
            cal.timeInMillis = file.dateAdded * 1000L
            val year = cal.get(Calendar.YEAR)
            if (year >= currentYear) return@forEach
            val diff = kotlin.math.abs(cal.get(Calendar.DAY_OF_YEAR) - todayDoy)
            if (diff <= 3 || diff >= 362) { // within a week window around today
                byYear.getOrPut(year) { mutableListOf() }.add(file)
            }
        }
        return byYear.entries
            .sortedByDescending { it.key }
            .take(8)
            .map { (year, files) ->
                val yearsAgo = currentYear - year
                Memory(
                    title = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago",
                    subtitle = "${files.size} photos · $year",
                    cover = files.first(),
                    uris = files.map { it.uri }
                )
            }
    }

    // ------------------------------------------------------------------ loading

    fun loadMedia() {
        if (_isLoading.value) return
        _isLoading.value = true

        viewModelScope.launch {
            val mediaList = withContext(Dispatchers.IO) {
                val fetched = fetchMedia()
                val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                fetched.forEach { it.isFavorite = favorites.contains(it.uri.toString()) }
                fetched
            }
            _allMedia.value = mediaList
            _isLoading.value = false
            // Analyse new/changed photos in the background (labels, duplicates, blur).
            SmartIndexRepository.sync(getApplication(), mediaList)
        }
    }

    private companion object {
        val VIDEO_WORDS = listOf("videos", "vids", "movies", "clips")
        val PHOTO_WORDS = listOf("photos", "images", "pictures", "pics")
        val SCREENSHOT_WORDS = listOf("screenshots")
        val FAVORITE_WORDS = listOf("favorites", "favourites", "favs")
        const val MIN_THING_PHOTOS = 3
        const val MAX_THINGS = 14
    }

    private fun fetchMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        list.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        return list.sortedWith(
            compareByDescending<MediaFile> { it.dateAdded }
                .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
        )
    }

    private fun queryMedia(uri: android.net.Uri, type: MediaType): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            "width",
            "height",
            "datetaken"
        )
        if (type == MediaType.VIDEO) {
            projection.add(MediaStore.Video.Media.DURATION)
        }

        try {
            contentResolver.query(
                uri,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumColumn = cursor.getColumnIndex("bucket_display_name")
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val widthColumn = cursor.getColumnIndex("width")
                val heightColumn = cursor.getColumnIndex("height")
                val takenColumn = cursor.getColumnIndex("datetaken")
                val durationColumn = if (type == MediaType.VIDEO) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                while (cursor.moveToNext()) {
                    val size = if (sizeColumn != -1) cursor.getString(sizeColumn) else null
                    if (size == "0") continue

                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val album = if (albumColumn != -1) {
                        cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Camera"
                    } else {
                        "Camera"
                    }
                    val date = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    if (type == MediaType.VIDEO && duration == 0L) continue

                    val contentUri = ContentUris.withAppendedId(uri, id)
                    list.add(
                        MediaFile(
                            contentUri, name, album, date, type, duration,
                            size = size?.toLongOrNull() ?: 0L,
                            width = if (widthColumn != -1) cursor.getInt(widthColumn) else 0,
                            height = if (heightColumn != -1) cursor.getInt(heightColumn) else 0,
                            mimeType = if (mimeColumn != -1) cursor.getString(mimeColumn) else null,
                            dateTaken = if (takenColumn != -1) cursor.getLong(takenColumn) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
