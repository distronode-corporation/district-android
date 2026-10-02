package com.distronode.districtai

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Crash and ANR reporting.
 *
 * ⛔ CRASHES AND ANRs ONLY, DELIBERATELY. No tracing, no profiling, no session replay, no
 * screenshots, no view hierarchy. Sentry bills by ingested volume, and an observability budget
 * that runs out mid-month fails quietly: the cap is usually the first anyone hears of it. A
 * crash reporter that only reports crashes is a fixed, tiny, predictable spend; every one of
 * those features is per-event volume for signal this app has not asked for. ⚠️ The one exception
 * is [reportNonFatal], for a failure that is otherwise invisible (see its doc); it is bounded per
 * process start and per sign-in, not per screen or per request.
 *
 * ⛔ AND `sendDefaultPii` IS OFF, WHICH IS NOT THE SDK DEFAULT BEHAVIOUR TO ASSUME EITHER WAY.
 * With it on, Sentry attaches the request's IP address and the device user, and on Android it
 * additionally relaxes what the SDK considers safe to send. This app's whole product is other
 * people's business conversations — call transcripts, SMS threads, contact records — under a
 * regional-residency promise that the service publishes as PUBLIC claims. A crash reporter is
 * not a place to widen what leaves the device, and "off" is the only setting that needs no
 * argument.
 *
 * ⚠️ THE DATA REGION IS A PROPERTY OF THE DSN, NOT A SETTING. The DSN's host (for an EU-region
 * organisation, `ingest.de.sentry.io`) is what routes events; there is no `region` option to
 * get wrong here. The place the region DOES have to be stated by hand is the Gradle plugin's
 * `url`, in app/build.gradle.kts; see the ⛔ there.
 *
 * ⚠️ `BuildConfig.SENTRY_DSN` IS EMPTY UNLESS THE BUILD WAS GIVEN ONE (`-PdistrictSentryDsn` or
 * `DISTRICT_SENTRY_DSN`; see app/build.gradle.kts), so a build from a plain checkout runs with
 * crash reporting off. [initialize] is what turns that empty value into "disabled".
 */
object DistrictSentry {

    /**
     * Arm the reporter. Safe to call exactly once, from [DistrictApplication.onCreate].
     *
     * ⚠️ A BLANK DSN IS A SUPPORTED STATE AND RETURNS WITHOUT INITIALISING, for the same reason
     * [DistrictFirebase][com.distronode.districtai.push.DistrictFirebase] tolerates an
     * unrecognised package: a build nobody configured should lose crash reporting, not fail to
     * start. It is also the default: a build made without a DSN carries an empty one.
     * `SentryAndroid.init` with an empty DSN logs a warning and disables the SDK anyway, so this
     * is a clearer statement of the same outcome rather than a behaviour change.
     *
     * @return whether `SentryAndroid.init` was called. ⛔ THE RETURN VALUE EXISTS SO THE GUARD CAN
     * BE ASSERTED WITHOUT READING `Sentry.isEnabled()`, AND THAT IS NOT A STYLE PREFERENCE.
     * Measured against sentry-android-core 8.53.0 under Robolectric:
     * `Sentry.isEnabled()` is **already true before any of this runs**. It stays true with
     * `io.sentry.auto-init` set to `false` (what the shipped manifest does), and it stayed true
     * even in an experiment that ALSO stripped both `SentryInitProvider` and
     * `SentryPerformanceProvider` from the merged manifest with `tools:node="remove"`. The shipped
     * manifest does NOT remove `SentryPerformanceProvider`; see the note there for why. So the
     * global is not a usable signal for "did we arm it": nothing we control moves it. A caller that wants to
     * know whether this method armed the reporter must read this value.
     *
     * ⚠️ This says nothing about a real device, where the provider startup path differs. Whether
     * the SDK comes up armed on a handset with a blank DSN is an open device-testing question,
     * alongside FCM delivery and Telecom audio routing.
     */
    fun initialize(context: Context, dsn: String, environment: String): Boolean {
        if (dsn.isBlank()) return false
        SentryAndroid.init(context) { options -> apply(options, dsn, environment) }
        return true
    }

    /**
     * The whole configuration, as a pure function of two strings.
     *
     * ⛔ SEPARATED FROM [initialize] SO IT CAN BE ASSERTED WITHOUT A DEVICE. `SentryAndroid.init`
     * installs process-wide global state — an uncaught-exception handler, an ANR watchdog and a
     * background sender — which a unit test must not do and cannot undo. The configuration is the
     * part that can be wrong (a DSN that never got set, PII that quietly came back on, tracing
     * that got enabled by a default change in an SDK upgrade), and it is the part
     * `DistrictSentryTest` pins.
     *
     * ⚠️ TYPED ON [SentryOptions], THE SUPERTYPE, NOT ON `SentryAndroidOptions`. That is what lets
     * the test construct a plain `SentryOptions()` on the JVM with no Robolectric and no Android
     * runtime. Nothing here needs an Android-specific option: ANR detection is on by default in
     * `sentry-android` and is left alone precisely so this stays true.
     */
    internal fun apply(options: SentryOptions, dsn: String, environment: String) {
        options.dsn = dsn

        // ⚠️ THE BUILD TYPE, NOT A HAND-MAINTAINED "prod"/"dev" STRING. `BuildConfig.BUILD_TYPE`
        // is generated from the variant that actually produced the artifact, so it cannot
        // disagree with reality — a hardcoded constant can, and the direction it fails in is a
        // debug crash filed as production.
        options.environment = environment

        // See the class header. Off, and it stays off.
        options.isSendDefaultPii = false

        // ⛔ BOTH NULL, WHICH IS "DISABLED", AND `0.0` WOULD NOT BE THE SAME THING. Sentry treats
        // a null sample rate as "this feature is off" and a rate of 0.0 as "on, sampling nothing"
        // — the second still initialises the tracing machinery and still allows a manually
        // started transaction through. Null is the setting that makes the spend impossible rather
        // than merely unlikely.
        //
        // ⚠️ STATED EXPLICITLY EVEN THOUGH NULL IS TODAY'S DEFAULT. A default is a decision
        // someone else can change in a minor upgrade; this is the one place in the integration
        // where a silent change would cost money rather than break a build, so it is written down
        // and `DistrictSentryTest` asserts it.
        options.tracesSampleRate = null
        options.profilesSampleRate = null
    }

    /**
     * Report a non-fatal condition nothing else would ever surface, as a warning-level message.
     *
     * ⛔ [message] MUST BE A FIXED STRING. Nothing a caller passes may carry a push token, an
     * account, a workspace or any other identifier: this is the one path in the app that sends
     * text of its own choosing to a vendor, and the class header's PII position applies to it in
     * full. Today's only caller is `PushRegistrar`, whose messages are compile-time constants.
     *
     * ⚠️ A NO-OP WHEN [initialize] DID NOT ARM THE SDK (a build with no DSN), exactly as a crash is.
     */
    fun reportNonFatal(message: String) {
        Sentry.captureMessage(message, SentryLevel.WARNING)
    }
}
