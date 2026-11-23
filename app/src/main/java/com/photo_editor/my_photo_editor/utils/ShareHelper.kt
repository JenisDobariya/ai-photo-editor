package com.photo_editor.my_photo_editor.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Sharing helpers for files the app creates itself (temporary copies, exports). */
object ShareHelper {

    /** Folder (inside the cache) that the FileProvider exposes. Cleared by [freshShareDir]. */
    fun shareDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

    /** Empties the share folder before preparing a new share, so old copies don't pile up. */
    fun freshShareDir(context: Context): File {
        val dir = shareDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        return dir
    }

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Builds a share-sheet intent for one or more content/file-provider uris. */
    fun shareIntent(uris: List<Uri>, mimeType: String, title: String = "Share"): Intent? {
        if (uris.isEmpty()) return null
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mimeType
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    /** Opens a saved file in another app (PDF viewer, video player...). */
    fun viewIntent(uri: Uri, mimeType: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Open with"
        )
}
