package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import android.content.DialogInterface
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photo_editor.my_photo_editor.databinding.ActivityCompressBinding
import com.photo_editor.my_photo_editor.databinding.ItemCompressCustomSizeBinding
import com.photo_editor.my_photo_editor.databinding.ItemCompressProgressBinding
import com.photo_editor.my_photo_editor.databinding.ItemCompressThumbBinding
import com.photo_editor.my_photo_editor.ui.compress.CompressViewModel
import com.photo_editor.my_photo_editor.ui.compress.CompressViewModel.Format
import com.photo_editor.my_photo_editor.ui.compress.CompressViewModel.SizeOption
import com.photo_editor.my_photo_editor.ui.gallery.loadThumbnail
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Compress & resize: saves or shares smaller copies of photos; originals stay untouched. */
class CompressActivity : BaseActivity() {

    companion object {
        const val EXTRA_URIS = "com.photo_editor.my_photo_editor.extra.COMPRESS_URIS"
        private const val MAX_PICK = 100

        fun intent(context: Context, uris: List<Uri>): Intent =
            Intent(context, CompressActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
    }

    override fun getScreenTag(): String = "Compress"

    private lateinit var binding: ActivityCompressBinding
    private val viewModel: CompressViewModel by viewModels()
    private val thumbAdapter = ThumbAdapter()

    private var progressDialog: AppDialog? = null
    private var progressBinding: ItemCompressProgressBinding? = null
    private var resultDialog: AppDialog? = null

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.init(uris)
        else if (viewModel.state.value.photos.isEmpty()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvThumbs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvThumbs.adapter = thumbAdapter
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + dp(12))
            insets
        }

        setupOptions()
        binding.btnSave.setOnClickListener { viewModel.saveCopies() }
        binding.btnShare.setOnClickListener { viewModel.share() }
        observe()

        val initial = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
        if (initial.isNotEmpty()) {
            viewModel.init(initial)
        } else if (!viewModel.pickerLaunched) {
            // First start, or the process was restarted and the picked photos are gone.
            viewModel.pickerLaunched = true
            try {
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: Exception) {
                Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBinding = null
        resultDialog?.dismiss()
        resultDialog = null
        super.onDestroy()
    }

    private fun setupOptions() {
        binding.chipSizeOriginal.setOnClickListener { viewModel.setSize(SizeOption.ORIGINAL) }
        binding.chipSizeLarge.setOnClickListener { viewModel.setSize(SizeOption.LARGE) }
        binding.chipSizeMedium.setOnClickListener { viewModel.setSize(SizeOption.MEDIUM) }
        binding.chipSizeSmall.setOnClickListener { viewModel.setSize(SizeOption.SMALL) }
        binding.chipSizeCustom.setOnClickListener { showCustomSizeDialog() }

        binding.sliderQuality.addOnChangeListener { _, value, fromUser ->
            binding.tvQualityValue.text = value.roundToInt().toString()
            if (fromUser) viewModel.setQuality(value.roundToInt())
        }

        binding.chipFormatJpeg.setOnClickListener { viewModel.setFormat(Format.JPEG) }
        binding.chipFormatWebp.setOnClickListener { viewModel.setFormat(Format.WEBP) }

        binding.switchStrip.setOnCheckedChangeListener { _, checked -> viewModel.setStripMetadata(checked) }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.estimate.collect { renderEstimate(it) } }
                launch { viewModel.progress.collect { renderProgress(it) } }
                launch { viewModel.result.collect { report -> if (report != null) showReport(report) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is CompressViewModel.Event.Message ->
                                Toast.makeText(this@CompressActivity, event.text, Toast.LENGTH_LONG).show()
                            is CompressViewModel.Event.StartIntent -> try {
                                startActivity(event.intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(this@CompressActivity, "No app found to share", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun render(state: CompressViewModel.UiState) {
        val count = state.photos.size
        binding.tvSubtitle.text = when {
            state.loading && count == 0 -> "Loading…"
            else -> "${CompressViewModel.photoCount(count)} · ${CompressViewModel.formatSize(state.totalBytes)}"
        }
        thumbAdapter.submitList(state.photos.map { it.uri })

        val o = state.options
        binding.chipSizeOriginal.isChecked = o.size == SizeOption.ORIGINAL
        binding.chipSizeLarge.isChecked = o.size == SizeOption.LARGE
        binding.chipSizeMedium.isChecked = o.size == SizeOption.MEDIUM
        binding.chipSizeSmall.isChecked = o.size == SizeOption.SMALL
        binding.chipSizeCustom.isChecked = o.size == SizeOption.CUSTOM
        binding.chipSizeCustom.text = if (o.size == SizeOption.CUSTOM) "Custom · ${o.customPx}px" else "Custom"

        val q = o.quality.toFloat().coerceIn(binding.sliderQuality.valueFrom, binding.sliderQuality.valueTo)
        if (binding.sliderQuality.value != q) binding.sliderQuality.value = q
        binding.tvQualityValue.text = o.quality.toString()

        binding.chipFormatJpeg.isChecked = o.format == Format.JPEG
        binding.chipFormatWebp.isChecked = o.format == Format.WEBP

        if (binding.switchStrip.isChecked != o.stripMetadata) binding.switchStrip.isChecked = o.stripMetadata
        binding.tvMetadataNote.text = when {
            o.stripMetadata -> "GPS location, camera model and date aren't copied"
            o.format == Format.WEBP -> "WebP copies never include photo info"
            else -> "Date, camera and location are copied to the JPEG"
        }

        val canRun = count > 0 && state.photos.any { it.readable }
        binding.btnSave.isEnabled = canRun
        binding.btnShare.isEnabled = canRun
        binding.btnSave.alpha = if (canRun) 1f else 0.5f
        binding.btnShare.alpha = if (canRun) 1f else 0.5f
    }

    private fun renderEstimate(estimate: CompressViewModel.Estimate) {
        val state = viewModel.state.value
        binding.progressEstimate.visibility =
            if (estimate.computing || (state.loading && state.photos.isEmpty())) android.view.View.VISIBLE
            else android.view.View.INVISIBLE
        val total = estimate.totalBytes
        binding.tvEstimate.text = when {
            total != null -> {
                val sizeText = "≈ ${CompressViewModel.formatSize(total)} total"
                if (estimate.originalBytes > 0) {
                    val change = ((total - estimate.originalBytes) * 100.0 / estimate.originalBytes).roundToInt()
                    val sign = if (change < 0) "−" else "+"
                    "$sizeText ($sign${kotlin.math.abs(change)}%)"
                } else {
                    sizeText
                }
            }
            estimate.computing || state.loading -> "Estimating…"
            state.photos.isNotEmpty() && state.photos.none { it.readable } -> "These photos can't be read"
            else -> "Estimating…"
        }
        binding.tvEstimateNote.isVisible = total != null
    }

    private fun showCustomSizeDialog() {
        val dialogBinding = ItemCompressCustomSizeBinding.inflate(layoutInflater)
        val current = viewModel.state.value.options.customPx
        dialogBinding.etSize.setText(current.toString())
        dialogBinding.etSize.setSelection(dialogBinding.etSize.text?.length ?: 0)
        var applied = false
        val dialog = AppDialog.Builder(this)
            .setTitle("Custom size")
            .setView(dialogBinding.root)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun tryApply(): Boolean {
            val px = dialogBinding.etSize.text?.toString()?.trim()?.toIntOrNull()
            if (px == null || px < CompressViewModel.MIN_CUSTOM_PX || px > CompressViewModel.MAX_CUSTOM_PX) {
                dialogBinding.tilSize.error =
                    "Enter ${CompressViewModel.MIN_CUSTOM_PX}–${CompressViewModel.MAX_CUSTOM_PX} px"
                return false
            }
            applied = true
            viewModel.setCustomSize(px)
            dialog.dismiss()
            return true
        }

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener { tryApply() }
        }
        dialogBinding.etSize.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { tryApply(); true } else false
        }
        // The chip toggled itself on click; put the chips back if nothing was applied.
        dialog.setOnDismissListener { if (!applied) render(viewModel.state.value) }
        dialog.show()
        dialogBinding.etSize.requestFocus()
    }

    // ------------------------------------------------------------------ progress & result

    private fun renderProgress(progress: CompressViewModel.Progress?) {
        if (progress == null) {
            progressDialog?.dismiss()
            progressDialog = null
            progressBinding = null
            return
        }
        if (progressDialog == null) {
            val pb = ItemCompressProgressBinding.inflate(layoutInflater)
            progressBinding = pb
            progressDialog = AppDialog.Builder(this)
                .setView(pb.root)
                .setCancelable(false)
                .setNegativeButton("Cancel") { _, _ -> viewModel.cancel() }
                .show()
        }
        val pb = progressBinding ?: return
        val shown = (progress.done + 1).coerceAtMost(progress.total)
        val verb = if (progress.sharing) "Preparing" else "Compressing"
        pb.tvProgress.text = "$verb… $shown / ${progress.total}"
        val percent = if (progress.total > 0) progress.done * 100 / progress.total else 0
        pb.progressBar.setProgressCompat(percent, true)
    }

    private fun showReport(report: CompressViewModel.Report) {
        if (resultDialog?.isShowing == true) return
        resultDialog = AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
            .setTitle(report.title)
            .setMessage(report.message)
            .setPositiveButton("Done") { _, _ ->
                viewModel.consumeResult()
                finish()
            }
            .setNegativeButton("Keep editing") { _, _ -> viewModel.consumeResult() }
            .setOnCancelListener { viewModel.consumeResult() }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ thumbnails

    private class ThumbAdapter : ListAdapter<Uri, ThumbAdapter.Holder>(DIFF) {
        class Holder(val binding: ItemCompressThumbBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCompressThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.binding.ivThumb.loadThumbnail(getItem(position))
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<Uri>() {
                override fun areItemsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
                override fun areContentsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
            }
        }
    }
}
