// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.PersonaVoiceGroup
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.defaultVoice
import com.distronode.districtai.core.model.voiceExists
import com.distronode.districtai.core.model.voiceGroups
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.launch

/** A step that reads before it shows a form. */
sealed interface SetupStepLoad {
    data object Loading : SetupStepLoad

    data object Ready : SetupStepLoad

    data class Failed(val failure: FailureText) : SetupStepLoad
}

/**
 * Step 3, "Your receptionist": a name, a greeting and a voice, saved into the persona.
 *
 * ⛔ ONLY WHAT THIS STEP OWNS IS SENT: name, greeting, language and (when offered) voice. The persona
 * route merges per field, so every other key is left as it is.
 *
 * ⛔ THE VOICES COME FROM THE SERVICE'S OPTIONS FOR THE PERSONA'S OWN ENGINE AND LANGUAGE, never from a
 * list kept here: the persona route coerces rather than rejects, so a voice typed by hand would be
 * stored and then silently replaced. With no engine recorded, or no voices for it, the step offers
 * none and leaves the voice alone.
 *
 * ⚠️ THE STARTER GREETING IS REPLACED BY THE BUSINESS'S OWN; A GREETING THE OWNER WROTE IS KEPT. A
 * greeting is the starter one when it is empty, one of the two provisioned starter sentences, or this
 * step's own template for the stored name.
 */
class SetupReceptionistModel(private val wizard: SetupWizardViewModel) : SetupStepModel {

    var load: SetupStepLoad by mutableStateOf(SetupStepLoad.Loading)
        private set
    var name: String by mutableStateOf("")
        private set
    var greeting: String by mutableStateOf("")
        private set
    var voice: String? by mutableStateOf(null)
    var greetingEdited: Boolean by mutableStateOf(false)
        private set
    var voiceGroups: List<PersonaVoiceGroup> by mutableStateOf(emptyList())
        private set
    var showErrors: Boolean by mutableStateOf(false)
        private set
    var isSaving: Boolean by mutableStateOf(false)
        private set
    var saveError: FailureText? by mutableStateOf(null)
        private set

    private var storedLanguage: String? = null

    init {
        load()
    }

    val language: SetupLanguage get() = wizard.draft.setupLanguage

    /** The persona `language` the save writes. */
    val languageToSave: String get() = SetupLanguage.toSave(storedLanguage, language, wizard.appDefault)

    /** Europe requires the disclosure, so the step says so; elsewhere it is a settings choice. */
    val disclosureIsMandatory: Boolean get() = wizard.region == "eu"

    val nameMissing: Boolean get() = name.isBlank()
    val greetingMissing: Boolean get() = greeting.isBlank()

    /**
     * The stored persona and the options for its engine, read one after the other.
     *
     * ⛔ A FAILED CONFIG READ IS A RETRY AND NO FORM: a form built from nothing would save the starter
     * name over the owner's. A failed OPTIONS read only hides the voice picker.
     */
    fun load() {
        load = SetupStepLoad.Loading
        wizard.scope.launch {
            val config = wizard.deps.config.load(wizard.workspaceId)
            val options = wizard.deps.personaOptions.options(wizard.workspaceId)
            load = when (config) {
                is ApiResult.Success -> {
                    adopt(config.value.aiPersona, (options as? ApiResult.Success)?.value)
                    SetupStepLoad.Ready
                }
                is ApiResult.Failure -> SetupStepLoad.Failed(config.toFailureText())
            }
        }
    }

    private fun adopt(persona: AiPersona?, options: PersonaOptionsResponse?) {
        storedLanguage = persona?.language
        val storedName = persona?.name?.trim().orEmpty()
        name = storedName.ifEmpty { SetupWire.FALLBACK_NAME }
        val stored = persona?.greeting?.trim().orEmpty()
        if (isStarter(stored, name, wizard.draft.businessName)) {
            greeting = template(name)
            greetingEdited = false
        } else {
            greeting = stored
            greetingEdited = true
        }
        val engine = persona?.modelId
        val language = languageToSave
        voiceGroups = options?.voiceGroups(engine, language).orEmpty()
        val storedVoice = persona?.voice
        voice = if (options?.voiceExists(storedVoice, engine, language) == true) {
            storedVoice
        } else {
            options?.defaultVoice(engine, language) ?: voiceGroups.firstOrNull()?.options?.firstOrNull()?.value
        }
    }

    fun isStarter(greeting: String, name: String, business: String): Boolean =
        greeting.isEmpty() || greeting in SetupWire.starterGreetings ||
            SetupLanguage.entries.any { language ->
                greeting == SetupWire.greeting(business, name, language) ||
                    greeting == SetupWire.greeting("", name, language)
            }

    fun template(name: String): String =
        SetupWire.greeting(wizard.draft.businessName, name.trim().ifEmpty { SetupWire.FALLBACK_NAME }, language)

    /** A rename carries the untouched template with it; a greeting the owner wrote stays. */
    fun editName(next: String) {
        name = next
        if (!greetingEdited) greeting = template(next)
    }

    fun editGreeting(next: String) {
        if (next != greeting) greetingEdited = true
        greeting = next
    }

    fun save() {
        showErrors = true
        if (nameMissing || greetingMissing || isSaving) return
        isSaving = true
        wizard.beginStepWrite()
        saveError = null
        val language = languageToSave
        val request = PersonaPatchRequest(
            workspaceId = wizard.workspaceId,
            name = name.trim(),
            greeting = greeting.trim(),
            voice = if (voiceGroups.isEmpty()) null else voice,
            language = language,
        )
        wizard.scope.launch {
            try {
                // ⚠️ "SAVED, COULD NOT RE-READ" IS SAVED: the write landed, and the next step reads again.
                val outcome = wizard.deps.config.savePersona(request)
                if (outcome is SaveOutcome.NotSaved) {
                    saveError = outcome.failure.toFailureText()
                } else {
                    storedLanguage = language
                    wizard.complete(SetupStep.RECEPTIONIST, SETUP_STEP_DONE)
                }
            } finally {
                isSaving = false
                wizard.endStepWrite()
            }
        }
    }
}
