// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.data.CallHandlingRepository
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.data.SetupWizardRepository
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.SETUP_STEP_TODO
import com.distronode.districtai.core.model.SetupProgress
import com.distronode.districtai.core.model.SetupSteps
import com.distronode.districtai.core.model.ToolConfig
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeCallHandlingApi
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakePersonaApi
import com.distronode.districtai.core.network.testing.FakeSetupWizardApi
import com.distronode.districtai.l10n.AppLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/** The step 1 draft store, in memory. */
class InMemorySetupDraftStore : SetupDraftStore {
    val drafts = mutableMapOf<String, SetupBusinessDraft>()

    override fun load(workspaceId: String): SetupBusinessDraft? = drafts[workspaceId]

    override fun save(draft: SetupBusinessDraft, workspaceId: String) {
        drafts[workspaceId] = draft
    }

    override fun clear(workspaceId: String) {
        drafts.remove(workspaceId)
    }
}

/** The district fake with its config read held until [gate] completes, for a step still reading. */
class GatedDistrictApi : FakeDistrictApi() {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun workspaceConfig(workspaceId: String): ApiResult<WorkspaceConfigResponse> {
        gate?.await()
        return super.workspaceConfig(workspaceId)
    }
}

/** Every fake the wizard reads and writes through, and a wizard built on them. */
class SetupHarness {
    val setup = FakeSetupWizardApi()
    val district = GatedDistrictApi()
    val persona = FakePersonaApi()
    val callHandling = FakeCallHandlingApi()
    val store = InMemorySetupDraftStore()

    val deps = SetupWizardDeps(
        setup = SetupWizardRepository(setup),
        config = WorkspaceConfigRepository(district),
        personaOptions = PersonaOptionsRepository(persona),
        callHandling = CallHandlingRepository(callHandling),
        members = MembersRepository(district),
        store = store,
    )

    fun wizard(
        name: String = "Clinique Lumière",
        language: AppLanguage = AppLanguage.EN,
        timing: SetupTiming = SetupTiming(pollIntervalMillis = 1_000, pollLimit = 3),
    ) = SetupWizardViewModel(deps, WORKSPACE, name, language, timing)

    /** The setup read answers [response] from now on. */
    fun reads(response: DistrictSetupResponse) {
        setup.setupResult = ApiResult.Success(response)
    }

    /** Every step write answers [response]. */
    fun writes(response: DistrictSetupResponse) {
        setup.patchResult = ApiResult.Success(response)
    }

    fun config(config: WorkspaceConfig) {
        district.workspaceConfigResult = ApiResult.Success(WorkspaceConfigResponse(success = true, config = config))
    }

    fun handling(mode: String, ring: Int = 20) {
        callHandling.handlingResult = ApiResult.Success(
            CallHandlingResponse(success = true, callHandling = mode, appRingSeconds = ring),
        )
    }

    companion object {
        const val WORKSPACE = "ws-setup"

        fun persona(
            name: String? = null,
            greeting: String? = null,
            language: String? = null,
            voice: String? = null,
            modelId: String? = null,
            dgiEnabled: Boolean? = null,
        ) = WorkspaceConfig(
            aiPersona = AiPersona(
                name = name,
                greeting = greeting,
                language = language,
                voice = voice,
                modelId = modelId,
                dgiEnabled = dgiEnabled,
            ),
        )

        fun withTransfer(config: WorkspaceConfig, number: String?, tier: String? = null) =
            config.copy(toolConfig = ToolConfig(supportPhoneNumber = number), subscriptionTier = tier)
    }
}

/** A setup state with these step states, in the wizard's order, and nothing else unless given. */
@Suppress("LongParameterList")
fun setupState(
    business: String = SETUP_STEP_TODO,
    number: String = SETUP_STEP_TODO,
    receptionist: String = SETUP_STEP_TODO,
    callers: String = SETUP_STEP_TODO,
    calls: String = SETUP_STEP_TODO,
    golive: String = SETUP_STEP_TODO,
    completedAt: String? = null,
    facts: String? = null,
    region: String? = "ca",
    numbersHeld: Int = 1,
    includedNumbers: Int? = 3,
) = DistrictSetupResponse(
    setupProgress = SetupProgress(
        steps = SetupSteps(business, number, receptionist, callers, calls, golive),
        completedAt = completedAt,
    ),
    businessFacts = facts?.let(::json),
    region = region,
    numbersHeld = numbersHeld,
    includedNumbers = includedNumbers,
)

fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
