package com.photo_editor.my_photo_editor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.photo_editor.my_photo_editor.AiCutoutActivity
import com.photo_editor.my_photo_editor.BeforeAfterActivity
import com.photo_editor.my_photo_editor.CollageActivity
import com.photo_editor.my_photo_editor.databinding.FragmentStudioBinding
import com.photo_editor.my_photo_editor.utils.AnalyticsManager

class StudioFragment : Fragment() {

    private var _binding: FragmentStudioBinding? = null
    private val binding get() = _binding!!

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
