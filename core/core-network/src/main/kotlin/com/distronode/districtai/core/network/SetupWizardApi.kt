// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.SetupTestCallResponse
import kotlinx.serialization.json.JsonObject

/**
 * The setup wizard's writes and the reads its steps make beside [SetupApi.districtSetup].
 *
 * ⚠️ AN EXTENSION OF [SetupApi], not more methods on it: the overview's card reads one fact through
 * [SetupApi] and nothing else, and its fakes stay one method wide, while the wizard gets the read and
 * its writes through one seam.
 *
 * ⛔ OWNER ONLY, AND THE OWNER IS THE WORKSPACE'S `ownerId`, NOT A ROLE: every route under `setup`
 * answers a member or an admin with [ApiResult.Forbidden], and a workspace that predates the
 * wizard with 409 `setup_not_available`. [workspaceConnections] is the exception, open to every
 * member.
 *
 * ⚠️ THE FUNCTION NAMES ARE THE APPLE CLIENTS' `EndpointID` CASES, so the parity test pairs them
 * without a spelling delta.
 */
interface SetupWizardApi : SetupApi {

    /**
     * `PATCH /api/district/setup`: saves step states and business-facts sections, and answers the
     * whole setup state after the write (the same body as the GET).
     *
     * ⛔ STEP STATES ONLY IN `setupProgress`. The service strips anything else, so nothing here can
     * send the paid stamp, the consent record or the forwarding check.
     *
     * ⚠️ EACH `businessFacts` SECTION PRESENT REPLACES THAT SECTION WHOLE, a list is sent in full,
     * and an explicit JSON null clears it; an absent section is left alone.
     *
     * @param steps step name to state (`todo`, `done`, `skipped`); empty sends no `setupProgress`.
     */
    suspend fun districtSetupPatch(
        workspaceId: String,
        steps: Map<String, String>,
        businessFacts: JsonObject?,
    ): ApiResult<DistrictSetupResponse>

    /**
     * `POST /api/district/setup/lookup`: drafts the business's facts from its own website, in
     * `locale` (`"fr"`, else English). Never writes. A page the service cannot read is 422
     * `website_unreadable`.
     */
    suspend fun setupLookup(workspaceId: String, websiteUrl: String, locale: String): ApiResult<SetupLookupResponse>

    /**
     * `POST /api/district/setup/test-call`: has the receptionist call `phone` (E.164).
     *
     * ⛔ THE CONSENT IS RECORDED BY THE SERVICE, with its own wording for the owner's locale, at this
     * call; there is nothing to send for it.
     */
    suspend fun setupTestCall(workspaceId: String, phone: String): ApiResult<SetupTestCallResponse>

    /** `GET /api/district/setup/forwarding-check`: the latest check and how many are left today. */
    suspend fun forwardingCheck(workspaceId: String): ApiResult<ForwardingCheckResponse>

    /**
     * `POST /api/district/setup/forwarding-check`: places a check call to the business line (E.164)
     * and answers 202 with the pending check.
     */
    suspend fun forwardingCheckStart(
        workspaceId: String,
        businessLine: String,
        mode: String,
    ): ApiResult<ForwardingCheckResponse>

    /** `GET /api/district/connections`: the connection rows, and for a non-viewer which calendar is in use. */
    suspend fun workspaceConnections(workspaceId: String): ApiResult<ConnectionsResponse>
}
