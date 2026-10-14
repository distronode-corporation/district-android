package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

// `GET` and `POST /api/settings`: the signed-in person's own account settings, the route the
// portal's settings page and the 3.x apps' language choice share.
//
// ⚠️ NO TRANSPORT READS THESE YET. They exist so the committed fixtures (`district-settings.json`,
// `district-settings-unset.json`, `district-settings-save.json`) are decoded by the strict contract
// decoder, which is what makes a field the server adds a failure here rather than a silent drop.
//
// ⛔ THE ROUTE TAKES `en` AND `fr` ONLY for [AccountProfile.locale], and the app is a second writer
// of the column the portal's language select writes (last writer wins).

/**
 * `GET /api/settings`.
 *
 * ⚠️ NO `success` KEY, like `workspace/provider/status`: the body is the settings themselves.
 */
@Serializable
data class AccountSettingsResponse(
    val profile: AccountProfile = AccountProfile(),
    val subscription: AccountSubscription = AccountSubscription(),
    /** The person's first workspace's region; `us` when the person has no workspace. */
    val region: String = "",
)

/** The person, as the settings route reports them. */
@Serializable
data class AccountProfile(
    val email: String = "",
    /** Empty, never null, for an account that never set one. */
    val displayName: String = "",
    /** An IANA zone name; the server answers `America/Toronto` for an account that never set one. */
    val timezone: String = "",
    /**
     * `en` or `fr`, or null.
     *
     * ⛔ NULL IS A THIRD STATE, "NEVER ASKED", and is not English: an app that read it as `en`
     * would overwrite a choice nobody made the first time it saved.
     */
    val locale: String? = null,
)

/**
 * The plan line of the settings page.
 *
 * ⚠️ [tier] IS "Free" HERE WHEN THE ROW HAS NONE, a substitution this route makes and
 * `workspace/billing` deliberately does not (see [WorkspaceBilling.subscriptionTier]).
 */
@Serializable
data class AccountSubscription(
    val tier: String = "",
    /** `none` when the person has no workspace. */
    val status: String = "",
    /** ISO-8601 instant. ABSENT, not null, when the person has no workspace. */
    val updatedAt: String? = null,
)

/** `POST /api/settings`: a bare success and the server's own sentence. */
@Serializable
data class AccountSettingsSaveResponse(
    val success: Boolean = false,
    val message: String = "",
)
