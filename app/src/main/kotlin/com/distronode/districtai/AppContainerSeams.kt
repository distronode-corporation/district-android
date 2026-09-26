package com.distronode.districtai

import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.network.CallHandlingApi
import com.distronode.districtai.core.network.DeskApi
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.InboxExtrasApi
import com.distronode.districtai.core.network.PersonaApi
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SupportApi
import com.distronode.districtai.push.PushTokenSource
import com.distronode.districtai.telecom.TelecomBridge

/**
 * Everything a test may put in place of what [AppContainer] would otherwise build.
 *
 * ⚠️ EVERY FIELD IS NULLABLE-WITH-A-FALLBACK RATHER THAN A DEFAULT EXPRESSION, because a default
 * cannot reference the container's application context or its API client, both of which the real
 * implementations are built from. Null means "build the real one", which is what production does
 * by passing nothing at all.
 *
 * ⛔ ONE OBJECT RATHER THAN ONE CONSTRUCTOR PARAMETER PER SEAM. The container's constructor sat at
 * detekt's `LongParameterList` ceiling with four seams, and the navigation graph's tests need the
 * network and the media engine faked as well: without them every destination's load either reaches
 * PRODUCTION (the API client is built from `ApiEnvironment.baseUrl`, a `BuildConfig` constant) or
 * fails as `NoSession` on an IO thread at a moment the test does not control.
 */
data class AppContainerSeams(
    /**
     * The credential store.
     *
     * ⚠️ Exists so the sign-out sequence can be driven without an AndroidKeyStore, which
     * Robolectric does not implement: under it a real store reads as "no session" and the whole
     * revoke path is skipped, so the tests would assert nothing.
     */
    val tokenStore: TokenStore? = null,
    /**
     * The revoke call.
     *
     * ⚠️ Needed because `NativeAuthApi` is constructed from `ApiEnvironment.baseUrl`, a
     * `BuildConfig` constant that cannot be pointed at a MockWebServer. Without this, an
     * `AppContainer` test of sign-out would talk to PRODUCTION.
     */
    val revokeApi: RevokeApi? = null,
    /**
     * The two push-registration routes.
     *
     * ⚠️ NEEDED FOR THE SAME REASON [revokeApi] IS, and sign-out is exactly where the push
     * unregister sits. When null the push routes go through [districtApi], faked or real.
     */
    val pushApi: PushApi? = null,
    /**
     * The FCM token.
     *
     * ⛔ NEEDED BECAUSE THE REAL SOURCE IS UNAVAILABLE ON EVERY MACHINE THIS PROJECT CAN TEST ON.
     * `FirebaseMessaging.getInstance()` throws with no default `FirebaseApp`, and Waydroid has no
     * Play Services at all, so the production implementation answers null on the only devices a test
     * could run on, which would make every registration assertion pass for the wrong reason.
     */
    val pushTokenSource: PushTokenSource? = null,
    /** The composed API every repository reads except those with their own interface below. */
    val districtApi: DistrictApi? = null,
    val deskApi: DeskApi? = null,
    val supportApi: SupportApi? = null,
    val personaApi: PersonaApi? = null,
    val callHandlingApi: CallHandlingApi? = null,
    val inboxExtrasApi: InboxExtrasApi? = null,
    /**
     * The scheduler's admin RPC route, its image upload and its recording download.
     *
     * ⚠️ NEEDED BECAUSE A SERVER-ONLY ANSWER IS WIRED HERE: the container decides what a 400
     * `unknown_op` does (see [reportUnknownSchedulingOp]), and only a fake can produce one.
     */
    val schedulingAdminApi: SchedulingAdminApi? = null,
    /** The media engine a room, the dialler and the persona audition connect through. */
    val callEngineFactory: CallEngineFactory? = null,
    /** What the softphone tells the OS; Robolectric does not model `TelecomManager`. */
    val telecomBridge: TelecomBridge? = null,
)
