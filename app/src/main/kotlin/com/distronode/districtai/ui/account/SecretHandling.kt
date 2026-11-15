package com.distronode.districtai.ui.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * ⛔ WHILE [secure] IS TRUE THE WINDOW IS `FLAG_SECURE`: no screenshot, no screen recording, and the
 * app switcher's thumbnail is blank. The Android counterpart of the iOS app's cover over the secret
 * steps while the app is not active. Cleared again as soon as the step no longer holds a secret, so
 * the rest of the app stays capturable.
 */
@Composable
internal fun SecureWindow(secure: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, secure) {
        if (secure) activity?.setSecure(true)
        onDispose { if (secure) activity?.setSecure(false) }
    }
}

private fun Activity.setSecure(on: Boolean) {
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * Copies a secret to this device's clipboard, marked sensitive and for two minutes.
 *
 * ⛔ MARKED SENSITIVE SO THE SYSTEM'S COPY PREVIEW DOES NOT SHOW IT, and wiped after
 * [EXPIRY_MILLIS] if it is still what the clipboard holds, so it is not left there for whatever is
 * pasted next week. Nothing here logs, persists or shares it.
 */
internal object SensitiveClipboard {

    /** Two minutes, the iOS app's pasteboard expiry. */
    const val EXPIRY_MILLIS: Long = 120_000L

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out: the key is honoured from Android 13 and
     * ignored before, so it is set on every version rather than behind a version check.
     */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, text: String, handler: Handler = Handler(Looper.getMainLooper())) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        handler.postDelayed({ clearIfStill(clipboard, text) }, EXPIRY_MILLIS)
    }

    internal fun clearIfStill(clipboard: ClipboardManager, text: String) {
        val held = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (held != text) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
