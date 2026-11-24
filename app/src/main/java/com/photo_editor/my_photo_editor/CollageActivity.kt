package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import com.photo_editor.my_photo_editor.utils.accentContainerColor
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.card.MaterialCardView
import com.photo_editor.my_photo_editor.databinding.ActivityCollageBinding
import com.photo_editor.my_photo_editor.model.CollageLayoutRepository
import com.photo_editor.my_photo_editor.model.CollageTemplate
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollageActivity : BaseActivity() {

    override fun getScreenTag(): String = "Collage"

    // Photos look best on dark: this screen stays dark in every theme.
    override val alwaysDark = true

    private lateinit var binding: ActivityCollageBinding
    private val selectedUris = mutableListOf<Uri>()
    private var replaceSlotIndex: Int = -1

    private val replacePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && replaceSlotIndex != -1) {
            loadSingleBitmapAndReplace(replaceSlotIndex, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parseIntentUris()
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, "No photos selected for collage", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        loadPhotos()
    }

    private fun parseIntentUris() {
        // Support both ArrayList<Uri> and ArrayList<String>
        val uriList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("COLLAGE_URIS", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("COLLAGE_URIS")
        }

        if (!uriList.isNullOrEmpty()) {
            selectedUris.addAll(uriList)
        } else {
            val stringList = intent.getStringArrayListExtra("COLLAGE_URIS")
            if (!stringList.isNullOrEmpty()) {
                selectedUris.addAll(stringList.map { Uri.parse(it) })
            }
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("Collage_btnBack_click")
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveCollage()
        }

        setupToolTabs()
        setupSlotActions()
        setupBorderSliders()
        setupRatioList()
        setupBackgroundColors()

        binding.collageView.onSlotSelectedListener = { index ->
            binding.slotToolbar.isVisible = (index != -1)
        }
    }

    private fun setupToolTabs() {
        val tabs = listOf(
            binding.tabLayout to binding.rvLayouts,
            binding.tabRatio to binding.rvRatios,
            binding.tabBorder to binding.borderPanel,
            binding.tabBackground to binding.rvBackgrounds
        )

        fun selectTab(selectedView: TextView, selectedPanel: View) {
            tabs.forEach { (tabView, panel) ->
                val isSelected = (tabView == selectedView)
                tabView.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tabView.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#9BA3AF"))
                panel.isVisible = isSelected
            }
        }

        binding.tabLayout.setOnClickListener { selectTab(binding.tabLayout, binding.rvLayouts) }
        binding.tabRatio.setOnClickListener { selectTab(binding.tabRatio, binding.rvRatios) }
        binding.tabBorder.setOnClickListener { selectTab(binding.tabBorder, binding.borderPanel) }
        binding.tabBackground.setOnClickListener { selectTab(binding.tabBackground, binding.rvBackgrounds) }
    }

    private fun setupSlotActions() {
        binding.btnRotateSlot.setOnClickListener {
            AnalyticsManager.logEvent("Collage_slot_rotate")
            binding.collageView.rotateSelectedSlot()
        }

        binding.btnReplaceSlot.setOnClickListener {
            AnalyticsManager.logEvent("Collage_slot_replace")
            replaceSlotIndex = binding.collageView.selectedSlotIndex
            if (replaceSlotIndex != -1) {
                replacePhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        binding.btnSwapSlot.setOnClickListener {
            AnalyticsManager.logEvent("Collage_slot_swap")
            val currentIndex = binding.collageView.selectedSlotIndex
            val count = binding.collageView.getSlotCount()
            if (currentIndex != -1 && count > 1) {
                val nextIndex = (currentIndex + 1) % count
                binding.collageView.swapSlots(currentIndex, nextIndex)
                Toast.makeText(this, "Swapped photo with slot ${nextIndex + 1}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBorderSliders() {
        val density = resources.displayMetrics.density

        binding.sbBorderMargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.collageView.outerMargin = progress * density
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.collageView.spacing = progress * density
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.collageView.cornerRadius = progress * density
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupRatioList() {
        val ratios = listOf(
            RatioItem("1:1", 1.0f),
            RatioItem("4:5", 4f / 5f),
            RatioItem("9:16", 9f / 16f),
            RatioItem("3:4", 3f / 4f),
            RatioItem("2:3", 2f / 3f),
            RatioItem("16:9", 16f / 9f)
        )

        binding.rvRatios.adapter = RatioAdapter(ratios) { ratio ->
            AnalyticsManager.logEvent("Collage_ratio_${ratio.name}")
            binding.collageView.aspectRatio = ratio.value
        }
    }

    private data class RatioItem(val name: String, val value: Float)

    private inner class RatioAdapter(
        private val items: List<RatioItem>,
        private val onSelected: (RatioItem) -> Unit
    ) : RecyclerView.Adapter<RatioAdapter.ViewHolder>() {
        private var selectedPosition = 0

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon: TextView = view.findViewById(R.id.tvRatioIcon)
            val tvName: TextView = view.findViewById(R.id.tvRatioName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ratio, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvIcon.text = item.name
            val isSelected = (position == selectedPosition)
            holder.tvIcon.setBackgroundResource(if (isSelected) R.drawable.bg_circle_accent else R.drawable.bg_pill_dark)
            holder.tvIcon.setTextColor(Color.WHITE)

            holder.view.setOnClickListener {
                val old = selectedPosition
                selectedPosition = holder.bindingAdapterPosition
                notifyItemChanged(old)
                notifyItemChanged(selectedPosition)
                onSelected(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun setupBackgroundColors() {
        val colors = listOf(
            Color.parseColor("#0F1114"), // App dark
            Color.parseColor("#000000"), // Pure Black
            Color.parseColor("#FFFFFF"), // Pure White
            Color.parseColor("#F8F9FA"), // Off White
            Color.parseColor("#21252B"), // Charcoal
            Color.parseColor("#3D8BFD"), // App blue
            Color.parseColor("#F59E0B"), // Lumina Gold
            Color.parseColor("#FCE7F3"), // Pastel Pink
            Color.parseColor("#E0F2FE"), // Pastel Blue
            Color.parseColor("#DCFCE7"), // Pastel Mint
            Color.parseColor("#FEF9C3"), // Pastel Yellow
            Color.parseColor("#EF4444")  // Crimson Red
        )

        binding.rvBackgrounds.adapter = ColorAdapter(colors) { color ->
            AnalyticsManager.logEvent("Collage_color_change")
            binding.collageView.collageBackgroundColor = color
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

    private fun loadPhotos() {
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val loadedBitmaps = withContext(Dispatchers.IO) {
                selectedUris.mapNotNull { uri ->
                    try {
                        val request = ImageRequest.Builder(this@CollageActivity)
                            .data(uri)
                            .size(1500)
                            .allowHardware(false)
                            .build()
                        val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                        val bmp = (result as? BitmapDrawable)?.bitmap
                        if (bmp != null) uri to bmp else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            binding.progressBar.isVisible = false

            if (loadedBitmaps.isEmpty()) {
                Toast.makeText(this@CollageActivity, "Could not load selected photos", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            // Set up layout templates
            val templates = CollageLayoutRepository.getTemplatesForCount(loadedBitmaps.size)
            binding.collageView.setTemplate(templates.first())
            binding.collageView.setImageBitmaps(loadedBitmaps)

            setupLayoutTemplates(templates)
        }
    }

    private fun setupLayoutTemplates(templates: List<CollageTemplate>) {
        binding.rvLayouts.adapter = LayoutAdapter(templates) { template ->
            AnalyticsManager.logEvent("Collage_template_${template.id}")
            binding.collageView.setTemplate(template)
        }
    }

    private inner class LayoutAdapter(
        private val templates: List<CollageTemplate>,
        private val onSelected: (CollageTemplate) -> Unit
    ) : RecyclerView.Adapter<LayoutAdapter.ViewHolder>() {
        private var selectedPosition = 0

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardLayout)
            val tvName: TextView = view.findViewById(R.id.tvLayoutName)
            val icon: ImageView = view.findViewById(R.id.ivLayoutIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_collage_layout, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = templates[position]
            holder.tvName.text = item.name
            // Show the real arrangement of this template instead of a generic grid icon.
            holder.icon.setImageDrawable(com.photo_editor.my_photo_editor.ui.CollagePreviewDrawable(item.slots))
            val isSelected = (position == selectedPosition)
            holder.card.strokeWidth = if (isSelected) 3 else 0
            holder.card.setCardBackgroundColor(if (isSelected) holder.card.context.accentContainerColor else Color.parseColor("#21252B"))

            holder.view.setOnClickListener {
                val old = selectedPosition
                selectedPosition = holder.bindingAdapterPosition
                notifyItemChanged(old)
                notifyItemChanged(selectedPosition)
                onSelected(item)
            }
        }

        override fun getItemCount(): Int = templates.size
    }

    private fun loadSingleBitmapAndReplace(slotIndex: Int, uri: Uri) {
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(this@CollageActivity)
                        .data(uri)
                        .size(1500)
                        .allowHardware(false)
                        .build()
                    val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                    (result as? BitmapDrawable)?.bitmap
                } catch (e: Exception) {
                    null
                }
            }

            binding.progressBar.isVisible = false

            if (bmp != null) {
                binding.collageView.replaceSelectedSlot(uri, bmp)
                Toast.makeText(this@CollageActivity, "Photo replaced", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@CollageActivity, "Failed to load replacement photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCollage() {
        AnalyticsManager.logEvent("Collage_btnSave_click")
        binding.progressBar.isVisible = true
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            val savedUri = try {
                val exportedBitmap = withContext(Dispatchers.Default) {
                    binding.collageView.exportBitmap(2048)
                }
                withContext(Dispatchers.IO) {
                    ImageSaver.saveDirectBitmapToMediaStore(this@CollageActivity, exportedBitmap, "COLLAGE")
                }
            } catch (e: Throwable) {
                // Out of memory / storage errors: report instead of crashing.
                e.printStackTrace()
                null
            }

            binding.progressBar.isVisible = false
            binding.btnSave.isEnabled = true

            if (savedUri != null) {
                showSaveSuccessDialog(savedUri)
            } else {
                Toast.makeText(this@CollageActivity, "Failed to save collage", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSaveSuccessDialog(savedUri: Uri) {
        val dialog = AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
            .setTitle("Collage saved")
            .setMessage("Your collage is ready in your gallery.")
            .addRow("Location", "Pictures/PhotoEditor")
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
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, savedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Collage"))
            }
            .setNegativeButton("Done") { _, _ ->
                finish()
            }
            .create()

        dialog.show()
    }
}
