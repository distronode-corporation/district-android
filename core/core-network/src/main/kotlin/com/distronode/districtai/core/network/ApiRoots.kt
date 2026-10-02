package com.distronode.districtai.core.network

/**
 * The path roots every endpoint family in this module builds on.
 *
 * ⛔ EACH ROOT IS SPELLED ONCE, HERE. `api/district` used to be typed in five files, one per family
 * path object, and the prefix has already moved once (`/api/district/` postdates a flat `/api/`).
 * A family that missed the next move would 404 at runtime with nothing comparing the copies. The
 * request tests pin each full path; this keeps them from having to catch a missed edit.
 *
 * ⚠️ A FILE OF ITS OWN rather than more entries on [DistrictPaths], for the reason the family path
 * objects exist at all: `HttpDistrictApi.kt` is the shared file parallel work collides in.
 */
internal object ApiRoots {
    private val API: List<String> = listOf("api")

    /** `api/district`, the root of every district route. */
    val DISTRICT: List<String> = API + "district"

    /**
     * `api/auth/native`, native session management.
     *
     * ⛔ NOT UNDER [DISTRICT], and the prefixes are not interchangeable; see
     * [DistrictPaths.NATIVE_DEVICES].
     */
    val NATIVE_AUTH: List<String> = API + "auth" + "native"

    /**
     * `api/billing`, the caller-scoped Stripe detail.
     *
     * ⛔ NOT UNDER [DISTRICT] EITHER; see [DistrictPaths.BILLING].
     */
    val BILLING: List<String> = API + "billing"
}
