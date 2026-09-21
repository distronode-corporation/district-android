package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One connected calendar account.
 *
 * ⛔ [isDestination] AND [checkConflicts] ARE INDEPENDENT AND BOTH ARE REQUIRED. A connection can
 * be consulted for conflicts without being where new events are written, and exactly one
 * connection may be the destination. Defaulting either would let a screen offer to write into a
 * read-only account, or quietly stop consulting one the host is relying on.
 *
 * ⚠️ THIS WHOLE NAMESPACE IS `viewer`, INCLUDING ITS WRITES. These are the CALLER'S OWN calendar
 * connections, so a viewer connecting their own Google account is not a tenancy change — see
 * `SchedulingAdminOp.minRole` in core-network.
 */
@Serializable
data class SchedulingCalendarConnection(
    val id: String,
    val provider: String,
    @SerialName("account_email") val accountEmail: String,
    @SerialName("is_destination") val isDestination: Boolean,
    @SerialName("check_conflicts") val checkConflicts: Boolean,
)

/**
 * What `calendar.status` answers.
 *
 * ⛔ [connected] AND [configured] ARE DIFFERENT QUESTIONS AND COLLAPSING THEM SHOWS THE WRONG
 * BUTTON. `configured` is "the INSTANCE has credentials for at least one provider" — an operator
 * of the scheduler instance set those up and a tenant cannot. `connected` is "this CALLER has at
 * least one connection". Not configured means "your administrator has not enabled calendar
 * sync"; configured and not connected means "connect yours".
 *
 * ⚠️ [connections] IS REQUIRED AND MAY BE EMPTY; [providers] and [unconfiguredProviders] are
 * absent rather than empty when the instance has nothing to say.
 */
@Serializable
data class SchedulingCalendarStatus(
    val connected: Boolean,
    val configured: Boolean,
    val providers: List<String>? = null,
    val connections: List<SchedulingCalendarConnection>,
    @SerialName("unconfigured_providers") val unconfiguredProviders: List<String>? = null,
    /** ⚠️ The caller's PRIMARY provider, when they have one. Not the list above's first entry. */
    val provider: String? = null,
)

/**
 * The acknowledgement of `calendar.caldav.connect`.
 *
 * ⛔ NO CREDENTIAL COMES BACK, AND NOTHING HERE MAY GROW ONE. The op sends a username and an app
 * password; the answer is a flag and the address it resolved to. A `password` or `token` key
 * appearing on this shape would be a credential echoed into whatever logs the response.
 */
@Serializable
data class SchedulingCaldavConnection(
    val connected: Boolean,
    @SerialName("account_email") val accountEmail: String,
)

/**
 * One calendar inside a connection, as listed and as written back.
 *
 * ⛔ THE SAME TYPE TRAVELS IN BOTH DIRECTIONS, which is why the four flags are optional: the
 * listing sends them for a calendar the caller has already configured and OMITS them for one
 * they have not (the fixture's second row is exactly that), and the PUT sends only what it means
 * to change. `false` and "not stated" are different instructions to the fork, so a non-null
 * default here would silently turn every unconfigured calendar into an explicit refusal.
 */
@Serializable
data class SchedulingCalendarSelection(
    val id: String,
    val name: String,
    val primary: Boolean? = null,
    val writable: Boolean? = null,
    @SerialName("check_conflicts") val checkConflicts: Boolean? = null,
    @SerialName("is_destination") val isDestination: Boolean? = null,
)

/**
 * ⚠️ `calendar.connections.calendars.get` DECLARES ITS OWN `{calendars}` KEY rather than using the
 * catalog's shared [SchedulingItems] wrapper. Reading it through that type decodes nothing and
 * reports an empty list, which is a wrong answer rather than an error.
 */
@Serializable
data class SchedulingCalendarSelections(
    val calendars: List<SchedulingCalendarSelection>,
)

/**
 * `zoom.status`.
 *
 * ⚠️ THE SAME TWO-FLAG SHAPE AS [SchedulingCalendarStatus] AND THE SAME DISTINCTION: [configured]
 * is the instance's Zoom app, [connected] is this caller's account.
 */
@Serializable
data class SchedulingZoomStatus(
    val configured: Boolean,
    val connected: Boolean,
)
