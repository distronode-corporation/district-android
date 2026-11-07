package com.distronode.districtai.core.model

/**
 * The coordination point for the contracts directory, and the Kotlin mirror of the iOS client's
 * `ContractManifest.swift`.
 *
 * ⛔ WHY THIS EXISTS. Without it, the ONLY thing standing behind fixtures nothing decodes is
 * `ContractFixtureTest.contracts directory contains fixtures`, which asserts the directory is
 * non-empty. A fixture nothing decodes is indistinguishable from a fixture nothing NEEDS, so the
 * corpus could grow endpoints this client has no DTO for and every gate would stay green. The debt
 * is written down by name instead.
 *
 * ⛔ THE COUNT IS ASSERTED EXACTLY, NOT AS A FLOOR. A floor (`>= 1`) goes green against a broken
 * `district.contracts.dir` that happens to resolve somewhere else, and never notices a fixture the
 * server's generator added. An exact count turns both into one failure that names the files it
 * did not expect. It WILL fail the day a fixture is added, and that is the design: nothing else on
 * this side watches the generator.
 *
 * ⚠️ THE TWO NUMBERS MOVE INDEPENDENTLY, AND CONFLATING THEM IS THE EASY MISTAKE.
 * [EXPECTED_FIXTURE_COUNT] counts FILES ON DISK. [NOT_YET_MODELLED_BASELINE] counts the BURN-DOWN.
 * Writing a DTO for a fixture that already exists moves the second and leaves the first alone.
 */
internal object ContractManifest {

    /**
     * Every `.json` file in the contracts directory.
     *
     * This is the same corpus the iOS client's `ContractManifest.expectedFixtureCount` pins. The two
     * files describe ONE directory and must agree; if they ever disagree, one of them was edited
     * without re-counting. A new fixture should arrive DECODED by its own test, so the count moves
     * and [NOT_YET_MODELLED] does not grow, which it may not.
     */
    const val EXPECTED_FIXTURE_COUNT: Int = 231

    /**
     * ⛔ SHRINK-ONLY RATCHET. [NOT_YET_MODELLED] may LOSE entries and must never gain one.
     * When a DTO lands, delete the fixture's line AND lower this literal in the same commit.
     *
     * ⚠️ THE BASELINE IS KEPT EQUAL TO THE ENTRY COUNT ON PURPOSE. A baseline that sits above its
     * list enforces nothing until the slack is used up. A baseline with slack is not a ratchet, it
     * is a budget.
     */
    const val NOT_YET_MODELLED_BASELINE: Int = 4

    /**
     * Fixtures that no Kotlin test decodes.
     *
     * ⛔ ONE EXPLICIT LIST, NEVER A PATTERN AND NEVER A "SKIP IF NOTHING DECODES IT" FALLBACK. A
     * rule that derives the skip set from whatever happens to be implemented cannot tell "not
     * ported yet" from "silently stopped being verified", and would be counting itself. Every
     * entry here is a debt somebody wrote down.
     *
     * ⛔ STALE ENTRIES FAIL IN BOTH DIRECTIONS. A name here that is not on disk means the corpus
     * moved underneath this file; a name here that IS decoded means the burn-down stopped being
     * true. [ContractManifestTest] checks both.
     *
     * ⚠️ ALPHABETICAL, NOT GROUPED BY OWNER. The groups below are comments over a single sorted
     * run so that a `git diff` of an addition or removal is one line in an obvious place.
     */
    val NOT_YET_MODELLED: Set<String> = setOf(
        // ── Alternate branches of endpoints this client already decodes (4) ──────────────────
        // ⚠️ NOT ONE MISSING TYPE BETWEEN THEM. The two `*-patch` bodies are the bare
        // `{"success": true}` a single `SuccessResponse` already covers, and
        // `district-dial-dormant` / `district-workspace-list-partial` are the SECOND branch of a
        // route whose first branch is gated today. What is missing is a test that reads the
        // branch, not a DTO to read it with.
        "district-dial-dormant.json",
        "district-directory-patch.json",
        "district-routing-patch.json",
        "district-workspace-list-partial.json",
    )

    /**
     * Fixtures for routes Android never calls, because the feature is not on Android.
     *
     * ⛔ NOT DEBT, AND NOT A WAY AROUND [NOT_YET_MODELLED]. Every entry is a route this client never
     * calls because the feature is not on Android today: the iOS Live Activity routes, and the iOS
     * parity routes (the native setup wizard, the integrations read, the campaign dialer, District HQ
     * by voice, and native re-authentication, two-factor and account deletion). A DTO and a decode
     * test for a route nothing here calls would be coverage of nothing, so they are not in the
     * shrink-only list either. They are here only because the corpus is shared with iOS and every
     * file must be accounted for. When Android gains one of these features, its fixtures leave this
     * set with the DTO and decode test that read them.
     *
     * ⛔ PINNED EXACTLY: [ContractManifestTest] fails on any entry not on disk, any entry that
     * IS decoded, and any change to [NOT_ON_ANDROID_COUNT]. Adding to this set needs the feature
     * to be absent from Android, written down in the entry's comment.
     */
    const val NOT_ON_ANDROID_COUNT: Int = 22

    val NOT_ON_ANDROID: Set<String> = setOf(
        // Android deletes an account in the browser (ACCOUNT_DELETION_URL), not natively.
        "district-account-delete.json",
        "district-call-live-activity.json", // iOS Live Activity: the call card
        // The campaign dialer: Android has the human dialer (`calls/dial`) only.
        "district-calls-outbound.json",
        // The integrations read: Android has no integrations screen.
        "district-connections-viewer.json",
        "district-connections.json",
        // The setup wizard's forwarding check: Android's setup reads progress and finishes on the web.
        "district-forwarding-check-start.json",
        "district-forwarding-check.json",
        // District HQ by voice: Android's HQ is the text console, which declines a proposal locally.
        "district-hq-proposal-cancel.json",
        "district-hq-voice-context.json",
        "district-hq-voice-settings-put.json",
        "district-hq-voice-settings.json",
        "district-hq-voice-token-allowance.json",
        "district-hq-voice-token.json",
        "district-live-activity-start-token.json", // iOS Live Activity: push-to-start token
        // Two-factor and native re-authentication: Android signs in through the browser.
        "district-mfa-confirm.json",
        "district-mfa-disable.json",
        "district-mfa-enroll.json",
        "district-native-reauth.json",
        // The rest of the native setup wizard (business lookup, the facts patch, the test call).
        "district-setup-lookup-unreadable.json",
        "district-setup-lookup.json",
        "district-setup-patch.json",
        "district-setup-test-call.json",
    )
}
