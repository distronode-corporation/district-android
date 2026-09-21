package com.distronode.districtai

import android.app.Application
import com.distronode.districtai.push.DistrictFirebase

/**
 * Application entry point, and the owner of the object graph.
 *
 * ⛔ THE CONTAINER IS HELD HERE, NOT IN THE ACTIVITY, BECAUSE ITS LIFETIME MUST OUTLIVE ONE.
 * [AppContainer] holds the single TokenRefreshCoordinator, which caches the access token in
 * memory and serialises refreshes behind a mutex. Rebuilding it per activity — which is what
 * happens if it is an activity field and the activity is recreated on rotation or on a
 * configuration change — produces two coordinators with two mutexes, either of which can present
 * the same refresh token. The server reads a re-presented refresh token as theft and revokes the
 * whole token family, so this is the difference between a rotation and a sign-out on every
 * device.
 *
 * ⚠️ Constructed lazily so nothing touches the Keystore or SharedPreferences on the main thread
 * during `onCreate`. Application startup is the most latency-sensitive moment in the app's life
 * and none of these objects are needed until a screen asks for one.
 *
 * ⛔ FIREBASE IS THE ONE THING THAT CANNOT BE LAZY, WHICH IS WHY IT IS THE ONLY WORK IN [onCreate].
 * `DistrictFirebaseMessagingService` is constructed BY THE SYSTEM, and the system may construct it
 * into a cold process with no Activity — a data push delivered to a killed app is exactly that path.
 * Anything reaching `FirebaseMessaging.getInstance()` before `initializeApp` has run throws
 * `IllegalStateException("Default FirebaseApp is not initialized")`, and it does so only on that
 * cold-start path, which nobody exercises by hand. `Application.onCreate` is the one hook that
 * always precedes a service callback.
 *
 * ⚠️ THE TELECOM PhoneAccount IS STILL **NOT** REGISTERED HERE, DELIBERATELY, and that half of the
 * old note stands. Telecom drops registrations across an app upgrade or a data clear, so a one-shot
 * at startup is precisely the shape that fails silently later; `AndroidTelecomBridge` re-asserts it
 * on every dial and on every inbound ring instead, where it is cheap and always current.
 *
 * ⛔ THERE ARE NOW **TWO** EAGER INITIALISERS, AND THE ORDER BETWEEN THEM IS THE POINT: SENTRY
 * FIRST. A crash reporter installed second cannot report a crash in the thing installed first, and
 * Firebase initialisation is exactly the kind of code that can throw on a device nobody tested —
 * a corrupt Play Services install, a package name no Firebase app is registered for. Sentry's
 * `init` is also the cheaper of the two and touches no I/O (see [DistrictSentry]), so putting it
 * first costs nothing measurable and buys the one window that would otherwise be blind.
 *
 * ⚠️ THE FIREBASE REASONING BELOW IS UNCHANGED AND STILL BINDING: it cannot be lazy, because the
 * system may construct `DistrictFirebaseMessagingService` into a cold process. Sentry being first
 * does not weaken that — both still complete before `onCreate` returns, which is before any
 * service callback can run.
 */
class DistrictApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * ⛔ THE ONLY WORK DONE EAGERLY, AND NEITHER HALF COSTS I/O. `initializeApp` builds an in-memory
     * component registry from the options handed to it; it reads no file, because the
     * `google-services` plugin is deliberately not applied and no `google-services.json` exists —
     * see [DistrictFirebase] for the three reasons. The container stays lazy so nothing touches the
     * Keystore or SharedPreferences on this path.
     *
     * ⚠️ `BuildConfig.APPLICATION_ID` RATHER THAN `packageName`, AND THE TWO DISAGREE HERE. The debug
     * build carries `applicationIdSuffix = ".debug"`, which is exactly the distinction Firebase
     * registers two separate apps for; `packageName` would agree today and would stop agreeing the
     * moment a test harness or an instrumentation runner is involved.
     *
     * ⚠️ FALSE IS AN ORDINARY OUTCOME AND IS NOT REPORTED. An unrecognised package (a staging variant
     * nobody registered) leaves Firebase uninitialised, which leaves push absent — a state every
     * path in this app already tolerates. See [DistrictFirebase.firebaseAppIdFor] for why refusing
     * beats guessing.
     */
    override fun onCreate() {
        super.onCreate()
        // ⛔ BEFORE Firebase; see the ⛔ in this class's header. When the build carries a DSN,
        // nothing below this line is unreported; without one this returns false and reports
        // nothing at all.
        DistrictSentry.initialize(
            context = this,
            dsn = BuildConfig.SENTRY_DSN,
            environment = BuildConfig.BUILD_TYPE,
        )
        DistrictFirebase.initialize(this, BuildConfig.APPLICATION_ID)
    }
}
