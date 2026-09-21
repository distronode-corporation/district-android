package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/messages/unread-count`.
 *
 * The envelope-shaped counterpart to [CallSummary]'s bare array — included in the
 * contract harness from the start precisely so both response shapes the API actually
 * uses are pinned by a fixture.
 *
 * ⚠️ `workspaceId` comes back even though the caller may not have sent one. That is
 * deliberate server-side: a param-less caller (the web sidebar) uses the response to
 * learn which workspace it was resolved to, because that id keys its telemetry-socket
 * subscription. The Android client needs the same value for the same reason.
 */
@Serializable
data class UnreadCountResponse(
    val success: Boolean,
    val count: Int,
    val workspaceId: String,
)
