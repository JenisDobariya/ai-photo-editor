package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
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
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import com.photo_editor.my_photo_editor.utils.ImageSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AiCutoutActivity : BaseActivity() {

    override fun getScreenTag(): String = "AiCutout"

    // Photos look best on dark: this screen stays dark in every theme.
    override val alwaysDark = true

    private lateinit var binding: ActivityAiCutoutBinding
    private var imageUri: Uri? = null

    private var originalBitmap: Bitmap? = null
    private var foregroundBitmap: Bitmap? = null
    private var currentDisplayBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private var currentMask: AiCutoutManager.SubjectMask? = null
    // Small subject-free copy of the photo, blurred for the portrait mode (one per mask).
    private var backgroundPlate: Bitmap? = null
    private var currentEdgeStyle = AiCutoutManager.EdgeStyle.SMOOTH

    private enum class CutoutMode { CUTOUT, BLUR, COLOR }
    private var currentMode = CutoutMode.CUTOUT
    // Mode that currentDisplayBitmap was rendered in (decides PNG vs JPEG on save).
    private var displayedMode = CutoutMode.CUTOUT

    // Serialises all background work that reads or replaces the bitmaps above, so a bitmap is
    // never recycled while another job is still drawing or saving it.
    private val renderMutex = Mutex()
    private var renderJob: Job? = null
    // Bitmaps replaced on screen; recycled once nothing references them any more.
    private val retiredBitmaps = mutableListOf<Bitmap>()

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
                    (previewBitmap ?: currentDisplayBitmap)?.let { binding.ivPreview.setImageBitmap(it) }
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
            chip.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#9BA3AF"))
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
            try {
                renderMutex.withLock {
                    val fg = withContext(Dispatchers.Default) {
                        AiCutoutManager.extractForeground(orig, mask, style)
                    }
                    foregroundBitmap?.let { retiredBitmaps.add(it) }
                    foregroundBitmap = fg
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                Toast.makeText(this@AiCutoutActivity, "Couldn't refine edges", Toast.LENGTH_SHORT).show()
            }
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
                tabView.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#9BA3AF"))
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
            Color.parseColor("#0F1114"), // App dark
            Color.parseColor("#21252B"), // Dark slate
            Color.parseColor("#3D8BFD"), // App blue
            Color.parseColor("#F59E0B"), // Amber
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
        val uri = imageUri ?: return
        binding.loadingLayout.isVisible = true
        binding.tvLoadingText.text = "Loading photo..."

        lifecycleScope.launch {
            // EXIF-upright and capped so segmentation and edge refinement stay memory-safe.
            val loaded = withContext(Dispatchers.IO) {
                BitmapLoader.decodeUpright(this@AiCutoutActivity, uri, AiCutoutManager.WORKING_MAX_PX)
                    ?: loadWithCoil(uri)
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
                val outcome = AiCutoutManager.segmentImage(loaded)
                val mask = outcome.mask
                currentMask = mask

                if (outcome.usedPersonFallback) {
                    Toast.makeText(
                        this@AiCutoutActivity,
                        "The AI model is still downloading, so only people can be cut out for now. Try again in a minute for pets and objects.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (!AiCutoutManager.isSubjectDetected(mask)) {
                    Toast.makeText(this@AiCutoutActivity, "Tip: AI Cutout works best on a clear subject such as a person, pet or object", Toast.LENGTH_SHORT).show()
                }

                binding.tvLoadingText.text = "Refining edges..."
                val style = currentEdgeStyle
                renderMutex.withLock {
                    val (foreground, plate) = withContext(Dispatchers.Default) {
                        AiCutoutManager.extractForeground(loaded, mask, style) to
                            AiCutoutManager.createBackgroundPlate(loaded, mask)
                    }
                    foregroundBitmap?.let { retiredBitmaps.add(it) }
                    backgroundPlate?.let { retiredBitmaps.add(it) }
                    foregroundBitmap = foreground
                    backgroundPlate = plate
                }
                binding.loadingLayout.isVisible = false

                updateDisplay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                binding.loadingLayout.isVisible = false
                val message = if (e is OutOfMemoryError) "Not enough memory to process this photo"
                else "AI Segmentation failed: ${e.message}"
                Toast.makeText(this@AiCutoutActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun loadWithCoil(uri: Uri): Bitmap? = try {
        val request = ImageRequest.Builder(this@AiCutoutActivity)
            .data(uri)
            .size(AiCutoutManager.WORKING_MAX_PX)
            .allowHardware(false)
            .build()
        ((imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    } catch (e: Exception) {
        null
    }

    private fun updateDisplay() {
        if (foregroundBitmap == null || originalBitmap == null) return
        // Only the latest request matters (e.g. while dragging the blur slider).
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            renderMutex.withLock { renderLocked() }
        }
    }

    /** Builds the bitmap for the current mode and shows it. Call with [renderMutex] held. */
    private suspend fun renderLocked() {
        val fg = foregroundBitmap ?: return
        val orig = originalBitmap ?: return
        val mode = currentMode
        val radius = blurRadius
        val color = selectedColor
        val mask = currentMask

        val rendered = try {
            withContext(Dispatchers.Default) {
                val display = when (mode) {
                    CutoutMode.CUTOUT -> fg
                    CutoutMode.BLUR -> {
                        val plate = backgroundPlate
                            ?: mask?.let { AiCutoutManager.createBackgroundPlate(orig, it) }?.also { backgroundPlate = it }
                        if (plate != null) AiCutoutManager.createPortraitBokeh(plate, fg, radius) else fg
                    }
                    CutoutMode.COLOR -> AiCutoutManager.composeWithColor(fg, color)
                }
                // Transparent cutouts are previewed on a checkerboard; the saved PNG stays transparent.
                val preview = if (mode == CutoutMode.CUTOUT) AiCutoutManager.withCheckerboard(display) else display
                display to preview
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.printStackTrace()
            return
        }

        val (display, preview) = rendered
        currentDisplayBitmap?.let { retiredBitmaps.add(it) }
        previewBitmap?.let { retiredBitmaps.add(it) }
        currentDisplayBitmap = display
        previewBitmap = preview
        displayedMode = mode
        binding.ivPreview.setImageBitmap(preview)
        recycleRetiredBitmaps()
    }

    /** Recycles replaced bitmaps that are no longer shown, saved or used as a source. */
    private fun recycleRetiredBitmaps() {
        val iterator = retiredBitmaps.iterator()
        while (iterator.hasNext()) {
            val bmp = iterator.next()
            iterator.remove()
            val inUse = bmp === foregroundBitmap || bmp === originalBitmap ||
                bmp === currentDisplayBitmap || bmp === previewBitmap || bmp === backgroundPlate
            if (!inUse && !bmp.isRecycled) bmp.recycle()
        }
    }

    private fun saveImage() {
        if (currentDisplayBitmap == null) return

        binding.loadingLayout.isVisible = true
        binding.tvLoadingText.text = "Saving..."
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            // Holding the lock keeps the bitmap alive (not recycled by a re-render) while saving.
            val saved = renderMutex.withLock {
                val bitmapToSave = currentDisplayBitmap
                val isPng = displayedMode == CutoutMode.CUTOUT
                if (bitmapToSave == null || bitmapToSave.isRecycled) {
                    null
                } else {
                    AnalyticsManager.logEvent("AiCutout_save_${displayedMode.name}")
                    val (format, prefix) = if (isPng) {
                        Bitmap.CompressFormat.PNG to "CUTOUT"
                    } else {
                        Bitmap.CompressFormat.JPEG to "PORTRAIT"
                    }
                    val uri = try {
                        withContext(Dispatchers.IO) {
                            ImageSaver.saveDirectBitmapToMediaStore(
                                context = this@AiCutoutActivity,
                                bitmap = bitmapToSave,
                                filenamePrefix = prefix,
                                format = format
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Storage errors: report instead of crashing.
                        e.printStackTrace()
                        null
                    }
                    uri?.let { it to isPng }
                }
            }

            binding.loadingLayout.isVisible = false
            binding.btnSave.isEnabled = true

            if (saved != null) {
                showSaveSuccessDialog(saved.first, saved.second)
            } else {
                Toast.makeText(this@AiCutoutActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSaveSuccessDialog(savedUri: Uri, isPng: Boolean) {
        val formatDesc = if (isPng) "transparent PNG sticker" else "high-resolution photo"
        val dialog = AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
            .setTitle("Saved")
            .setMessage("Your $formatDesc is ready in your gallery.")
            .addRow("Location", "Pictures/PhotoEditor")
            .addRow("Format", if (isPng) "PNG · transparent" else "JPEG")
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
