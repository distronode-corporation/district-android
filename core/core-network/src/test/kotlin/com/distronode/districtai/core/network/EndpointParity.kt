package com.distronode.districtai.core.network

/**
 * The recorded difference between this client's endpoint surface and the iOS one.
 *
 * ⛔ WHY THIS EXISTS. `EndpointID.swift` holds 118 cases and this module declares 101 `suspend
 * fun`s across the API interfaces in ten files, and without [EndpointParityTest] NOTHING compares
 * the two. A hand-kept parity document can list endpoints as missing on the Kotlin side that
 * already ship here. A parity claim nobody can re-derive rots, quietly, in the direction of
 * flattering whichever document was written last.
 *
 * ⛔ THE LISTS BELOW ARE THE CLAIM, AND THE TEST IS WHAT MAKES THEM COST SOMETHING. They are
 * asserted by EQUALITY, not by containment, so the diff cannot silently grow in either direction:
 * an endpoint iOS adds fails here until somebody either implements it or writes it down, and an
 * Android-only endpoint fails until it is acknowledged.
 *
 * ⛔ THE SURFACE IS EVERY API INTERFACE IN THE PACKAGE, NOT `DistrictApi.kt` ALONE. Most feature
 * areas (scheduling-admin, desk, support, persona, call-handling, inbox) are SIBLING interfaces
 * beside it (see the ⚠️ in `AppContainer`, which explains why they are siblings). A single-file
 * read reports their endpoints as missing while they sit implemented in the next file over, and
 * it does so while PASSING, because a recorded list built the same way still matches. That is the
 * exact failure mode this gate exists to prevent, so the extractor walks the package.
 */
internal object EndpointParity {

    /**
     * ⛔ SHRINK-ONLY RATCHET, the same contract as `ContractManifest.NOT_YET_MODELLED_BASELINE` in
     * :core:core-model. [MISSING_ON_ANDROID] may LOSE entries and must never gain one: when an
     * endpoint lands here, delete its line AND lower this literal in the same commit.
     *
     * ⚠️ KEPT EQUAL TO THE ENTRY COUNT, never above it. A baseline with slack is a budget rather
     * than a ratchet: it enforces nothing until the slack is used up.
     */
    const val MISSING_ON_ANDROID_BASELINE: Int = 16

    /**
     * The places the clients spell the same endpoint differently, keyed by the KOTLIN function
     * name and valued with the SWIFT case name.
     *
     * ⚠️ NORMALISATION RUNS ANDROID → iOS, arbitrarily but consistently, because `EndpointID`'s
     * raw values are already documented as "spelled as Kotlin function names" and iOS is
     * therefore the side that has committed to the spelling in a serialised form.
     *
     * ⛔ BOTH HALVES OF EVERY ENTRY ARE ASSERTED LIVE by [EndpointParityTest]: a key that no
     * longer appears in an API interface, or a value that no longer appears in
     * `EndpointID.swift`, is a stale rule that silently normalises nothing and would hide a real
     * divergence behind a rename that already happened.
     *
     * ⚠️ THE TWO SCHEDULING-ADMIN ENTRIES ARE NOT A TIDY-UP WAITING TO HAPPEN. Kotlin names
     * those two by WHAT THEY DO (`performSchedulingOp` is an RPC over 64 catalogued ops,
     * `uploadSchedulingImage` publishes one of three images) while `EndpointID` names them by the
     * ROUTE FAMILY they share. Renaming either side to match the other would make one of the two
     * files read worse in order to delete two lines here, so the divergence is recorded rather
     * than removed.
     */
    val SPELLING_DELTAS: Map<String, String> = mapOf(
        "enableScheduling" to "schedulingEnable",
        "schedulingHandOff" to "schedulingHandoff",
        "performSchedulingOp" to "schedulingAdmin",
        "uploadSchedulingImage" to "schedulingAdminUpload",
    )

    /**
     * iOS cases whose server route is GONE, which this client has already dropped and the vendored
     * snapshot still declares.
     *
     * ⛔ NOT A SECOND [MISSING_ON_ANDROID], AND THE DIFFERENCE IS THE DIRECTION OF TRAVEL. Those are
     * endpoints this client has yet to gain; these are endpoints iOS has yet to LOSE. The server
     * removed call recordings and the scheduler's recording download (monorepo d92b7894c,
     * 2026-10-03): `GET /api/district/calls/{id}/recording` and
     * `/api/district/scheduling/admin/download/{id}` no longer exist, so implementing them here
     * would call a 404.
     *
     * ⚠️ SELF-CLEANING. [EndpointParityTest] requires every entry to still be a case in the iOS
     * source AND absent here, so the snapshot refresh that follows the iOS removal fails until
     * this set is emptied. It may never gain an entry for any other reason.
     */
    val RETIRED_PENDING_IOS: Set<String> = setOf(
        "callRecordingUrl",
        "schedulingAdminDownload",
    )

    /**
     * Endpoints iOS declares and this client does not, after normalisation.
     *
     * ⚠️ IT IS ONE SURFACE, and what it records is a single coherent product decision: this client
     * can SEARCH for and LIST numbers, and cannot buy, configure, register or release one. That is
     * a screen nobody has written, not 16 oversights.
     */
    val MISSING_ON_ANDROID: Set<String> = setOf(
        // ── Numbers, carrier and compliance (16) ──────────────────────────────────────────────
        // Buying, configuring and releasing a number, plus the A2P/toll-free/registration
        // paperwork and the Verify service toggle. `NumbersApi` here covers search and the owned
        // list; everything that MUTATES carrier state is iOS-only.
        "configureNumber",
        "createNumberRegistration",
        "createSipTrunk",
        "deleteRegistrationDocument",
        "lookupNumber",
        "numberRegistrations",
        "numberRequirements",
        "providerStatus",
        "releaseNumber",
        "setVerifyServiceEnabled",
        "sipTrunks",
        "submitA2PRegistration",
        "submitNumberRegistration",
        "submitTollFreeVerification",
        "uploadRegistrationDocument",
        "verifyService",
    )

    /**
     * Endpoints this client declares and iOS does not.
     *
     * ⚠️ ASSERTED BY EQUALITY AND DELIBERATELY WITHOUT A BASELINE. There is no ratchet here
     * because the direction is not a debt: this client shipping first is a normal outcome, and
     * the only thing worth enforcing is that it is WRITTEN DOWN rather than discovered later by
     * someone auditing two files by hand.
     */
    val MISSING_ON_IOS: Set<String> = setOf(
        // ⚠️ THE PREDECESSOR OF `schedulingHandOff`, NOT A SIBLING OF IT — see the ⛔ on
        // `DistrictApi.schedulingHandOff`. It reads a redirect target into the scheduler's own
        // `/admin/` console, which is being switched off per region; both ship for one release so
        // an installed build keeps working either side of the flip. So this entry is expected to
        // leave by DELETION here rather than by arriving on iOS, and when it does, this set
        // becomes empty rather than shorter.
        "schedulingSsoTarget",
        // ⚠️ THE NATIVE VOICE STUDIO READ (`GET workspace/persona/voice-studio`), built for all
        // three apps at once. The vendored iOS snapshot predates it; this entry leaves when the
        // snapshot is refreshed from an iOS build that has it (under this name, or recorded in
        // SPELLING_DELTAS if iOS spells it differently).
        "personaVoiceStudio",
    )
}
