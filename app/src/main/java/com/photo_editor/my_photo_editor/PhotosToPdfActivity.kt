package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.photo_editor.my_photo_editor.databinding.ActivityPdfBinding
import com.photo_editor.my_photo_editor.databinding.ItemPdfOptionsBinding
import com.photo_editor.my_photo_editor.databinding.ItemPdfProgressBinding
import com.photo_editor.my_photo_editor.databinding.ItemPdfRenameBinding
import com.photo_editor.my_photo_editor.ui.pdf.PdfPagesAdapter
import com.photo_editor.my_photo_editor.ui.pdf.PhotosToPdfViewModel
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.PdfMaker
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.launch
import com.photo_editor.my_photo_editor.utils.adaptiveColumns
import com.photo_editor.my_photo_editor.utils.GRID_CELL_DP

/** Photos → PDF: reorder pages, pick page options and save into Documents/PhotoEditor. */
class PhotosToPdfActivity : BaseActivity(), PdfPagesAdapter.Listener {

    companion object {
        const val EXTRA_URIS = "com.photo_editor.my_photo_editor.extra.PDF_URIS"
        private const val MAX_PICK = 100

        fun intent(context: Context, uris: List<Uri>): Intent =
            Intent(context, PhotosToPdfActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
    }

    override fun getScreenTag(): String = "PhotosToPdf"

    private lateinit var binding: ActivityPdfBinding
    private val viewModel: PhotosToPdfViewModel by viewModels()
    private lateinit var adapter: PdfPagesAdapter

    private var progressDialog: AppDialog? = null
    private var progressBinding: ItemPdfProgressBinding? = null
    private var doneDialog: AppDialog? = null

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPhotos(uris)
        } else if (viewModel.state.value.pages.isEmpty()) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCreate.setOnClickListener { viewModel.create() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + dp(12))
            insets
        }

        setupGrid()
        observe()

        val initial = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
        viewModel.init(initial)
        if (savedInstanceState == null && initial.isEmpty()) launchPicker()
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBinding = null
        doneDialog?.dismiss()
        doneDialog = null
        super.onDestroy()
    }

    private fun launchPicker() {
        try {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show()
            if (viewModel.state.value.pages.isEmpty()) finish()
        }
    }

    private fun setupGrid() {
        adapter = PdfPagesAdapter(this)
        binding.rvPages.layoutManager = GridLayoutManager(this, adaptiveColumns(3, GRID_CELL_DP)).apply {

            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.isFullSpan(position)) spanCount else 1
            }
        }
        binding.rvPages.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (!adapter.isPage(viewHolder.bindingAdapterPosition)) return 0
                val drag = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                return makeMovementFlags(drag, 0)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = adapter.isPage(target.bindingAdapterPosition)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (!adapter.isPage(from) || !adapter.isPage(to)) return false
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled() = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.itemView.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.9f).setDuration(120).start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                viewModel.reorder(adapter.pages.map { it.id })
                recyclerView.post { adapter.refreshPageNumbers() }
            }
        })
        touchHelper.attachToRecyclerView(binding.rvPages)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var lastOptions: Pair<PdfMaker.Options, String>? = null
                    viewModel.state.collect { state ->
                        val count = state.pages.size
                        binding.tvSubtitle.text = if (count == 1) "1 page" else "$count pages"
                        binding.btnCreate.isEnabled = count > 0
                        binding.btnCreate.alpha = if (count > 0) 1f else 0.5f
                        adapter.submit(state.pages)
                        val options = state.options to state.fileName
                        if (options != lastOptions) {
                            lastOptions = options
                            adapter.refreshOptions()
                        }
                    }
                }
                launch {
                    viewModel.progress.collect { renderProgress(it) }
                }
                launch {
                    viewModel.done.collect { done -> if (done != null) showDoneDialog(done) }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is PhotosToPdfViewModel.Event.Message ->
                                Toast.makeText(this@PhotosToPdfActivity, event.text, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ options card

    override fun onOptionsCreated(binding: ItemPdfOptionsBinding) {
        binding.chipSizeA4.setOnClickListener { viewModel.setPageSize(PdfMaker.PageSize.A4) }
        binding.chipSizeLetter.setOnClickListener { viewModel.setPageSize(PdfMaker.PageSize.LETTER) }
        binding.chipSizeFit.setOnClickListener { viewModel.setPageSize(PdfMaker.PageSize.FIT) }
        binding.chipOrientAuto.setOnClickListener { viewModel.setOrientation(PdfMaker.Orientation.AUTO) }
        binding.chipOrientPortrait.setOnClickListener { viewModel.setOrientation(PdfMaker.Orientation.PORTRAIT) }
        binding.chipOrientLandscape.setOnClickListener { viewModel.setOrientation(PdfMaker.Orientation.LANDSCAPE) }
        binding.chipMarginNone.setOnClickListener { viewModel.setMargin(PdfMaker.Margin.NONE) }
        binding.chipMarginSmall.setOnClickListener { viewModel.setMargin(PdfMaker.Margin.SMALL) }
        binding.chipMarginLarge.setOnClickListener { viewModel.setMargin(PdfMaker.Margin.LARGE) }
        binding.chipQualityStandard.setOnClickListener { viewModel.setQuality(PdfMaker.Quality.STANDARD) }
        binding.chipQualityHigh.setOnClickListener { viewModel.setQuality(PdfMaker.Quality.HIGH) }
        binding.rowFileName.setOnClickListener { showRenameDialog() }
    }

    override fun onOptionsBind(binding: ItemPdfOptionsBinding) {
        val state = viewModel.state.value
        val o = state.options
        binding.chipSizeA4.isChecked = o.pageSize == PdfMaker.PageSize.A4
        binding.chipSizeLetter.isChecked = o.pageSize == PdfMaker.PageSize.LETTER
        binding.chipSizeFit.isChecked = o.pageSize == PdfMaker.PageSize.FIT

        val fit = o.pageSize == PdfMaker.PageSize.FIT
        binding.chipGroupOrientation.isVisible = !fit
        binding.tvOrientationNote.isVisible = fit
        binding.chipOrientAuto.isChecked = o.orientation == PdfMaker.Orientation.AUTO
        binding.chipOrientPortrait.isChecked = o.orientation == PdfMaker.Orientation.PORTRAIT
        binding.chipOrientLandscape.isChecked = o.orientation == PdfMaker.Orientation.LANDSCAPE

        binding.chipMarginNone.isChecked = o.margin == PdfMaker.Margin.NONE
        binding.chipMarginSmall.isChecked = o.margin == PdfMaker.Margin.SMALL
        binding.chipMarginLarge.isChecked = o.margin == PdfMaker.Margin.LARGE

        binding.chipQualityStandard.isChecked = o.quality == PdfMaker.Quality.STANDARD
        binding.chipQualityHigh.isChecked = o.quality == PdfMaker.Quality.HIGH

        binding.tvFileName.text = "${state.fileName}.pdf"
    }

    private fun showRenameDialog() {
        val dialogBinding = ItemPdfRenameBinding.inflate(layoutInflater)
        val current = viewModel.state.value.fileName
        dialogBinding.etName.setText(current)
        dialogBinding.etName.setSelection(current.length)
        val dialog = AppDialog.Builder(this)
            .setTitle("File name")
            .setView(dialogBinding.root)
            .setPositiveButton("OK") { _, _ ->
                viewModel.setFileName(dialogBinding.etName.text?.toString().orEmpty())
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialogBinding.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.setFileName(dialogBinding.etName.text?.toString().orEmpty())
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
        dialogBinding.etName.requestFocus()
    }

    // ------------------------------------------------------------------ adapter callbacks

    override fun onRemovePage(page: PhotosToPdfViewModel.Page) {
        viewModel.remove(page.id)
    }

    override fun onAddPhotos() {
        if (viewModel.isBusy) return
        AnalyticsManager.logEvent("PhotosToPdf_add_photos")
        launchPicker()
    }

    // ------------------------------------------------------------------ progress & result

    private fun renderProgress(progress: PhotosToPdfViewModel.Progress?) {
        if (progress == null) {
            progressDialog?.dismiss()
            progressDialog = null
            progressBinding = null
            return
        }
        if (progressDialog == null) {
            val pb = ItemPdfProgressBinding.inflate(layoutInflater)
            progressBinding = pb
            progressDialog = AppDialog.Builder(this)
                .setView(pb.root)
                .setCancelable(false)
                .setNegativeButton("Cancel") { _, _ -> viewModel.cancel() }
                .show()
        }
        val pb = progressBinding ?: return
        val shown = (progress.done + 1).coerceAtMost(progress.total)
        pb.tvProgress.text = "Creating PDF… $shown / ${progress.total}"
        val percent = if (progress.total > 0) progress.done * 100 / progress.total else 0
        pb.progressBar.setProgressCompat(percent, true)
    }

    private fun showDoneDialog(done: PhotosToPdfViewModel.Done) {
        if (doneDialog?.isShowing == true) return
        val builder = AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
            .setTitle("PDF created")
            .addRow("Location", "Documents/PhotoEditor")
            .addRow("Pages", done.pages.toString())
        if (done.failed > 0) {
            builder.addRow("Skipped", if (done.failed == 1) "1 photo couldn't be read" else "${done.failed} photos couldn't be read")
        }
        doneDialog = builder
            .setPositiveButton("Open") { _, _ ->
                viewModel.consumeDone()
                try {
                    startActivity(ShareHelper.viewIntent(done.viewUri, "application/pdf"))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "No app found to open PDFs", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Share") { _, _ ->
                viewModel.consumeDone()
                val uri = done.shareUri ?: done.viewUri
                ShareHelper.shareIntent(listOf(uri), "application/pdf")?.let {
                    try {
                        startActivity(it)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "No app found to share", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Done") { _, _ ->
                viewModel.consumeDone()
                finish()
            }
            .setOnCancelListener { viewModel.consumeDone() }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
