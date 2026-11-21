package com.photo_editor.my_photo_editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.photo_editor.my_photo_editor.databinding.ActivityGalleryBinding
import com.photo_editor.my_photo_editor.ui.FoldersFragment
import com.photo_editor.my_photo_editor.ui.MainViewModel
import com.photo_editor.my_photo_editor.ui.PhotosFragment
import com.photo_editor.my_photo_editor.ui.ProfileFragment
import com.photo_editor.my_photo_editor.ui.StudioFragment
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.AppUpdateManager

class GalleryActivity : BaseActivity() {

    override fun getScreenTag(): String = "Gallery"

    private lateinit var binding: ActivityGalleryBinding
    private val viewModel: MainViewModel by viewModels()

    private val photosFragment = PhotosFragment()
    private val foldersFragment = FoldersFragment()
    private val studioFragment = StudioFragment()
    private val profileFragment = ProfileFragment()
    private var activeFragment: Fragment = photosFragment

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true) ||
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (granted) {
            viewModel.loadMedia()
        } else {
            Toast.makeText(this, "Permissions required to show photos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackPressHandler()
        setupNavigation()
        setupFragments()
        checkPermissions()
        AppUpdateManager.checkForUpdate(this, getScreenTag())
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AnalyticsManager.logEvent("Gallery_onBackPressed")
                if (activeFragment != photosFragment) {
                    binding.bottomNav.selectedItemId = R.id.nav_library
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, profileFragment, "profile").hide(profileFragment)
            add(R.id.fragment_container, studioFragment, "studio").hide(studioFragment)
            add(R.id.fragment_container, foldersFragment, "folders").hide(foldersFragment)
            add(R.id.fragment_container, photosFragment, "photos")
        }.commit()
        
        binding.headerTitle.text = getString(R.string.nav_library)
    }

    private fun setupNavigation() {
        binding.btnBackHeader.isVisible = false
        binding.btnSettings.isVisible = false
        
        binding.bottomNav.selectedItemId = R.id.nav_library
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> {
                    AnalyticsManager.logEvent("Gallery_nav_library")
                    showFragment(photosFragment, getString(R.string.nav_library))
                    true
                }
                R.id.nav_folders -> {
                    AnalyticsManager.logEvent("Gallery_nav_folders")
                    showFragment(foldersFragment, getString(R.string.nav_folders))
                    true
                }
                R.id.nav_studio -> {
                    AnalyticsManager.logEvent("Gallery_nav_studio")
                    showFragment(studioFragment, "Studio")
                    true
                }
                R.id.nav_profile -> {
                    AnalyticsManager.logEvent("Gallery_nav_profile")
                    showFragment(profileFragment, "Profile")
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment, title: String) {
        if (activeFragment == fragment) return
        
        binding.headerTitle.text = title
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(fragment)
            .commit()
        activeFragment = fragment
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) {
            if (viewModel.allMedia.value.isEmpty()) {
                viewModel.loadMedia()
            }
        }
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        if (hasMediaPermission()) {
            viewModel.loadMedia()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            requestPermissionLauncher.launch(permissions)
        }
    }



    private fun showExitDialog() {
        AnalyticsManager.logEvent("Gallery_showExitDialog")
        val dialogView = layoutInflater.inflate(R.layout.dialog_exit_app, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            AnalyticsManager.logEvent("Gallery_exitDialog_btnCancel")
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnExit).setOnClickListener {
            AnalyticsManager.logEvent("Gallery_exitDialog_btnExit")
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }
}
