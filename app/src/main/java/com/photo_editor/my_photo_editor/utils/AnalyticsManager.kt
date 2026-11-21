package com.photo_editor.my_photo_editor.utils

import android.os.Bundle
import android.util.Log
import com.photo_editor.my_photo_editor.PhotoEditorApplication

object AnalyticsManager {

    private const val TAG = "PhotoGallery----"

    fun logEvent(eventName: String, bundle: Bundle = Bundle()) {
        Log.e(TAG, eventName)
        try {
            PhotoEditorApplication.pFirebaseAnalytics?.logEvent(eventName, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging event $eventName: ${e.message}")
        }
    }
}
