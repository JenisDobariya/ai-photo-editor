package com.photo_editor.my_photo_editor

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.google.firebase.analytics.FirebaseAnalytics

class PhotoEditorApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
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
            }
            .crossfade(true)
            .build()
    }

    companion object {
        var instanceOfApplication: PhotoEditorApplication? = null
        var pFirebaseAnalytics: FirebaseAnalytics? = null

    }
}
