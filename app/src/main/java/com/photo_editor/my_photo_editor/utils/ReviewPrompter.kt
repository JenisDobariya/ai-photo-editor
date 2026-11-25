package com.photo_editor.my_photo_editor.utils

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Google Play rating at a good moment, using Play's in-app review sheet.
 *
 * Tool screens call [recordSuccess] when the user finishes something (a saved cutout,
 * a collage, freed-up space...). Many of those screens close right after saving, so the
 * sheet itself is shown later by [maybeAsk] from the main gallery screen, once the user
 * is back there after a success.
 *
 * We only ask when the user has had several successes, has had the app for a few days,
 * and hasn't been asked recently. Play also applies its own quota and may silently show
 * nothing, which is expected. The sheet only appears for installs from Google Play.
 */
object ReviewPrompter {

    private const val TAG = "PhotoGallery----"
    private const val PREFS = "review_prompt_prefs"
    private const val KEY_SUCCESS_COUNT = "success_count"
    private const val KEY_PENDING = "pending_after_success"
    private const val KEY_LAST_ASKED = "last_asked_at"
    private const val KEY_ASK_COUNT = "ask_count"

    private const val MIN_SUCCESSES = 3
    private const val MIN_INSTALL_AGE_MS = 2L * 24 * 60 * 60 * 1000      // 2 days
    private const val MIN_GAP_BETWEEN_ASKS_MS = 90L * 24 * 60 * 60 * 1000 // 90 days
    private const val MAX_ASKS = 3

    private var requestInFlight = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call when the user has just completed a task successfully. */
    fun recordSuccess(context: Context, source: String) {
        try {
            val p = prefs(context)
            val count = p.getInt(KEY_SUCCESS_COUNT, 0) + 1
            p.edit().putInt(KEY_SUCCESS_COUNT, count).putBoolean(KEY_PENDING, true).apply()
            Log.e(TAG, "Review_success_$source: count=$count")
        } catch (e: Exception) {
            Log.e(TAG, "Review_recordSuccess_exception: ${e.message}")
        }
    }

    /** Call from the main screen's onResume. Shows the Play review sheet if the time is right. */
    fun maybeAsk(activity: AppCompatActivity) {
        if (requestInFlight || !isEligible(activity)) return
        requestInFlight = true

        val p = prefs(activity)
        // Consume the trigger now so a failed or skipped request doesn't retry on every resume.
        p.edit().putBoolean(KEY_PENDING, false).apply()

        try {
            val manager = ReviewManagerFactory.create(activity.applicationContext)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful) {
                    requestInFlight = false
                    Log.e(TAG, "Review_request_failed: ${request.exception?.message}")
                    return@addOnCompleteListener
                }
                if (!activity.isUsable()) {
                    requestInFlight = false
                    return@addOnCompleteListener
                }
                p.edit()
                    .putLong(KEY_LAST_ASKED, System.currentTimeMillis())
                    .putInt(KEY_ASK_COUNT, p.getInt(KEY_ASK_COUNT, 0) + 1)
                    .apply()
                AnalyticsManager.logEvent("Review_flow_launch")
                manager.launchReviewFlow(activity, request.result).addOnCompleteListener {
                    // Play doesn't tell us whether the sheet was shown or what the user chose.
                    requestInFlight = false
                    AnalyticsManager.logEvent("Review_flow_complete")
                }
            }
        } catch (e: Exception) {
            requestInFlight = false
            Log.e(TAG, "Review_maybeAsk_exception: ${e.message}")
        }
    }

    private fun isEligible(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_PENDING, false)) return false
        if (p.getInt(KEY_SUCCESS_COUNT, 0) < MIN_SUCCESSES) return false
        if (p.getInt(KEY_ASK_COUNT, 0) >= MAX_ASKS) return false

        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST_ASKED, 0L) < MIN_GAP_BETWEEN_ASKS_MS) return false

        // Debug builds skip the install-age wait so the flow can be tested right away.
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return true

        val installedAt = try {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            now
        }
        return now - installedAt >= MIN_INSTALL_AGE_MS
    }

    private fun Activity.isUsable(): Boolean {
        if (isFinishing || isDestroyed) return false
        return (this as? AppCompatActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
    }
}
