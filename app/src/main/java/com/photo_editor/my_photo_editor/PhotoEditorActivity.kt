package com.photo_editor.my_photo_editor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.photo_editor.my_photo_editor.databinding.ActivityMainBinding
import com.photo_editor.my_photo_editor.databinding.ItemAdjustmentBinding
import com.photo_editor.my_photo_editor.databinding.ItemFilterBinding
import com.photo_editor.my_photo_editor.databinding.ItemEditorModeBinding
import com.photo_editor.my_photo_editor.model.EditorLayer
import com.photo_editor.my_photo_editor.ui.ColorWheelView
import com.photo_editor.my_photo_editor.ui.DrawingView
import com.photo_editor.my_photo_editor.model.LayerContent
import com.photo_editor.my_photo_editor.ui.PhotoEditorViewModel
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.ColorMatrixBuilder
import com.photo_editor.my_photo_editor.utils.FilterPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class PhotoEditorActivity : BaseActivity() {

    override fun getScreenTag(): String = "PhotoEditor"

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PhotoEditorViewModel by viewModels()
    private var activeCropMode = "straighten"

    data class StickerCategory(
        val name: String,
        val isCustom: Boolean = false,
        val stickers: List<String> = emptyList()
    )

    private val stickerCategories = listOf(
        StickerCategory(
            name = "🔥 Trending",
            stickers = listOf("✨", "🔥", "❤️", "👑", "🌟", "🎉", "💖", "💯", "🚀", "⚡", "😍", "💎", "🦋", "🥳", "😎", "🌈", "🌸", "⭐", "📸", "👏")
        ),
        StickerCategory(
            name = "😃 Emojis",
            stickers = listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥹", "😊", "😇", "🙂", "🙃", "😉", "😌",
                "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫",
                "🤔", "🫡", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥", "😌", "😴",
                "😷", "🤒", "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "🥸", "😎",
                "🤓", "🧐", "😕", "😟", "🙁", "😮", "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢",
                "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿",
                "💀", "💩", "🤡", "👹", "👻", "👽", "🤖"
            )
        ),
        StickerCategory(
            name = "❤️ Love",
            stickers = listOf("❤️", "💖", "💕", "💞", "💓", "💗", "💘", "💝", "💟", "💌", "❤️‍🔥", "❤️‍🩹", "❣️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💐", "🌸", "🌹", "🥀", "🌺", "🌻", "🌼", "🌷", "🪻", "💍", "🕊️", "✨", "💫", "⭐", "🌟")
        ),
        StickerCategory(
            name = "🎉 Party",
            stickers = listOf("🎉", "🎊", "🎈", "🎂", "🍰", "🧁", "🍾", "🥂", "🍻", "🍺", "🎁", "🎀", "🪄", "🪅", "🪩", "🥳", "👏", "🙌", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🎫", "🎟️", "🍿", "🍩", "🍪", "🍫", "🍬", "🍭", "🎸", "🎵", "🎶")
        ),
        StickerCategory(
            name = "🌸 Nature",
            stickers = listOf("🌸", "🌺", "🌹", "🌻", "🌼", "🌷", "💐", "🪷", "🌱", "🪴", "🌲", "🌳", "🌴", "🍀", "🌿", "🍃", "🍂", "🍁", "🍄", "🦋", "🐝", "🐞", "🐶", "🐱", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐵", "🦄", "🌈", "☀️", "🌙", "⭐", "☁️", "⚡", "❄️")
        ),
        StickerCategory(
            name = "🍕 Food",
            stickers = listOf("🍕", "🍔", "🍟", "🌭", "🍿", "🥞", "🧇", "🧀", "🥩", "🍗", "🌮", "🌯", "🥪", "🍝", "🍜", "🍣", "🍱", "🥟", "🍤", "🍦", "🍧", "🍨", "🍩", "🍪", "🎂", "🍰", "🧁", "🍫", "🍬", "🍭", "☕", "🧋", "🍵", "🥤", "🧃", "🍺", "🥂", "🍷")
        ),
        StickerCategory(
            name = "🕶️ Cool",
            stickers = listOf("🕶️", "🎩", "👑", "💎", "💍", "🔥", "⚡", "🚀", "💯", "🎯", "🎲", "🎸", "🎵", "🎶", "🎧", "📸", "🏎️", "🏍️", "🛸", "🔮", "🧿", "💰", "💵", "💸", "🏆", "🥇", "🥊", "🥋", "🛹", "🏄")
        ),
        StickerCategory(
            name = "🖼️ Custom",
            isCustom = true
        )
    )

    private var selectedStickerCategoryIndex = 0

    private val pickCustomStickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            AnalyticsManager.logEvent("PhotoEditor_custom_sticker_added")
            val cx = if (binding.layersContainer.width > 0) {
                (binding.layersContainer.width / 2f) - 50.dpToPx()
            } else 200f
            val cy = if (binding.layersContainer.height > 0) {
                (binding.layersContainer.height / 2f) - 50.dpToPx()
            } else 300f
            val layer = EditorLayer(
                content = LayerContent.Sticker(it.toString()),
                x = cx,
                y = cy
            )
            viewModel.addLayer(layer)
            viewModel.setSelectedLayerId(layer.id)
            Toast.makeText(this, "Sticker added!", Toast.LENGTH_SHORT).show()
        }
    }

    data class FontStyleItem(
        val name: String,
        val typeface: android.graphics.Typeface,
        val isNeon: Boolean = false,
        val letterSpacing: Float = 0f
    )

    data class TextBadgeItem(val name: String, val color: Int?, val textColor: Int = Color.WHITE)

    private val fontStyles by lazy {
        listOf(
            FontStyleItem("Classic", android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)),
            FontStyleItem("Bold", android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)),
            FontStyleItem("Modern", android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL), letterSpacing = 0.08f),
            FontStyleItem("Serif", android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)),
            FontStyleItem("Neon", android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD), isNeon = true),
            FontStyleItem("Cursive", android.graphics.Typeface.create("cursive", android.graphics.Typeface.BOLD)),
            FontStyleItem("Casual", android.graphics.Typeface.create("casual", android.graphics.Typeface.BOLD)),
            FontStyleItem("Code", android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD))
        )
    }

    private val textColors = listOf(
        Color.WHITE,
        Color.parseColor("#F59E0B"), // Lumina Gold
        Color.parseColor("#EF4444"), // Red
        Color.parseColor("#EC4899"), // Hot Pink
        Color.parseColor("#A855F7"), // Purple
        Color.parseColor("#3B82F6"), // Blue
        Color.parseColor("#06B6D4"), // Cyan
        Color.parseColor("#10B981"), // Emerald
        Color.parseColor("#84CC16"), // Lime
        Color.parseColor("#F97316"), // Orange
        Color.parseColor("#18181B")  // Black
    )

    private val textBadges = listOf(
        TextBadgeItem("None", null),
        TextBadgeItem("Dark", Color.parseColor("#B318181B")),
        TextBadgeItem("Gold", Color.parseColor("#F59E0B"), textColor = Color.BLACK),
        TextBadgeItem("White", Color.WHITE, textColor = Color.BLACK),
        TextBadgeItem("Indigo", Color.parseColor("#6366F1")),
        TextBadgeItem("Red", Color.parseColor("#EF4444")),
        TextBadgeItem("Emerald", Color.parseColor("#10B981"))
    )

    data class TextShadowPreset(val name: String, val radius: Float, val dx: Float, val dy: Float, val color: Int)

    private val textShadowPresets = listOf(
        TextShadowPreset("None", 0f, 0f, 0f, Color.TRANSPARENT),
        TextShadowPreset("Soft", 8f, 2f, 2f, Color.parseColor("#99000000")),
        TextShadowPreset("Hard", 1f, 4f, 4f, Color.BLACK),
        TextShadowPreset("Neon", 18f, 0f, 0f, Color.parseColor("#00E5FF")),
        TextShadowPreset("Glow", 14f, 0f, 0f, Color.WHITE)
    )

    private var activeFontIndex = 0
    private var activeTextColor = Color.WHITE
    private var activeBadgeIndex = 0
    private var activeShadowIndex = 0

    override fun setupSystemBarInsets(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            val topInset = maxOf(statusBars.top, displayCutout.top)
            val isKeyboardOpen = imeInsets.bottom > 0
            val bottomInset = if (isKeyboardOpen) imeInsets.bottom else navInsets.bottom

            v.updatePadding(
                left = maxOf(statusBars.left, displayCutout.left),
                top = topInset,
                right = maxOf(statusBars.right, displayCutout.right),
                bottom = bottomInset
            )

            // When typing text and keyboard opens, hide bottomModeNav to maximize preview space
            if (isKeyboardOpen && binding.textControls.isVisible) {
                binding.bottomModeNav.isVisible = false
            } else {
                binding.bottomModeNav.isVisible = true
            }

            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()

        intent.getStringExtra("IMAGE_URI")?.let { uriString ->
            viewModel.onImageSelected(uriString.toUri())
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_btnBack_click")
            finish()
        }
        binding.btnUndo.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_btnUndo_click")
            viewModel.undo()
            updatePreview()
        }
        binding.btnRedo.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_btnRedo_click")
            viewModel.redo()
            updatePreview()
        }
        binding.btnSave.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_btnSave_click")
            viewModel.setSelectedLayerId(null)
            val drawMarkupAction: ((android.graphics.Canvas) -> Unit)? = if (binding.drawingView.hasDrawings() || viewModel.layers.value.isNotEmpty()) {
                { canvas ->
                    if (binding.drawingView.hasDrawings()) {
                        binding.drawingView.drawOntoCanvas(canvas)
                    }
                    if (viewModel.layers.value.isNotEmpty()) {
                        val lw = binding.layersContainer.width.toFloat()
                        val lh = binding.layersContainer.height.toFloat()
                        if (lw > 0 && lh > 0) {
                            val scaleX = canvas.width.toFloat() / lw
                            val scaleY = canvas.height.toFloat() / lh
                            canvas.save()
                            canvas.scale(scaleX, scaleY)
                            binding.layersContainer.draw(canvas)
                            canvas.restore()
                        }
                    }
                }
            } else null
            viewModel.saveImage(this, drawMarkupAction)
        }
        binding.layersContainer.setOnClickListener {
            viewModel.setSelectedLayerId(null)
        }

        // Compare Logic
        binding.btnCompare.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                    binding.photoPreview.colorFilter = null
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    updatePreview()
                    true
                }
                else -> false
            }
        }
        binding.btnCompare.setOnClickListener { } // Ensure performClick works

        // Filter Intensity Slider
        binding.filterIntensitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.filterIntensity.value = progress / 100f
                    binding.tvFilterIntensityValue.text = "${progress}%"
                    updateFilterSliderProgress(progress / 100f)
                    updatePreview()
                    (binding.filterList.adapter as? FilterAdapter)?.updateIntensity(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                viewModel.recordSnapshot()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        setupFilterList()
        setupAdjustmentUI()
        setupTextUI()
        setupStickersUI()
        setupRatioList()
        setupModeList()
        setupMarkupUI()

        binding.straightenRuler.setOnDegreesChangedListener { degrees ->
            when (activeCropMode) {
                "straighten" -> viewModel.straighten.value = degrees
                "vertical" -> viewModel.rotationX.value = degrees
                "horizontal" -> viewModel.rotationY.value = degrees
            }
            updatePreview()
        }

        // Crop Actions
        binding.btnRotate.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_btnRotate_click")
            viewModel.recordSnapshot()
            viewModel.rotation.value = (viewModel.rotation.value + 90f) % 360f
        }
        binding.btnMirror.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_btnMirror_click")
            viewModel.recordSnapshot()
            viewModel.flipHorizontal.value = !viewModel.flipHorizontal.value
        }

        binding.btnStraighten.setOnClickListener { selectCropMode("straighten") }
        binding.btnCropVertical.setOnClickListener { selectCropMode("vertical") }
        binding.btnCropHorizontal.setOnClickListener { selectCropMode("horizontal") }
        binding.btnCropAuto.setOnClickListener { selectCropMode("auto") }
        binding.btnCropAction.setOnClickListener { selectCropMode("crop") }

        showControlPanel(binding.filterControls)
        binding.tvTitle.text = "Filter"
    }

    private fun setupModeList() {
        val modes = listOf(
            EditorMode(R.id.mode_filter, "Filter", R.drawable.ic_studio),
            EditorMode(R.id.mode_adjust, "Adjust", R.drawable.ic_tune),
            EditorMode(R.id.mode_crop, "Crop", R.drawable.ic_crop),
            EditorMode(R.id.mode_markup, "Markup", R.drawable.ic_markup),
            EditorMode(R.id.mode_text, "Text", R.drawable.ic_text),
            EditorMode(R.id.mode_stickers, "Stickers", R.drawable.ic_stickers)
        )

        binding.bottomModeNav.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.bottomModeNav.adapter = ModeAdapter(modes) { mode ->
            when (mode.id) {
                R.id.mode_filter -> {
                    showControlPanel(binding.filterControls)
                    binding.tvTitle.text = "Filter"
                }
                R.id.mode_adjust -> {
                    showControlPanel(binding.adjustmentControls)
                    binding.tvTitle.text = "Adjust"
                    setupAdjustmentUI()
                }
                R.id.mode_crop -> {
                    showControlPanel(binding.cropControls)
                    binding.tvTitle.text = "Crop"
                    selectCropMode("straighten")
                }
                R.id.mode_markup -> {
                    showControlPanel(binding.markupControls)
                    binding.tvTitle.text = "Markup"
                    binding.drawingView.isVisible = true
                    binding.drawingView.bringToFront()
                    binding.drawingView.isEnabled = true
                }
                R.id.mode_text -> {
                    showControlPanel(binding.textControls)
                    binding.tvTitle.text = "Text"
                }
                R.id.mode_stickers -> {
                    showControlPanel(binding.stickersControls)
                    binding.tvTitle.text = "Stickers"
                }
            }
        }
    }

    private fun setupRatioList() {
        val ratios = listOf(
            AspectRatio("Original", null),
            AspectRatio("1:1", 1f),
            AspectRatio("4:5", 0.8f),
            AspectRatio("9:16", 9f / 16f),
            AspectRatio("3:4", 3f / 4f),
            AspectRatio("2:3", 2f / 3f),
            AspectRatio("16:9", 16f / 9f)
        )
        binding.ratioList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.ratioList.adapter = RatioAdapter(ratios) { ratio ->
            viewModel.cropRatio.value = ratio.value
            binding.cropOverlay.aspectRatio = ratio.value
        }
    }

    data class AspectRatio(val name: String, val value: Float?)

    inner class RatioAdapter(
        private val list: List<AspectRatio>,
        private val onSelected: (AspectRatio) -> Unit
    ) : RecyclerView.Adapter<RatioAdapter.ViewHolder>() {
        private var selectedIndex = 0
        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.tvRatioIcon)
            val name: TextView = view.findViewById(R.id.tvRatioName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ratio, parent, false)
            return ViewHolder(v)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.icon.text = item.name
            val isSelected = selectedIndex == position
            holder.icon.setBackgroundResource(if (isSelected) R.drawable.bg_circle_accent else R.drawable.bg_pill_dark)
            holder.icon.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
            holder.view.setOnClickListener {
                val old = selectedIndex
                selectedIndex = holder.adapterPosition
                notifyItemChanged(old)
                notifyItemChanged(selectedIndex)
                onSelected(item)
            }
        }
        override fun getItemCount() = list.size
    }

    private fun selectCropMode(mode: String) {
        activeCropMode = mode
        binding.btnStraighten.alpha = 0.5f
        binding.btnCropVertical.alpha = 0.5f
        binding.btnCropHorizontal.alpha = 0.5f
        binding.btnCropAction.alpha = 0.5f
        
        binding.straightenRuler.isVisible = mode != "crop"
        binding.ratioList.isVisible = mode == "crop"
        binding.cropOverlay.isVisible = true // Always show overlay when in crop mode
        binding.cropOverlay.setImageRect(getImageBounds())

        when (mode) {
            "straighten" -> {
                binding.btnStraighten.alpha = 1f
                binding.straightenRuler.setRange(-3600f, 3600f)
                binding.straightenRuler.setDegrees(viewModel.straighten.value)
            }
            "vertical" -> {
                binding.btnCropVertical.alpha = 1f
                binding.straightenRuler.setRange(-45f, 45f)
                binding.straightenRuler.setDegrees(viewModel.rotationX.value)
            }
            "horizontal" -> {
                binding.btnCropHorizontal.alpha = 1f
                binding.straightenRuler.setRange(-45f, 45f)
                binding.straightenRuler.setDegrees(viewModel.rotationY.value)
            }
            "crop" -> {
                binding.btnCropAction.alpha = 1f
            }
            "auto" -> {
                viewModel.straighten.value = 0f
                viewModel.rotationX.value = 0f
                viewModel.rotationY.value = 0f
                binding.straightenRuler.setDegrees(0f)
                binding.cropOverlay.aspectRatio = null // Reset ratio to original
                binding.cropOverlay.resetCropRect()
                updatePreview()
            }
        }
    }

    private fun showControlPanel(panel: View) {
        // If we're leaving crop mode, save the crop rect
        if (binding.cropControls.isVisible && panel != binding.cropControls) {
            viewModel.normalizedCropRect.value = binding.cropOverlay.getNormalizedCropRect()
        }

        binding.filterControls.isVisible = panel == binding.filterControls
        binding.adjustmentControls.isVisible = panel == binding.adjustmentControls
        binding.cropControls.isVisible = panel == binding.cropControls
        binding.markupControls.isVisible = panel == binding.markupControls
        binding.textControls.isVisible = panel == binding.textControls
        binding.stickersControls.isVisible = panel == binding.stickersControls
        binding.watermarkPicker.isVisible = panel == binding.watermarkPicker
        binding.pixelateControls.isVisible = panel == binding.pixelateControls
        binding.drawingView.isVisible = (panel == binding.markupControls)
        if (panel == binding.markupControls) {
            binding.drawingView.bringToFront()
            binding.drawingView.isEnabled = true
        }
        
        if (panel != binding.cropControls) {
            binding.cropOverlay.isVisible = false
            updatePreviewZoom()
        } else {
            // When entering crop mode, reset zoom to show full image for adjustment
            resetPreviewZoom()
            binding.cropOverlay.isVisible = true
        }
    }

    private fun updatePreviewZoom() {
        val crop = viewModel.normalizedCropRect.value ?: return
        
        binding.photoPreview.post {
            val viewWidth = binding.photoPreview.width.toFloat()
            val viewHeight = binding.photoPreview.height.toFloat()
            if (viewWidth <= 0 || viewHeight <= 0) return@post

            val bounds = getImageBounds()
            if (bounds.isEmpty) return@post

            val cropWidth = crop.width() * bounds.width()
            val cropHeight = crop.height() * bounds.height()
            if (cropWidth <= 0 || cropHeight <= 0) return@post

            // Target size is the view size
            val scale = Math.min(viewWidth / cropWidth, viewHeight / cropHeight)
            
            // cX in view coordinates
            val cX = bounds.left + (crop.centerX() * bounds.width())
            val cY = bounds.top + (crop.centerY() * bounds.height())

            val dx = (viewWidth / 2f - cX) * scale
            val dy = (viewHeight / 2f - cY) * scale

            binding.photoPreview.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationX(dx)
                .translationY(dy)
                .setDuration(300)
                .start()
        }
    }

    private fun getImageBounds(): android.graphics.RectF {
        val drawable = binding.photoPreview.drawable ?: return android.graphics.RectF()
        val viewWidth = binding.photoPreview.width.toFloat()
        val viewHeight = binding.photoPreview.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return android.graphics.RectF()

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        
        val scale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val w = drawableWidth * scale
        val h = drawableHeight * scale
        val x = (viewWidth - w) / 2f
        val y = (viewHeight - h) / 2f
        
        return android.graphics.RectF(x, y, x + w, y + h)
    }

    private fun resetPreviewZoom() {
        binding.photoPreview.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(300)
            .start()
    }

    private fun setupFilterList() {
        val filters = mutableListOf<Pair<String, ColorMatrix>>()
        FilterPresets.AllCategories.forEach { it.second.forEach { filter -> filters.add(filter) } }
        binding.filterList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.filterList.adapter = FilterAdapter(filters) { matrix ->
            viewModel.selectedFilter.value = matrix
            updatePreview()
        }
    }

    private var selectedAdjustment: AdjustmentType? = null
    private fun setupAdjustmentUI() {
        val adjustments = listOf(
            AdjustmentType("Auto", R.drawable.ic_auto),
            AdjustmentType("Exposure", R.drawable.ic_exposure),
            AdjustmentType("Brilliance", R.drawable.ic_brilliance),
            AdjustmentType("Highlights", R.drawable.ic_highlights),
            AdjustmentType("Shadows", R.drawable.ic_shadows),
            AdjustmentType("Contrast", R.drawable.ic_tune),
            AdjustmentType("Saturation", R.drawable.ic_studio),
            AdjustmentType("Warmth", R.drawable.ic_warmth)
        )
        binding.adjustmentPicker.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.adjustmentPicker.adapter = AdjustmentAdapter(adjustments) { type ->
            if (type.name == "Auto") applyAutoAdjustments()
            else {
                selectedAdjustment = type
                updateAdjustmentSlider(type)
            }
        }

        binding.adjustmentSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && selectedAdjustment != null) {
                    val value = (progress - 100) / 100f
                    applyAdjustment(selectedAdjustment!!.name, value)
                    updatePreview()
                    updateSliderProgress(value)
                    (binding.adjustmentPicker.adapter as? AdjustmentAdapter)?.notifyDataSetChanged()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                viewModel.recordSnapshot()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        if (adjustments.size > 1) {
            selectedAdjustment = adjustments[1]
            updateAdjustmentSlider(adjustments[1])
        }
    }

    private fun setupRetouchUI() {
        val items = listOf(
            AdjustmentType("Skin tone", R.drawable.ic_retouch),
            AdjustmentType("Skin texture", R.drawable.ic_retouch),
            AdjustmentType("Spot healing", R.drawable.ic_retouch),
            AdjustmentType("Cheeks", R.drawable.ic_retouch),
            AdjustmentType("Eye size", R.drawable.ic_retouch)
        )
        binding.adjustmentPicker.adapter = AdjustmentAdapter(items) { type ->
            selectedAdjustment = type
            updateAdjustmentSlider(type)
        }
    }

    private fun setupMarkupUI() {
        val tools = listOf(binding.toolPen, binding.toolPencil, binding.toolHighlighter, binding.toolFountain, binding.toolEraser)
        
        fun selectTool(selected: View, brushType: DrawingView.BrushType) {
            tools.forEach { tool ->
                val params = tool.layoutParams
                params.height = if (tool == selected) {
                    (72f * resources.displayMetrics.density).toInt()
                } else {
                    (48f * resources.displayMetrics.density).toInt()
                }
                tool.layoutParams = params
                tool.alpha = if (tool == selected) 1f else 0.5f
            }
            binding.drawingView.brushType = brushType
        }

        binding.toolPen.setOnClickListener { selectTool(it, DrawingView.BrushType.PEN) }
        binding.toolPencil.setOnClickListener { selectTool(it, DrawingView.BrushType.PENCIL) }
        binding.toolHighlighter.setOnClickListener { selectTool(it, DrawingView.BrushType.HIGHLIGHTER) }
        binding.toolFountain.setOnClickListener { selectTool(it, DrawingView.BrushType.FOUNTAIN) }
        binding.toolEraser.setOnClickListener { selectTool(it, DrawingView.BrushType.ERASER) }

        selectTool(binding.toolPen, DrawingView.BrushType.PEN)

        binding.btnMarkupColor.setOnClickListener {
            showColorPickerDialog()
        }
        
        updateColorPickerRing(Color.RED)
    }

    private fun showColorPickerDialog() {
        AnalyticsManager.logEvent("PhotoEditor_showColorPickerDialog")
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_color_picker, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val preview = dialogView.findViewById<View>(R.id.selectedColorPreview)
        val presetGrid = dialogView.findViewById<GridLayout>(R.id.presetColorsGrid)
        val colorGrid = dialogView.findViewById<GridLayout>(R.id.colorGrid)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseColorPicker)
        val tabGrid = dialogView.findViewById<TextView>(R.id.tabGrid)
        val tabRing = dialogView.findViewById<TextView>(R.id.tabRing)
        val gridViewContainer = dialogView.findViewById<View>(R.id.gridViewContainer)
        val ringViewContainer = dialogView.findViewById<View>(R.id.ringViewContainer)
        val opacityBar = dialogView.findViewById<SeekBar>(R.id.opacitySeekBar)
        val colorWheel = dialogView.findViewById<ColorWheelView>(R.id.colorWheelView)

        var currentColor = binding.drawingView.brushColor
        var currentOpacityProgress = opacityBar.progress
        
        fun updatePreviewColor(color: Int, progress: Int) {
            val alpha = (progress * 2.55f).toInt()
            val argb = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            val gd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(argb)
            }
            preview.background = gd
        }

        updatePreviewColor(currentColor, currentOpacityProgress)

        btnClose.setOnClickListener { dialog.dismiss() }

        fun updateSeekBarColor(color: Int) {
            val progressDrawable = opacityBar.progressDrawable as? android.graphics.drawable.LayerDrawable
            val clipDrawable = progressDrawable?.findDrawableByLayerId(android.R.id.progress) as? android.graphics.drawable.ClipDrawable
            val shapeDrawable = clipDrawable?.drawable as? android.graphics.drawable.GradientDrawable
            shapeDrawable?.colors = intArrayOf(Color.TRANSPARENT, color)
        }

        updateSeekBarColor(currentColor)

        colorWheel.onColorSelected = { color ->
            currentColor = color
            updatePreviewColor(color, currentOpacityProgress)
            val alpha = (currentOpacityProgress * 2.55f).toInt()
            val alphaColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            binding.drawingView.brushColor = alphaColor
            updateColorPickerRing(color)
            updateSeekBarColor(color)
        }

        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentOpacityProgress = progress
                    val alpha = (progress * 2.55f).toInt()
                    val alphaColor = Color.argb(alpha, Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                    binding.drawingView.brushColor = alphaColor
                    updatePreviewColor(currentColor, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                viewModel.recordSnapshot()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        tabGrid.setOnClickListener {
            tabGrid.setBackgroundResource(R.drawable.bg_pill_dark)
            tabGrid.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#444444"))
            tabGrid.setTypeface(null, android.graphics.Typeface.BOLD)
            tabGrid.setTextColor(Color.WHITE)

            tabRing.background = null
            tabRing.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabRing.setTextColor(Color.parseColor("#888888"))

            gridViewContainer.isVisible = true
            ringViewContainer.isVisible = false
        }

        tabRing.setOnClickListener {
            tabRing.setBackgroundResource(R.drawable.bg_pill_dark)
            tabRing.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#444444"))
            tabRing.setTypeface(null, android.graphics.Typeface.BOLD)
            tabRing.setTextColor(Color.WHITE)

            tabGrid.background = null
            tabGrid.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabGrid.setTextColor(Color.parseColor("#888888"))

            ringViewContainer.isVisible = true
            gridViewContainer.isVisible = false
        }

        val currentBaseColor = Color.rgb(Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))

        val presetColors = intArrayOf(
            Color.parseColor("#B71C1C"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#FFEB3B"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#03A9F4"),
            Color.parseColor("#673AB7"),
            Color.parseColor("#E91E63"),
            Color.BLACK,
            Color.WHITE
        )

        presetGrid.removeAllViews()
        var selectedPresetView: View? = null

        presetColors.forEach { color ->
            val isSelected = (Color.rgb(Color.red(color), Color.green(color), Color.blue(color)) == currentBaseColor)
            val circle = View(this).apply {
                val lp = GridLayout.LayoutParams().apply {
                    width = 36.dpToPx()
                    height = 36.dpToPx()
                    setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                }
                layoutParams = lp
                tag = color
                updateCellDrawable(this, color, isSelected)
                if (isSelected) {
                    selectedPresetView = this
                }
                setOnClickListener {
                    selectedPresetView?.let { updateCellDrawable(it, (it.tag as? Int) ?: Color.RED, false) }
                    selectedPresetView = this
                    updateCellDrawable(this, color, true)

                    currentColor = color
                    updatePreviewColor(color, currentOpacityProgress)
                    val alpha = currentOpacityProgress * 2.55f
                    val alphaColor = Color.argb(alpha.toInt(), Color.red(color), Color.green(color), Color.blue(color))
                    binding.drawingView.brushColor = alphaColor
                    updateColorPickerRing(color)
                    updateSeekBarColor(color)
                }
            }
            presetGrid.addView(circle)
        }

        val addButton = TextView(this).apply {
            val lp = GridLayout.LayoutParams().apply {
                width = 36.dpToPx()
                height = 36.dpToPx()
                setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            }
            layoutParams = lp
            background = ContextCompat.getDrawable(context, R.drawable.bg_color_swatch)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#444444"))
            text = "+"
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            textSize = 18f
            setOnClickListener {
                Toast.makeText(this@PhotoEditorActivity, "Custom color", Toast.LENGTH_SHORT).show()
            }
        }
        presetGrid.addView(addButton)

        colorGrid.removeAllViews()
        val paletteRows = arrayOf(
            intArrayOf(Color.WHITE, Color.parseColor("#E0E0E0"), Color.parseColor("#BDBDBD"), Color.parseColor("#9E9E9E"), Color.parseColor("#757575"), Color.parseColor("#616161"), Color.parseColor("#424242"), Color.parseColor("#212121"), Color.parseColor("#111111"), Color.BLACK),
            intArrayOf(Color.parseColor("#FFCDD2"), Color.parseColor("#FFE0B2"), Color.parseColor("#FFF9C4"), Color.parseColor("#C8E6C9"), Color.parseColor("#B3E5FC"), Color.parseColor("#D1C4E9"), Color.parseColor("#F8BBD0"), Color.parseColor("#D7CCC8"), Color.parseColor("#CFD8DC"), Color.parseColor("#90A4AE")),
            intArrayOf(Color.parseColor("#E57373"), Color.parseColor("#FFB74D"), Color.parseColor("#FFF176"), Color.parseColor("#81C784"), Color.parseColor("#4FC3F7"), Color.parseColor("#9575CD"), Color.parseColor("#F06292"), Color.parseColor("#A1887F"), Color.parseColor("#90A4AE"), Color.parseColor("#546E7A")),
            intArrayOf(Color.parseColor("#F44336"), Color.parseColor("#FF9800"), Color.parseColor("#FFEB3B"), Color.parseColor("#4CAF50"), Color.parseColor("#03A9F4"), Color.parseColor("#673AB7"), Color.parseColor("#E91E63"), Color.parseColor("#795548"), Color.parseColor("#607D8B"), Color.parseColor("#37474F")),
            intArrayOf(Color.parseColor("#B71C1C"), Color.parseColor("#E65100"), Color.parseColor("#F57F17"), Color.parseColor("#1B5E20"), Color.parseColor("#01579B"), Color.parseColor("#311B92"), Color.parseColor("#880E4F"), Color.parseColor("#3E2723"), Color.parseColor("#263238"), Color.parseColor("#000000"))
        )

        var selectedCellView: View? = null

        paletteRows.forEach { row ->
            row.forEach { color ->
                val isSelected = (Color.rgb(Color.red(color), Color.green(color), Color.blue(color)) == currentBaseColor)
                val cell = View(this).apply {
                    val lp = GridLayout.LayoutParams().apply {
                        width = 28.dpToPx()
                        height = 28.dpToPx()
                        setMargins(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
                    }
                    layoutParams = lp
                    tag = color
                    updateCellDrawable(this, color, isSelected)
                    if (isSelected) {
                        selectedCellView = this
                    }
                    setOnClickListener {
                        selectedCellView?.let { updateCellDrawable(it, (it.tag as? Int) ?: Color.RED, false) }
                        selectedCellView = this
                        updateCellDrawable(this, color, true)

                        currentColor = color
                        updatePreviewColor(color, currentOpacityProgress)
                        val alpha = currentOpacityProgress * 2.55f
                        val alphaColor = Color.argb(alpha.toInt(), Color.red(color), Color.green(color), Color.blue(color))
                        binding.drawingView.brushColor = alphaColor
                        updateColorPickerRing(color)
                        updateSeekBarColor(color)
                    }
                }
                colorGrid.addView(cell)
            }
        }

        dialog.show()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateColorPickerRing(color: Int) {
        val ringDrawable = ContextCompat.getDrawable(this, R.drawable.bg_color_picker_ring)?.mutate() as? android.graphics.drawable.LayerDrawable
        ringDrawable?.let { layer ->
            val inner = layer.getDrawable(1) as? android.graphics.drawable.GradientDrawable
            inner?.color = android.content.res.ColorStateList.valueOf(color)
            binding.btnMarkupColor.background = layer
        }
    }

    private fun updateCellDrawable(view: View, color: Int, isSelected: Boolean) {
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 4f * resources.displayMetrics.density
            setColor(color)
            if (isSelected) {
                setStroke(3.dpToPx(), Color.WHITE)
            } else {
                setStroke(1.dpToPx(), Color.parseColor("#33000000"))
            }
        }
        view.background = drawable
    }

    private fun setupTextUI() {
        binding.fontPicker.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.textColorPicker.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.shadowPresetPicker.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.textBadgePicker.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        fun switchTextSubTab(tab: Int) {
            val tabs = listOf(binding.tabFontStyles, binding.tabTextColors, binding.tabTextAdjust, binding.tabTextEffects)
            tabs.forEachIndexed { index, tv ->
                val isSel = index == tab
                tv.setBackgroundResource(if (isSel) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tv.setTextColor(if (isSel) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))
                tv.setTypeface(null, if (isSel) Typeface.BOLD else Typeface.NORMAL)
            }

            binding.fontPicker.isVisible = (tab == 0)
            binding.textColorPicker.isVisible = (tab == 1)
            binding.panelTextAdjust.isVisible = (tab == 2)
            binding.panelTextEffects.isVisible = (tab == 3)
        }

        binding.tabFontStyles.setOnClickListener { switchTextSubTab(0) }
        binding.tabTextColors.setOnClickListener { switchTextSubTab(1) }
        binding.tabTextAdjust.setOnClickListener { switchTextSubTab(2) }
        binding.tabTextEffects.setOnClickListener { switchTextSubTab(3) }

        fun getSelectedTextLayer(): EditorLayer? {
            val selectedId = viewModel.selectedLayerId.value ?: return null
            val layer = viewModel.layers.value.firstOrNull { it.id == selectedId }
            return if (layer?.content is LayerContent.Text) layer else null
        }

        // Live typing synchronization
        binding.etTextInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                val selectedLayer = getSelectedTextLayer()
                if (selectedLayer != null && (selectedLayer.content as? LayerContent.Text)?.text != text) {
                    viewModel.updateLayerText(selectedLayer, text)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Done button on soft keyboard
        binding.etTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etTextInput.windowToken, 0)
                binding.etTextInput.clearFocus()
                true
            } else false
        }

        // Add / Update Button
        binding.btnAddText.setOnClickListener {
            val text = binding.etTextInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val selectedLayer = getSelectedTextLayer()
                if (selectedLayer != null) {
                    viewModel.updateLayerText(selectedLayer, text)
                    Toast.makeText(this@PhotoEditorActivity, "Text updated!", Toast.LENGTH_SHORT).show()
                } else {
                    val cx = if (binding.layersContainer.width > 0) {
                        (binding.layersContainer.width / 2f) - 60.dpToPx()
                    } else 200f
                    val cy = if (binding.layersContainer.height > 0) {
                        (binding.layersContainer.height / 2f) - 30.dpToPx()
                    } else 350f

                    val style = fontStyles[activeFontIndex]
                    val badge = textBadges[activeBadgeIndex]

                    viewModel.addTextLayer(
                        text = text,
                        fontSize = binding.textSizeSlider.progress.toFloat(),
                        initialX = cx,
                        initialY = cy,
                        color = if (badge.color == Color.WHITE) Color.BLACK else activeTextColor,
                        fontStyleIndex = activeFontIndex,
                        bgBadgeColor = badge.color,
                        letterSpacing = style.letterSpacing
                    )
                    binding.etTextInput.text?.clear()
                    Toast.makeText(this@PhotoEditorActivity, "Text added!", Toast.LENGTH_SHORT).show()
                }
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etTextInput.windowToken, 0)
                binding.etTextInput.clearFocus()
            } else {
                Toast.makeText(this@PhotoEditorActivity, "Please enter text first", Toast.LENGTH_SHORT).show()
            }
        }

        // Text Size Slider
        binding.textSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvTextSizeValue.text = "${progress}sp"
                if (fromUser) {
                    val selected = getSelectedTextLayer()
                    if (selected != null) {
                        viewModel.updateLayerFontSize(selected, progress.toFloat())
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Text Opacity Slider
        binding.textOpacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvTextOpacityValue.text = "${progress}%"
                if (fromUser) {
                    val selected = getSelectedTextLayer()
                    if (selected != null) {
                        viewModel.updateLayerOpacity(selected, progress / 100f)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Alignment Chips
        fun updateAlignUI(gravity: Int) {
            binding.btnAlignLeft.setBackgroundResource(if (gravity == Gravity.START) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            binding.btnAlignLeft.setTextColor(if (gravity == Gravity.START) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

            binding.btnAlignCenter.setBackgroundResource(if (gravity == Gravity.CENTER) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            binding.btnAlignCenter.setTextColor(if (gravity == Gravity.CENTER) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

            binding.btnAlignRight.setBackgroundResource(if (gravity == Gravity.END) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            binding.btnAlignRight.setTextColor(if (gravity == Gravity.END) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))
        }

        binding.btnAlignLeft.setOnClickListener {
            updateAlignUI(Gravity.START)
            getSelectedTextLayer()?.let { viewModel.updateLayerAlignment(it, Gravity.START) }
        }
        binding.btnAlignCenter.setOnClickListener {
            updateAlignUI(Gravity.CENTER)
            getSelectedTextLayer()?.let { viewModel.updateLayerAlignment(it, Gravity.CENTER) }
        }
        binding.btnAlignRight.setOnClickListener {
            updateAlignUI(Gravity.END)
            getSelectedTextLayer()?.let { viewModel.updateLayerAlignment(it, Gravity.END) }
        }

        // Formatting Chips
        binding.btnBold.setOnClickListener {
            val selected = getSelectedTextLayer()
            val isBold = ((selected?.content as? LayerContent.Text)?.isBold ?: false).not()
            binding.btnBold.setBackgroundResource(if (isBold) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            binding.btnBold.setTextColor(if (isBold) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))
            if (selected != null) viewModel.updateLayerFormat(selected, isBold = isBold)
        }

        binding.btnItalic.setOnClickListener {
            val selected = getSelectedTextLayer()
            val isItalic = ((selected?.content as? LayerContent.Text)?.isItalic ?: false).not()
            binding.btnItalic.setBackgroundResource(if (isItalic) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            binding.btnItalic.setTextColor(if (isItalic) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))
            if (selected != null) viewModel.updateLayerFormat(selected, isItalic = isItalic)
        }

        binding.btnAllCaps.setOnClickListener {
            val selected = getSelectedTextLayer()
            val isAllCaps = ((selected?.content as? LayerContent.Text)?.isAllCaps ?: false).not()
            binding.btnAllCaps.setBackgroundResource(if (isAllCaps) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            binding.btnAllCaps.setTextColor(if (isAllCaps) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))
            if (selected != null) viewModel.updateLayerFormat(selected, isAllCaps = isAllCaps)
        }

        // Sub-Panel 1: Fonts
        binding.fontPicker.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_font_style, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val style = fontStyles[position]
                val cardRoot = holder.itemView.findViewById<View>(R.id.fontCardRoot)
                val tvPreview = holder.itemView.findViewById<TextView>(R.id.tvFontPreview)
                val tvName = holder.itemView.findViewById<TextView>(R.id.tvFontName)

                tvName.text = style.name
                tvPreview.typeface = style.typeface
                tvPreview.letterSpacing = style.letterSpacing

                if (style.isNeon) {
                    tvPreview.setShadowLayer(16f, 0f, 0f, Color.parseColor("#00E5FF"))
                } else {
                    tvPreview.setShadowLayer(0f, 0f, 0f, 0)
                }

                val isSelected = position == activeFontIndex
                cardRoot.setBackgroundResource(if (isSelected) R.drawable.bg_font_card_selected else R.drawable.bg_font_card_unselected)
                tvName.setTextColor(if (isSelected) ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_gold) else ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_text_secondary))

                cardRoot.setOnClickListener {
                    val prev = activeFontIndex
                    activeFontIndex = holder.bindingAdapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(activeFontIndex)

                    val selectedLayer = getSelectedTextLayer()
                    if (selectedLayer != null) {
                        viewModel.updateLayerStyle(selectedLayer, activeFontIndex, style.letterSpacing)
                    }
                }
            }

            override fun getItemCount() = fontStyles.size
        }

        // Sub-Panel 2: Colors
        binding.textColorPicker.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_text_color_swatch, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val color = textColors[position]
                val vRing = holder.itemView.findViewById<View>(R.id.vSelectionRing)
                val vCircle = holder.itemView.findViewById<View>(R.id.vColorCircle)

                vCircle.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (color == Color.BLACK) {
                        setStroke(1.dpToPx(), Color.WHITE)
                    }
                }

                val isSelected = (color == activeTextColor)
                vRing.isVisible = isSelected
                if (isSelected) {
                    vRing.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(2.dpToPx(), Color.parseColor("#F59E0B"))
                    }
                }

                holder.itemView.setOnClickListener {
                    activeTextColor = color
                    notifyDataSetChanged()

                    val selectedLayer = getSelectedTextLayer()
                    if (selectedLayer != null) {
                        viewModel.updateLayerColor(selectedLayer, activeTextColor)
                    }
                }
            }

            override fun getItemCount() = textColors.size
        }

        // Sub-Panel 4: Shadow Presets
        binding.shadowPresetPicker.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_text_badge, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val preset = textShadowPresets[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tvBadgeSample)
                tv.text = preset.name

                val isSelected = position == activeShadowIndex
                tv.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tv.setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_text_secondary))
                tv.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)

                tv.setOnClickListener {
                    val prev = activeShadowIndex
                    activeShadowIndex = holder.bindingAdapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(activeShadowIndex)

                    val selected = getSelectedTextLayer()
                    if (selected != null) {
                        val sColor = if (preset.name == "Neon") {
                            (selected.content as? LayerContent.Text)?.color ?: Color.parseColor("#00E5FF")
                        } else preset.color
                        viewModel.updateLayerShadow(selected, preset.radius, preset.dx, preset.dy, sColor)
                    }
                }
            }

            override fun getItemCount() = textShadowPresets.size
        }

        // Sub-Panel 4: Badges
        binding.textBadgePicker.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_text_badge, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val badge = textBadges[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tvBadgeSample)
                tv.text = badge.name

                val isSelected = position == activeBadgeIndex
                if (badge.color != null) {
                    tv.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16.dpToPx().toFloat()
                        setColor(badge.color)
                        if (isSelected) {
                            setStroke(2.dpToPx(), Color.parseColor("#FFD700"))
                        }
                    }
                    tv.setTextColor(badge.textColor)
                } else {
                    tv.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                    tv.setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_text_secondary))
                }

                tv.setOnClickListener {
                    val prev = activeBadgeIndex
                    activeBadgeIndex = holder.bindingAdapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(activeBadgeIndex)

                    val selectedLayer = getSelectedTextLayer()
                    if (selectedLayer != null) {
                        viewModel.updateLayerBadge(selectedLayer, badge.color)
                        if (badge.color == Color.WHITE) {
                            viewModel.updateLayerColor(selectedLayer, Color.BLACK)
                        }
                    }
                }
            }

            override fun getItemCount() = textBadges.size
        }

        switchTextSubTab(0)
    }

    private fun setupStickersUI() {
        binding.stickerCategoryRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.stickerPicker.layoutManager = GridLayoutManager(this, 5)

        binding.btnPickCustomSticker.setOnClickListener {
            AnalyticsManager.logEvent("PhotoEditor_custom_sticker_pick")
            pickCustomStickerLauncher.launch("image/*")
        }

        fun updateStickerDisplay() {
            val cat = stickerCategories[selectedStickerCategoryIndex]
            if (cat.isCustom) {
                binding.customStickerContainer.isVisible = true
                binding.stickerPicker.isVisible = false
            } else {
                binding.customStickerContainer.isVisible = false
                binding.stickerPicker.isVisible = true
                binding.stickerPicker.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
                        return object : RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val sticker = cat.stickers[position]
                        val tv = holder.itemView.findViewById<TextView>(R.id.tvStickerEmoji)
                        tv.text = sticker
                        holder.itemView.setOnClickListener {
                            holder.itemView.animate()
                                .scaleX(1.3f)
                                .scaleY(1.3f)
                                .setDuration(90)
                                .withEndAction {
                                    holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                                }
                                .start()

                            AnalyticsManager.logEvent("PhotoEditor_add_sticker")
                            val cx = if (binding.layersContainer.width > 0) {
                                (binding.layersContainer.width / 2f) - 45.dpToPx()
                            } else 250f
                            val cy = if (binding.layersContainer.height > 0) {
                                (binding.layersContainer.height / 2f) - 45.dpToPx()
                            } else 350f
                            viewModel.addTextLayer(sticker, fontSize = 56f, initialX = cx, initialY = cy)
                        }
                    }

                    override fun getItemCount() = cat.stickers.size
                }
            }
        }

        binding.stickerCategoryRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker_category, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val cat = stickerCategories[position]
                val chip = holder.itemView.findViewById<TextView>(R.id.tvCategoryChip)
                chip.text = cat.name
                val isSelected = position == selectedStickerCategoryIndex

                if (isSelected) {
                    chip.setBackgroundResource(R.drawable.bg_chip_selected)
                    chip.setTextColor(Color.WHITE)
                    chip.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                    chip.setTextColor(ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_text_secondary))
                    chip.setTypeface(null, android.graphics.Typeface.NORMAL)
                }

                chip.setOnClickListener {
                    val prev = selectedStickerCategoryIndex
                    selectedStickerCategoryIndex = holder.bindingAdapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(selectedStickerCategoryIndex)
                    updateStickerDisplay()
                }
            }

            override fun getItemCount() = stickerCategories.size
        }

        updateStickerDisplay()
    }

    private fun applyAutoAdjustments() {
        lifecycleScope.launch {
            val uri = viewModel.selectedUri.value ?: return@launch
            val result = withContext(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    val analysisStream = contentResolver.openInputStream(uri)
                    options.apply {
                        inJustDecodeBounds = false
                        inSampleSize = calculateInSampleSize(options, 500, 500)
                    }
                    val bitmap = BitmapFactory.decodeStream(analysisStream, null, options)
                    analysisStream?.close()
                    if (bitmap != null) calculateAutoValues(bitmap) else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                } catch (e: OutOfMemoryError) { null }
            }
            result?.let { (exp, contrast, sat, brilliance) ->
                viewModel.exposure.value = exp
                viewModel.contrast.value = contrast
                viewModel.saturation.value = sat
                viewModel.brightness.value = brilliance
                updatePreview()
                binding.adjustmentPicker.adapter?.notifyDataSetChanged()
                if (selectedAdjustment != null) updateAdjustmentSlider(selectedAdjustment!!)
                Toast.makeText(this@PhotoEditorActivity, "Auto-Adjust Applied", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this@PhotoEditorActivity, "Auto-Adjust failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun calculateAutoValues(bitmap: Bitmap): FloatArray {
        val thumb = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
        var totalLuminance = 0L
        for (x in 0 until thumb.width) {
            for (y in 0 until thumb.height) {
                val color = thumb.getPixel(x, y)
                totalLuminance += (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toLong()
            }
        }
        val avgLuminance = totalLuminance / (thumb.width * thumb.height).toFloat()
        val expAdjustment = ((128f - avgLuminance) / 128f) * 0.5f
        return floatArrayOf(expAdjustment.coerceIn(-1f, 1f), 1.15f, 1.05f, 0.1f)
    }

    private fun updateAdjustmentSlider(type: AdjustmentType) {
        val value = when (type.name) {
            "Exposure" -> viewModel.exposure.value / 2f
            "Contrast" -> viewModel.contrast.value - 1f
            "Saturation" -> viewModel.saturation.value - 1f
            "Highlights" -> viewModel.highlights.value
            "Shadows" -> viewModel.shadows.value
            "Warmth" -> viewModel.temperature.value
            "Brilliance" -> viewModel.brightness.value
            else -> 0f
        }
        binding.adjustmentSlider.progress = ((value * 100) + 100).toInt()
        updateSliderProgress(value)
    }

    private fun updateSliderProgress(value: Float) {
        val slider = binding.adjustmentSlider
        val progressView = binding.adjustmentSliderProgress
        
        val sliderWidth = slider.width - slider.paddingStart - slider.paddingEnd
        if (sliderWidth <= 0) {
            slider.post { updateSliderProgress(value) }
            return
        }
        
        val halfWidth = sliderWidth / 2f
        // Reduce width slightly to create a small gap before thumb if desired
        // Or keep it as is. User said "little space between circle white".
        // Let's subtract 2dp (approx 6px) from progress width
        val gap = (1 * resources.displayMetrics.density).toInt()
        val progressWidth = ((Math.abs(value) * halfWidth).toInt() - gap).coerceAtLeast(0)
        
        val params = progressView.layoutParams
        params.width = progressWidth
        progressView.layoutParams = params
        
        if (value >= 0) {
            progressView.translationX = (progressWidth / 2f)
        } else {
            progressView.translationX = -(progressWidth / 2f)
        }
    }

    private fun updateFilterSliderProgress(intensity: Float) {
        val slider = binding.filterIntensitySlider
        val progressView = binding.filterSliderProgress
        
        val sliderWidth = slider.width - slider.paddingStart - slider.paddingEnd
        if (sliderWidth <= 0) {
            slider.post { updateFilterSliderProgress(intensity) }
            return
        }
        
        val gap = (1 * resources.displayMetrics.density).toInt()
        val progressWidth = ((intensity * sliderWidth).toInt() - gap).coerceAtLeast(0)
        
        val params = progressView.layoutParams
        params.width = progressWidth
        progressView.layoutParams = params
    }

    private fun applyAdjustment(name: String, value: Float) {
        when (name) {
            "Exposure" -> viewModel.exposure.value = value * 2f
            "Contrast" -> viewModel.contrast.value = 1f + value
            "Saturation" -> viewModel.saturation.value = 1f + value
            "Highlights" -> viewModel.highlights.value = value
            "Shadows" -> viewModel.shadows.value = value
            "Warmth" -> viewModel.temperature.value = value
            "Brilliance" -> viewModel.brightness.value = value
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.selectedUri.collectLatest { uri ->
                        uri?.let { 
                            binding.photoPreview.load(it) {
                                listener(onSuccess = { _, result ->
                                    val ratio = result.drawable.intrinsicWidth.toFloat() / result.drawable.intrinsicHeight
                                    binding.cropOverlay.originalRatio = ratio
                                })
                            }
                            (binding.filterList.adapter as? FilterAdapter)?.updateImage(it)
                        }
                        updatePreview()
                    }
                }
                launch {
                    viewModel.filterIntensity.collectLatest { intensity ->
                        val progress = (intensity * 100).toInt()
                        binding.filterIntensitySlider.progress = progress
                        binding.tvFilterIntensityValue.text = "${progress}%"
                        updateFilterSliderProgress(intensity)
                    }
                }
                launch { viewModel.layers.collectLatest { layers -> renderLayers(layers) } }
                launch { viewModel.selectedLayerId.collectLatest { selectedId -> updateLayerSelection(selectedId) } }
                launch { 
                    combine(viewModel.rotation, viewModel.straighten) { rot, straight ->
                        rot + straight
                    }.collectLatest { totalRot ->
                        binding.photoPreview.rotation = totalRot
                    }
                }
                launch { viewModel.flipHorizontal.collectLatest { flip -> binding.photoPreview.scaleX = if (flip) -1f else 1f } }
                launch { viewModel.rotationX.collectLatest { rotX -> binding.photoPreview.rotationX = rotX } }
                launch { viewModel.rotationY.collectLatest { rotY -> binding.photoPreview.rotationY = rotY } }
                launch {
                    viewModel.canUndo.collectLatest { canUndo ->
                        binding.btnUndo.isEnabled = canUndo
                        binding.btnUndo.alpha = if (canUndo) 1.0f else 0.35f
                    }
                }
                launch {
                    viewModel.canRedo.collectLatest { canRedo ->
                        binding.btnRedo.isEnabled = canRedo
                        binding.btnRedo.alpha = if (canRedo) 1.0f else 0.35f
                    }
                }
                launch {
                    viewModel.saveResultUri.collectLatest { uri ->
                        uri?.let {
                            Toast.makeText(this@PhotoEditorActivity, "Image Saved!", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun updatePreview() {
        val matrix = ColorMatrixBuilder.build(
            brightness = viewModel.brightness.value,
            contrast = viewModel.contrast.value,
            saturation = viewModel.saturation.value,
            exposure = viewModel.exposure.value,
            temperature = viewModel.temperature.value,
            tint = viewModel.tint.value,
            shadows = viewModel.shadows.value,
            highlights = viewModel.highlights.value,
            skinTone = viewModel.skinTone.value,
            skinTexture = viewModel.skinTexture.value,
            cheeks = viewModel.cheeks.value,
            eyeSize = viewModel.eyeSize.value,
            filterMatrix = viewModel.selectedFilter.value,
            filterIntensity = viewModel.filterIntensity.value
        )
        binding.photoPreview.colorFilter = ColorMatrixColorFilter(matrix)
    }

    private fun renderLayers(layers: List<EditorLayer>) {
        binding.layersContainer.removeAllViews()
        val selectedId = viewModel.selectedLayerId.value
        layers.forEach { layer ->
            val container = FrameLayout(this)
            container.apply {
                tag = layer.id
                x = layer.x
                y = layer.y
                scaleX = layer.scale
                scaleY = layer.scale
                rotation = layer.rotation
                minimumWidth = 140.dpToPx()
                minimumHeight = 84.dpToPx()
                clipChildren = false
                clipToPadding = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )

                val isSelected = layer.id == selectedId
                if (isSelected) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setStroke(2.dpToPx(), Color.parseColor("#F59E0B"))
                        setColor(Color.parseColor("#15000000"))
                        cornerRadius = 14.dpToPx().toFloat()
                    }
                }

                when (val content = layer.content) {
                    is LayerContent.Text -> {
                        val textView = TextView(context).apply {
                            text = if (content.isAllCaps) content.text.uppercase() else content.text
                            setTextColor(content.color)
                            textSize = content.fontSize
                            alpha = content.opacity
                            gravity = content.alignment
                            letterSpacing = content.letterSpacing

                            val style = fontStyles.getOrNull(content.fontStyleIndex) ?: fontStyles[0]
                            var tf = style.typeface
                            val styleFlags = (if (content.isBold) Typeface.BOLD else 0) or (if (content.isItalic) Typeface.ITALIC else 0)
                            if (styleFlags != 0) {
                                tf = Typeface.create(tf, styleFlags)
                            }
                            typeface = tf

                            if (content.shadowRadius > 0f) {
                                setShadowLayer(content.shadowRadius, content.shadowDx, content.shadowDy, content.shadowColor)
                            } else if (style.isNeon) {
                                setShadowLayer(18f, 0f, 0f, content.color)
                            } else if (content.shadowColor != Color.TRANSPARENT) {
                                setShadowLayer(6f, 2f, 2f, content.shadowColor)
                            } else {
                                setShadowLayer(0f, 0f, 0f, 0)
                            }

                            if (content.bgBadgeColor != null) {
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    setColor(content.bgBadgeColor)
                                    cornerRadius = 10.dpToPx().toFloat()
                                }
                                setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
                            }

                            isClickable = false
                            isFocusable = false
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = android.view.Gravity.CENTER
                                setMargins(32.dpToPx(), 22.dpToPx(), 32.dpToPx(), 22.dpToPx())
                            }
                        }
                        addView(textView)
                    }
                    is LayerContent.Sticker -> {
                        val imageView = ImageView(context).apply {
                            load(content.uri)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            isClickable = false
                            isFocusable = false
                            layoutParams = FrameLayout.LayoutParams(
                                72.dpToPx(),
                                72.dpToPx()
                            ).apply {
                                gravity = android.view.Gravity.CENTER
                                setMargins(32.dpToPx(), 22.dpToPx(), 32.dpToPx(), 22.dpToPx())
                            }
                        }
                        addView(imageView)
                    }
                }

                // Top-Right Delete Handle (X)
                val btnDelete = ImageButton(context).apply {
                    tag = "btnDelete"
                    setImageResource(R.drawable.ic_delete)
                    setBackgroundResource(R.drawable.bg_circle_accent)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                    setColorFilter(Color.WHITE)
                    elevation = 4.dpToPx().toFloat()
                    setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    layoutParams = FrameLayout.LayoutParams(28.dpToPx(), 28.dpToPx()).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    }
                    isVisible = isSelected
                    setOnClickListener {
                        AnalyticsManager.logEvent("PhotoEditor_delete_layer")
                        viewModel.removeLayer(layer)
                    }
                }
                addView(btnDelete)

                // Bottom-Right Scale/Resize Handle (Drag to zoom / tap to cycle)
                val btnScale = ImageButton(context).apply {
                    tag = "btnScale"
                    setImageResource(R.drawable.ic_scale_handle)
                    setBackgroundResource(R.drawable.bg_circle_accent)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6366F1"))
                    setColorFilter(Color.WHITE)
                    elevation = 4.dpToPx().toFloat()
                    setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    layoutParams = FrameLayout.LayoutParams(28.dpToPx(), 28.dpToPx()).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    }
                    isVisible = isSelected

                    setOnTouchListener(object : View.OnTouchListener {
                        var centerScreenX = 0f
                        var centerScreenY = 0f
                        var initialDist = 1f
                        var initialScale = 1f
                        var isScaling = false

                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    val parentLoc = IntArray(2)
                                    binding.layersContainer.getLocationOnScreen(parentLoc)
                                    centerScreenX = parentLoc[0] + container.x + (container.width / 2f)
                                    centerScreenY = parentLoc[1] + container.y + (container.height / 2f)

                                    val dx = (event.rawX - centerScreenX).toDouble()
                                    val dy = (event.rawY - centerScreenY).toDouble()
                                    initialDist = Math.max(20.0, Math.hypot(dx, dy)).toFloat()
                                    initialScale = container.scaleX
                                    isScaling = false
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                    return true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val dx = (event.rawX - centerScreenX).toDouble()
                                    val dy = (event.rawY - centerScreenY).toDouble()
                                    val currentDist = Math.hypot(dx, dy).toFloat()
                                    if (Math.abs(currentDist - initialDist) > 5 || isScaling) {
                                        isScaling = true
                                        val ratio = currentDist / initialDist
                                        val newScale = (initialScale * ratio).coerceIn(0.4f, 4.5f)
                                        container.scaleX = newScale
                                        container.scaleY = newScale
                                    }
                                    return true
                                }
                                MotionEvent.ACTION_UP -> {
                                    if (isScaling) {
                                        viewModel.updateLayerTransform(
                                            layer,
                                            container.x,
                                            container.y,
                                            container.scaleX,
                                            container.rotation
                                        )
                                    } else {
                                        val sizes = listOf(0.8f, 1.2f, 1.6f, 2.2f, 3.0f, 1.0f)
                                        val currentScale = container.scaleX
                                        val nextScale = sizes.firstOrNull { it > currentScale + 0.1f } ?: sizes.last()
                                        container.scaleX = nextScale
                                        container.scaleY = nextScale
                                        viewModel.updateLayerTransform(
                                            layer,
                                            container.x,
                                            container.y,
                                            nextScale,
                                            container.rotation
                                        )
                                    }
                                    return true
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    if (isScaling) {
                                        viewModel.updateLayerTransform(
                                            layer,
                                            container.x,
                                            container.y,
                                            container.scaleX,
                                            container.rotation
                                        )
                                    }
                                    return true
                                }
                            }
                            return false
                        }
                    })
                }
                addView(btnScale)

                // Bottom-Left Rotate Handle (Drag to rotate freely 360°)
                val btnRotate = ImageButton(context).apply {
                    tag = "btnRotate"
                    setImageResource(R.drawable.ic_rotate_handle)
                    setBackgroundResource(R.drawable.bg_circle_accent)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#06B6D4"))
                    setColorFilter(Color.WHITE)
                    elevation = 4.dpToPx().toFloat()
                    setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    layoutParams = FrameLayout.LayoutParams(28.dpToPx(), 28.dpToPx()).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                    }
                    isVisible = isSelected

                    setOnTouchListener(object : View.OnTouchListener {
                        var centerScreenX = 0f
                        var centerScreenY = 0f
                        var startAngle = 0f
                        var initialRotation = 0f

                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    val parentLoc = IntArray(2)
                                    binding.layersContainer.getLocationOnScreen(parentLoc)
                                    centerScreenX = parentLoc[0] + container.x + (container.width / 2f)
                                    centerScreenY = parentLoc[1] + container.y + (container.height / 2f)

                                    val dx = (event.rawX - centerScreenX).toDouble()
                                    val dy = (event.rawY - centerScreenY).toDouble()
                                    startAngle = Math.toDegrees(Math.atan2(dy, dx)).toFloat()
                                    initialRotation = container.rotation
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                    return true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val dx = (event.rawX - centerScreenX).toDouble()
                                    val dy = (event.rawY - centerScreenY).toDouble()
                                    val currentAngle = Math.toDegrees(Math.atan2(dy, dx)).toFloat()
                                    val delta = currentAngle - startAngle
                                    val newRot = (initialRotation + delta) % 360f
                                    container.rotation = newRot
                                    return true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    viewModel.updateLayerTransform(
                                        layer,
                                        container.x,
                                        container.y,
                                        container.scaleX,
                                        container.rotation
                                    )
                                    return true
                                }
                            }
                            return false
                        }
                    })
                }
                addView(btnRotate)

                // Top-Left Edit Handle (for text)
                val hasText = (layer.content as? LayerContent.Text)?.text?.any { it.isLetterOrDigit() } == true
                val btnEdit = ImageButton(context).apply {
                    tag = "btnEdit"
                    setImageResource(R.drawable.ic_edit)
                    setBackgroundResource(R.drawable.bg_circle_accent)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
                    setColorFilter(Color.parseColor("#F59E0B"))
                    elevation = 4.dpToPx().toFloat()
                    setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    layoutParams = FrameLayout.LayoutParams(28.dpToPx(), 28.dpToPx()).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    }
                    isVisible = isSelected && hasText
                    setOnClickListener {
                        showControlPanel(binding.textControls)
                        (binding.bottomModeNav.adapter as? ModeAdapter)?.selectMode(R.id.mode_text)
                        binding.etTextInput.requestFocus()
                        binding.etTextInput.setSelection(binding.etTextInput.text?.length ?: 0)
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(binding.etTextInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                addView(btnEdit)

                setOnTouchListener(object : View.OnTouchListener {
                    var startRawX = 0f
                    var startRawY = 0f
                    var initialViewX = 0f
                    var initialViewY = 0f
                    var isDragging = false

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startRawX = event.rawX
                                startRawY = event.rawY
                                initialViewX = v.x
                                initialViewY = v.y
                                isDragging = false
                                v.bringToFront()
                                viewModel.setSelectedLayerId(layer.id)
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - startRawX
                                val dy = event.rawY - startRawY
                                if (Math.hypot(dx.toDouble(), dy.toDouble()) > 8 || isDragging) {
                                    isDragging = true
                                    v.x = initialViewX + dx
                                    v.y = initialViewY + dy
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (isDragging) {
                                    viewModel.updateLayerTransform(
                                        layer,
                                        v.x,
                                        v.y,
                                        v.scaleX,
                                        v.rotation
                                    )
                                } else {
                                    v.performClick()
                                    viewModel.setSelectedLayerId(layer.id)
                                }
                                return true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                if (isDragging) {
                                    viewModel.updateLayerTransform(
                                        layer,
                                        v.x,
                                        v.y,
                                        v.scaleX,
                                        v.rotation
                                    )
                                }
                                return true
                            }
                        }
                        return false
                    }
                })
            }
            binding.layersContainer.addView(container)
        }
    }

    private fun updateLayerSelection(selectedId: Long?) {
        for (i in 0 until binding.layersContainer.childCount) {
            val child = binding.layersContainer.getChildAt(i) as? FrameLayout ?: continue
            val layerId = child.tag as? Long ?: continue
            val isSelected = (layerId == selectedId)

            child.background = if (isSelected) {
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setStroke(2.dpToPx(), Color.parseColor("#F59E0B"))
                    setColor(Color.parseColor("#15000000"))
                    cornerRadius = 14.dpToPx().toFloat()
                }
            } else null

            child.findViewWithTag<View>("btnDelete")?.isVisible = isSelected
            child.findViewWithTag<View>("btnScale")?.isVisible = isSelected
            child.findViewWithTag<View>("btnRotate")?.isVisible = isSelected
            child.findViewWithTag<View>("btnEdit")?.let { btn ->
                val tv = (child.getChildAt(0) as? TextView)?.text?.toString() ?: ""
                btn.isVisible = isSelected && tv.any { it.isLetterOrDigit() }
            }
        }

        if (selectedId != null) {
            val selectedLayer = viewModel.layers.value.firstOrNull { it.id == selectedId }
            if (selectedLayer?.content is LayerContent.Text) {
                val content = selectedLayer.content
                binding.btnAddText.text = "Done"
                val currentInput = binding.etTextInput.text?.toString() ?: ""
                if (currentInput != content.text) {
                    binding.etTextInput.setText(content.text)
                    binding.etTextInput.setSelection(content.text.length)
                }

                // Sync sliders
                binding.textSizeSlider.progress = content.fontSize.toInt().coerceIn(12, 72)
                binding.tvTextSizeValue.text = "${content.fontSize.toInt()}sp"
                binding.textOpacitySlider.progress = (content.opacity * 100).toInt().coerceIn(10, 100)
                binding.tvTextOpacityValue.text = "${(content.opacity * 100).toInt()}%"

                // Sync formatting chips
                binding.btnBold.setBackgroundResource(if (content.isBold) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                binding.btnBold.setTextColor(if (content.isBold) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

                binding.btnItalic.setBackgroundResource(if (content.isItalic) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                binding.btnItalic.setTextColor(if (content.isItalic) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

                binding.btnAllCaps.setBackgroundResource(if (content.isAllCaps) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                binding.btnAllCaps.setTextColor(if (content.isAllCaps) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

                // Sync alignment
                binding.btnAlignLeft.setBackgroundResource(if (content.alignment == Gravity.START) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                binding.btnAlignLeft.setTextColor(if (content.alignment == Gravity.START) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

                binding.btnAlignCenter.setBackgroundResource(if (content.alignment == Gravity.CENTER) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                binding.btnAlignCenter.setTextColor(if (content.alignment == Gravity.CENTER) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))

                binding.btnAlignRight.setBackgroundResource(if (content.alignment == Gravity.END) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                binding.btnAlignRight.setTextColor(if (content.alignment == Gravity.END) Color.WHITE else ContextCompat.getColor(this, R.color.lumina_text_secondary))
            } else {
                binding.btnAddText.text = "Add"
            }
        } else {
            binding.btnAddText.text = "Add"
        }
    }

    data class AdjustmentType(val name: String, val iconRes: Int)
    data class EditorMode(val id: Int, val name: String, val iconRes: Int)

    inner class ModeAdapter(
        private val modes: List<EditorMode>,
        private val onModeSelected: (EditorMode) -> Unit
    ) : RecyclerView.Adapter<ModeAdapter.ModeViewHolder>() {
        private var selectedIndex = 0

        fun selectMode(modeId: Int) {
            val idx = modes.indexOfFirst { it.id == modeId }
            if (idx != -1 && idx != selectedIndex) {
                val old = selectedIndex
                selectedIndex = idx
                notifyItemChanged(old)
                notifyItemChanged(selectedIndex)
            }
        }

        inner class ModeViewHolder(val b: ItemEditorModeBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModeViewHolder {
            return ModeViewHolder(ItemEditorModeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ModeViewHolder, position: Int) {
            val mode = modes[position]
            holder.b.tvModeName.text = mode.name
            holder.b.ivModeIcon.setImageResource(mode.iconRes)
            val isSelected = selectedIndex == position
            holder.b.tvModeName.setTextColor(if (isSelected) ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_gold) else Color.WHITE)
            holder.b.ivModeIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (isSelected) ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_gold) else Color.WHITE)
            holder.itemView.setOnClickListener {
                val old = selectedIndex
                val currentPosition = holder.adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    selectedIndex = currentPosition
                    notifyItemChanged(old)
                    notifyItemChanged(selectedIndex)
                    onModeSelected(mode)
                    Toast.makeText(this@PhotoEditorActivity, "Selected: ${mode.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        override fun getItemCount() = modes.size
    }

    inner class AdjustmentAdapter(
        private val list: List<AdjustmentType>,
        private val onSelected: (AdjustmentType) -> Unit
    ) : RecyclerView.Adapter<AdjustmentAdapter.ViewHolder>() {
        private var selectedIndex = 1
        inner class ViewHolder(val b: ItemAdjustmentBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemAdjustmentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.b.tvAdjustmentName.text = item.name
            holder.b.ivAdjustmentIcon.setImageResource(item.iconRes)
            val isSelected = selectedIndex == position
            holder.b.iconContainer.isSelected = isSelected
            val value = getAdjustmentValue(item.name)
            val intValue = (value * 100).roundToInt()
            val isModified = intValue != 0 && item.name != "Auto"
            holder.b.adjustmentProgress.isVisible = isModified || (isSelected && item.name != "Auto")
            holder.b.adjustmentProgress.setValue(value)
            if (isSelected && isModified) {
                holder.b.tvAdjustmentValueOverlay.isVisible = true
                holder.b.tvAdjustmentValueOverlay.text = if (intValue > 0) "+$intValue" else "$intValue"
                holder.b.ivAdjustmentIcon.isVisible = false
            } else {
                holder.b.tvAdjustmentValueOverlay.isVisible = false
                holder.b.ivAdjustmentIcon.isVisible = true
            }
            holder.itemView.setOnClickListener {
                val old = selectedIndex
                val currentPosition = holder.adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    selectedIndex = currentPosition
                    notifyItemChanged(old)
                    notifyItemChanged(selectedIndex)
                    onSelected(item)
                }
            }
        }
        private fun getAdjustmentValue(name: String): Float {
            return when (name) {
                "Exposure" -> viewModel.exposure.value / 2f
                "Contrast" -> viewModel.contrast.value - 1f
                "Saturation" -> viewModel.saturation.value - 1f
                "Highlights" -> viewModel.highlights.value
                "Shadows" -> viewModel.shadows.value
                "Warmth" -> viewModel.temperature.value
                "Brilliance" -> viewModel.brightness.value
                else -> 0f
            }
        }
        override fun getItemCount() = list.size
    }

    inner class FilterAdapter(
        private val filters: List<Pair<String, ColorMatrix>>,
        private val onFilterSelected: (ColorMatrix) -> Unit
    ) : RecyclerView.Adapter<FilterAdapter.FilterViewHolder>() {
        private var imageUri: Uri? = null
        private var selectedIndex = 0
        fun updateImage(uri: Uri) { this.imageUri = uri; notifyDataSetChanged() }
        fun updateIntensity(intensity: Int) { notifyItemChanged(selectedIndex) }
        inner class FilterViewHolder(val binding: ItemFilterBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
            val binding = ItemFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return FilterViewHolder(binding)
        }
        override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
            val (name, matrix) = filters[position]
            holder.binding.tvFilterName.text = name
            val isSelected = selectedIndex == position
            if (name == "Normal") {
                holder.binding.ivOriginalIcon.isVisible = true
                holder.binding.ivFilterPreview.setImageDrawable(null)
                holder.binding.tvFilterIntensityOverlay.isVisible = false
                holder.binding.tvInternalLabel.isVisible = false
            } else {
                holder.binding.ivOriginalIcon.isVisible = false
                imageUri?.let {
                    holder.binding.ivFilterPreview.load(it) { crossfade(true) }
                    holder.binding.ivFilterPreview.colorFilter = ColorMatrixColorFilter(matrix)
                }
                holder.binding.tvFilterIntensityOverlay.isVisible = isSelected
                if (isSelected) holder.binding.tvFilterIntensityOverlay.text = "${(viewModel.filterIntensity.value * 100).toInt()}"
                holder.binding.tvInternalLabel.isVisible = name.contains("Silk") || name.contains("Cinema")
                holder.binding.tvInternalLabel.text = if (name.contains("Silk")) "NC Film" else "NH Film"
            }
            holder.binding.filterCard.strokeWidth = if (isSelected) 4 else 0
            holder.binding.tvFilterName.setTextColor(
                if (isSelected) ContextCompat.getColor(this@PhotoEditorActivity, R.color.lumina_gold)
                else Color.parseColor("#808080")
            )
            holder.itemView.setOnClickListener {
                val oldIndex = selectedIndex
                val currentPosition = holder.adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    selectedIndex = currentPosition
                    notifyItemChanged(oldIndex)
                    notifyItemChanged(selectedIndex)
                    onFilterSelected(matrix)
                }
            }
        }
        override fun getItemCount() = filters.size
    }
}
