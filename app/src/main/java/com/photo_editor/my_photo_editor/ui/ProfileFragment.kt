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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.text.format.Formatter
import com.photo_editor.my_photo_editor.HiddenMediaActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.TrashActivity
import com.photo_editor.my_photo_editor.databinding.FragmentProfileBinding
import kotlinx.coroutines.launch
import java.util.Locale

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeLibraryStats()
    }

    private fun observeLibraryStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.libraryStats.collect { stats ->
                    binding.tvStatPhotos.text = String.format(Locale.getDefault(), "%,d", stats.photoCount)
                    binding.tvStatVideos.text = String.format(Locale.getDefault(), "%,d", stats.videoCount)
                    binding.tvStatAlbums.text = String.format(Locale.getDefault(), "%,d", stats.albumCount)
                    binding.tvStatFavorites.text = String.format(Locale.getDefault(), "%,d", stats.favoriteCount)
                    binding.tvStatStorage.text = Formatter.formatShortFileSize(requireContext(), stats.totalBytes)
                }
            }
        }
    }

    private fun setupUI() {
        // Settings Section
//        setupItem(binding.itemTheme.root, R.drawable.ic_studio, "Appearance", "Dark Theme") {
//            showThemeDialog()
//        }
//
//        setupItem(binding.itemNotifications.root, R.drawable.ic_settings, "Notifications", "On") {
//            Toast.makeText(requireContext(), "Notification settings coming soon", Toast.LENGTH_SHORT).show()
//        }

        // Gallery Section
        setupItem(binding.itemRecycleBin.root, R.drawable.ic_g_delete, "Recycle Bin", "30 days") {
            startActivity(Intent(requireContext(), TrashActivity::class.java))
        }

        setupItem(binding.itemLockedFolder.root, R.drawable.ic_g_lock, "Locked Folder", "Private") {
            startActivity(Intent(requireContext(), HiddenMediaActivity::class.java))
        }

        // Support Section
        setupItem(binding.itemRateUs.root, R.drawable.ic_like, "Rate Us", null) {
            openPlayStore()
        }

        setupItem(binding.itemShare.root, R.drawable.ic_share, "Share App", null) {
            shareApp()
        }

        setupItem(binding.itemFeedback.root, R.drawable.ic_text, "Send Feedback", null) {
            sendFeedback()
        }

        // About Section
        setupItem(binding.itemPrivacy.root, R.drawable.ic_settings, "Privacy Policy", null) {
//            Toast.makeText(requireContext(), "Privacy Policy coming soon", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaiphotoeditor.blogspot.com/2026/09/privacy-policy.html")))

        }

        setupItem(binding.itemPlayStore.root, R.drawable.ic_add_photo, "More Apps", null) {
            openPlayStore()
        }

        // Version Info
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = "Version 1.0.0"
        }
    }

    private fun showThemeDialog() {
        val themes = arrayOf("Light", "Dark", "System Default")
        AppDialog.Builder(requireContext())
            .setTitle("Choose Theme")
            .setItems(themes) { _, which ->
                Toast.makeText(requireContext(), "Theme set to: ${themes[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setupItem(
        view: View, 
        iconRes: Int, 
        title: String, 
        value: String?, 
        onClick: () -> Unit
    ) {
        val ivIcon = view.findViewById<ImageView>(R.id.ivItemIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvItemTitle)
        val tvValue = view.findViewById<TextView>(R.id.tvItemValue)

        ivIcon.setImageResource(iconRes)
        tvTitle.text = title
        if (value != null) {
            tvValue.text = value
            tvValue.visibility = View.VISIBLE
        } else {
            tvValue.visibility = View.GONE
        }

        view.setOnClickListener { onClick() }
    }

    private fun openPlayStore() {
        val appPackageName = requireContext().packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")))
        }
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_full_name))
            putExtra(
                Intent.EXTRA_TEXT,
                getString(
                    R.string.share_app_text, getString(R.string.app_full_name),
                    "https://play.google.com/store/apps/details?id=${requireContext().packageName}"
                )
            )
        }
        startActivity(Intent.createChooser(shareIntent, "Share via"))
    }

    private fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:mydjearth@gmail.com")
            putExtra(Intent.EXTRA_SUBJECT, "Feedback for ${getString(R.string.app_name)}")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
