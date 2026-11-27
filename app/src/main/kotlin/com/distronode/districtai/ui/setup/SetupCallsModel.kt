// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.launch

/**
 * Step 5, "Calls, people and bookings".
 *
 * ⛔ WHO ANSWERS IS SAVED HERE, through the call-handling route the Settings screen uses: `ai_first`,
 * or `app_first` with a 5 to 30 second ring. An `ai_then_app` chosen earlier in Settings is shown and
 * kept, never offered (the web's step 5 offers only the two). "Only when I don't pick up" is the
 * carrier's no-answer forward, set up in step 6, and is not a mode.
 *
 * ⛔ THE TRANSFER NUMBER AND THE CALENDAR ARE SHOWN HERE AND CHANGED ON THE WEB'S PAGES, IN A CUSTOM
 * TAB. The mobile tools write carries the allowlist alone (no transfer number), and connecting a
 * calendar is the provider's consent screen. The step reads both again when the person comes back
 * ([refreshAfterHandOff]).
 *
 * ⛔ CALLER PROFILES ARE AN OPT-IN THAT STARTS OFF: read strictly (only `true` is on), offered only on
 * the plans that include them (Voice Studio and DGI), and written only when the owner flips the switch.
 */
@Suppress("TooManyFunctions")
class SetupCallsModel(private val wizard: SetupWizardViewModel) : SetupStepModel {

    var load: SetupStepLoad by mutableStateOf(SetupStepLoad.Loading)
        private set
    var storedMode: String by mutableStateOf(CallHandling.AI_FIRST)
        private set
    var storedRing: Int by mutableIntStateOf(CallHandling.DEFAULT_RING_SECONDS)
        private set
    var mode: String by mutableStateOf(CallHandling.AI_FIRST)
        private set
    var ringSeconds: Int by mutableIntStateOf(CallHandling.DEFAULT_RING_SECONDS)
        private set
    var transferNumber: String? by mutableStateOf(null)
        private set
    var calendarName: String? by mutableStateOf(null)
        private set
    var profilesOffered: Boolean by mutableStateOf(false)
        private set
    var profilesOn: Boolean by mutableStateOf(false)
        private set
    var isSavingAnswering: Boolean by mutableStateOf(false)
        private set
    var isSavingProfiles: Boolean by mutableStateOf(false)
        private set
    var isContinuing: Boolean by mutableStateOf(false)
        private set
    var answeringSaved: Boolean by mutableStateOf(false)
        private set
    var error: FailureText? by mutableStateOf(null)
        private set

    init {
        load()
    }

    /** The modes offered: the web's two, and a stored third shown as itself. */
    val modes: List<String>
        get() = if (storedMode == CallHandling.AI_THEN_APP) {
            listOf(CallHandling.AI_FIRST, CallHandling.APP_FIRST, CallHandling.AI_THEN_APP)
        } else {
            listOf(CallHandling.AI_FIRST, CallHandling.APP_FIRST)
        }

    val answeringChanged: Boolean
        get() = mode != storedMode || (mode == CallHandling.APP_FIRST && ringSeconds != storedRing)

    fun selectMode(next: String) {
        mode = next
    }

    fun selectRing(seconds: Int) {
        ringSeconds = CallHandling.clampRing(seconds)
    }

    // ── Reading ─────────────────────────────────────────────────────────────────

    /**
     * ⛔ A FAILED CALL-HANDLING OR CONFIG READ IS A RETRY, NEVER A FORM OF DEFAULTS: "every call" shown
     * for a workspace that rings the app first would be saved back over it. The calendar read is the
     * one that may fail alone; it then says no calendar is known.
     */
    fun load() {
        load = SetupStepLoad.Loading
        error = null
        wizard.scope.launch {
            val handling = wizard.deps.callHandling.callHandling(wizard.workspaceId)
            val config = wizard.deps.config.load(wizard.workspaceId)
            val connections = wizard.deps.setup.connections(wizard.workspaceId)
            load = when {
                handling is ApiResult.Failure -> SetupStepLoad.Failed(handling.toFailureText())
                config is ApiResult.Failure -> SetupStepLoad.Failed(config.toFailureText())
                else -> {
                    val answering = (handling as ApiResult.Success).value
                    val stored = (config as ApiResult.Success).value
                    storedMode = answering.callHandling
                    storedRing = answering.appRingSeconds
                    mode = answering.callHandling
                    ringSeconds = answering.appRingSeconds
                    transferNumber = transferNumber(stored)
                    profilesOffered = profilesOffered(stored.subscriptionTier)
                    profilesOn = stored.aiPersona?.dgiEnabled == true
                    calendarName = (connections as? ApiResult.Success)?.value?.let(::calendarName)
                    SetupStepLoad.Ready
                }
            }
        }
    }

    /**
     * After a page opened in a Custom Tab: the transfer number and the calendar again, the two things
     * those pages change.
     *
     * ⚠️ NOT [load]: that resets an answering choice not yet saved, and shows the skeleton. A read that
     * fails keeps what is on screen, as the wizard's own re-read does.
     */
    suspend fun refreshAfterHandOff() {
        if (load != SetupStepLoad.Ready) return
        val config = wizard.deps.config.load(wizard.workspaceId)
        if (config is ApiResult.Success) transferNumber = transferNumber(config.value)
        val connections = wizard.deps.setup.connections(wizard.workspaceId)
        if (connections is ApiResult.Success) calendarName = calendarName(connections.value)
    }

    // ── Writing ─────────────────────────────────────────────────────────────────

    /**
     * Any of the step's three writes in flight.
     *
     * ⛔ ONE WRITE AT A TIME, across all three: they share [error], so an overlap would let one answer
     * hide the other's refusal.
     */
    private val isWriting: Boolean get() = isSavingAnswering || isSavingProfiles || isContinuing

    /** Saves who answers; only the fields that changed are sent. */
    fun saveAnswering() {
        if (!answeringChanged || isWriting) return
        isSavingAnswering = true
        wizard.beginStepWrite()
        wizard.scope.launch { writeAnswering() }
    }

    /** The write behind [saveAnswering]; the caller has already set [isSavingAnswering] and begun a step write. */
    private suspend fun writeAnswering() {
        try {
            error = null
            answeringSaved = false
            val result = wizard.deps.callHandling.saveCallHandling(
                workspaceId = wizard.workspaceId,
                callHandling = if (mode == storedMode) null else mode,
                appRingSeconds = if (mode == CallHandling.APP_FIRST && ringSeconds != storedRing) {
                    CallHandling.clampRing(ringSeconds)
                } else {
                    null
                },
            )
            when (result) {
                is ApiResult.Success -> {
                    // ⛔ THE ECHO IS THE NEW BASELINE, as on the Settings screen.
                    storedMode = result.value.callHandling
                    storedRing = result.value.appRingSeconds
                    mode = result.value.callHandling
                    ringSeconds = result.value.appRingSeconds
                    answeringSaved = true
                }
                is ApiResult.Failure -> error = result.toFailureText()
            }
        } finally {
            isSavingAnswering = false
            wizard.endStepWrite()
        }
    }

    /**
     * ⚠️ ONLY THE FLIP IS SENT. The persona route answers `dgi_requires_studio` for a plan without it,
     * which is why the switch is only drawn on the plans that have it.
     */
    fun setProfiles(on: Boolean) {
        if (!profilesOffered || on == profilesOn || isWriting) return
        isSavingProfiles = true
        wizard.beginStepWrite()
        error = null
        wizard.scope.launch {
            try {
                val outcome = wizard.deps.config.savePersona(PersonaPatchRequest(wizard.workspaceId, dgiEnabled = on))
                if (outcome is SaveOutcome.NotSaved) error = outcome.failure.toFailureText() else profilesOn = on
            } finally {
                isSavingProfiles = false
                wizard.endStepWrite()
            }
        }
    }

    /** Saves an unsaved answering choice first, so Continue never drops one. */
    fun saveAndContinue() {
        if (isWriting) return
        isContinuing = true
        wizard.beginStepWrite()
        wizard.scope.launch {
            try {
                if (answeringChanged) {
                    isSavingAnswering = true
                    wizard.beginStepWrite()
                    writeAnswering()
                }
                if (!answeringChanged) wizard.complete(SetupStep.CALLS, SETUP_STEP_DONE)
            } finally {
                isContinuing = false
                wizard.endStepWrite()
            }
        }
    }

    companion object {
        fun transferNumber(config: WorkspaceConfig): String? =
            config.toolConfig?.supportPhoneNumber?.takeIf { it.isNotBlank() }

        fun profilesOffered(tier: String?): Boolean = tier.orEmpty().lowercase() in PROFILE_TIERS

        /**
         * The calendar the receptionist books into: a provider whose row is `active` and which the
         * service names as the one in use.
         *
         * ⚠️ PRODUCT NAMES, NOT COPY, SO NOT IN THE CATALOG: translating one would name a product that
         * does not exist.
         */
        fun calendarName(response: ConnectionsResponse): String? = CALENDARS.firstOrNull { calendar ->
            response.connections.firstOrNull { it.provider == calendar.connection }?.status == ACTIVE &&
                response.calendar?.activeProvider == calendar.id
        }?.name

        private data class Calendar(val id: String, val connection: String, val name: String)

        private val CALENDARS = listOf(
            Calendar("google", "google-calendar", "Google Calendar"),
            Calendar("microsoft", "microsoft-calendar", "Microsoft Outlook Calendar"),
        )
        private const val ACTIVE = "active"
        private val PROFILE_TIERS = setOf("voicestudio", "dgi")
    }
}
