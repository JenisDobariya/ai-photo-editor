package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photo_editor.my_photo_editor.databinding.ActivityGalleryBinding
import com.photo_editor.my_photo_editor.model.AlbumSort
import com.photo_editor.my_photo_editor.model.GroupBy
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.SortOrder
import com.photo_editor.my_photo_editor.ui.FoldersFragment
import com.photo_editor.my_photo_editor.ui.MainViewModel
import com.photo_editor.my_photo_editor.ui.MediaSelectionViewModel
import com.photo_editor.my_photo_editor.ui.PhotosFragment
import com.photo_editor.my_photo_editor.ui.ProfileFragment
import com.photo_editor.my_photo_editor.ui.SelectionMoreMenu
import com.photo_editor.my_photo_editor.ui.StudioFragment
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.AppUpdateManager
import kotlinx.coroutines.launch

class GalleryActivity : BaseActivity() {

    override fun getScreenTag(): String = "Gallery"

    private lateinit var binding: ActivityGalleryBinding
    private val viewModel: MainViewModel by viewModels()
    private val selectionViewModel: MediaSelectionViewModel by viewModels()

    // Tab screens. On recreation (theme/accent change, process restore) the FragmentManager has
    // already restored them, so the restored instances are reused instead of adding new copies.
    private lateinit var photosFragment: PhotosFragment
    private lateinit var foldersFragment: FoldersFragment
    private lateinit var studioFragment: StudioFragment
    private lateinit var profileFragment: ProfileFragment
    private lateinit var activeFragment: Fragment
    private var isSearchMode = false
    private var pendingAction: MediaSelectionViewModel.PendingAction? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true) ||
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (granted) {
            viewModel.loadMedia()
        } else {
            Toast.makeText(this, "Permissions required to show photos", Toast.LENGTH_SHORT).show()
        }
    }

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingAction ?: return@registerForActivityResult
        pendingAction = null
        selectionViewModel.onIntentSenderResult(action, result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackPressHandler()
        setupFragments(savedInstanceState)
        setupNavigation(savedInstanceState)
        setupTopBar()
        setupSelectionBars()
        observeSelection()
        checkPermissions()
        AppUpdateManager.checkForUpdate(this, getScreenTag())
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AnalyticsManager.logEvent("Gallery_onBackPressed")
                if (selectionViewModel.isSelectionMode) {
                    selectionViewModel.clear()
                } else if (isSearchMode) {
                    exitSearch()
                } else if (activeFragment != photosFragment) {
                    binding.bottomNav.selectedItemId = R.id.nav_library
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        photosFragment = fm.findFragmentByTag("photos") as? PhotosFragment ?: PhotosFragment()
        foldersFragment = fm.findFragmentByTag("folders") as? FoldersFragment ?: FoldersFragment()
        studioFragment = fm.findFragmentByTag("studio") as? StudioFragment ?: StudioFragment()
        profileFragment = fm.findFragmentByTag("profile") as? ProfileFragment ?: ProfileFragment()

        if (savedInstanceState == null || !photosFragment.isAdded) {
            fm.beginTransaction().apply {
                add(R.id.fragment_container, profileFragment, "profile").hide(profileFragment)
                add(R.id.fragment_container, studioFragment, "studio").hide(studioFragment)
                add(R.id.fragment_container, foldersFragment, "folders").hide(foldersFragment)
                add(R.id.fragment_container, photosFragment, "photos")
            }.commitNow()
        }
        // The tab that was open before recreation (restored hidden/shown state), else Photos.
        activeFragment = listOf(photosFragment, foldersFragment, studioFragment, profileFragment)
            .firstOrNull { it.isAdded && !it.isHidden } ?: photosFragment
        binding.headerTitle.text = titleFor(activeFragment)
    }

    private fun titleFor(fragment: Fragment): String = when (fragment) {
        foldersFragment -> getString(R.string.nav_folders)
        studioFragment -> "Studio"
        profileFragment -> "Profile"
        else -> getString(R.string.nav_library)
    }

    private fun navIdFor(fragment: Fragment): Int = when (fragment) {
        foldersFragment -> R.id.nav_folders
        studioFragment -> R.id.nav_studio
        profileFragment -> R.id.nav_profile
        else -> R.id.nav_library
    }

    private fun setupNavigation(savedInstanceState: Bundle?) {
        binding.btnBackHeader.isVisible = false
        binding.btnSettings.isVisible = false

        // Bottom bar matches the visible tab (set before the listener, so nothing is re-shown).
        binding.bottomNav.selectedItemId = navIdFor(activeFragment)
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> {
                    AnalyticsManager.logEvent("Gallery_nav_library")
                    showFragment(photosFragment, getString(R.string.nav_library))
                    true
                }
                R.id.nav_folders -> {
                    AnalyticsManager.logEvent("Gallery_nav_folders")
                    showFragment(foldersFragment, getString(R.string.nav_folders))
                    true
                }
                R.id.nav_studio -> {
                    AnalyticsManager.logEvent("Gallery_nav_studio")
                    showFragment(studioFragment, "Studio")
                    true
                }
                R.id.nav_profile -> {
                    AnalyticsManager.logEvent("Gallery_nav_profile")
                    showFragment(profileFragment, "Profile")
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment, title: String) {
        if (activeFragment == fragment) return

        binding.headerTitle.text = title
        if (isSearchMode) exitSearch()
        selectionViewModel.clear()
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(fragment)
            .commit()
        activeFragment = fragment
        updateHeaderActions()
    }

    // ------------------------------------------------------------------ top bar

    private fun setupTopBar() {
        binding.btnSearch.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_btnSearch_click")
            enterSearch()
        }
        binding.btnMore.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_btnMore_click")
            showMoreMenu(it)
        }
        binding.btnCancelSearch.setOnClickListener { exitSearch() }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }
        binding.etSearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.btnClearSearch.isVisible = query.isNotEmpty()
            viewModel.setSearchQuery(query)
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
        updateHeaderActions()
    }

    private fun updateHeaderActions() {
        val galleryTab = activeFragment == photosFragment || activeFragment == foldersFragment
        binding.btnSearch.isVisible = galleryTab
        binding.btnMore.isVisible = galleryTab
        binding.etSearch.hint = if (activeFragment == foldersFragment) "Search albums" else "Try \"dog\", \"food\", \"beach\", a date…"
    }

    private fun enterSearch() {
        isSearchMode = true
        binding.searchContainer.isVisible = true
        binding.header.visibility = View.INVISIBLE
        binding.etSearch.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitSearch() {
        isSearchMode = false
        binding.etSearch.setText("")
        viewModel.setSearchQuery("")
        hideKeyboard()
        binding.searchContainer.isVisible = false
        binding.header.visibility = if (selectionViewModel.isSelectionMode) View.INVISIBLE else View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        val menu = popup.menu
        if (activeFragment == photosFragment) {
            menu.add(0, MENU_SELECT, 0, "Select items")

            val sort = menu.addSubMenu(0, 0, 1, "Sort by")
            val currentSort = viewModel.sortOrder.value
            sort.add(GROUP_SORT, MENU_SORT_NEWEST, 0, "Newest first").isChecked = currentSort == SortOrder.DATE_DESC
            sort.add(GROUP_SORT, MENU_SORT_OLDEST, 1, "Oldest first").isChecked = currentSort == SortOrder.DATE_ASC
            sort.add(GROUP_SORT, MENU_SORT_NAME, 2, "Name (A–Z)").isChecked = currentSort == SortOrder.NAME_ASC
            sort.add(GROUP_SORT, MENU_SORT_SIZE, 3, "Largest first").isChecked = currentSort == SortOrder.SIZE_DESC
            sort.setGroupCheckable(GROUP_SORT, true, true)

            val group = menu.addSubMenu(0, 0, 2, "Group by")
            val currentGroup = viewModel.groupBy.value
            group.add(GROUP_GROUP, MENU_GROUP_DAY, 0, "Day").isChecked = currentGroup == GroupBy.DAY
            group.add(GROUP_GROUP, MENU_GROUP_MONTH, 1, "Month").isChecked = currentGroup == GroupBy.MONTH
            group.add(GROUP_GROUP, MENU_GROUP_YEAR, 2, "Year").isChecked = currentGroup == GroupBy.YEAR
            group.setGroupCheckable(GROUP_GROUP, true, true)

            val columns = menu.addSubMenu(0, 0, 3, "Grid size")
            val currentColumns = viewModel.gridColumns.value
            (2..6).forEach { c ->
                columns.add(GROUP_COLUMNS, MENU_COLUMNS_BASE + c, c, "$c per row").isChecked = currentColumns == c
            }
            columns.setGroupCheckable(GROUP_COLUMNS, true, true)

            menu.add(0, MENU_MEMORIES, 4, "Show memories").apply {
                isCheckable = true
                isChecked = viewModel.showMemories.value
            }
        } else {
            val sort = menu.addSubMenu(0, 0, 1, "Sort albums")
            val current = viewModel.albumSort.value
            sort.add(GROUP_ALBUM_SORT, MENU_ALBUM_RECENT, 0, "Recently updated").isChecked = current == AlbumSort.RECENT
            sort.add(GROUP_ALBUM_SORT, MENU_ALBUM_NAME, 1, "Name (A–Z)").isChecked = current == AlbumSort.NAME
            sort.add(GROUP_ALBUM_SORT, MENU_ALBUM_COUNT, 2, "Most items").isChecked = current == AlbumSort.COUNT
            sort.setGroupCheckable(GROUP_ALBUM_SORT, true, true)
        }
        menu.add(0, MENU_TRASH, 10, "Recycle Bin")
        menu.add(0, MENU_LOCKED, 11, "Locked Folder")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SELECT -> photosFragment.startSelectionMode()
                MENU_SORT_NEWEST -> viewModel.setSortOrder(SortOrder.DATE_DESC)
                MENU_SORT_OLDEST -> viewModel.setSortOrder(SortOrder.DATE_ASC)
                MENU_SORT_NAME -> viewModel.setSortOrder(SortOrder.NAME_ASC)
                MENU_SORT_SIZE -> viewModel.setSortOrder(SortOrder.SIZE_DESC)
                MENU_GROUP_DAY -> viewModel.setGroupBy(GroupBy.DAY)
                MENU_GROUP_MONTH -> viewModel.setGroupBy(GroupBy.MONTH)
                MENU_GROUP_YEAR -> viewModel.setGroupBy(GroupBy.YEAR)
                MENU_MEMORIES -> viewModel.setShowMemories(!viewModel.showMemories.value)
                MENU_ALBUM_RECENT -> viewModel.setAlbumSort(AlbumSort.RECENT)
                MENU_ALBUM_NAME -> viewModel.setAlbumSort(AlbumSort.NAME)
                MENU_ALBUM_COUNT -> viewModel.setAlbumSort(AlbumSort.COUNT)
                MENU_TRASH -> startActivity(Intent(this, TrashActivity::class.java))
                MENU_LOCKED -> startActivity(Intent(this, HiddenMediaActivity::class.java))
                in (MENU_COLUMNS_BASE + 2)..(MENU_COLUMNS_BASE + 6) -> viewModel.setGridColumns(item.itemId - MENU_COLUMNS_BASE)
                else -> return@setOnMenuItemClickListener false
            }
            AnalyticsManager.logEvent("Gallery_menu_${item.itemId}")
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------ selection

    private fun setupSelectionBars() {
        val header = binding.selectionHeader
        header.btnCloseSelection.setOnClickListener { selectionViewModel.clear() }
        header.btnSelectAll.setOnClickListener {
            val all = if (activeFragment == photosFragment) viewModel.visibleMedia.value else viewModel.libraryMedia.value
            val uris = all.map { it.uri }.toSet()
            if (selectionViewModel.selection.value.containsAll(uris)) selectionViewModel.clear()
            else selectionViewModel.setSelection(uris)
        }

        val actions = binding.selectionActions
        actions.actionShare.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_selection_share")
            selectionViewModel.share(selectedItems())
        }
        actions.actionFavorite.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_selection_favorite")
            selectionViewModel.toggleFavorite(selectedItems())
        }
        actions.actionHide.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_selection_lock")
            val items = selectedItems()
            AppDialog.Builder(this)
                .setIcon(R.drawable.ic_g_lock, R.color.lumina_accent)
                .setTitle("Move to Locked Folder?")
                .setMessage("${if (items.size == 1) "This item" else "These ${items.size} items"} will be hidden from Photos and Albums. Open them anytime from Locked Folder.")
                .setPositiveButton("Move") { _, _ -> selectionViewModel.hide(items) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        actions.actionDelete.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_selection_delete")
            confirmDelete(selectedItems())
        }
        actions.actionMore.setOnClickListener {
            AnalyticsManager.logEvent("Gallery_selection_more")
            SelectionMoreMenu.show(this, it, selectedItems(), selectionViewModel)
        }

        ViewCompat.setOnApplyWindowInsetsListener(actions.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + (4 * resources.displayMetrics.density).toInt())
            insets
        }
    }

    private fun confirmDelete(items: List<MediaFile>) {
        if (items.isEmpty()) return
        if (selectionViewModel.isRecycleBinSupported) {
            selectionViewModel.moveToTrash(items) // system dialog asks for confirmation
        } else {
            AppDialog.Builder(this)
                .setIcon(R.drawable.ic_g_delete, R.color.lumina_danger)
                .setTitle(if (items.size == 1) "Delete this item?" else "Delete ${items.size} items?")
                .setMessage("This can't be undone on this Android version.")
                .setPositiveButton("Delete") { _, _ -> selectionViewModel.moveToTrash(items) }
                .setDestructive()
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun selectedItems(): List<MediaFile> {
        val selected = selectionViewModel.selection.value
        return viewModel.libraryMedia.value.filter { selected.contains(it.uri) }
    }

    private fun observeSelection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    selectionViewModel.selection.collect { selected ->
                        val active = selected.isNotEmpty()
                        binding.selectionHeader.root.isVisible = active
                        binding.selectionActions.root.isVisible = active
                        binding.bottomNav.isVisible = !active
                        binding.header.visibility = if (active || isSearchMode) View.INVISIBLE else View.VISIBLE
                        if (active) {
                            binding.selectionHeader.tvSelectionCount.text =
                                if (selected.size == 1) "1 selected" else "${selected.size} selected"
                            val allFav = selectedItems().let { it.isNotEmpty() && it.all { f -> f.isFavorite } }
                            binding.selectionActions.ivActionFavorite.setImageResource(
                                if (allFav) R.drawable.ic_g_heart else R.drawable.ic_g_heart_outline
                            )
                            binding.selectionActions.tvActionFavorite.text = if (allFav) "Unfavorite" else "Favorite"
                        }
                    }
                }
                launch {
                    selectionViewModel.events.collect { event ->
                        when (event) {
                            is MediaSelectionViewModel.SelectionEvent.Message ->
                                Toast.makeText(this@GalleryActivity, event.text, Toast.LENGTH_SHORT).show()
                            is MediaSelectionViewModel.SelectionEvent.StartIntent ->
                                startActivity(event.intent)
                            is MediaSelectionViewModel.SelectionEvent.LaunchIntentSender -> {
                                pendingAction = event.action
                                intentSenderLauncher.launch(IntentSenderRequest.Builder(event.sender).build())
                            }
                            MediaSelectionViewModel.SelectionEvent.Changed -> viewModel.loadMedia()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) {
            if (viewModel.allMedia.value.isEmpty()) {
                viewModel.loadMedia()
            }
        }
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        if (hasMediaPermission()) {
            viewModel.loadMedia()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            requestPermissionLauncher.launch(permissions)
        }
    }



    private fun showExitDialog() {
        AnalyticsManager.logEvent("Gallery_showExitDialog")
        val dialogView = layoutInflater.inflate(R.layout.dialog_exit_app, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            AnalyticsManager.logEvent("Gallery_exitDialog_btnCancel")
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnExit).setOnClickListener {
            AnalyticsManager.logEvent("Gallery_exitDialog_btnExit")
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private companion object {
        const val GROUP_SORT = 1
        const val GROUP_GROUP = 2
        const val GROUP_COLUMNS = 3
        const val GROUP_ALBUM_SORT = 4
        const val MENU_SELECT = 100
        const val MENU_SORT_NEWEST = 110
        const val MENU_SORT_OLDEST = 111
        const val MENU_SORT_NAME = 112
        const val MENU_SORT_SIZE = 113
        const val MENU_GROUP_DAY = 120
        const val MENU_GROUP_MONTH = 121
        const val MENU_GROUP_YEAR = 122
        const val MENU_COLUMNS_BASE = 130
        const val MENU_MEMORIES = 140
        const val MENU_ALBUM_RECENT = 150
        const val MENU_ALBUM_NAME = 151
        const val MENU_ALBUM_COUNT = 152
        const val MENU_TRASH = 160
        const val MENU_LOCKED = 161
    }
}
