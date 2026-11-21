package com.photo_editor.my_photo_editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.card.MaterialCardView
import com.photo_editor.my_photo_editor.databinding.ActivityAiCutoutBinding
import com.photo_editor.my_photo_editor.utils.AiCutoutManager
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.FastBlurUtil
import com.photo_editor.my_photo_editor.utils.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiCutoutActivity : BaseActivity() {

    override fun getScreenTag(): String = "AiCutout"

    private lateinit var binding: ActivityAiCutoutBinding
    private var imageUri: Uri? = null

    private var originalBitmap: Bitmap? = null
    private var foregroundBitmap: Bitmap? = null
    private var currentDisplayBitmap: Bitmap? = null
    private var currentMask: com.google.mlkit.vision.segmentation.SegmentationMask? = null
    private var currentEdgeStyle = AiCutoutManager.EdgeStyle.SMOOTH

    private enum class CutoutMode { CUTOUT, BLUR, COLOR }
    private var currentMode = CutoutMode.CUTOUT

    private var blurRadius = 25
    private var selectedColor = Color.parseColor("#FFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiCutoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra("IMAGE_URI")
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        imageUri = Uri.parse(uriString)

        setupUI()
        processImage()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("AiCutout_btnBack_click")
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveImage()
        }

        // Compare Button
        binding.btnCompare.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    originalBitmap?.let { binding.ivPreview.setImageBitmap(it) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentDisplayBitmap?.let { binding.ivPreview.setImageBitmap(it) }
                    true
                }
                else -> false
            }
        }
        binding.btnCompare.setOnClickListener { }

        setupModeTabs()
        setupEdgeControls()
        setupBlurSlider()
        setupColorPalette()
    }

    private fun setupEdgeControls() {
        binding.chipEdgeSmooth.setOnClickListener { applyEdgeStyle(AiCutoutManager.EdgeStyle.SMOOTH) }
        binding.chipEdgeCrisp.setOnClickListener { applyEdgeStyle(AiCutoutManager.EdgeStyle.CRISP) }
        binding.chipEdgeFeather.setOnClickListener { applyEdgeStyle(AiCutoutManager.EdgeStyle.FEATHER) }
    }

    private fun updateEdgeChipsUI() {
        val chips = listOf(
            binding.chipEdgeSmooth to AiCutoutManager.EdgeStyle.SMOOTH,
            binding.chipEdgeCrisp to AiCutoutManager.EdgeStyle.CRISP,
            binding.chipEdgeFeather to AiCutoutManager.EdgeStyle.FEATHER
        )
        chips.forEach { (chip, style) ->
            val isSelected = (currentEdgeStyle == style)
            chip.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chip.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#94A3B8"))
            chip.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun applyEdgeStyle(style: AiCutoutManager.EdgeStyle) {
        if (currentEdgeStyle == style && foregroundBitmap != null) return
        currentEdgeStyle = style
        updateEdgeChipsUI()
        val orig = originalBitmap ?: return
        val mask = currentMask ?: return

        lifecycleScope.launch {
            binding.loadingLayout.isVisible = true
            binding.tvLoadingText.text = "Refining edges..."
            val fg = withContext(Dispatchers.Default) {
                AiCutoutManager.extractForeground(orig, mask, currentEdgeStyle)
            }
            foregroundBitmap = fg
            binding.loadingLayout.isVisible = false
            updateDisplay()
        }
    }

    private fun setupModeTabs() {
        val tabs = listOf(
            binding.tabCutout to (CutoutMode.CUTOUT to binding.panelCutout),
            binding.tabBlur to (CutoutMode.BLUR to binding.panelBlur),
            binding.tabColor to (CutoutMode.COLOR to binding.rvColors)
        )

        fun selectMode(selectedTab: TextView, mode: CutoutMode, panel: View) {
            tabs.forEach { (tabView, pair) ->
                val (m, p) = pair
                val isSelected = (tabView == selectedTab)
                tabView.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tabView.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#94A3B8"))
                p.isVisible = isSelected
            }
            currentMode = mode
            updateDisplay()
        }

        binding.tabCutout.setOnClickListener { selectMode(binding.tabCutout, CutoutMode.CUTOUT, binding.panelCutout) }
        binding.tabBlur.setOnClickListener { selectMode(binding.tabBlur, CutoutMode.BLUR, binding.panelBlur) }
        binding.tabColor.setOnClickListener { selectMode(binding.tabColor, CutoutMode.COLOR, binding.rvColors) }
    }

    private fun setupBlurSlider() {
        binding.sbBlurRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    blurRadius = progress.coerceAtLeast(1)
                    binding.tvBlurValue.text = blurRadius.toString()
                    updateDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupColorPalette() {
        val colors = listOf(
            Color.parseColor("#FFFFFF"), // Studio White
            Color.parseColor("#000000"), // Pure Black
            Color.parseColor("#0B0F19"), // Lumina Dark
            Color.parseColor("#1E293B"), // Dark Slate
            Color.parseColor("#6366F1"), // Lumina Accent
            Color.parseColor("#F59E0B"), // Lumina Gold
            Color.parseColor("#FCE7F3"), // Pastel Pink
            Color.parseColor("#E0F2FE"), // Pastel Sky
            Color.parseColor("#DCFCE7"), // Pastel Mint
            Color.parseColor("#FEF9C3"), // Pastel Cream
            Color.parseColor("#C084FC"), // Lavender
            Color.parseColor("#F87171")  // Coral
        )

        binding.rvColors.adapter = ColorAdapter(colors) { color ->
            selectedColor = color
            updateDisplay()
        }
    }

    private inner class ColorAdapter(
        private val colors: List<Int>,
        private val onSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<ColorAdapter.ViewHolder>() {
        private var selectedPosition = 0

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardColor)
            val colorView: View = view.findViewById(R.id.colorView)
            val ivCheck: ImageView = view.findViewById(R.id.ivCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_collage_color, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val color = colors[position]
            holder.colorView.setBackgroundColor(color)
            val isSelected = (position == selectedPosition)
            holder.ivCheck.isVisible = isSelected
            holder.card.strokeWidth = if (isSelected) 4 else 0

            holder.view.setOnClickListener {
                val old = selectedPosition
                selectedPosition = holder.bindingAdapterPosition
                notifyItemChanged(old)
                notifyItemChanged(selectedPosition)
                onSelected(color)
            }
        }

        override fun getItemCount(): Int = colors.size
    }

    private fun processImage() {
        binding.loadingLayout.isVisible = true
        binding.tvLoadingText.text = "Loading photo..."

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(this@AiCutoutActivity)
                        .data(imageUri)
                        .size(2048)
                        .allowHardware(false)
                        .build()
                    val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                    (result as? BitmapDrawable)?.bitmap
                } catch (e: Exception) {
                    null
                }
            }

            if (loaded == null) {
                binding.loadingLayout.isVisible = false
                Toast.makeText(this@AiCutoutActivity, "Failed to load photo", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            originalBitmap = loaded
            binding.ivPreview.setImageBitmap(loaded)

            binding.tvLoadingText.text = "AI extracting subject..."

            try {
                val mask = withContext(Dispatchers.Default) {
                    AiCutoutManager.segmentImage(loaded)
                }
                currentMask = mask

                if (!AiCutoutManager.isSubjectDetected(mask)) {
                    Toast.makeText(this@AiCutoutActivity, "Tip: AI Cutout works best on portraits and people", Toast.LENGTH_SHORT).show()
                }

                val foreground = withContext(Dispatchers.Default) {
                    AiCutoutManager.extractForeground(loaded, mask, currentEdgeStyle)
                }

                foregroundBitmap = foreground
                binding.loadingLayout.isVisible = false

                updateDisplay()
            } catch (e: Exception) {
                binding.loadingLayout.isVisible = false
                Toast.makeText(this@AiCutoutActivity, "AI Segmentation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateDisplay() {
        val fg = foregroundBitmap ?: return
        val orig = originalBitmap ?: return

        lifecycleScope.launch {
            val display = withContext(Dispatchers.Default) {
                when (currentMode) {
                    CutoutMode.CUTOUT -> fg
                    CutoutMode.BLUR -> {
                        AiCutoutManager.createPortraitBokeh(orig, fg, blurRadius)
                    }
                    CutoutMode.COLOR -> {
                        AiCutoutManager.composeWithColor(fg, selectedColor)
                    }
                }
            }
            currentDisplayBitmap = display
            binding.ivPreview.setImageBitmap(display)
        }
    }

    private fun saveImage() {
        val bitmapToSave = currentDisplayBitmap ?: return
        AnalyticsManager.logEvent("AiCutout_save_${currentMode.name}")

        binding.loadingLayout.isVisible = true
        binding.tvLoadingText.text = "Saving..."
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            val (format, prefix) = if (currentMode == CutoutMode.CUTOUT) {
                Bitmap.CompressFormat.PNG to "CUTOUT"
            } else {
                Bitmap.CompressFormat.JPEG to "PORTRAIT"
            }

            val savedUri = withContext(Dispatchers.IO) {
                ImageSaver.saveDirectBitmapToMediaStore(
                    context = this@AiCutoutActivity,
                    bitmap = bitmapToSave,
                    filenamePrefix = prefix,
                    format = format
                )
            }

            binding.loadingLayout.isVisible = false
            binding.btnSave.isEnabled = true

            if (savedUri != null) {
                showSaveSuccessDialog(savedUri, currentMode == CutoutMode.CUTOUT)
            } else {
                Toast.makeText(this@AiCutoutActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSaveSuccessDialog(savedUri: Uri, isPng: Boolean) {
        val formatDesc = if (isPng) "transparent PNG sticker" else "high-resolution photo"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Saved Successfully!")
            .setMessage("Your $formatDesc was saved to your gallery in the Pictures/PhotoEditor folder.")
            .setPositiveButton("View") { _, _ ->
                val intent = Intent(this, PhotoDetailActivity::class.java).apply {
                    putExtra("IMAGE_URI", savedUri.toString())
                    putExtra("IS_VIDEO", false)
                }
                startActivity(intent)
                finish()
            }
            .setNeutralButton("Share") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (isPng) "image/png" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, savedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Image"))
            }
            .setNegativeButton("Done") { _, _ ->
                finish()
            }
            .create()

        dialog.show()
    }
}
