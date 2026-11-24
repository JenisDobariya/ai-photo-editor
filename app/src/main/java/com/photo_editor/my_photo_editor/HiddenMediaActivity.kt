package com.photo_editor.my_photo_editor

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.photo_editor.my_photo_editor.model.GroupBy
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.SortOrder
import com.photo_editor.my_photo_editor.databinding.ActivityHiddenMediaBinding
import com.photo_editor.my_photo_editor.ui.HiddenMediaViewModel
import com.photo_editor.my_photo_editor.ui.MediaSelectionViewModel
import com.photo_editor.my_photo_editor.ui.gallery.GridItem
import com.photo_editor.my_photo_editor.ui.gallery.MediaGridAdapter
import com.photo_editor.my_photo_editor.ui.gallery.MediaGrouping
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.photo_editor.my_photo_editor.utils.adaptiveColumns
import com.photo_editor.my_photo_editor.utils.GRID_CELL_DP

/**
 * Locked Folder: media hidden from every gallery view, protected by the device
 * screen lock (PIN / pattern / biometrics) and excluded from screenshots & recents.
 */
class HiddenMediaActivity : BaseActivity(), MediaGridAdapter.Listener {

    override fun getScreenTag(): String = "HiddenMedia"

    private lateinit var binding: ActivityHiddenMediaBinding
    private val viewModel: HiddenMediaViewModel by viewModels()
    private val selectionViewModel: MediaSelectionViewModel by viewModels()
    private lateinit var adapter: MediaGridAdapter

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.isAuthInProgress = false
        if (result.resultCode == RESULT_OK) {
            AnalyticsManager.logEvent("HiddenMedia_unlock_success")
            viewModel.unlock()
        } else {
            Toast.makeText(this, "Authentication cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep private content out of screenshots and the recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUnlock.setOnClickListener { authenticate() }

        adapter = MediaGridAdapter(this)
        binding.rvHidden.layoutManager = GridLayoutManager(this, adaptiveColumns(3, GRID_CELL_DP)).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.isFullSpan(position)) spanCount else 1
            }
        }
        binding.rvHidden.adapter = adapter

        setupSelection()
        observe()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionViewModel.isSelectionMode) selectionViewModel.clear() else finish()
            }
        })

        if (savedInstanceState == null) authenticate()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun authenticate() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            binding.tvNoLockWarning.isVisible = true
            viewModel.unlock()
            return
        }
        if (viewModel.isAuthInProgress) return
        @Suppress("DEPRECATION")
        val intent: Intent? = keyguard.createConfirmDeviceCredentialIntent(
            "Locked Folder", "Confirm your screen lock to view hidden items"
        )
        if (intent == null) {
            viewModel.unlock()
        } else {
            viewModel.isAuthInProgress = true
            authLauncher.launch(intent)
        }
    }

    private fun setupSelection() {
        binding.selectionHeader.btnCloseSelection.setOnClickListener { selectionViewModel.clear() }
        binding.selectionHeader.btnSelectAll.setOnClickListener {
            val all = viewModel.items.value.map { it.uri }.toSet()
            if (selectionViewModel.selection.value.containsAll(all)) selectionViewModel.clear()
            else selectionViewModel.setSelection(all)
        }
        binding.btnUnhide.setOnClickListener {
            AnalyticsManager.logEvent("HiddenMedia_unhide_click")
            selectionViewModel.unhide(selectionViewModel.selection.value.toList())
        }
        binding.btnShareHidden.setOnClickListener {
            selectionViewModel.share(selectedItems())
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.hiddenActions) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + (12 * resources.displayMetrics.density).toInt())
            insets
        }
    }

    private fun selectedItems(): List<MediaFile> {
        val selected = selectionViewModel.selection.value
        return viewModel.items.value.filter { selected.contains(it.uri) }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.isUnlocked, viewModel.items, selectionViewModel.selection) { unlocked, items, selection ->
                        Triple(unlocked, items, selection)
                    }.collect { (unlocked, items, selection) ->
                        binding.lockedOverlay.isVisible = !unlocked
                        binding.emptyState.isVisible = unlocked && items.isEmpty()
                        val active = selection.isNotEmpty()
                        binding.selectionHeader.root.isVisible = active
                        binding.hiddenActions.isVisible = active
                        binding.header.visibility = if (active) View.INVISIBLE else View.VISIBLE
                        binding.selectionHeader.tvSelectionCount.text =
                            if (selection.size == 1) "1 selected" else "${selection.size} selected"
                        binding.tvSubtitle.text = if (items.size == 1) "1 item" else "${items.size} items"
                        val rows = withContext(Dispatchers.Default) {
                            if (!unlocked) emptyList()
                            else MediaGrouping.build(items, GroupBy.MONTH, SortOrder.DATE_DESC, selection)
                        }
                        adapter.submitList(rows)
                    }
                }
                launch {
                    selectionViewModel.events.collect { event ->
                        when (event) {
                            is MediaSelectionViewModel.SelectionEvent.Message ->
                                Toast.makeText(this@HiddenMediaActivity, event.text, Toast.LENGTH_SHORT).show()
                            is MediaSelectionViewModel.SelectionEvent.StartIntent -> startActivity(event.intent)
                            MediaSelectionViewModel.SelectionEvent.Changed -> viewModel.load()
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ adapter callbacks

    override fun onMediaClick(file: MediaFile, position: Int) {
        if (selectionViewModel.isSelectionMode) {
            selectionViewModel.toggle(file.uri)
            return
        }
        val intent = Intent(this, PhotoDetailActivity::class.java).apply {
            putExtra("IMAGE_URI", file.uri.toString())
            putExtra("IS_VIDEO", file.type == MediaType.VIDEO)
            putExtra(PhotoDetailActivity.EXTRA_TITLE, "Locked")
            putStringArrayListExtra(
                PhotoDetailActivity.EXTRA_URI_LIST,
                PhotoDetailActivity.cappedUriList(viewModel.items.value.map { it.uri.toString() }, file.uri.toString())
            )

        }
        startActivity(intent)
    }

    override fun onMediaLongClick(file: MediaFile, position: Int) {
        binding.rvHidden.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectionViewModel.toggle(file.uri)
    }

    override fun onHeaderClick(header: GridItem.Header) {
        selectionViewModel.select(header.uris, !header.allSelected)
    }
}
