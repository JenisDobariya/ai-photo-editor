package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photo_editor.my_photo_editor.databinding.ActivityQuickFixBinding
import com.photo_editor.my_photo_editor.ui.quickfix.FaceStatus
import com.photo_editor.my_photo_editor.ui.quickfix.QuickFixEvent
import com.photo_editor.my_photo_editor.ui.quickfix.QuickFixMode
import com.photo_editor.my_photo_editor.ui.quickfix.QuickFixUiState
import com.photo_editor.my_photo_editor.ui.quickfix.QuickFixViewModel
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.FaceBlurrer
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.launch

/**
 * One-tap photo fixes: "Auto enhance" (levels, colour, contrast, detail) and "Blur faces"
 * (privacy before sharing). Always saves a new copy; the original is never touched.
 */
class QuickFixActivity : BaseActivity() {

    companion object {
        /** Same key the rest of the app uses for a photo uri string. */
        const val EXTRA_IMAGE_URI = "IMAGE_URI"
        const val EXTRA_MODE = "QUICK_FIX_MODE"
        const val MODE_ENHANCE = "enhance"
        const val MODE_BLUR_FACES = "blur_faces"

        fun intent(context: Context, uri: Uri, mode: String): Intent =
            Intent(context, QuickFixActivity::class.java)
                .putExtra(EXTRA_IMAGE_URI, uri.toString())
                .putExtra(EXTRA_MODE, mode)
    }

    override fun getScreenTag(): String = "QuickFix"

    private lateinit var binding: ActivityQuickFixBinding
    private val viewModel: QuickFixViewModel by viewModels()

    private var comparing = false
    private var shownVersion = -1
    private var hintDismissed = false
    private var dialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickFixBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_BLUR_FACES) QuickFixMode.BLUR_FACES else QuickFixMode.ENHANCE

        setupBottomInset()
        setupUi()
        observe()
        viewModel.start(Uri.parse(uriString), mode)
    }

    private fun setupBottomInset() {
        val basePadding = binding.bottomControls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomControls) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = basePadding + nav.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.bottomControls)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUi() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("QuickFix_btnBack_click")
            finish()
        }
        binding.btnSave.setOnClickListener { viewModel.save() }

        binding.chipEnhance.setOnClickListener { viewModel.setMode(QuickFixMode.ENHANCE) }
        binding.chipBlurFaces.setOnClickListener { viewModel.setMode(QuickFixMode.BLUR_FACES) }

        binding.btnCompare.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    setComparing(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setComparing(false)
                    true
                }
                else -> true
            }
        }
        binding.btnCompare.setOnClickListener { }

        binding.sliderStrength.addOnChangeListener { _, value, fromUser ->
            binding.tvStrengthValue.text = value.toInt().toString()
            if (fromUser) viewModel.setStrength(value.toInt())
        }
        binding.sliderBlurStrength.addOnChangeListener { _, value, fromUser ->
            binding.tvBlurStrengthValue.text = value.toInt().toString()
            if (fromUser) viewModel.setBlurStrength(value.toInt())
        }

        binding.chipStyleBlur.setOnClickListener { viewModel.setBlurStyle(FaceBlurrer.Style.BLUR) }
        binding.chipStylePixelate.setOnClickListener { viewModel.setBlurStyle(FaceBlurrer.Style.PIXELATE) }
        binding.btnRetry.setOnClickListener { viewModel.retryFaceDetection() }

        binding.faceOverlay.onFaceTap = { index -> viewModel.toggleFace(index) }
    }

    private fun setComparing(on: Boolean) {
        if (comparing == on) return
        comparing = on
        if (on) {
            hintDismissed = true
            binding.tvCompareHint.isVisible = false
        }
        render(viewModel.state.value, forceImage = true)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    private fun render(state: QuickFixUiState, forceImage: Boolean = false) {
        val enhance = state.mode == QuickFixMode.ENHANCE
        binding.tvTitle.text = if (enhance) "Auto enhance" else "Blur faces"
        styleChip(binding.chipEnhance, enhance)
        styleChip(binding.chipBlurFaces, !enhance)
        binding.panelEnhance.isVisible = enhance
        binding.panelFaces.isVisible = !enhance

        // Preview image
        val image = if (comparing) state.original else (state.display ?: state.original)
        if (image != null && !image.isRecycled && (forceImage || state.displayVersion != shownVersion)) {
            binding.ivPreview.setImageBitmap(image)
            shownVersion = state.displayVersion
        }
        binding.tvOriginalBadge.isVisible = comparing && state.original != null
        binding.btnCompare.isEnabled = state.original != null
        if (!hintDismissed && state.original != null && state.loadingText == null) {
            hintDismissed = true
            binding.tvCompareHint.isVisible = true
            binding.tvCompareHint.postDelayed({ binding.tvCompareHint.isVisible = false }, 3500)
        }

        // Loading
        val detecting = !enhance && state.faceStatus == FaceStatus.DETECTING
        val loadingText = state.loadingText ?: if (detecting) "Looking for faces…" else null
        binding.loadingLayout.isVisible = loadingText != null
        if (loadingText != null) binding.tvLoadingText.text = loadingText
        binding.btnSave.isEnabled = !state.saving && state.original != null

        // Enhance panel
        binding.tvEnhanceNote.text = when {
            !state.enhanceReady -> "Balancing light, colour and detail…"
            state.alreadyGood -> "This photo is already well exposed, so only a light touch was applied."
            else -> "Light, colour and detail balanced automatically. Adjust the strength to taste."
        }
        if (binding.sliderStrength.value.toInt() != state.strength) binding.sliderStrength.value = state.strength.toFloat()

        // Faces panel
        val selectedCount = state.faces.count { it.selected }
        binding.tvFaceStatus.text = when (state.faceStatus) {
            FaceStatus.IDLE, FaceStatus.DETECTING -> "Looking for faces…"
            FaceStatus.READY -> {
                val total = state.faces.size
                val found = if (total == 1) "1 face found" else "$total faces found"
                "$found · $selectedCount blurred · tap a face to include or exclude it"
            }
            FaceStatus.NONE -> "No faces found in this photo"
            FaceStatus.UNAVAILABLE -> QuickFixViewModel.MSG_DETECTOR_NOT_READY
            FaceStatus.FAILED -> "Couldn't scan this photo for faces"
        }
        binding.btnRetry.isVisible = state.faceStatus == FaceStatus.UNAVAILABLE || state.faceStatus == FaceStatus.FAILED
        val facesReady = state.faceStatus == FaceStatus.READY
        listOf(binding.rowBlurStyle, binding.rowBlurStrength).forEach { it.alpha = if (facesReady) 1f else 0.4f }
        binding.chipStyleBlur.isEnabled = facesReady
        binding.chipStylePixelate.isEnabled = facesReady
        binding.sliderBlurStrength.isEnabled = facesReady
        styleChip(binding.chipStyleBlur, state.blurStyle == FaceBlurrer.Style.BLUR)
        styleChip(binding.chipStylePixelate, state.blurStyle == FaceBlurrer.Style.PIXELATE)
        if (binding.sliderBlurStrength.value.toInt() != state.blurStrength) binding.sliderBlurStrength.value = state.blurStrength.toFloat()

        // Face outlines
        val showOverlay = !enhance && facesReady && !comparing && state.original != null
        binding.faceOverlay.isVisible = showOverlay
        state.original?.let { binding.faceOverlay.setFaces(it.width, it.height, state.faces) }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        val color = if (selected) ContextCompat.getColor(this, R.color.white)
        else ContextCompat.getColor(this, R.color.lumina_text_secondary)
        chip.setTextColor(color)
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        TextViewCompat.setCompoundDrawableTintList(chip, ColorStateList.valueOf(color))
    }

    private fun handleEvent(event: QuickFixEvent) {
        when (event) {
            is QuickFixEvent.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is QuickFixEvent.Saved -> showSavedDialog(event.uri)
            QuickFixEvent.NoFaces -> showNoFacesDialog()
            QuickFixEvent.LoadFailed -> {
                Toast.makeText(this, "Couldn't open this photo", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showNoFacesDialog() {
        if (dialogShowing || isFinishing) return
        dialogShowing = true
        AppDialog.Builder(this)
            .setIcon(R.drawable.ic_t_face, R.color.lumina_gold)
            .setTitle("No faces found")
            .setMessage("There are no faces to blur in this photo.")
            .setPositiveButton("Leave") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    private fun showSavedDialog(savedUri: Uri) {
        if (isFinishing) return
        dialogShowing = true
        AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
            .setTitle("Photo saved")
            .setMessage("Saved as a new copy. Your original photo was not changed.")
            .addRow("Location", "Pictures/PhotoEditor")
            .setPositiveButton("View") { _, _ ->
                AnalyticsManager.logEvent("QuickFix_saved_view")
                startActivity(
                    Intent(this, PhotoDetailActivity::class.java)
                        .putExtra("IMAGE_URI", savedUri.toString())
                        .putExtra("IS_VIDEO", false)
                )
                finish()
            }
            .setNeutralButton("Share") { _, _ ->
                AnalyticsManager.logEvent("QuickFix_saved_share")
                ShareHelper.shareIntent(listOf(savedUri), "image/jpeg", "Share photo")?.let {
                    try {
                        startActivity(it)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No app available to share", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Done") { _, _ -> finish() }
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.ivPreview.setImageDrawable(null)
        super.onDestroy()
    }
}
