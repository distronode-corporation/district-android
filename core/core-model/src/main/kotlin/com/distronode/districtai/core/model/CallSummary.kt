package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of `GET /api/district/calls`.
 *
 * ⛔ THAT ENDPOINT RETURNS A BARE JSON ARRAY, NOT AN ENVELOPE. Almost every other
 * district route answers `{ success: true, ... }`, but this one is
 * `NextResponse.json(calls)` in the server's calls route handler.
 * Do not "normalise" it here by inventing a wrapper; the client's response handling has
 * to cope with both shapes because the API genuinely has both.
 *
 * ⛔ EVERY FIELD BELOW IS PINNED BY A COMMITTED FIXTURE and verified by
 * ContractFixtureTest with `ignoreUnknownKeys = false`. Adding a field server-side
 * without regenerating the fixture fails the website suite; regenerating without
 * updating this class fails the Android suite. That two-sided break is the entire point
 * — there is no OpenAPI spec in this repo, so this is the only thing standing between a
 * renamed server field and a silent parse failure on a user's phone.
 *
 * ⚠️ The handler mixes DISPLAY-FORMATTED and RAW values for the same underlying data,
 * deliberately, for two different web callers. Prefer the raw ones in the app:
 *   - `duration` is a human string ("1m 5s"); `durationRaw` is seconds.
 *   - `number` is already resolved to a contact name when one exists, falling back to
 *     the phone number and then the literal "Unknown"; `from` is the raw caller number.
 *   - `time` is pre-formatted IN THE USER'S TIMEZONE by the server, so it is not
 *     parseable as an instant. `createdAt` is the ISO timestamp to use for anything
 *     that sorts, groups or re-formats.
 */
@Serializable
data class CallSummary(
    val id: String,
    /**
     * "inbound" | "outbound" | "missed". Derived by the handler from the normalised
     * status plus direction — a `no-answer` or `failed` call becomes "missed" — so it is
     * NOT the same value as [direction].
     */
    val type: String,
    /** Contact name if resolved, else the raw number, else the literal "Unknown". */
    val number: String,
    /**
     * Display status from `displayCallStatus`, which downgrades a stale in-progress call
     * (its terminal webhook was lost) to no-answer rather than showing it live forever.
     */
    val status: String,
    /** Human-formatted, e.g. "1m 5s". Use [durationRaw] for arithmetic. */
    val duration: String,
    /** Pre-formatted in the user's timezone by the server. Not a parseable instant. */
    val time: String,
    val aiSummary: String,
    val recordingUrl: String? = null,
    /**
     * ⛔ ALWAYS `""`, AND NEVER READ. The server keeps the key only because builds up to 3670
     * declared it with no default, and a MISSING required key fails decoding whatever
     * `ignoreUnknownKeys` says, which would have emptied the calls feed on every installed app. The
     * default here is what lets the server drop the key once those builds age out.
     */
    val transcript: String = "",
    /**
     * Whether `GET /api/district/calls/{callId}/transcript` has a transcript to return.
     *
     * ⛔ THE TEXT IS NOT ON THIS ROW. It would be the largest value on the feed and ride every
     * page load; fetch it with [CallTranscriptResponse] when a call is opened. Defaults
     * to false so a response from an older server, which sends no such key, still decodes.
     */
    val hasTranscript: Boolean = false,

    // ── Fields the web console also consumes; the handler calls these
    // "compatibility fields". They overlap the above on purpose.
    val callerName: String,
    /** Raw caller number, E.164 from telephony. Null on rows that never had one. */
    val from: String? = null,
    val direction: String? = null,
    /** Duration in SECONDS. Null when the call never recorded one. */
    val durationRaw: Int? = null,
    val summary: String,
    /** ISO-8601 instant. */
    val createdAt: String,

    /** Present only when a follow-up was actually sent; otherwise null. */
    val followUp: CallFollowUp? = null,
    val sentiment: String? = null,
    val disposition: String? = null,
    /**
     * ⛔ A JSON OBJECT, NOT A STRING. This was typed `String?` first, on the reasoning that the
     * server column is "unstructured" — and that would have thrown a JsonDecodingException on
     * the phone for every call that has an analysis blob.
     *
     * The mocked contract fixture could not catch it: the generator's seed rows all had
     * `analysis: null`, so the field was never exercised. It was found by pointing the fixture
     * comparison at a REAL database-backed response, where it arrived as a dict. The fixture
     * now includes a populated analysis object precisely so this stays caught.
     */
    val analysis: CallAnalysis? = null,
    val transferStatus: String? = null,
    val transferReason: String? = null,
    /**
     * The other party's number, described: the caller on an inbound call, the number dialled on an
     * outbound one (where [from] is the workspace's own line). See [PhoneIntel].
     */
    val phoneIntel: PhoneIntel? = null,
)

/**
 * The post-call analysis blob.
 *
 * ⚠️ EVERY FIELD IS OPTIONAL WITH A DEFAULT, DELIBERATELY. `Call.analysis` is a Prisma `Json?`
 * column, so nothing in the database enforces this shape — the documented one is
 * `{ keyPoints, objections, topics, actionItems, followUpSuggested? }`, but rows written by an
 * earlier version of the pipeline may carry fewer keys. Defaults mean a partial object decodes
 * instead of crashing the calls list.
 *
 * ⚠️ Drift is caught by the CONTRACT TEST, not at runtime: that test decodes with
 * `ignoreUnknownKeys = false`, so a new key added server-side reds CI, while the production
 * parser stays lenient so an unexpected key never breaks a shipped app.
 */
@Serializable
data class CallAnalysis(
    val keyPoints: List<String> = emptyList(),
    val objections: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val followUpSuggested: Boolean? = null,
)

/**
 * The `followUp` object. ⚠️ Note the server key is `sms`, not `smsBody` — the underlying
 * column is `followUpSMSBody` and the handler renames it on the way out.
 */
@Serializable
data class CallFollowUp(
    val email: String? = null,
    @SerialName("sms")
    val sms: String? = null,
    /** ISO-8601 instant, or null. */
    val sentAt: String? = null,
)
