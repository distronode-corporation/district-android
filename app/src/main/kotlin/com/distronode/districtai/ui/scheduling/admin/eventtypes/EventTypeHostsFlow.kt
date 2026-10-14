package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingEventTypeChanges
import com.distronode.districtai.core.data.SchedulingHostAssignment
import com.distronode.districtai.core.data.eventTypeHosts
import com.distronode.districtai.core.data.patchEventType
import com.distronode.districtai.core.data.putEventTypeHosts
import com.distronode.districtai.core.data.schedulerUsers
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Who can be booked on an event type, and how the next booking is routed to them; ported from
 * district-ios's `SchedulingEventTypeHostsModel`.
 *
 * ⛔ `eventTypes.hosts.put` IS A FULL REPLACEMENT, so the list is built only from a read this dialog
 * made itself, and Save is refused until that read is in hand: a list it never saw would delete the
 * hosts on it.
 *
 * ⛔ THE SAVE IS TWO OPS, HOSTS FIRST, as the web does: the put, then a patch of `routing_mode` and
 * `rr_strategy`. Patching first would leave a round-robin event type pointing at a host list that had
 * not arrived. ⚠️ A failed patch after a put that landed is reported, not rolled back.
 *
 * ⛔ THE LAST HOST CAN BE TAKEN OFF THE LIST AND THE SAVE THEN REFUSES IT, which is the reference's
 * arrangement: swapping the only host for another passes through zero rows, and the schema's
 * `.min(1)` holds at the wire either way.
 */
class EventTypeHostsFlow(
    private val host: EventTypeDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: () -> Unit,
) {
    private val kind = EventTypeDialog.Hosts::class

    /** ⚠️ An unset mode reads as `fixed` and an unset strategy as `even`, the fork's defaults. */
    fun open(row: SchedulingEventType) {
        host.show(
            EventTypeDialog.Hosts(
                slug = row.slug,
                routingMode = row.routingMode ?: DEFAULT_ROUTING,
                rotationStrategy = row.rrStrategy ?: DEFAULT_ROTATION,
            ),
        )
        load()
    }

    /**
     * Read the hosts, then the scheduler users who could be added. ⛔ A refused directory read is not
     * a failed dialog: `users.list` is admin-only, so the list stays editable with no add control.
     */
    fun load() {
        val dialog = host.current as? EventTypeDialog.Hosts ?: return
        host.show(dialog.copy(load = SectionState.Loading))
        scope.launch {
            when (val hosts = admin.eventTypeHosts(workspaceId, dialog.slug)) {
                is SchedulingAdminResult.Failed -> host.update(kind, dialog.slug) {
                    it.copy(load = SectionState.Failed(hosts.error.toFailureText()))
                }
                is SchedulingAdminResult.Ok -> {
                    val rows = hosts.value.map(SchedulingHostRow::from)
                    val users = admin.schedulerUsers(workspaceId).valueOrNull()
                    val assigned = rows.map { it.userId }.toSet()
                    host.update(kind, dialog.slug) {
                        it.copy(
                            load = SectionState.Ready(Unit),
                            rows = rows,
                            candidates = users.orEmpty().filter { user -> !user.archived && user.id !in assigned },
                            canAddHosts = users != null,
                        )
                    }
                }
            }
        }
    }

    /** Add the candidate [userId] as a required host with priority 0. */
    fun add(userId: String) = edit { dialog ->
        val user = dialog.candidates.firstOrNull { it.id == userId }
        if (user == null) {
            dialog
        } else {
            dialog.copy(rows = dialog.rows + SchedulingHostRow.added(user), candidates = dialog.candidates - user)
        }
    }

    fun remove(userId: String) = edit { it.copy(rows = it.rows.filterNot { row -> row.userId == userId }) }

    /** Change one row's role or priority. */
    fun editRow(userId: String, change: (SchedulingHostRow) -> SchedulingHostRow) =
        edit { dialog -> dialog.copy(rows = dialog.rows.map { if (it.userId == userId) change(it) else it }) }

    fun setRouting(mode: String) = edit { it.copy(routingMode = mode) }

    fun setRotation(strategy: String) = edit { it.copy(rotationStrategy = strategy) }

    fun save() {
        val dialog = (host.current as? EventTypeDialog.Hosts)?.takeUnless { it.state.isWorking } ?: return
        if (dialog.load !is SectionState.Ready) return
        val assignments = dialog.rows.mapNotNull { row ->
            SchedulingEventTypeForm.whole(row.priority, atLeast = 0)?.let {
                SchedulingHostAssignment(row.userId, row.role, it)
            }
        }
        val refusal = when {
            dialog.rows.isEmpty() -> R.string.scheduling_admin_event_types_hosts_need_one
            assignments.size != dialog.rows.size -> R.string.scheduling_admin_event_types_host_priority_invalid
            else -> null
        }
        if (refusal != null) {
            host.show(dialog.copy(validation = refusal))
            return
        }
        host.show(dialog.copy(validation = null, state = WriteState.Working))
        scope.launch {
            when (val result = put(dialog, assignments)) {
                is SchedulingAdminResult.Ok -> {
                    host.update(kind, dialog.slug) { null }
                    onSaved()
                }
                is SchedulingAdminResult.Failed ->
                    host.update(kind, dialog.slug) { it.copy(state = result.toWriteState()) }
            }
        }
    }

    private suspend fun put(
        dialog: EventTypeDialog.Hosts,
        assignments: List<SchedulingHostAssignment>,
    ): SchedulingAdminResult<*> {
        val put = admin.putEventTypeHosts(workspaceId, dialog.slug, assignments)
        if (put is SchedulingAdminResult.Failed) return put
        val routing = SchedulingEventTypeChanges(routingMode = dialog.routingMode, rrStrategy = dialog.rotationStrategy)
        return admin.patchEventType(workspaceId, dialog.slug, routing)
    }

    /** Change the open hosts dialog, unless a save is on its way. */
    private fun edit(change: (EventTypeDialog.Hosts) -> EventTypeDialog.Hosts) {
        val dialog = (host.current as? EventTypeDialog.Hosts)?.takeUnless { it.state.isWorking } ?: return
        host.show(change(dialog).copy(validation = null, state = clearedFailure(dialog.state)))
    }

    companion object {
        const val DEFAULT_ROUTING: String = "fixed"
        const val DEFAULT_ROTATION: String = "even"

        /** The routing mode under which the rotation strategy means something. */
        const val ROUND_ROBIN: String = "round_robin"
    }
}
