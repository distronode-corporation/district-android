package com.distronode.districtai.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the authorize URL in a Chrome Custom Tab.
 *
 * ⛔ A SYSTEM BROWSER, NEVER A WEBVIEW. Two independent reasons, either sufficient:
 *   1. Google's OAuth policy refuses embedded user-agents, so Google SSO — one of the three
 *      login methods the server's authorize page offers — simply will not work in a WebView.
 *   2. A WebView puts this app between the user and their identity provider's password field.
 *      A Custom Tab does not: it is the real browser, with the real URL bar and the user's
 *      existing session cookies, and this app cannot read it.
 *
 * ⚠️ FALLS BACK TO A PLAIN VIEW INTENT. A Custom Tab needs a browser that supports the
 * protocol; on a device with none (some AOSP builds, and Waydroid's vanilla LineageOS image),
 * `CustomTabsIntent.launchUrl` throws ActivityNotFoundException. The flow still works through
 * an ordinary browser, so the fallback is a real path rather than defensive noise — and if
 * there is no browser at all, that is reported rather than crashing.
 *
 * ⛔ SINCE APP LINKS (TASK 27) THIS APP IS A VERIFIED HANDLER FOR SOME OF THE URLs IT ASKS A
 * BROWSER TO OPEN, AND AN IMPLICIT `ACTION_VIEW` FOR ONE OF THOSE COMES STRAIGHT BACK HERE. The
 * OS does not exempt the app that started the intent: once `/.well-known/assetlinks.json` names
 * the installed signing certificate, `https://www.distronode.com/dashboard/district/...` resolves
 * to `MainActivity` rather than to a browser. The marketplace hand-off is exactly such a URL, and
 * it exists *because* buying a number must stay on the web under Play's Payments policy — so
 * bouncing it back into the app defeats the one thing it is for. Every intent built here is
 * therefore pinned to a browser package when one can be named ([browserPackage]); an EXPLICIT
 * intent is not subject to App Links resolution at all, which is what makes the loop impossible
 * rather than unlikely.
 */
object CustomTabsLauncher {

    /**
     * Open [url] in a browser, preferring a Custom Tab.
     *
     * ⚠️ UNCHANGED BEHAVIOUR WHEN NO BROWSER CAN BE NAMED: the intent goes out implicit, exactly as
     * it did before App Links existed. That is deliberate — sign-in is the flow this launcher was
     * written for, its URL (`/auth/native`) is NOT inside the claimed path prefix, and a stricter
     * gate here could only ever break a login that works today. Callers whose URL the app DOES
     * claim must use [launchExternally] instead.
     */
    fun launch(context: Context, url: String): LaunchResult =
        launch(context, url, browserPackage(context))

    /**
     * Open a URL **this app itself claims**, in a browser, or not at all.
     *
     * ⛔ REFUSES RATHER THAN RISK THE INTENT RESOLVING BACK INTO THIS APP, and "refuses" is the
     * safe half of a choice with no safe default. Without a browser package the intent is implicit,
     * the OS hands it to the verified handler — us — and the App Links path resolves it to the same
     * hand-off that started it. That is not a wasted tap: it is an unbounded relaunch loop with no
     * user input in it, on a device the user cannot easily escape from. Reporting [LaunchResult.NoBrowser]
     * shows the caller's "no browser" message instead, which is both true and dismissable.
     */
    fun launchExternally(context: Context, url: String): LaunchResult {
        val browser = browserPackage(context) ?: return LaunchResult.NoBrowser(
            customTabsReason = NO_BROWSER_PACKAGE,
            browserReason = NO_BROWSER_PACKAGE,
        )
        return launch(context, url, browser)
    }

    /**
     * The package of the activity that would handle an ordinary web link.
     *
     * ⛔ THE PROBE URI HAS NO HOST ON PURPOSE, AND THAT IS WHAT KEEPS THIS APP OUT OF ITS OWN
     * ANSWER. `http://` matches a browser's catch-all filter and matches nothing this app declares:
     * the App Links filter is `https` only and is bound to two named hosts and a path prefix. So the
     * probe can resolve to a browser, to the system's resolver activity when several browsers exist
     * with no default, or to nothing — never to `MainActivity`. This is the same idiom androidx's
     * own `CustomTabsClient.getPackageName` uses to find the default browser.
     *
     * ⚠️ THE RESOLVER ACTIVITY IS FILTERED OUT RATHER THAN PINNED. `android` is the platform's
     * disambiguation dialog, not a browser; setting it as an intent's package makes the launch fail
     * with ActivityNotFoundException. Falling through to null there gives the user the chooser
     * through an implicit intent, which is the correct outcome for [launch] — and makes
     * [launchExternally] refuse, which is the correct outcome for a URL we claim.
     *
     * ⚠️ REQUIRES THE `<queries>` ELEMENT IN AndroidManifest.xml on API 30+. Without it package
     * visibility filtering makes this return null on every modern device, which would silently turn
     * every [launchExternally] call into a "no browser" message.
     */
    internal fun browserPackage(context: Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
        val handler = context.packageManager.resolveActivity(probe, 0) ?: return null
        val packageName = handler.activityInfo?.packageName ?: return null
        return packageName.takeIf { it != ANDROID_RESOLVER_PACKAGE }
    }

    private fun launch(context: Context, url: String, browserPackage: String?): LaunchResult {
        val uri = Uri.parse(url)

        return try {
            val customTabs = CustomTabsIntent.Builder()
                // The login page is a one-shot errand; showing the title avoids a blank bar
                // while the server redirects through the SSO provider.
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            // ⛔ SEE [newTaskIntent]: THIS PATH NEEDS THE FLAG TOO, which is easy to miss because
            // `CustomTabsIntent` hides its Intent behind a builder. androidx does NOT add
            // FLAG_ACTIVITY_NEW_TASK for you — `launchUrl` is a bare `context.startActivity`.
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // ⛔ EXPLICIT WHENEVER A BROWSER CAN BE NAMED — see the ⛔ in this object's header. A
            // browser that does not implement the Custom Tabs service still handles this as an
            // ordinary page load, which is the documented degradation, so pinning costs nothing.
            // ⚠️ `setPackage(null)` is legal and means "leave it implicit", which is the
            // pre-App-Links behaviour [launch] deliberately keeps.
            customTabs.intent.setPackage(browserPackage)
            customTabs.launchUrl(context, uri)
            LaunchResult.OpenedCustomTab
        } catch (customTabsMissing: ActivityNotFoundException) {
            try {
                context.startActivity(newTaskIntent(uri).setPackage(browserPackage))
                LaunchResult.OpenedBrowser
            } catch (noBrowserAtAll: ActivityNotFoundException) {
                // ⚠️ BOTH causes are carried, not discarded. "Sign-in cannot start" with no
                // diagnostic is unactionable in a bug report, and this is the one branch a user
                // can neither retry past nor understand. The messages name which stage failed.
                LaunchResult.NoBrowser(
                    customTabsReason = customTabsMissing.message,
                    browserReason = noBrowserAtAll.message,
                )
            }
        }
    }

    /**
     * An ACTION_VIEW intent that is legal to start from a NON-ACTIVITY context.
     *
     * ⛔ THE MISSING FLAG WAS A CRASH, NOT A LINT NIT, AND THE COMMENT AT THE CALL SITE CLAIMED
     * IT WAS ALREADY HERE. `startActivity` on an application context without
     * FLAG_ACTIVITY_NEW_TASK throws `AndroidRuntimeException` — a RuntimeException, and NOT an
     * [ActivityNotFoundException], so neither catch below saw it and it killed the process.
     *
     * ⛔ IT ONLY FIRED ON ONE OF THE TWO CALL SITES, WHICH IS WHY IT SURVIVED TESTING.
     * `MainActivity` passes `this` — an Activity, which already belongs to a task, so no flag is
     * needed and sign-in worked everywhere. `DistrictNavHost` deliberately passes the
     * APPLICATION context (correctly: it is handed to a callback that can outlive a rotation),
     * and that is the account-deletion hand-off. So the crash was reachable only from the
     * Settings screen, on the one flow Play requires to exist.
     */
    private fun newTaskIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    sealed interface LaunchResult {
        data object OpenedCustomTab : LaunchResult

        /** No Custom Tabs provider, but an ordinary browser handled it. The flow still works. */
        data object OpenedBrowser : LaunchResult

        /** No browser on the device at all. Login cannot proceed; tell the user rather than hang. */
        data class NoBrowser(
            val customTabsReason: String?,
            val browserReason: String?,
        ) : LaunchResult
    }

    /**
     * ⚠️ THE PLATFORM'S DISAMBIGUATION ACTIVITY, WHICH IS NOT A BROWSER. `resolveActivity` returns
     * it when several browsers are installed and none is the default.
     */
    private const val ANDROID_RESOLVER_PACKAGE = "android"

    /**
     * ⚠️ Carried in place of an exception message because there is no exception: nothing was
     * attempted. The two reasons are identical for the same reason.
     */
    private const val NO_BROWSER_PACKAGE = "no browser package could be resolved"
}
