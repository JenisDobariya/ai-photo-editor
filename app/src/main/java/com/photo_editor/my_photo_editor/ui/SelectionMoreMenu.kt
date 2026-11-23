package com.photo_editor.my_photo_editor.ui

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.photo_editor.my_photo_editor.CompressActivity
import com.photo_editor.my_photo_editor.PhotosToPdfActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.repository.MediaOrganizer
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.launch

/**
 * The "More" action of the multi-select bar (and the viewer): organise and convert the
 * selected items. Shared by every grid screen; all work happens in [MediaSelectionViewModel].
 */
object SelectionMoreMenu {

    private const val COPY = 1
    private const val MOVE = 2
    private const val SHARE_NO_LOCATION = 3
    private const val PDF = 4
    private const val COMPRESS = 5

    fun show(activity: AppCompatActivity, anchor: View, items: List<MediaFile>, viewModel: MediaSelectionViewModel) {
        if (items.isEmpty()) return
        val photos = items.filter { it.type == MediaType.IMAGE }
        val popup = PopupMenu(activity, anchor, Gravity.END)
        popup.menu.add(0, COPY, 0, "Copy to album…")
        if (viewModel.canEditInPlace) popup.menu.add(0, MOVE, 1, "Move to album…")
        if (photos.isNotEmpty()) {
            popup.menu.add(0, SHARE_NO_LOCATION, 2, "Share without location")
            popup.menu.add(0, PDF, 3, "Create PDF")
            popup.menu.add(0, COMPRESS, 4, "Compress & resize")
        }
        popup.setOnMenuItemClickListener { item ->
            AnalyticsManager.logEvent("Selection_more_${item.itemId}")
            when (item.itemId) {
                COPY -> pickAlbum(activity, viewModel, "Copy to album") { viewModel.copyToAlbum(items, it) }
                MOVE -> pickAlbum(activity, viewModel, "Move to album") { viewModel.moveToAlbum(items, it) }
                SHARE_NO_LOCATION -> viewModel.shareWithoutLocation(photos)
                PDF -> {
                    activity.startActivity(PhotosToPdfActivity.intent(activity, photos.map { it.uri }))
                    viewModel.clear()
                }
                COMPRESS -> {
                    activity.startActivity(CompressActivity.intent(activity, photos.map { it.uri }))
                    viewModel.clear()
                }
            }
            true
        }
        popup.show()
    }

    /** Album chooser: "New album" first, then existing albums (newest activity first). */
    fun pickAlbum(
        activity: AppCompatActivity,
        viewModel: MediaSelectionViewModel,
        title: String,
        onPicked: (MediaOrganizer.AlbumTarget) -> Unit
    ) {
        activity.lifecycleScope.launch {
            val targets = viewModel.albumTargets()
            val labels = arrayOf("+  New album") + targets.map { it.name }.toTypedArray()
            AppDialog.Builder(activity)
                .setIcon(R.drawable.ic_g_folder, R.color.lumina_accent)
                .setTitle(title)
                .setItems(labels) { _, which ->
                    if (which == 0) askNewAlbumName(activity, viewModel, onPicked)
                    else onPicked(targets[which - 1])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun askNewAlbumName(
        activity: AppCompatActivity,
        viewModel: MediaSelectionViewModel,
        onPicked: (MediaOrganizer.AlbumTarget) -> Unit
    ) {
        val input = EditText(activity).apply {
            hint = "Album name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setTextColor(ContextCompat.getColor(activity, R.color.lumina_text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.lumina_text_tertiary))
        }
        val pad = (22 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AppDialog.Builder(activity)
            .setTitle("New album")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Please enter a name", Toast.LENGTH_SHORT).show()
                } else {
                    onPicked(viewModel.newAlbumTarget(name))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    /** Rename dialog for one file (Android 11+). */
    fun askRename(activity: AppCompatActivity, viewModel: MediaSelectionViewModel, file: MediaFile) {
        val current = file.name.substringBeforeLast('.')
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(ContextCompat.getColor(activity, R.color.lumina_text_primary))
        }
        val pad = (22 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AppDialog.Builder(activity)
            .setTitle("Rename")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty() && name != current) viewModel.rename(file.uri, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
