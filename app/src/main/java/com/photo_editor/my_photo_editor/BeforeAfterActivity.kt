package com.photo_editor.my_photo_editor

import android.content.Intent
import com.photo_editor.my_photo_editor.utils.accentColor
import com.photo_editor.my_photo_editor.utils.appColor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.photo_editor.my_photo_editor.databinding.ActivityBeforeAfterBinding
import com.photo_editor.my_photo_editor.databinding.ItemSplitModeBinding
import com.photo_editor.my_photo_editor.ui.BeforeAfterView
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BeforeAfterActivity : BaseActivity() {

    override fun getScreenTag(): String = "BeforeAfter"

    // Photos look best on dark: this screen stays dark in every theme.
    override val alwaysDark = true

    private lateinit var binding: ActivityBeforeAfterBinding

    private var beforeUri: Uri? = null
    private var afterUri: Uri? = null

    private var isReplacingBefore = false

    private val replacePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            loadSinglePhoto(uri, isBefore = isReplacingBefore)
        }
    }

    data class SplitModeItem(val mode: BeforeAfterView.Mode, val name: String, val iconRes: Int)
    data class RatioItem(val name: String, val ratio: Float?)

    private val splitModes = listOf(
        SplitModeItem(BeforeAfterView.Mode.SLIDER, "Slider", R.drawable.ic_compare),
        SplitModeItem(BeforeAfterView.Mode.SIDE_BY_SIDE, "Side by Side", R.drawable.ic_swap_horiz),
        SplitModeItem(BeforeAfterView.Mode.TOP_BOTTOM, "Top / Bottom", R.drawable.ic_aspect_ratio),
        SplitModeItem(BeforeAfterView.Mode.DIAGONAL, "Diagonal", R.drawable.ic_crop)
    )

    private val ratios = listOf(
        RatioItem("Original", null),
        RatioItem("1:1", 1.0f),
        RatioItem("4:5", 0.8f),
        RatioItem("9:16", 9f / 16f),
        RatioItem("16:9", 16f / 9f)
    )

    private val lineColors = listOf(
        Color.parseColor("#3D8BFD"), // App blue
        Color.parseColor("#F59E0B"), // Amber
        Color.WHITE,
        Color.parseColor("#06B6D4"), // Cyan
        Color.parseColor("#10B981"), // Emerald
        Color.parseColor("#EF4444"), // Red
        Color.parseColor("#21252B")  // Dark Slate
    )

    private var selectedModeIndex = 0
    private var selectedRatioIndex = 0
    private var selectedColor = Color.parseColor("#3D8BFD")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeforeAfterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parseUris()
        setupUI()
        loadPhotos()
    }

    private fun parseUris() {
        val bString = intent.getStringExtra("BEFORE_URI")
        val aString = intent.getStringExtra("AFTER_URI")

        if (bString != null) beforeUri = Uri.parse(bString)
        if (aString != null) afterUri = Uri.parse(aString)

        val list = intent.getStringArrayListExtra("URIS")
        if (list != null && list.isNotEmpty()) {
            if (beforeUri == null && list.size > 0) beforeUri = Uri.parse(list[0])
            if (afterUri == null && list.size > 1) afterUri = Uri.parse(list[1])
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("BeforeAfter_btnBack_click")
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveComparison()
        }

        val swapAction = {
            AnalyticsManager.logEvent("BeforeAfter_swap_click")
            binding.beforeAfterView.swapBitmaps()
            val tempUri = beforeUri
            beforeUri = afterUri
            afterUri = tempUri
            Toast.makeText(this, "Photos swapped", Toast.LENGTH_SHORT).show()
        }

        binding.btnSwapTop.setOnClickListener { swapAction() }
        binding.btnSwapAction.setOnClickListener { swapAction() }

        setupToolTabs()
        setupSplitModesRecycler()
        setupRatiosRecycler()
        setupBadgesPanel()
        setupStylePanel()
        setupReplaceButtons()
    }

    private fun setupToolTabs() {
        val tabs = listOf(
            binding.tabSplitMode,
            binding.tabRatio,
            binding.tabBadges,
            binding.tabStyle,
            binding.tabReplace
        )

        fun switchTab(index: Int) {
            tabs.forEachIndexed { i, tv ->
                val isSel = i == index
                tv.setBackgroundResource(if (isSel) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tv.setTextColor(if (isSel) Color.WHITE else this.appColor(R.color.lumina_text_secondary))
                tv.setTypeface(null, if (isSel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }

            binding.rvSplitModes.isVisible = (index == 0)
            binding.rvRatios.isVisible = (index == 1)
            binding.panelBadges.isVisible = (index == 2)
            binding.panelStyle.isVisible = (index == 3)
            binding.panelReplace.isVisible = (index == 4)
        }

        tabs.forEachIndexed { index, tv ->
            tv.setOnClickListener { switchTab(index) }
        }
    }

    private fun setupSplitModesRecycler() {
        binding.rvSplitModes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSplitModes.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val b = ItemSplitModeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return object : RecyclerView.ViewHolder(b.root) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = splitModes[position]
                val b = ItemSplitModeBinding.bind(holder.itemView)
                b.tvModeName.text = item.name
                b.ivModeIcon.setImageResource(item.iconRes)

                val isSelected = position == selectedModeIndex
                b.modeRoot.setBackgroundResource(if (isSelected) R.drawable.bg_font_card_selected else R.drawable.bg_font_card_unselected)
                b.tvModeName.setTextColor(if (isSelected) this@BeforeAfterActivity.appColor(R.color.lumina_gold) else this@BeforeAfterActivity.appColor(R.color.lumina_text_secondary))
                b.ivModeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isSelected) this@BeforeAfterActivity.appColor(R.color.lumina_gold) else this@BeforeAfterActivity.appColor(R.color.lumina_text_secondary)
                )

                b.modeRoot.setOnClickListener {
                    val prev = selectedModeIndex
                    selectedModeIndex = holder.bindingAdapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(selectedModeIndex)
                    binding.beforeAfterView.mode = item.mode
                }
            }

            override fun getItemCount() = splitModes.size
        }
    }

    private fun setupRatiosRecycler() {
        binding.rvRatios.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRatios.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_text_badge, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = ratios[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tvBadgeSample)
                tv.text = item.name

                val isSelected = position == selectedRatioIndex
                tv.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tv.setTextColor(if (isSelected) Color.WHITE else this@BeforeAfterActivity.appColor(R.color.lumina_text_secondary))
                tv.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                tv.setOnClickListener {
                    val prev = selectedRatioIndex
                    selectedRatioIndex = holder.bindingAdapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(selectedRatioIndex)
                    binding.beforeAfterView.aspectRatio = item.ratio
                }
            }

            override fun getItemCount() = ratios.size
        }
    }

    private fun setupBadgesPanel() {
        val badgeButtons = listOf(
            binding.btnBadgeDark to BeforeAfterView.BadgeStyle.DARK_PILL,
            binding.btnBadgeGold to BeforeAfterView.BadgeStyle.GOLD_ACCENT,
            binding.btnBadgeMinimal to BeforeAfterView.BadgeStyle.MINIMAL,
            binding.btnBadgeHide to BeforeAfterView.BadgeStyle.NONE
        )

        badgeButtons.forEach { (btn, style) ->
            btn.setOnClickListener {
                badgeButtons.forEach { (b, _) ->
                    val isSel = b == btn
                    b.setBackgroundResource(if (isSel) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                    b.setTextColor(if (isSel) Color.WHITE else this.appColor(R.color.lumina_text_secondary))
                    b.setTypeface(null, if (isSel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
                binding.beforeAfterView.badgeStyle = style
            }
        }

        val presetButtons = listOf(
            binding.btnPresetBeforeAfter to ("BEFORE" to "AFTER"),
            binding.btnPresetOriginalEdited to ("ORIGINAL" to "EDITED"),
            binding.btnPresetRawRetouch to ("RAW" to "RETOUCH")
        )

        presetButtons.forEach { (btn, pair) ->
            btn.setOnClickListener {
                presetButtons.forEach { (b, _) ->
                    val isSel = b == btn
                    b.setBackgroundResource(if (isSel) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                    b.setTextColor(if (isSel) Color.WHITE else this.appColor(R.color.lumina_text_secondary))
                    b.setTypeface(null, if (isSel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
                binding.beforeAfterView.beforeLabel = pair.first
                binding.beforeAfterView.afterLabel = pair.second
            }
        }
    }

    private fun setupStylePanel() {
        binding.sbThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThicknessValue.text = "${progress}dp"
                if (fromUser) {
                    binding.beforeAfterView.dividerThicknessDp = progress.toFloat()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvRadiusValue.text = "${progress}dp"
                if (fromUser) {
                    binding.beforeAfterView.cornerRadiusDp = progress.toFloat()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.rvLineColors.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvLineColors.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_text_color_swatch, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val color = lineColors[position]
                val vRing = holder.itemView.findViewById<View>(R.id.vSelectionRing)
                val vCircle = holder.itemView.findViewById<View>(R.id.vColorCircle)

                vCircle.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (color == Color.BLACK) {
                        setStroke(1, Color.WHITE)
                    }
                }

                val isSelected = color == selectedColor
                vRing.isVisible = isSelected
                if (isSelected) {
                    vRing.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(2, accentColor)
                    }
                }

                holder.itemView.setOnClickListener {
                    selectedColor = color
                    notifyDataSetChanged()
                    binding.beforeAfterView.dividerColor = selectedColor
                }
            }

            override fun getItemCount() = lineColors.size
        }
    }

    private fun setupReplaceButtons() {
        binding.btnReplaceBefore.setOnClickListener {
            isReplacingBefore = true
            replacePhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnReplaceAfter.setOnClickListener {
            isReplacingBefore = false
            replacePhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun loadPhotos() {
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val bBm = beforeUri?.let { fetchBitmap(it) }
            val aBm = afterUri?.let { fetchBitmap(it) } ?: bBm

            binding.beforeAfterView.beforeBitmap = bBm
            binding.beforeAfterView.afterBitmap = aBm
            binding.progressBar.isVisible = false

            if (bBm == null && aBm == null) {
                Toast.makeText(this@BeforeAfterActivity, "Please select at least one photo", Toast.LENGTH_SHORT).show()
                finish()
            } else if (afterUri == null) {
                Toast.makeText(this@BeforeAfterActivity, "Pick an 'After' photo from Photos tab below to compare", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSinglePhoto(uri: Uri, isBefore: Boolean) {
        binding.progressBar.isVisible = true
        lifecycleScope.launch {
            val bm = fetchBitmap(uri)
            binding.progressBar.isVisible = false
            if (bm != null) {
                if (isBefore) {
                    beforeUri = uri
                    binding.beforeAfterView.beforeBitmap = bm
                    Toast.makeText(this@BeforeAfterActivity, "Before photo updated", Toast.LENGTH_SHORT).show()
                } else {
                    afterUri = uri
                    binding.beforeAfterView.afterBitmap = bm
                    Toast.makeText(this@BeforeAfterActivity, "After photo updated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun fetchBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(this@BeforeAfterActivity)
                .data(uri)
                .size(2048, 2048)
                // Software bitmaps: the export draws them into a normal Canvas, which can't
                // take hardware bitmaps ("Software rendering doesn't support hardware bitmaps").
                .allowHardware(false)
                .build()
            val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
            (result as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveComparison() {
        AnalyticsManager.logEvent("BeforeAfter_save_click")
        binding.progressBar.isVisible = true
        binding.btnSave.isEnabled = false // no duplicate files from a double tap

        lifecycleScope.launch {
            val savedUri = try {
                val bitmap = withContext(Dispatchers.Default) {
                    binding.beforeAfterView.exportBitmap(2048)
                }
                withContext(Dispatchers.IO) {
                    ImageSaver.saveDirectBitmapToMediaStore(this@BeforeAfterActivity, bitmap, "BEFORE_AFTER")
                }
            } catch (e: Throwable) {
                // Out of memory / storage errors: report instead of crashing.
                e.printStackTrace()
                null
            }

            binding.progressBar.isVisible = false
            binding.btnSave.isEnabled = true
            if (savedUri != null) {
                Toast.makeText(this@BeforeAfterActivity, "Comparison card saved to Gallery!", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@BeforeAfterActivity, PhotoDetailActivity::class.java).apply {
                    putExtra("IMAGE_URI", savedUri.toString())
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@BeforeAfterActivity, "Failed to save comparison card", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
