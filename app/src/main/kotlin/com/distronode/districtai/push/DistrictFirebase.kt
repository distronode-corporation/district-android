package com.distronode.districtai.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Firebase, initialised by hand.
 *
 * ⛔ THE `com.google.gms:google-services` GRADLE PLUGIN IS DELIBERATELY NOT APPLIED AND NO
 * `google-services.json` IS READ. Two facts make the file-driven path the wrong one here, and
 * neither of them is a preference:
 *
 *   1. **CI cannot fetch the file.** The CI build has no cloud credentials of any kind, so a
 *      build that needed the json would work on a developer machine and fail in CI, with a plugin
 *      error about a missing file rather than about a missing credential.
 *   2. **The plugin buys nothing else.** It generates string resources that `FirebaseApp`
 *      auto-initialises from at startup. Naming the same four values here is the whole of it, and
 *      they are all CLIENT configuration — the same class of value as a Sentry DSN. The API key
 *      below authorises nothing on its own; a Firebase API key identifies the project to Google's
 *      client SDKs and every operation behind it is gated on something else (an FCM registration
 *      token here, and the send side is gated on a service account this app has never seen).
 *
 * ⚠️ A COPY OF THE ORIGINAL `google-services.json` IS KEPT OUTSIDE THE REPO AS REFERENCE and is
 * not a dependency of anything in this build. It is where these values came from; if Firebase
 * ever reissues one, that is the artefact to diff against.
 *
 * ⛔ INITIALISATION ORDER IS THE REASON THIS RUNS IN `Application.onCreate` AND NOT LAZILY.
 * `FirebaseMessagingService` is constructed BY THE SYSTEM, and the system may construct it before
 * any Activity exists — a data push delivered to a cold process starts the app precisely that way.
 * Anything that reached `FirebaseMessaging.getInstance()` before `initializeApp` had run would
 * throw `IllegalStateException("Default FirebaseApp is not initialized")`, and it would do so only
 * on the cold-start path, which is the one nobody exercises by hand.
 *
 * ⚠️ NOTHING HERE IS VERIFIABLE ON AN EMULATOR WITHOUT PLAY SERVICES. Waydroid is API 33 with no
 * Play Services, so FCM cannot be exercised there at all: token acquisition, delivery and Doze
 * wake are all real-device work. What IS tested is the pure part: which application id maps to
 * which Firebase app id, and that an unrecognised one is refused rather than guessed.
 */
internal object DistrictFirebase {

    /**
     * The GCP project that owns FCM.
     *
     * ⚠️ THE SAME PROJECT THE SERVER'S Pub/Sub AND SECRET STORE RESOLVE FROM, and pointedly NOT the
     * separate project that still holds some other server services as a billing decision. The
     * server's own sender builds its FCM endpoint from `GCP_PROJECT_ID` for the same reason. The
     * two halves must name the same project or nothing is delivered.
     */
    const val PROJECT_ID: String = "distronode"

    /**
     * The sender id, which is the project NUMBER rather than its id.
     *
     * ⚠️ FCM's registration handshake keys on this. It is also what `SENDER_ID_MISMATCH` names when
     * a token minted for one project is presented to another — a failure the server treats as
     * permanent and retires the row for, so getting this wrong is not a transient outage.
     */
    const val GCM_SENDER_ID: String = "409911651910"

    /**
     * The Android client API key.
     *
     * ⛔ COMMITTABLE CLIENT CONFIGURATION, NOT A SECRET, AND THE DISTINCTION IS WORTH BEING PRECISE
     * ABOUT RATHER THAN NERVOUS ABOUT. A Firebase Android API key identifies the project to Google's
     * client SDKs; it grants nothing by itself, exactly as a Sentry DSN or a Stripe publishable key
     * does not. Every Firebase app in the world ships one inside `google-services.json`, which is
     * itself committed in the ordinary setup. ⚠️ It is still not a FCM SERVER key — those were
     * retired with the legacy API, and the HTTP v1 send path this project uses is authorised by a
     * service account that lives in the server's secret store and never comes near a phone.
     */
    const val API_KEY: String = "AIzaSyCfxucS4K_z-HppqEpbamLC-zTMh3AY4_A"

    /** The Play package. ⛔ Permanent — see `applicationId` in app/build.gradle.kts. */
    const val RELEASE_APPLICATION_ID: String = "com.distronode.districtai"

    /** ⚠️ The debug build gets `.debug` appended so it can coexist with a Play install. */
    const val DEBUG_APPLICATION_ID: String = "$RELEASE_APPLICATION_ID.debug"

    /**
     * ⛔ TWO FIREBASE APPS, ONE PER PACKAGE, AND THEY ARE NOT INTERCHANGEABLE. Firebase registers an
     * Android app against its package name, so the debug build — a DIFFERENT package, by design —
     * needs its own registration. Handing the release app id to a `.debug` install produces a token
     * FCM will later reject with `SENDER_ID_MISMATCH`, which the server treats as PERMANENT and
     * disables the row for. The failure therefore looks like "push stopped working on that phone"
     * long after the build that caused it.
     */
    const val RELEASE_FIREBASE_APP_ID: String = "1:409911651910:android:e5e8f615d04b0ae677a447"
    const val DEBUG_FIREBASE_APP_ID: String = "1:409911651910:android:d5f6b5bbf616e4df77a447"

    /**
     * Which Firebase app id belongs to [applicationId], or null for one we have never registered.
     *
     * ⛔ NULL RATHER THAN A DEFAULT, AND THAT IS THE WHOLE VALUE OF THIS FUNCTION BEING SEPARATE.
     * Falling back to the release id for an unrecognised package is the one behaviour that produces
     * a token FCM permanently rejects, and it is exactly what a `?:` would do. Refusing to
     * initialise leaves push simply absent, which every path in this app already tolerates: the
     * inbox has a poll and an unanswered ring falls through to PSTN.
     *
     * ⚠️ THE UNRECOGNISED CASE IS REACHABLE, not theoretical — an `applicationIdSuffix` added for a
     * staging or benchmark variant would land here, and this is the only thing that would say so
     * rather than shipping a build whose notifications quietly stop after the first send.
     *
     * ⚠️ PURE, AND IT TAKES THE ID RATHER THAN READING `BuildConfig` ITSELF, so the mapping is
     * testable without a variant. The one production caller passes `BuildConfig.APPLICATION_ID`.
     */
    fun firebaseAppIdFor(applicationId: String): String? = when (applicationId) {
        RELEASE_APPLICATION_ID -> RELEASE_FIREBASE_APP_ID
        DEBUG_APPLICATION_ID -> DEBUG_FIREBASE_APP_ID
        else -> null
    }

    /**
     * The options for [applicationId], or null when it is not one of ours.
     *
     * ⚠️ ALL FOUR FIELDS ARE REQUIRED BY FCM AND NONE IS OPTIONAL DESPITE THE BUILDER ACCEPTING A
     * PARTIAL SET. `setApplicationId` and `setApiKey` are enforced by the builder; the project id
     * and the sender id are not, and omitting either produces a `FirebaseApp` that initialises
     * cleanly and then fails at token acquisition with a message about the project rather than about
     * the configuration.
     */
    fun optionsFor(applicationId: String): FirebaseOptions? {
        val appId = firebaseAppIdFor(applicationId) ?: return null
        return FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(API_KEY)
            .setProjectId(PROJECT_ID)
            .setGcmSenderId(GCM_SENDER_ID)
            .build()
    }

    /**
     * Initialise the default [FirebaseApp] for [applicationId], if it is one of ours.
     *
     * ⛔ IT NEVER THROWS, AND FOR THE REASON THE WHOLE PUSH LAYER NEVER DOES: this runs inside
     * `Application.onCreate`, so an exception here is a crash on every cold start of the app, in
     * exchange for a courtesy channel. A device without Play Services is the ordinary case that
     * reaches it.
     *
     * @return true when a default app now exists. ⚠️ Also true when one already did — the SDK's
     *   `initializeApp` throws `IllegalStateException` on a second call with the same name rather
     *   than being idempotent, and a process can genuinely run `onCreate` twice (the system
     *   restarting a killed process to deliver a push is exactly that case).
     */
    fun initialize(context: Context, applicationId: String): Boolean {
        // ⚠️ CHECKED FIRST RATHER THAN CAUGHT. `FirebaseApp.getApps` is the documented way to ask,
        // and relying on the throw would mean an ordinary re-entry produced a logged exception.
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val options = optionsFor(applicationId) ?: return false
        return runCatching { FirebaseApp.initializeApp(context, options) }.getOrNull() != null
    }
}
