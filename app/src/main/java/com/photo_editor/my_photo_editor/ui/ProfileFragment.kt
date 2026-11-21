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
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

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
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            putExtra(Intent.EXTRA_TEXT, "Check out this amazing Photo Editor app: https://play.google.com/store/apps/details?id=${requireContext().packageName}")
        }
        startActivity(Intent.createChooser(shareIntent, "Share via"))
    }

    private fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("feedback@lumina.com"))
            putExtra(Intent.EXTRA_SUBJECT, "Feedback for Photo Editor")
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
