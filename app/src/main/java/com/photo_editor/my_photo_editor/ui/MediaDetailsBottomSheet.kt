package com.photo_editor.my_photo_editor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.DialogMediaDetailsBinding
import com.photo_editor.my_photo_editor.model.MediaDetails
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.launch
import java.util.Locale

/** "Details" sheet: date, file, resolution, camera/EXIF and location. */
class MediaDetailsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogMediaDetailsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MediaDetailsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogMediaDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val uri = Uri.parse(requireArguments().getString(ARG_URI))
        viewModel.load(uri, requireArguments().getBoolean(ARG_IS_VIDEO))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.details.collect { details ->
                    binding.progressDetails.isVisible = details == null
                    if (details != null) render(details)
                }
            }
        }
    }

    private fun render(d: MediaDetails) {
        binding.tvDetailsDate.text = d.dateText ?: "Unknown date"
        binding.tvDetailsTime.text = d.timeText.orEmpty()
        binding.tvDetailsTime.isVisible = d.timeText != null

        val rows = binding.detailRows
        rows.removeAllViews()
        val fileInfo = listOfNotNull(d.megapixels, d.resolution, d.sizeText, d.durationText).joinToString("  ·  ")
        addRow(R.drawable.ic_g_photo, d.name, fileInfo.ifBlank { d.mimeType })
        d.path?.let { addRow(R.drawable.ic_g_folder, "Location on device", it) }
        if (d.camera != null || d.exposure != null) {
            addRow(R.drawable.ic_g_camera, d.camera ?: "Camera", d.exposure)
        }
        d.mimeType?.let { addRow(R.drawable.ic_g_file, "Format", formatLabel(it)) }

        val lat = d.latitude
        val lng = d.longitude
        if (lat != null && lng != null) {
            addRow(R.drawable.ic_g_location, "Location", String.format(Locale.US, "%.5f, %.5f", lat, lng))
            binding.btnOpenMap.isVisible = true
            binding.btnOpenMap.setOnClickListener {
                AnalyticsManager.logEvent("MediaDetails_openMap_click")
                val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, geo))
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "No maps app found", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            binding.btnOpenMap.isVisible = false
        }
    }

    private fun addRow(icon: Int, title: String, subtitle: String?) {
        val row = layoutInflater.inflate(R.layout.item_detail_row, binding.detailRows, false)
        row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = subtitle
            isVisible = !subtitle.isNullOrBlank()
        }
        binding.detailRows.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MediaDetailsBottomSheet"
        private const val ARG_URI = "uri"
        private const val ARG_IS_VIDEO = "is_video"

        fun newInstance(uri: Uri, isVideo: Boolean) = MediaDetailsBottomSheet().apply {
            arguments = bundleOf(ARG_URI to uri.toString(), ARG_IS_VIDEO to isVideo)
        }
    }

    /** "image/jpeg" -> "JPEG image", "video/mp4" -> "MP4 video". */
    private fun formatLabel(mime: String): String {
        val type = mime.substringBefore('/').lowercase(Locale.ROOT)
        val sub = mime.substringAfter('/', "").removePrefix("x-").uppercase(Locale.ROOT)
        return if (sub.isBlank()) mime else "$sub $type"
    }
}
