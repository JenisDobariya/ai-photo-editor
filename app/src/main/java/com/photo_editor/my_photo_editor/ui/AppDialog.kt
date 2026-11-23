package com.photo_editor.my_photo_editor.ui

import android.app.Dialog
import com.photo_editor.my_photo_editor.utils.appColor
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.DialogAppBinding
import com.photo_editor.my_photo_editor.databinding.ItemAppDialogItemBinding
import com.photo_editor.my_photo_editor.databinding.ItemAppDialogRowBinding
import kotlin.math.min

/**
 * The app's one dialog: same card, sizes, corners, fonts and button layout everywhere.
 *
 * Built like MaterialAlertDialogBuilder (same method names) so screens use it the same way:
 * ```
 * AppDialog.Builder(context)
 *     .setIcon(R.drawable.ic_g_check_circle, R.color.lumina_teal)
 *     .setTitle("Video saved")
 *     .addRow("Location", "Movies/PhotoEditor")
 *     .setPositiveButton("Open") { _, _ -> … }
 *     .setNegativeButton("Done", null)
 *     .show()
 * ```
 * Extras over a plain alert: a tinted header icon, label/value detail rows, and a
 * destructive (red) main button. A tapped button runs its listener, then closes the dialog;
 * screens that must keep it open can replace the click listener via [getButton] after show().
 */
class AppDialog private constructor(
    context: Context,
    private val spec: Builder
) : Dialog(context, R.style.ThemeOverlay_App_Dialog) {

    private var buttons: Map<Int, MaterialButton> = emptyMap()

    /** [which] = DialogInterface.BUTTON_POSITIVE / NEGATIVE / NEUTRAL. Available after show(). */
    fun getButton(which: Int): Button? = buttons[which]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inflater = LayoutInflater.from(context)
        val b = DialogAppBinding.inflate(inflater)
        setContentView(b.root)

        // Header
        b.dialogTitle.text = spec.title
        b.dialogTitle.isVisible = !spec.title.isNullOrEmpty()
        val icon = spec.icon
        if (icon != null) {
            val tint = context.appColor(spec.iconTint)
            b.dialogIconWrap.isVisible = true
            b.dialogIconWrap.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(tint, 0x2E))
            }
            b.dialogIcon.setImageResource(icon)
            b.dialogIcon.setColorFilter(tint)
        }
        b.dialogHeader.isVisible = b.dialogTitle.isVisible || icon != null

        // Body
        b.dialogMessage.text = spec.message
        b.dialogMessage.isVisible = !spec.message.isNullOrEmpty()
        if (!b.dialogHeader.isVisible) (b.dialogMessage.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0

        if (spec.rows.isNotEmpty()) {
            b.dialogRows.isVisible = true
            spec.rows.forEach { (label, value) ->
                val row = ItemAppDialogRowBinding.inflate(inflater, b.dialogRows, false)
                row.rowLabel.text = label
                row.rowValue.text = value
                b.dialogRows.addView(row.root)
            }
        }

        spec.items?.let { items ->
            b.dialogItems.isVisible = true
            items.forEachIndexed { index, text ->
                val item = ItemAppDialogItemBinding.inflate(inflater, b.dialogItems, false)
                item.itemText.text = text
                item.root.setOnClickListener {
                    spec.itemsListener?.onClick(this, index)
                    dismiss()
                }
                b.dialogItems.addView(item.root)
            }
        }

        spec.view?.let { custom ->
            (custom.parent as? ViewGroup)?.removeView(custom)
            b.dialogCustom.isVisible = true
            b.dialogCustom.addView(custom)
        }
        if (!b.dialogMessage.isVisible && spec.rows.isEmpty() && spec.items == null && spec.view == null) {
            b.dialogBody.isVisible = false
        }

        // Buttons
        val map = HashMap<Int, MaterialButton>()
        fun bindButton(button: MaterialButton, which: Int, action: Builder.Action?) {
            if (action == null) return
            button.isVisible = true
            button.text = action.text
            button.setOnClickListener {
                action.listener?.onClick(this, which)
                dismiss()
            }
            map[which] = button
        }
        bindButton(b.dialogPositive, DialogInterface.BUTTON_POSITIVE, spec.positive)
        bindButton(b.dialogNegative, DialogInterface.BUTTON_NEGATIVE, spec.negative)
        bindButton(b.dialogNeutral, DialogInterface.BUTTON_NEUTRAL, spec.neutral)
        if (spec.destructive) {
            b.dialogPositive.backgroundTintList =
                ContextCompat.getColorStateList(context, R.color.lumina_danger)
        }
        buttons = map
        b.dialogButtons.isVisible = map.isNotEmpty()
        if (map.isEmpty()) b.root.setPadding(b.root.paddingLeft, b.root.paddingTop, b.root.paddingRight, b.root.paddingTop)

        // Window: transparent so only the rounded card shows; same width on every screen.
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = context.resources.displayMetrics
            val width = min(metrics.widthPixels - (40 * metrics.density).toInt(), (400 * metrics.density).toInt())
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            // Never taller than 80% of the screen: long lists scroll inside the body instead.
            val maxHeight = (metrics.heightPixels * 0.8f).toInt()
            b.root.post {
                if (b.root.height > maxHeight) w.setLayout(width, maxHeight)
            }
            if (spec.view != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    class Builder(private val context: Context) {

        class Action(val text: CharSequence, val listener: DialogInterface.OnClickListener?)

        internal var title: CharSequence? = null
        internal var message: CharSequence? = null
        @DrawableRes internal var icon: Int? = null
        @ColorRes internal var iconTint: Int = R.color.lumina_accent
        internal var view: View? = null
        internal var items: Array<out CharSequence>? = null
        internal var itemsListener: DialogInterface.OnClickListener? = null
        internal val rows = ArrayList<Pair<CharSequence, CharSequence>>()
        internal var positive: Action? = null
        internal var negative: Action? = null
        internal var neutral: Action? = null
        internal var destructive = false
        private var cancelable = true
        private var onCancel: DialogInterface.OnCancelListener? = null
        private var onDismiss: DialogInterface.OnDismissListener? = null

        fun setTitle(title: CharSequence?) = apply { this.title = title }
        fun setTitle(@StringRes title: Int) = apply { this.title = context.getText(title) }
        fun setMessage(message: CharSequence?) = apply { this.message = message }
        fun setMessage(@StringRes message: Int) = apply { this.message = context.getText(message) }

        /** Small tinted icon disc before the title. */
        fun setIcon(@DrawableRes icon: Int, @ColorRes tint: Int = R.color.lumina_accent) = apply {
            this.icon = icon
            this.iconTint = tint
        }

        /** A label / value line in the details block (e.g. "Location" → "Movies/PhotoEditor"). */
        fun addRow(label: CharSequence, value: CharSequence) = apply { rows += label to value }

        fun setView(view: View) = apply { this.view = view }

        fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener?) = apply {
            this.items = items
            this.itemsListener = listener
        }

        fun setPositiveButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { positive = Action(text, listener) }

        fun setNegativeButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { negative = Action(text, listener) }

        fun setNeutralButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { neutral = Action(text, listener) }

        /** Paints the main button red (delete, empty bin…). */
        fun setDestructive(destructive: Boolean = true) = apply { this.destructive = destructive }

        fun setCancelable(cancelable: Boolean) = apply { this.cancelable = cancelable }
        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) = apply { onCancel = listener }
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) = apply { onDismiss = listener }

        fun create(): AppDialog = AppDialog(context, this).also { d ->
            d.setCancelable(cancelable)
            d.setCanceledOnTouchOutside(cancelable)
            onCancel?.let { d.setOnCancelListener(it) }
            onDismiss?.let { d.setOnDismissListener(it) }
        }

        fun show(): AppDialog = create().also { it.show() }
    }
}
