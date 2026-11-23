package com.photo_editor.my_photo_editor.ui.gallery

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

/** A MediaStore item whose thumbnail should come from the system thumbnail cache. */
data class SystemThumbnail(val uri: Uri)

/**
 * Loads [SystemThumbnail]s with ContentResolver.loadThumbnail (Android 10+). The system
 * keeps these cached, so the grid no longer decodes video frames itself — that used
 * MediaMetadataRetriever, which on some devices stalls long enough to crash the app
 * ("MediaMetadataRetriever.finalize() timed out") while scrolling many videos.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemThumbnailFetcher(
    private val data: SystemThumbnail,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { DEFAULT_PX }.coerceIn(MIN_PX, MAX_PX)
        val height = options.size.height.pxOrElse { DEFAULT_PX }.coerceIn(MIN_PX, MAX_PX)
        val signal = CancellationSignal()
        coroutineContext[Job]?.invokeOnCompletion { signal.cancel() }
        val bitmap = options.context.contentResolver.loadThumbnail(data.uri, Size(width, height), signal)
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<SystemThumbnail> {
        override fun create(data: SystemThumbnail, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SystemThumbnailFetcher(data, options) else null
    }

    class Keyer : coil.key.Keyer<SystemThumbnail> {
        override fun key(data: SystemThumbnail, options: Options): String = "system-thumb:${data.uri}"
    }

    companion object {
        private const val DEFAULT_PX = 384
        private const val MIN_PX = 96
        private const val MAX_PX = 1024

        /** True for MediaStore video uris (content://media/…/video/…). */
        fun isMediaStoreVideo(uri: Uri): Boolean =
            uri.scheme == "content" && uri.authority == "media" && uri.pathSegments.contains("video")
    }
}
