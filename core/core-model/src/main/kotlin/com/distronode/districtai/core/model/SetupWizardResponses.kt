// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The setup wizard's writes and the reads its steps make beside [DistrictSetupResponse].
//
// ⛔ EVERY FIELD HAS A DEFAULT, as on every other response here: a key the server stops sending
// must not break an installed build. The contract gate decodes the fixtures with unknown keys
// refused, so a key the server ADDS fails there first.
//
// ⚠️ OWNER ONLY. Every `setup/*` route answers a member or an admin with 403 and a workspace that
// predates the wizard with 409 `setup_not_available`. `GET /api/district/connections` is the
// exception, open to every member.

/**
 * `POST /api/district/setup/lookup`: a draft of the business's facts, read off its own website.
 *
 * ⛔ A SUGGESTION, NEVER A WRITE. The service stores nothing; the owner reviews the draft and
 * saves what they keep through `PATCH /api/district/setup`. Every service and question arrives
 * `enabled: false`.
 */
@Serializable
data class SetupLookupResponse(
    val draft: SetupLookupDraft = SetupLookupDraft(),
    val source: SetupLookupSource = SetupLookupSource(),
)

/**
 * The drafted facts.
 *
 * ⚠️ [address] AND [weeklyHours] STAY OPAQUE OBJECTS: they go back to the service unchanged inside a
 * `businessFacts` PATCH, and the service owns their validation.
 */
@Serializable
data class SetupLookupDraft(
    val address: JsonObject? = null,
    val weeklyHours: JsonObject? = null,
    val services: List<SetupDraftService> = emptyList(),
    val faqs: List<SetupDraftFaq> = emptyList(),
    /** How many drafted items the service dropped as unsupported by the page's text. */
    val dropped: Int = 0,
)

/** One service on the business's website. [price] is free text, read aloud as written. */
@Serializable
data class SetupDraftService(
    val name: String = "",
    val price: String? = null,
    val enabled: Boolean = false,
)

/** One question and answer on the business's website. */
@Serializable
data class SetupDraftFaq(
    val question: String = "",
    val answer: String = "",
    val enabled: Boolean = false,
)

/** Where a draft came from. Today always `kind: "website"` with the page actually read. */
@Serializable
data class SetupLookupSource(
    val kind: String = "",
    val url: String = "",
)

/**
 * The 422 `website_unreadable` body of a lookup. The client keeps only the code and the message of
 * a failure, so [reason] (`host_unavailable`, `blocked_scheme` and the other page refusals) is here
 * for the contract gate.
 */
@Serializable
data class SetupLookupRefusal(
    val error: String = "",
    val code: String = "",
    val reason: String = "",
)

/** `POST /api/district/setup/test-call`: the receptionist is calling the owner's phone. */
@Serializable
data class SetupTestCallResponse(
    val success: Boolean = false,
    val callId: String = "",
)

/**
 * `GET` and `POST /api/district/setup/forwarding-check`: whether the business line forwards to the
 * District number. The POST answers 202 with the same body.
 */
@Serializable
data class ForwardingCheckResponse(
    /** False when this region has no verifier configured; the step then offers no check. */
    val available: Boolean = false,
    /** The latest check, or null before the first one. */
    val forwardingCheck: ForwardingCheck? = null,
    /** Checks left today. */
    val attemptsLeft: Int = 0,
)

/**
 * One forwarding check.
 *
 * ⚠️ THE STATUS, CARRIER AND FAILURE ARE STRINGS: the service owns those vocabularies and a new
 * value must not fail the decode.
 */
@Serializable
data class ForwardingCheck(
    val id: String = "",
    /** `pending`, `passed` or `failed`. */
    val status: String = "",
    val mode: String = "",
    val businessLine: String = "",
    val startedAt: String = "",
    val at: String = "",
    val verifierNumber: String = "",
    val carrier: String = "",
    val failure: String? = null,
    /** The District number the check call arrived on, once it did. */
    val arrivedOn: String? = null,
    /** Set when a newer check replaced this one before it finished. */
    val supersededAt: String? = null,
) {
    val isPending: Boolean get() = status == FORWARDING_CHECK_PENDING
    val hasPassed: Boolean get() = status == FORWARDING_CHECK_PASSED
}

const val FORWARDING_CHECK_PENDING: String = "pending"
const val FORWARDING_CHECK_PASSED: String = "passed"

/** Which calls the owner says their line forwards: the two wire values the start route accepts. */
object ForwardingCheckMode {
    const val ALL: String = "all"
    const val UNANSWERED: String = "unanswered"

    val MODES: List<String> = listOf(ALL, UNANSWERED)
}

/**
 * `GET /api/district/connections`: the workspace's connected providers.
 *
 * ⚠️ A VIEWER GETS THE ROWS WITHOUT [calendar], so its absence is not "no calendar".
 */
@Serializable
data class ConnectionsResponse(
    val connections: List<WorkspaceConnection> = emptyList(),
    val calendar: CalendarResolution? = null,
)

/** One connection row, as stored. */
@Serializable
data class WorkspaceConnection(
    val provider: String = "",
    val status: String = "",
)

/** Which calendar the receptionist books into, and why that one. Both are null when none is connected. */
@Serializable
data class CalendarResolution(
    val activeProvider: String? = null,
    val source: String? = null,
)
