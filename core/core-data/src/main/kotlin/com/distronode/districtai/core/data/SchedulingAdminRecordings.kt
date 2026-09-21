package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingRecording
import com.distronode.districtai.core.model.SchedulingRecordingConsent
import com.distronode.districtai.core.model.SchedulingRecordingConsents
import com.distronode.districtai.core.model.SchedulingRecordingList
import com.distronode.districtai.core.model.SchedulingRecordingsDeleted
import com.distronode.districtai.core.network.SchedulingAdminOp

/*
 * The four `recordings.*` ops, typed.
 *
 * ⛔ LISTING IS `viewer` AND DOWNLOADING IS NOT. A viewer may see that a recording exists and may
 * not take a copy of a customer conversation away — the download lives on
 * `SchedulingAdminMediaRepository` and is `agency`/`client` at the route. A UI that draws a play
 * button from a row in this list must not assume it will answer.
 *
 * ⛔ AND NEITHER OF THESE TWO LISTS USES THE CATALOG'S SHARED `{items}` WRAPPER: `recordings.list`
 * declares `{recordings}` and `recordings.consent` declares `{consents}`, both by hand. Reading
 * either through `SchedulingItems` decodes nothing and reports an EMPTY list — a wrong answer
 * rather than an error, which is why this is stated twice on this surface.
 */

suspend fun SchedulingAdminRepository.recordings(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingRecording>> = perform(
    SchedulingAdminOp.RECORDINGS_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingRecordingList.serializer(),
).map { it.recordings }

suspend fun SchedulingAdminRepository.deleteRecording(
    workspaceId: String,
    recordingId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.RECORDINGS_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(recordingId)),
    SchedulingNoContent.serializer(),
)

/**
 * ⛔ NO ARGUMENTS AND NO CONFIRMATION IN THE PROTOCOL. It removes EVERY recording the tenancy
 * holds; the only thing standing between a misplaced tap and a customer's whole call archive is
 * the screen. ⚠️ A non-zero `failed` in the answer is not a failure of the op — see
 * [SchedulingRecordingsDeleted].
 */
suspend fun SchedulingAdminRepository.deleteAllRecordings(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingRecordingsDeleted> = perform(
    SchedulingAdminOp.RECORDINGS_DELETE_ALL,
    workspaceId,
    schedulingParams(),
    SchedulingRecordingsDeleted.serializer(),
)

suspend fun SchedulingAdminRepository.recordingConsents(
    workspaceId: String,
    recordingId: String,
): SchedulingAdminOutcome<List<SchedulingRecordingConsent>> = perform(
    SchedulingAdminOp.RECORDINGS_CONSENT,
    workspaceId,
    schedulingParams("id" to textParam(recordingId)),
    SchedulingRecordingConsents.serializer(),
).map { it.consents }
