package com.photo_editor.my_photo_editor.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.photo_editor.my_photo_editor.AiCutoutActivity
import com.photo_editor.my_photo_editor.BeforeAfterActivity
import com.photo_editor.my_photo_editor.CleanerActivity
import com.photo_editor.my_photo_editor.CollageActivity
import com.photo_editor.my_photo_editor.CompressActivity
import com.photo_editor.my_photo_editor.PhotosToPdfActivity
import com.photo_editor.my_photo_editor.QuickFixActivity
import com.photo_editor.my_photo_editor.TextLensActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.VideoToolsActivity
import com.photo_editor.my_photo_editor.databinding.FragmentStudioBinding
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.launch

class StudioFragment : Fragment() {

    private var _binding: FragmentStudioBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StudioViewModel by viewModels()

    /** Which Smart tool asked for the photo / video picker. */
    private var pendingTool: StudioViewModel.Tool? = null

    private val pickCollageMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size < 2) {
                Toast.makeText(requireContext(), "Please select at least 2 photos to create a collage", Toast.LENGTH_SHORT).show()
            } else {
                AnalyticsManager.logEvent("Studio_collage_launched", Bundle().apply {
                    putInt("photo_count", uris.size)
                })
                val intent = Intent(requireContext(), CollageActivity::class.java).apply {
                    putParcelableArrayListExtra("COLLAGE_URIS", ArrayList(uris))
                }
                startActivity(intent)
            }
        }
    }

    private val pickCutoutMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            AnalyticsManager.logEvent("Studio_aicutout_launched")
            val intent = Intent(requireContext(), AiCutoutActivity::class.java).apply {
                putExtra("IMAGE_URI", uri.toString())
            }
            startActivity(intent)
        }
    }

    private val pickBeforeAfterMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { uris ->
        if (uris.isNotEmpty()) {
            AnalyticsManager.logEvent("Studio_beforeafter_launched", Bundle().apply {
                putInt("photo_count", uris.size)
            })
            val intent = Intent(requireContext(), BeforeAfterActivity::class.java).apply {
                putExtra("BEFORE_URI", uris[0].toString())
                if (uris.size > 1) {
                    putExtra("AFTER_URI", uris[1].toString())
                }
            }
            startActivity(intent)
        }
    }

    /** One picker for every single-photo / single-video Smart tool. */
    private val pickToolMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val tool = pendingTool
        pendingTool = null
        if (uri != null && tool != null) openToolWith(tool, uri)
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        viewModel.saveScan(scan?.pdf?.uri, scan?.pages?.map { it.imageUri }.orEmpty())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupStudioTools()
        setupSmartTools()
        observeViewModel()
    }

    private fun setupStudioTools() {
        val startCollagePicker = {
            AnalyticsManager.logEvent("Studio_cardCollage_click")
            pickCollageMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.cardCollage.setOnClickListener { startCollagePicker() }
        binding.btnCreateCollage.setOnClickListener { startCollagePicker() }

        val startCutoutPicker = {
            AnalyticsManager.logEvent("Studio_cardAiCutout_click")
            pickCutoutMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.cardAiCutout.setOnClickListener { startCutoutPicker() }
        binding.btnTryCutout.setOnClickListener { startCutoutPicker() }

        val startBeforeAfterPicker = {
            AnalyticsManager.logEvent("Studio_cardBeforeAfter_click")
            pickBeforeAfterMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.cardBeforeAfter.setOnClickListener { startBeforeAfterPicker() }
        binding.btnCreateComparison.setOnClickListener { startBeforeAfterPicker() }
    }

    // ------------------------------------------------------------------ smart tools

    private fun setupSmartTools() {
        binding.rvSmartTools.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        binding.rvSmartTools.adapter = ToolAdapter(viewModel.tools) { onToolClick(it.tool) }

        val openCleanup = View.OnClickListener { onToolClick(StudioViewModel.Tool.CLEAN_UP) }
        binding.cardCleanup.setOnClickListener(openCleanup)
        binding.btnStartCleanup.setOnClickListener(openCleanup)
    }

    private fun onToolClick(tool: StudioViewModel.Tool) {
        AnalyticsManager.logEvent("Studio_tool_${tool.name.lowercase()}")
        val context = requireContext()
        when (tool) {
            StudioViewModel.Tool.CLEAN_UP -> startActivity(CleanerActivity.intent(context))
            StudioViewModel.Tool.SCAN -> startDocumentScan()
            StudioViewModel.Tool.PDF -> startActivity(PhotosToPdfActivity.intent(context, emptyList()))
            StudioViewModel.Tool.COMPRESS -> startActivity(CompressActivity.intent(context, emptyList()))
            StudioViewModel.Tool.ENHANCE, StudioViewModel.Tool.BLUR_FACES, StudioViewModel.Tool.COPY_TEXT -> {
                pendingTool = tool
                pickToolMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StudioViewModel.Tool.TRIM, StudioViewModel.Tool.GIF -> {
                pendingTool = tool
                pickToolMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }
    }

    private fun openToolWith(tool: StudioViewModel.Tool, uri: Uri) {
        val context = context ?: return
        when (tool) {
            StudioViewModel.Tool.ENHANCE -> startActivity(QuickFixActivity.intent(context, uri, QuickFixActivity.MODE_ENHANCE))
            StudioViewModel.Tool.BLUR_FACES -> startActivity(QuickFixActivity.intent(context, uri, QuickFixActivity.MODE_BLUR_FACES))
            StudioViewModel.Tool.COPY_TEXT -> startActivity(TextLensActivity.intent(context, uri))
            StudioViewModel.Tool.TRIM -> startActivity(VideoToolsActivity.intent(context, uri, VideoToolsActivity.MODE_TRIM))
            StudioViewModel.Tool.GIF -> startActivity(VideoToolsActivity.intent(context, uri, VideoToolsActivity.MODE_GIF))
            else -> Unit
        }
    }

    /** Google's on-device document scanner: edge detection, crop, clean-up filters, multi-page. */
    private fun startDocumentScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(requireActivity())
            .addOnSuccessListener { sender ->
                if (_binding != null) scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                context?.let {
                    Toast.makeText(it, "Document scanner isn't available yet. Make sure Google Play services is up to date.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cleanupStatus.collect { status ->
                    binding.tvCleanupStats.text = status.text
                    binding.progressCleanup.isVisible = status.running
                    if (status.running) binding.progressCleanup.setProgressCompat(status.percent, true)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    val context = context ?: return@collect
                    when (event) {
                        is StudioViewModel.Event.ScanSaved -> showScanSaved(event)
                        is StudioViewModel.Event.PagesSaved -> Toast.makeText(
                            context, "Saved ${event.count} page(s) to Pictures/PhotoEditor", Toast.LENGTH_SHORT
                        ).show()
                        is StudioViewModel.Event.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showScanSaved(event: StudioViewModel.Event.ScanSaved) {
        val context = context ?: return
        val builder = AppDialog.Builder(context)
            .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
            .setTitle("Scan saved")
            .addRow("Location", "Documents/PhotoEditor")
            .addRow("Pages", event.pageUris.size.coerceAtLeast(1).toString())
            .setPositiveButton("Open") { _, _ ->
                try {
                    startActivity(ShareHelper.viewIntent(event.pdfUri, "application/pdf"))
                } catch (e: Exception) {
                    Toast.makeText(context, "No PDF viewer found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Share") { _, _ ->
                ShareHelper.shareIntent(listOf(event.pdfUri), "application/pdf")?.let { startActivity(it) }
            }
        if (event.pageUris.isNotEmpty()) {
            builder.setNegativeButton("Save as photos") { _, _ -> viewModel.savePagesAsPhotos(event.pageUris) }
        }
        builder.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Smart tools grid: Albums-style icon tile, label and an optional "AI" badge. */
    private class ToolAdapter(
        private val items: List<StudioViewModel.ToolItem>,
        private val onClick: (StudioViewModel.ToolItem) -> Unit
    ) : RecyclerView.Adapter<ToolAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivToolIcon)
            val title: TextView = view.findViewById(R.id.tvToolTitle)
            val badge: TextView = view.findViewById(R.id.tvToolBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_studio_tool, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.icon)
            holder.icon.setColorFilter(ContextCompat.getColor(holder.itemView.context, item.tint))
            holder.title.text = item.title
            holder.badge.isVisible = item.badge != null
            holder.badge.text = item.badge
            holder.itemView.contentDescription = item.title
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    private companion object {
        const val GRID_COLUMNS = 4
    }
}
