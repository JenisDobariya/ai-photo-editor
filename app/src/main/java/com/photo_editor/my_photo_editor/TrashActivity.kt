package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import com.photo_editor.my_photo_editor.utils.appColor
import com.photo_editor.my_photo_editor.utils.accentColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMicros
import com.photo_editor.my_photo_editor.databinding.ActivityTrashBinding
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.TrashedMedia
import com.photo_editor.my_photo_editor.ui.TrashViewModel
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** System Recycle Bin (Android 11+): restore or permanently delete trashed media. */
class TrashActivity : BaseActivity() {

    override fun getScreenTag(): String = "Trash"

    private lateinit var binding: ActivityTrashBinding
    private val viewModel: TrashViewModel by viewModels()
    private val adapter = TrashAdapter { viewModel.toggle(it.media.uri) }
    private var pending: Pair<TrashViewModel.Action, Int>? = null

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val (action, count) = pending ?: return@registerForActivityResult
        pending = null
        viewModel.onRequestResult(action, count, result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.selection.value.isNotEmpty()) viewModel.clearSelection() else finish()
            }
        })
        binding.rvTrash.layoutManager = GridLayoutManager(this, 3)
        binding.rvTrash.adapter = adapter
        binding.swipeRefresh.setColorSchemeColors(this.accentColor)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.lumina_surface_high)
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        binding.btnSelectAll.setOnClickListener { viewModel.toggleSelectAll() }
        binding.btnRestore.setOnClickListener {
            AnalyticsManager.logEvent("Trash_restore_click")
            viewModel.restoreSelected()
        }
        binding.btnDeleteForever.setOnClickListener {
            AnalyticsManager.logEvent("Trash_deleteForever_click")
            viewModel.deleteSelected()
        }
        binding.btnEmpty.setOnClickListener {
            AnalyticsManager.logEvent("Trash_empty_click")
            AppDialog.Builder(this)
                .setIcon(R.drawable.ic_g_delete, R.color.lumina_danger)
                .setTitle("Empty Recycle Bin?")
                .setMessage("All ${viewModel.items.value.size} items will be permanently deleted. This can't be undone.")
                .setPositiveButton("Empty") { _, _ -> viewModel.emptyBin() }
                .setDestructive()
                .setNegativeButton("Cancel", null)
                .show()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.actionBar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + (12 * resources.displayMetrics.density).toInt())
            insets
        }

        if (!viewModel.isSupported) {
            showUnsupported()
            return
        }
        observe()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isSupported) viewModel.load()
    }

    private fun showUnsupported() {
        binding.infoBanner.isVisible = false
        binding.btnEmpty.isVisible = false
        binding.btnSelectAll.isVisible = false
        binding.emptyState.isVisible = true
        binding.tvEmptyTitle.text = "Recycle Bin needs Android 11+"
        binding.tvEmptySubtitle.text = "On this device deleted items are removed permanently."
        binding.tvSubtitle.text = ""
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.items, viewModel.selection) { items, selection -> items to selection }
                        .collect { (items, selection) ->
                            adapter.submitList(items.map { TrashRow(it, selection.contains(it.media.uri)) })
                            val empty = items.isEmpty()
                            binding.emptyState.isVisible = empty && !viewModel.isLoading.value
                            binding.infoBanner.isVisible = !empty
                            binding.btnEmpty.isVisible = !empty && selection.isEmpty()
                            binding.btnSelectAll.isVisible = !empty
                            binding.actionBar.isVisible = selection.isNotEmpty()
                            binding.tvSubtitle.text = when {
                                selection.isNotEmpty() -> "${selection.size} selected"
                                items.size == 1 -> "1 item"
                                else -> "${items.size} items"
                            }
                        }
                }
                launch {
                    viewModel.isLoading.collect { binding.swipeRefresh.isRefreshing = it }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is TrashViewModel.TrashEvent.Message ->
                                Toast.makeText(this@TrashActivity, event.text, Toast.LENGTH_SHORT).show()
                            is TrashViewModel.TrashEvent.LaunchIntentSender -> {
                                pending = event.action to event.count
                                intentSenderLauncher.launch(IntentSenderRequest.Builder(event.sender).build())
                            }
                        }
                    }
                }
            }
        }
    }


    data class TrashRow(val item: TrashedMedia, val selected: Boolean)

    private class TrashAdapter(
        private val onClick: (TrashedMedia) -> Unit
    ) : ListAdapter<TrashRow, TrashAdapter.VH>(DIFF) {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.ivTrash)
            val days: TextView = view.findViewById(R.id.tvDaysLeft)
            val video: ImageView = view.findViewById(R.id.ivTrashVideo)
            val select: ImageView = view.findViewById(R.id.ivTrashSelect)
            val overlay: View = view.findViewById(R.id.trashSelectedOverlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = getItem(position)
            val media = row.item.media
            val path = row.item.dataPath
            holder.image.load(media.uri) {
                if (media.type == MediaType.VIDEO) videoFrameMicros(1_000_000)
                listener(onError = { _, _ ->
                    // Trashed files can't always be opened by uri; fall back to the file path.
                    if (path != null) holder.image.load(File(path))
                })
            }
            holder.video.isVisible = media.type == MediaType.VIDEO
            val daysLeft = if (row.item.expiresAtSeconds > 0) {
                val seconds = row.item.expiresAtSeconds - System.currentTimeMillis() / 1000
                max(0, ceil(seconds / 86_400.0).toInt())
            } else -1
            holder.days.isVisible = daysLeft >= 0
            holder.days.text = if (daysLeft == 1) "1 day" else "$daysLeft days"
            holder.select.setImageResource(if (row.selected) R.drawable.ic_g_check_circle else R.drawable.ic_g_circle_outline)
            holder.select.setColorFilter(
                holder.itemView.context.appColor(if (row.selected) R.color.lumina_accent else R.color.white)
            )
            holder.overlay.isVisible = row.selected
            holder.itemView.setOnClickListener { onClick(row.item) }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<TrashRow>() {
                override fun areItemsTheSame(a: TrashRow, b: TrashRow) = a.item.media.uri == b.item.media.uri
                override fun areContentsTheSame(a: TrashRow, b: TrashRow) = a == b
            }
        }
    }
}
