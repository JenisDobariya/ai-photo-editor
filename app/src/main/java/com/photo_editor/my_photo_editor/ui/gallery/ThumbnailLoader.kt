package com.photo_editor.my_photo_editor.ui.gallery

import android.net.Uri
import android.os.Build
import android.widget.ImageView
import coil.load
import coil.request.videoFrameMicros
import com.photo_editor.my_photo_editor.R

/**
 * Loads a grid/album thumbnail. Video frames are taken at [frameMicros]; if that fails
 * (e.g. clips shorter than the requested time or odd encodings) it retries with the first
 * frame, and finally shows a neutral placeholder instead of an empty tile.
 */
fun ImageView.loadThumbnail(uri: Uri, frameMicros: Long = 1_000_000L, crossfadeMs: Int = 150) {
    // Android 10+: videos use the system thumbnail cache (fast, and avoids decoding frames
    // ourselves); only if that fails do we fall back to grabbing a frame.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && SystemThumbnailFetcher.isMediaStoreVideo(uri)) {
        load(SystemThumbnail(uri)) {
            crossfade(crossfadeMs)
            listener(onError = { _, _ -> loadVideoFrame(uri, frameMicros, crossfadeMs) })
        }
        return
    }
    loadVideoFrame(uri, frameMicros, crossfadeMs)
}

private fun ImageView.loadVideoFrame(uri: Uri, frameMicros: Long, crossfadeMs: Int) {
    load(uri) {
        crossfade(crossfadeMs)
        videoFrameMicros(frameMicros)
        listener(onError = { _, _ ->
            load(uri) {
                videoFrameMicros(0)
                error(R.drawable.thumb_error)
            }
        })
    }
}
