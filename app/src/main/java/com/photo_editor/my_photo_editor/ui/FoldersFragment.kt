package com.photo_editor.my_photo_editor.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.photo_editor.my_photo_editor.AlbumDetailActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.FragmentMediaGridBinding
import com.photo_editor.my_photo_editor.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

import coil.request.videoFrameMicros

class FoldersFragment : Fragment() {

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var albumAdapter: AlbumAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        albumAdapter = AlbumAdapter(emptyList()) { albumItem ->
            val intent = Intent(requireContext(), AlbumDetailActivity::class.java).apply {
                putExtra("ALBUM_NAME", albumItem.name)
            }
            startActivity(intent)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = albumAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadMedia()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allMedia.collectLatest { media ->
                        processAndShowAlbums(media)
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                    }
                }
            }
        }
    }

    private suspend fun processAndShowAlbums(allMedia: List<MediaFile>) {
        val albumItems = withContext(Dispatchers.Default) {
            val albumGroups = allMedia.groupBy { it.album }
            val items = mutableListOf<AlbumItem>()
            
            val favorites = allMedia.filter { it.isFavorite }
            if (favorites.isNotEmpty()) {
                items.add(AlbumItem("Favorites", favorites.first().uri, favorites.size, true))
            }

            val otherAlbums = albumGroups.map { (name, photos) ->
                AlbumItem(name, photos.first().uri, photos.size)
            }.sortedByDescending { albumItem ->
                allMedia.filter { it.album == albumItem.name }.maxOfOrNull { it.dateAdded } ?: 0L
            }
            items.addAll(otherAlbums)
            items
        }

        binding.emptyStateView.isVisible = albumItems.isEmpty()
        albumAdapter.updateAlbums(albumItems)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class AlbumItem(
        val name: String, 
        val coverUri: android.net.Uri, 
        val count: Int,
        val isFavoritesFolder: Boolean = false
    )

    class AlbumAdapter(
        private var albums: List<AlbumItem>,
        private val onClick: (AlbumItem) -> Unit
    ) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

        fun updateAlbums(newAlbums: List<AlbumItem>) {
            albums = newAlbums
            notifyDataSetChanged()
        }

        class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.iv_photo)
            val labelView: TextView = view.findViewById(R.id.tv_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
            return AlbumViewHolder(view)
        }

        override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
            val album = albums[position]
            holder.labelView.isVisible = true
            holder.labelView.text = String.format(Locale.getDefault(), "%s (%d)", album.name, album.count)
            holder.imageView.load(album.coverUri) {
                crossfade(true)
                videoFrameMicros(1000000)
            }
            holder.itemView.setOnClickListener { onClick(album) }
        }

        override fun getItemCount() = albums.size
    }
}
