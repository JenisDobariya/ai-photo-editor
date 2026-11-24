package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import com.photo_editor.my_photo_editor.utils.appColor
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.photo_editor.my_photo_editor.databinding.ActivityCleanerBinding
import com.photo_editor.my_photo_editor.databinding.ItemCleanerChipBinding
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import com.photo_editor.my_photo_editor.ui.cleaner.CleanerAdapter
import com.photo_editor.my_photo_editor.ui.cleaner.CleanerCategory
import com.photo_editor.my_photo_editor.ui.cleaner.CleanerEvent
import com.photo_editor.my_photo_editor.ui.cleaner.CleanerRow
import com.photo_editor.my_photo_editor.ui.cleaner.CleanerUiState
import com.photo_editor.my_photo_editor.ui.cleaner.CleanerViewModel
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.launch
import com.photo_editor.my_photo_editor.utils.adaptiveColumns
import com.photo_editor.my_photo_editor.utils.GRID_CELL_DP

/**
 * Storage cleaner ("Clean up"): duplicates, similar shots, blurry photos, screenshots and
 * large videos, all found on the device. Deleted items go to the system Recycle Bin on
 * Android 11+.
 */
class CleanerActivity : BaseActivity(), CleanerAdapter.Listener {

    override fun getScreenTag(): String = "Cleaner"

    private lateinit var binding: ActivityCleanerBinding
    private val viewModel: CleanerViewModel by viewModels()
    private lateinit var adapter: CleanerAdapter
    private lateinit var chips: Map<CleanerCategory, ItemCleanerChipBinding>

    private var lastState = CleanerUiState()
    private var lastProgress = SmartIndexRepository.Progress()
    private var lastCategory: CleanerCategory? = null

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onTrashResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCleanerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvSafetyNote.text = if (viewModel.isRecycleBinSupported) {
            "Checked on your phone. Deleted items go to the Recycle Bin."
        } else {
            "Checked on your phone. Deleted items can't be recovered."
        }

        setupChips()
        setupList()
        setupBottomBar()
        observe()

        viewModel.start(intent.getStringExtra(EXTRA_START_CATEGORY))
    }

    override fun onRestart() {
        super.onRestart()
        // Items may have been deleted or edited in the viewer or another app.
        viewModel.reload()
    }

    private fun setupChips() {
        chips = linkedMapOf(
            CleanerCategory.DUPLICATES to binding.chipDuplicates,
            CleanerCategory.SIMILAR to binding.chipSimilar,
            CleanerCategory.BLURRY to binding.chipBlurry,
            CleanerCategory.SCREENSHOTS to binding.chipScreenshots,
            CleanerCategory.LARGE_VIDEOS to binding.chipLargeVideos
        )
        chips.forEach { (category, chip) ->
            chip.tvChipLabel.text = category.title
            chip.ivChipIcon.setImageResource(iconFor(category))
            chip.root.contentDescription = category.title
            chip.root.setOnClickListener { viewModel.selectCategory(category) }
        }
    }

    private fun setupList() {
        adapter = CleanerAdapter(this)
        val spanCount = adaptiveColumns(3, GRID_CELL_DP)
        binding.rvCleaner.layoutManager = GridLayoutManager(this, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.isFullSpan(position)) spanCount else 1
            }
        }
        binding.rvCleaner.setHasFixedSize(true)
        binding.rvCleaner.itemAnimator?.changeDuration = 0L
        binding.rvCleaner.adapter = adapter

        binding.btnSelectAll.setOnClickListener { viewModel.toggleSelectAll() }
    }

    private fun setupBottomBar() {
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + (12 * density).toInt())
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvCleaner) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + (96 * density).toInt())
            insets
        }
        binding.btnDelete.setOnClickListener { viewModel.deleteSelected() }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        lastState = state
                        render(state)
                    }
                }
                launch {
                    viewModel.analysisProgress.collect { progress ->
                        lastProgress = progress
                        renderProgress(progress)
                        renderEmptyState(lastState)
                    }
                }
                launch {
                    viewModel.events.collect { handleEvent(it) }
                }
            }
        }
    }

    private fun render(state: CleanerUiState) {
        val category = state.category
        val categoryChanged = lastCategory != null && lastCategory != category
        lastCategory = category

        binding.tvSubtitle.text = when {
            state.loading -> "Scanning your library…"
            state.totalReclaimableBytes > 0 -> "${viewModel.formatSize(state.totalReclaimableBytes)} can be freed"
            else -> "Nothing to clean up"
        }
        binding.tvTotalSize.text = if (state.loading) "—" else viewModel.formatSize(state.totalReclaimableBytes)

        val isNightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        chips.forEach { (c, chip) ->
            val selected = c == category
            chip.root.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            val textColor = this.appColor(if (selected) R.color.white else R.color.lumina_text_secondary)
            chip.tvChipLabel.setTextColor(textColor)
            ImageViewCompat.setImageTintList(chip.ivChipIcon, ColorStateList.valueOf(textColor))
            val count = state.counts[c] ?: 0
            chip.tvChipCount.isVisible = !state.loading && count > 0
            chip.tvChipCount.text = count.toString()
            chip.tvChipCount.setTextColor(
                when {
                    // Light theme: the on-container tone is too dark on the accent fill.
                    selected && !isNightTheme -> ColorUtils.setAlphaComponent(this.appColor(R.color.white), 0xD9)
                    selected -> this.appColor(R.color.lumina_on_accent_container)
                    else -> this.appColor(R.color.lumina_text_tertiary)
                }
            )
        }

        binding.tvCategoryInfo.text = state.categoryInfo
        binding.btnSelectAll.isVisible = !state.loading && !state.categoryEmpty
        binding.btnSelectAll.text = when {
            state.selectedCount > 0 -> "Deselect all"
            category.grouped -> "Auto-select"
            else -> "Select all"
        }

        binding.progressLoading.isVisible = state.loading
        adapter.submitList(state.rows) {
            if (categoryChanged) binding.rvCleaner.scrollToPosition(0)
        }
        renderEmptyState(state)

        val count = state.selectedCount
        binding.bottomBar.isVisible = count > 0
        if (count > 0) {
            val items = if (count == 1) "1 item" else "$count items"
            binding.btnDelete.text = "Delete $items · frees ${viewModel.formatSize(state.selectedBytes)}"
        }
    }

    private fun renderProgress(progress: SmartIndexRepository.Progress) {
        val running = progress.running && progress.total > 0
        binding.analysisRow.isVisible = running
        if (running) {
            binding.tvAnalysis.text = "Analysing photos… ${progress.done} / ${progress.total}"
            binding.progressAnalysis.max = progress.total
            binding.progressAnalysis.setProgressCompat(progress.done.coerceAtMost(progress.total), false)
        }
    }

    private fun renderEmptyState(state: CleanerUiState) {
        val show = !state.loading && state.categoryEmpty
        binding.emptyState.isVisible = show
        if (!show) return
        val category = state.category
        val analysing = lastProgress.running && category.needsIndex
        val (title, message) = if (analysing) {
            "Still analysing your photos" to "Results show up here while photos are checked."
        } else when (category) {
            CleanerCategory.DUPLICATES -> "No duplicates found" to "Exact copies of the same photo will show up here."
            CleanerCategory.SIMILAR -> "No similar photos found" to "Bursts and near-identical shots taken close together show up here."
            CleanerCategory.BLURRY -> "No blurry photos found" to "Out-of-focus or shaky photos show up here."
            CleanerCategory.SCREENSHOTS -> "No screenshots" to "Screenshots you take show up here, oldest first."
            CleanerCategory.LARGE_VIDEOS -> "No large videos" to "Videos of 50 MB or more show up here, largest first."
        }
        binding.tvEmptyTitle.text = title
        binding.tvEmptyMessage.text = message
        binding.ivEmptyIcon.setImageResource(if (analysing) iconFor(category) else R.drawable.ic_g_check_circle)
    }

    private fun handleEvent(event: CleanerEvent) {
        when (event) {
            is CleanerEvent.Message -> Toast.makeText(this, event.text, Toast.LENGTH_SHORT).show()
            is CleanerEvent.LaunchTrashRequest -> {
                try {
                    trashLauncher.launch(IntentSenderRequest.Builder(event.sender).build())
                } catch (e: Exception) {
                    e.printStackTrace()
                    viewModel.onTrashResult(false)
                    Toast.makeText(this, "Couldn't move items to Recycle Bin", Toast.LENGTH_SHORT).show()
                }
            }
            is CleanerEvent.ConfirmPermanentDelete -> {
                val items = if (event.count == 1) "this item" else "these ${event.count} items"
                AppDialog.Builder(this)
                    .setIcon(R.drawable.ic_g_delete, R.color.lumina_danger)
                    .setTitle("Delete permanently?")
                    .setMessage(
                        "Delete $items and free ${viewModel.formatSize(event.bytes)}? " +
                            "This can't be undone on this version of Android."
                    )
                    .setPositiveButton("Delete") { _, _ -> viewModel.confirmPermanentDelete() }
                    .setDestructive()
                    .setNegativeButton("Cancel") { _, _ -> viewModel.cancelPendingDelete() }
                    .setOnCancelListener { viewModel.cancelPendingDelete() }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ adapter callbacks

    override fun onPreview(row: CleanerRow.Media) {
        AnalyticsManager.logEvent("Cleaner_preview")
        val intent = Intent(this, PhotoDetailActivity::class.java).apply {
            putExtra("IMAGE_URI", row.media.uri.toString())
            putExtra("IS_VIDEO", row.media.isVideo)
            putExtra(PhotoDetailActivity.EXTRA_TITLE, lastState.category.title)
            putStringArrayListExtra(
                PhotoDetailActivity.EXTRA_URI_LIST,
                PhotoDetailActivity.cappedUriList(lastState.categoryUris.map { it.toString() }, row.media.uri.toString())
            )

        }
        startActivity(intent)
    }

    override fun onToggle(row: CleanerRow.Media) {
        viewModel.setSelected(row.media.uri, !row.selected)
    }

    override fun onGroupAction(header: CleanerRow.GroupHeader) {
        viewModel.toggleGroup(header.groupId)
    }

    private fun iconFor(category: CleanerCategory): Int = when (category) {
        CleanerCategory.DUPLICATES -> R.drawable.ic_cl_duplicate
        CleanerCategory.SIMILAR -> R.drawable.ic_cl_similar
        CleanerCategory.BLURRY -> R.drawable.ic_cl_blur
        CleanerCategory.SCREENSHOTS -> R.drawable.ic_g_screenshot
        CleanerCategory.LARGE_VIDEOS -> R.drawable.ic_g_video
    }

    companion object {
        /** String extra: one of the CATEGORY_* values, the tab to open first. */
        const val EXTRA_START_CATEGORY = "com.photo_editor.my_photo_editor.cleaner.START_CATEGORY"

        const val CATEGORY_DUPLICATES = "duplicates"
        const val CATEGORY_SIMILAR = "similar"
        const val CATEGORY_BLURRY = "blurry"
        const val CATEGORY_SCREENSHOTS = "screenshots"
        const val CATEGORY_LARGE_VIDEOS = "large_videos"

        /** Opens the cleaner, optionally on [startCategory] (a CATEGORY_* value). */
        fun intent(context: Context, startCategory: String? = null): Intent =
            Intent(context, CleanerActivity::class.java).apply {
                if (startCategory != null) putExtra(EXTRA_START_CATEGORY, startCategory)
            }
    }
}
