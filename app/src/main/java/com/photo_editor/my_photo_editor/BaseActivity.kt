package com.photo_editor.my_photo_editor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.photo_editor.my_photo_editor.utils.AnalyticsManager

abstract class BaseActivity : AppCompatActivity() {

    abstract fun getScreenTag(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsManager.logEvent("${getScreenTag()}_onCreate")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Ensure status bar icons are light (white) on dark theme
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        view?.let { setupSystemBarInsets(it) }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        findViewById<View>(android.R.id.content)?.let { setupSystemBarInsets(it) }
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        view?.let { setupSystemBarInsets(it) }
    }

    protected open fun setupSystemBarInsets(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBars.top, displayCutout.top)
            val leftInset = maxOf(statusBars.left, displayCutout.left)
            val rightInset = maxOf(statusBars.right, displayCutout.right)

            v.updatePadding(
                left = leftInset,
                top = topInset,
                right = rightInset
            )
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    override fun onStart() {
        super.onStart()
        AnalyticsManager.logEvent("${getScreenTag()}_onStart")
    }

    override fun onResume() {
        super.onResume()
        AnalyticsManager.logEvent("${getScreenTag()}_onResume")
    }

    override fun onPause() {
        super.onPause()
        AnalyticsManager.logEvent("${getScreenTag()}_onPause")
    }

    override fun onStop() {
        super.onStop()
        AnalyticsManager.logEvent("${getScreenTag()}_onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        AnalyticsManager.logEvent("${getScreenTag()}_onDestroy")
    }

    @Deprecated("Deprecated in Java", ReplaceWith("super.onBackPressed()"))
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "ObsoleteSdkInt")
    override fun onBackPressed() {
        AnalyticsManager.logEvent("${getScreenTag()}_onBackPressed")
        super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val bundle = Bundle().apply {
            putInt("request_code", requestCode)
            putInt("result_code", resultCode)
        }
        AnalyticsManager.logEvent("${getScreenTag()}_onActivityResult", bundle)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        AnalyticsManager.logEvent("${getScreenTag()}_onSaveInstanceState")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        AnalyticsManager.logEvent("${getScreenTag()}_onRestoreInstanceState")
    }
}
