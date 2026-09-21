package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One call recording held by the tenancy.
 *
 * ⛔ TWO REQUIRED FIELDS, BECAUSE A FAILED RECORDING HAS NOTHING ELSE. The fixture's second row is
 * `{"id":…,"status":"failed"}` and nothing more — no room, no booking, no duration — so anything
 * else made required would refuse the row an operator most needs to see.
 *
 * ⛔ [hasFile] IS NOT `status == "ready"` AND MUST NOT BE DERIVED FROM IT. A recording can be
 * marked ready with its object already deleted by the retention job; the download 404s and the
 * row still reads ready. A play button drawn from the status alone is a button that fails.
 *
 * ⚠️ LISTING A RECORDING IS `viewer` AND DOWNLOADING ONE IS NOT. `recordings.list` admits a
 * viewer so they can see that a conversation exists; the download route is `agency`/`client` so
 * they cannot take a copy of a customer conversation away. A UI that draws the row from this list
 * must not assume the download beside it will answer.
 */
@Serializable
data class SchedulingRecording(
    val id: String,
    @SerialName("booking_id") val bookingId: String? = null,
    val room: String? = null,
    val status: String,
    @SerialName("duration_s") val durationS: Int? = null,
    @SerialName("has_file") val hasFile: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("booker_name") val bookerName: String? = null,
)

/**
 * ⚠️ `recordings.list` DECLARES ITS OWN `{recordings}` KEY rather than using the catalog's shared
 * [SchedulingItems] wrapper. Reading it through that type decodes nothing and reports an empty
 * list — a wrong answer rather than an error.
 */
@Serializable
data class SchedulingRecordingList(
    val recordings: List<SchedulingRecording>,
)

/**
 * The tally from `recordings.deleteAll`.
 *
 * ⛔ [failed] IS NOT A FAILURE OF THE OP. The op succeeded; some objects could not be removed from
 * storage, and the rows for them survive so the next run retries. A screen that reported the whole
 * operation as failed because this is non-zero would tell an operator nothing was deleted when
 * [deleted] of them were.
 */
@Serializable
data class SchedulingRecordingsDeleted(
    val deleted: Int,
    val failed: Int,
)

/**
 * One participant's recording consent.
 *
 * ⛔ [decision] IS THREE-VALUED AND `pending` IS NOT `denied`. The fixture's second row is a guest
 * who was asked and has not answered; rendering that as a refusal misreports a legal position, and
 * rendering it as consent is worse.
 *
 * ⚠️ [name] AND [decidedAt] ARE ABSENT ON A PENDING ROW, which is why neither is required.
 */
@Serializable
data class SchedulingRecordingConsent(
    val identity: String,
    val name: String? = null,
    val decision: String,
    @SerialName("decided_at") val decidedAt: String? = null,
)

/**
 * ⚠️ `recordings.consent` DECLARES ITS OWN `{consents}` KEY, the second of the four ops that do
 * not use the catalog's shared [SchedulingItems] wrapper.
 */
@Serializable
data class SchedulingRecordingConsents(
    val consents: List<SchedulingRecordingConsent>,
)
