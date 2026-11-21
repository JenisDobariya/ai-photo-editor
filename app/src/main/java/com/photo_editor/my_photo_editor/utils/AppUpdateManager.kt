package com.photo_editor.my_photo_editor.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.photo_editor.my_photo_editor.R

object AppUpdateManager {

    private const val TAG = "PhotoGallery----"

    private const val KEY_LATEST_VERSION_CODE = "latest_version_code"
    private const val KEY_IS_FORCE_UPDATE = "is_force_update"
    private const val KEY_UPDATE_TITLE = "update_title"
    private const val KEY_UPDATE_MESSAGE = "update_message"

    fun checkForUpdate(activity: Activity, screenTag: String = "Gallery") {
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            val configSettings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(3600)
                .build()
            remoteConfig.setConfigSettingsAsync(configSettings)

            val defaults = mapOf(
                KEY_LATEST_VERSION_CODE to getAppVersionCode(activity).toLong(),
                KEY_IS_FORCE_UPDATE to false,
                KEY_UPDATE_TITLE to "New Version Available",
                KEY_UPDATE_MESSAGE to "A new version of Photo Editor is available with new features and improvements. Please update now."
            )
            remoteConfig.setDefaultsAsync(defaults)

            remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val latestVersionCode = remoteConfig.getLong(KEY_LATEST_VERSION_CODE)
                    val isForceUpdate = remoteConfig.getBoolean(KEY_IS_FORCE_UPDATE)
                    val updateTitle = remoteConfig.getString(KEY_UPDATE_TITLE)
                    val updateMessage = remoteConfig.getString(KEY_UPDATE_MESSAGE)

                    val currentVersionCode = getAppVersionCode(activity)
                    Log.e(TAG, "${screenTag}_checkAppUpdate: current=$currentVersionCode, latest=$latestVersionCode")

                    if (latestVersionCode > currentVersionCode) {
                        showUpdateDialog(
                            activity = activity,
                            isForceUpdate = isForceUpdate,
                            title = updateTitle,
                            message = updateMessage,
                            screenTag = screenTag
                        )
                    }
                } else {
                    Log.e(TAG, "${screenTag}_checkAppUpdate_failed: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "${screenTag}_checkAppUpdate_exception: ${e.message}")
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        isForceUpdate: Boolean,
        title: String,
        message: String,
        screenTag: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        AnalyticsManager.logEvent("${screenTag}_updateDialog_show")

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_app_update, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(dialogView)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvUpdateMessage)
        val btnLater = dialogView.findViewById<Button>(R.id.btnLater)
        val btnUpdateNow = dialogView.findViewById<Button>(R.id.btnUpdateNow)

        tvTitle.text = title.ifBlank { "New Version Available" }
        tvMessage.text = message.ifBlank { "A new version of Photo Editor is available with new features and improvements. Please update now." }

        if (isForceUpdate) {
            btnLater.visibility = View.GONE
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        } else {
            btnLater.visibility = View.VISIBLE
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
        }

        btnLater.setOnClickListener {
            AnalyticsManager.logEvent("${screenTag}_updateDialog_btnLaterClick")
            dialog.dismiss()
        }

        btnUpdateNow.setOnClickListener {
            AnalyticsManager.logEvent("${screenTag}_updateDialog_btnUpdateClick")
            openPlayStore(activity)
            if (isForceUpdate) {
                activity.finish()
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun openPlayStore(activity: Activity) {
        val packageName = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (_: Exception) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    private fun getAppVersionCode(activity: Activity): Int {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }
}
