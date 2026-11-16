package com.distronode.districtai.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Step 4, "What callers should know": the time zone, the address, the opening hours, the services and
 * the common questions the receptionist answers from.
 *
 * ⛔ A DRAFTED ITEM ARRIVES SWITCHED OFF AND STAYS OFF UNTIL THE OWNER SWITCHES IT ON (the website read
 * produces drafts, never facts; only enabled items reach the agent). An item the owner adds by hand
 * starts on, because typing one is the confirmation.
 *
 * ⛔ HOURS FROM THE WEBSITE ARE A DRAFT UNTIL THE OWNER USES THEM. "Fill from my website" puts the
 * lookup's hours on screen only; a save leaves `weeklyHours` out while that draft is unconfirmed, and
 * Continue refuses to move on past it rather than drop it silently. "Use these hours", or editing any
 * day, confirms it; "Discard" puts back what was there.
 *
 * ⚠️ SAVED ON SAVE AND ON CONTINUE, NOT ON EVERY KEYSTROKE as the web's autosave does. Every section
 * present replaces the stored one whole, so each save sends all five, and an empty section is sent as
 * null (a clear).
 */
@Suppress("TooManyFunctions")
class SetupCallersModel(private val wizard: SetupWizardViewModel) : SetupStepModel {

    private val facts: JsonObject? = wizard.data?.businessFacts

    var timezone: String by mutableStateOf(facts?.str("timezone").orEmpty())
    var address: SetupAddressDraft by mutableStateOf(SetupFacts.address(facts))
    var hours: Map<SetupWeekday, SetupDayDraft> by mutableStateOf(SetupFacts.hours(facts?.obj("weeklyHours")))
        private set
    var services: List<SetupServiceDraft> by mutableStateOf(SetupFacts.services(facts))
        private set
    var faqs: List<SetupFaqDraft> by mutableStateOf(SetupFacts.faqs(facts))
        private set
    var websiteUrl: String by mutableStateOf("")

    var hoursDraftPending: Boolean by mutableStateOf(false)
        private set
    var hoursNotice: UiText? by mutableStateOf(null)
        private set
    var isReading: Boolean by mutableStateOf(false)
        private set
    var isSaving: Boolean by mutableStateOf(false)
        private set
    var saved: Boolean by mutableStateOf(false)
        private set
    var showErrors: Boolean by mutableStateOf(false)
        private set
    var saveError: UiText? by mutableStateOf(null)
        private set

    private companion object {
        /** A period added to a day with none starts an hour after nine. */
        const val DAY_START = 9 * SetupInterval.MINUTES_PER_HOUR

        /** The latest an added period starts, so it still fits before midnight. */
        const val LAST_START = 23 * SetupInterval.MINUTES_PER_HOUR
    }

    private var hoursBeforeDraft: Map<SetupWeekday, SetupDayDraft> = emptyMap()

    /** The zones this business's country offers, with the stored one. */
    val zones: List<String>
        get() {
            val country = address.country.ifEmpty { wizard.draft.country }.uppercase()
            return SetupPlaces.zones(country, timezone)
        }

    val hasDrafts: Boolean get() = services.any { !it.enabled } || faqs.any { !it.enabled }

    val isValid: Boolean
        get() = SetupFacts.isValid(address) &&
            hours.values.all(SetupFacts::isValid) &&
            services.all(SetupFacts::isValid) &&
            faqs.all(SetupFacts::isValid)

    // ── Editing ─────────────────────────────────────────────────────────────────

    /** ⚠️ ANY EDIT TO THE HOURS CONFIRMS A DRAFT FROM THE WEBSITE, as "Use these hours" does. */
    private fun editHours(next: Map<SetupWeekday, SetupDayDraft>) {
        hours = next
        hoursDraftPending = false
    }

    fun setState(state: SetupDayState, day: SetupWeekday) {
        val draft = day(day)
        val intervals = if (state == SetupDayState.OPEN && draft.intervals.isEmpty()) {
            listOf(SetupInterval.standard())
        } else {
            draft.intervals
        }
        editHours(hours + (day to draft.copy(state = state, intervals = intervals)))
    }

    fun day(day: SetupWeekday): SetupDayDraft = hours[day] ?: SetupDayDraft(SetupDayState.NOT_SET)

    fun addPeriod(day: SetupWeekday) {
        val draft = day(day)
        if (draft.intervals.size >= SetupFacts.INTERVALS_PER_DAY) return
        val hour = SetupInterval.MINUTES_PER_HOUR
        val start = minOf((draft.intervals.maxOfOrNull { it.close } ?: DAY_START) + hour, LAST_START)
        val added = SetupInterval(open = start, close = minOf(start + hour, SetupInterval.MIDNIGHT))
        editHours(hours + (day to draft.copy(intervals = draft.intervals + added)))
    }

    fun removePeriod(id: Long, day: SetupWeekday) {
        val draft = day(day)
        val left = draft.intervals.filterNot { it.id == id }
        val state = if (left.isEmpty()) SetupDayState.NOT_SET else draft.state
        editHours(hours + (day to SetupDayDraft(state, left)))
    }

    fun setPeriod(day: SetupWeekday, id: Long, open: Int? = null, close: Int? = null) {
        val draft = day(day)
        val next = draft.intervals.map { interval ->
            if (interval.id == id) {
                interval.copy(open = open ?: interval.open, close = close ?: interval.close)
            } else {
                interval
            }
        }
        editHours(hours + (day to draft.copy(intervals = next)))
    }

    fun addService() {
        if (services.size >= SetupFacts.SERVICES) return
        services = services + SetupServiceDraft(name = "", price = "", enabled = true)
    }

    fun updateService(id: Long, change: (SetupServiceDraft) -> SetupServiceDraft) {
        services = services.map { if (it.id == id) change(it) else it }
    }

    fun removeService(id: Long) {
        services = services.filterNot { it.id == id }
    }

    fun addFaq() {
        if (faqs.size >= SetupFacts.FAQS) return
        faqs = faqs + SetupFaqDraft(question = "", answer = "", enabled = true)
    }

    fun updateFaq(id: Long, change: (SetupFaqDraft) -> SetupFaqDraft) {
        faqs = faqs.map { if (it.id == id) change(it) else it }
    }

    fun removeFaq(id: Long) {
        faqs = faqs.filterNot { it.id == id }
    }

    // ── Hours from the website ──────────────────────────────────────────────────

    fun fillHoursFromWebsite() {
        val url = websiteUrl.trim()
        if (isReading || url.isEmpty()) return
        isReading = true
        hoursNotice = null
        val locale = if (wizard.appLanguage == AppLanguage.FR) "fr" else "en"
        wizard.scope.launch {
            try {
                when (val result = wizard.deps.setup.lookUp(wizard.workspaceId, url, locale)) {
                    is ApiResult.Success -> adoptHours(result.value.draft.weeklyHours)
                    is ApiResult.Failure -> hoursNotice = result.toFailureText().message
                }
            } finally {
                isReading = false
            }
        }
    }

    private fun adoptHours(found: JsonObject?) {
        if (found.isNullOrEmpty()) {
            hoursNotice = UiText.Resource(R.string.setup_callers_no_hours_found)
            return
        }
        if (!hoursDraftPending) hoursBeforeDraft = hours
        hours = SetupFacts.hours(found)
        hoursDraftPending = true
    }

    fun useDraftHours() {
        hoursDraftPending = false
    }

    fun discardDraftHours() {
        if (!hoursDraftPending) return
        hours = hoursBeforeDraft
        hoursDraftPending = false
    }

    // ── Saving ──────────────────────────────────────────────────────────────────

    /** Every section, whole; `weeklyHours` left out while the website's hours are unconfirmed. */
    fun patch(): JsonObject = buildJsonObject {
        put("address", SetupFacts.json(address))
        put("services", SetupFacts.json(services))
        put("faqs", SetupFacts.jsonFaqs(faqs))
        if (timezone.isNotEmpty()) put("timezone", timezone)
        if (!hoursDraftPending) put("weeklyHours", SetupFacts.jsonHours(hours))
    }

    fun save() {
        start(continuing = false)
    }

    fun saveAndContinue() {
        if (isSaving) return
        if (hoursDraftPending) {
            showErrors = true
            saveError = UiText.Resource(R.string.setup_callers_confirm_hours_first)
            return
        }
        start(continuing = true)
    }

    /**
     * The facts, then (when [continuing]) the step's state.
     *
     * ⛔ ONE BUSY SPAN FOR THE WHOLE TAP. [isSaving] and the wizard's step write are held from the
     * facts write to the end of the move, so Continue and Save stay disabled until the next step is on
     * screen (or the move failed), and no move lands in between.
     */
    private fun start(continuing: Boolean) {
        if (isSaving) return
        showErrors = true
        saved = false
        if (!isValid) {
            saveError = UiText.Resource(R.string.setup_callers_fix_errors)
            return
        }
        isSaving = true
        wizard.beginStepWrite()
        saveError = null
        val body = patch()
        wizard.scope.launch {
            try {
                when (val result = wizard.deps.setup.save(wizard.workspaceId, businessFacts = body)) {
                    is ApiResult.Success -> {
                        wizard.adopt(result.value)
                        saved = true
                        if (continuing) wizard.complete(SetupStep.CALLERS, SETUP_STEP_DONE)
                    }
                    is ApiResult.Failure -> saveError = result.toFailureText().message
                }
            } finally {
                isSaving = false
                wizard.endStepWrite()
            }
        }
    }
}
