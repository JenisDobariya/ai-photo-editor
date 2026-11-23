package com.photo_editor.my_photo_editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.photo_editor.my_photo_editor.databinding.ActivityProfileBinding
import com.photo_editor.my_photo_editor.utils.AnalyticsManager

class ProfileActivity : BaseActivity() {

    override fun getScreenTag(): String = "Profile"

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("Profile_btnBack_click")
            finish()
        }

        // Support Section
        setupItem(binding.itemUpdateApp.root, R.drawable.ic_settings, "Update App", null) {
            openPlayStore()
        }

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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://myaiphotoeditor.blogspot.com/2026/09/privacy-policy.html"))
            startActivity(intent)
        }

        setupItem(binding.itemPlayStore.root, R.drawable.ic_add_photo, "More Apps", null) {
            openPlayStore()
        }

        // Version Info
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = "Version 1.0.0"
        }
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

        view.setOnClickListener {
            val eventTag = "Profile_" + title.replace(" ", "") + "_click"
            AnalyticsManager.logEvent(eventTag)
            onClick()
        }
    }

    private fun openPlayStore() {
        val appPackageName = packageName
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
                getString(R.string.share_app_text, getString(R.string.app_full_name), "https://play.google.com/store/apps/details?id=$packageName")
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
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }
}
