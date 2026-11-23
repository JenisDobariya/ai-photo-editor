package com.photo_editor.my_photo_editor

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.photo_editor.my_photo_editor.ui.gallery.SystemThumbnailFetcher
import com.google.firebase.analytics.FirebaseAnalytics

class PhotoEditorApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Light / Dark / System (Profile → Appearance)
        com.photo_editor.my_photo_editor.utils.ThemePreferences.applyNightMode(this)
        instanceOfApplication = this
        pFirebaseAnalytics = try {
            FirebaseAnalytics.getInstance(this)
        } catch (e: Exception) {
            Log.e("PhotoGallery----", "FirebaseAnalytics init failed: ${e.message}")
            null
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
                // Video thumbnails from the system cache (see SystemThumbnailFetcher).
                add(SystemThumbnailFetcher.Factory())
                add(SystemThumbnailFetcher.Keyer())
            }
            .crossfade(true)
            .build()
    }

    companion object {
        var instanceOfApplication: PhotoEditorApplication? = null
        var pFirebaseAnalytics: FirebaseAnalytics? = null

    }
}
