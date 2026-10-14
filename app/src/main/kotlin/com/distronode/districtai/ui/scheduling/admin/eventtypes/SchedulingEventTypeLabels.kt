package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusLabel
import com.distronode.districtai.ui.scheduling.admin.schedulingBookingUrl
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue

// The event types' wire vocabularies and how each value reads.
//
// ⚠️ NONE OF THESE IS IN contracts/i18n/wire-vocabulary.json YET (host role, routing mode, rotation
// strategy, question type, test-email type, location type), so each is labelled from this catalog
// with the value as sent for one this build does not know, the way SchedulingLabels.kt does.
// ⛔ The wire values are the catalog's (`admin-ops.ts`), in the web's order, never derived from a label.

/** A host's routing role on one event type. */
val SCHEDULING_HOST_ROLES: List<Pair<String, Int>> = listOf(
    "required" to R.string.scheduling_admin_event_types_role_required,
    "rotation" to R.string.scheduling_admin_event_types_role_rotation,
    "optional" to R.string.scheduling_admin_event_types_role_optional,
)

/** How a booking is given to the hosts (`routing_mode`). */
val SCHEDULING_ROUTING_MODES: List<Pair<String, Int>> = listOf(
    "fixed" to R.string.scheduling_admin_event_types_routing_fixed,
    "round_robin" to R.string.scheduling_admin_event_types_routing_round_robin,
    "collective" to R.string.scheduling_admin_event_types_routing_collective,
)

/** Which round-robin host the next booking goes to (`rr_strategy`). */
val SCHEDULING_ROTATION_STRATEGIES: List<Pair<String, Int>> = listOf(
    "even" to R.string.scheduling_admin_event_types_rotation_even,
    "soonest" to R.string.scheduling_admin_event_types_rotation_soonest,
    "priority" to R.string.scheduling_admin_event_types_rotation_priority,
)

/** A booking question's type. */
val SCHEDULING_QUESTION_TYPES: List<Pair<String, Int>> = listOf(
    SchedulingQuestionForm.TYPE_TEXT to R.string.scheduling_admin_event_types_question_type_text,
    SchedulingQuestionForm.TYPE_SELECT to R.string.scheduling_admin_event_types_question_type_select,
    "checkbox" to R.string.scheduling_admin_event_types_question_type_checkbox,
)

/** The four templates `eventTypes.testEmail` accepts. */
val SCHEDULING_EMAIL_TEMPLATES: List<Pair<String, Int>> = listOf(
    "confirmation" to R.string.scheduling_admin_event_types_email_confirmation,
    "cancellation" to R.string.scheduling_admin_event_types_email_cancellation,
    "reschedule" to R.string.scheduling_admin_event_types_email_reschedule,
    "reminder" to R.string.scheduling_admin_event_types_email_reminder,
)

/** [value]'s label in [vocabulary], or the value as sent. */
@Composable
@ReadOnlyComposable
fun schedulingWireLabel(value: String, vocabulary: List<Pair<String, Int>>): String {
    val label = vocabulary.firstOrNull { it.first == value }?.second ?: return schedulingUnknownValue(value)
    return stringResource(label)
}

/**
 * A location type. ⛔ "Not set" ONLY FOR AN ABSENT VALUE; an unknown one is echoed, because a kind
 * the fork added since this build is still meaningful to an operator.
 */
@Composable
@ReadOnlyComposable
fun schedulingLocationLabel(wire: String?): String {
    if (wire.isNullOrEmpty()) return stringResource(R.string.scheduling_admin_event_types_location_not_set)
    val known = SchedulingLocationType.known(wire) ?: return schedulingUnknownValue(wire)
    return stringResource(known.label)
}

/**
 * Active, Inactive or Archived. ⛔ ARCHIVED WINS OVER INACTIVE: an archived event type is also
 * inactive, and it is the archive that explains why and that somebody has to undo.
 */
@Composable
@ReadOnlyComposable
fun schedulingEventTypeState(item: SchedulingEventType): SchedulingStatusLabel = when {
    item.archived == true -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_event_types_state_archived),
        SchedulingStatusKind.INFO,
    )
    item.isActive == false -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_event_types_state_inactive),
        SchedulingStatusKind.STOPPED,
    )
    else -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_event_types_state_active),
        SchedulingStatusKind.SUCCESS,
    )
}

/**
 * The row's public booking address, or null for "Not public".
 *
 * ⛔ NULL WHENEVER THE HOST IS UNKNOWN OR THE ROW IS NOT LIVE (archived, inactive or private), and
 * never a fabricated URL: an address for a page that does not answer is the one mistake a customer
 * would discover.
 */
fun schedulingBookingPage(item: SchedulingEventType, publicHost: String?): String? {
    val live = item.archived != true && item.isActive != false && item.isPublic != false
    return if (publicHost != null && live) schedulingBookingUrl(publicHost, item.slug) else null
}
