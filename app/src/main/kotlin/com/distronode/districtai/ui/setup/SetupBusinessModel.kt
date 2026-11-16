package com.distronode.districtai.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SetupDraftFaq
import com.distronode.districtai.core.model.SetupDraftService
import com.distronode.districtai.core.model.SetupLookupDraft
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the website read said. */
sealed interface SetupBusinessNotice {
    data class Found(val services: Int, val faqs: Int, val hours: Boolean, val address: Boolean) : SetupBusinessNotice

    data object Nothing : SetupBusinessNotice

    data class Failed(val failure: FailureText) : SetupBusinessNotice
}

/**
 * Step 1, "Find your business": the website read, then who the business is and where.
 *
 * ⛔ "READ MY WEBSITE" SAVES DRAFTS, NEVER LIVE FACTS. The lookup writes nothing; this appends what it
 * found to the stored services and questions with every one `enabled: false` exactly as the service
 * drafted it, so nothing reaches a caller until the owner switches it on in step 4. ⛔ DRAFTED HOURS
 * ARE NOT SAVED AT ALL: a day has no `enabled` flag, so stored hours are live. The step says hours
 * were found and step 4 reads them again.
 *
 * ⛔ THE BUSINESS NAME IS THE WORKSPACE NAME, renamed through the existing route and only when it
 * changed. There is no name in business facts.
 *
 * ⚠️ THE LANGUAGE IS NOT SAVED HERE. It goes into the shell's draft and step 3 writes it into the
 * persona, the one step that owns the persona.
 *
 * ⚠️ "WHAT YOU DO" IS STORED AS ONE QUESTION in the receptionist's language ([SetupWire.whatQuestion]),
 * because the facts have no description field and a question is how a caller asks for it. It starts
 * on; a switch turned off in step 4 is kept.
 *
 * ⚠️ THE EMAIL SENDER NAME AND REPLY-TO ARE NOT HERE: there is no mobile contract for the email
 * identity, so the step opens the Skills page for them.
 *
 * ⛔ THE READ AND THE SAVE NEVER OVERLAP. Each builds its `faqs` from the stored ones, and the service
 * replaces a section whole, so whichever answered second would drop the other's questions. [isBusy]
 * keeps both buttons off while either runs.
 */
@Suppress("TooManyFunctions")
class SetupBusinessModel(private val wizard: SetupWizardViewModel) : SetupStepModel {

    var websiteUrl: String by mutableStateOf("")
    var businessName: String by mutableStateOf(wizard.draft.businessName)
    var what: String by mutableStateOf(SetupFacts.what(wizard.data?.businessFacts))
    var country: String by mutableStateOf("")
        private set
    var subdivision: String by mutableStateOf("")
    var city: String by mutableStateOf("")
    var street: String by mutableStateOf(SetupFacts.address(wizard.data?.businessFacts).line1)
    var language: SetupLanguage by mutableStateOf(wizard.draft.setupLanguage)

    var notice: SetupBusinessNotice? by mutableStateOf(null)
        private set
    var isReading: Boolean by mutableStateOf(false)
        private set
    var isSaving: Boolean by mutableStateOf(false)
        private set
    var showErrors: Boolean by mutableStateOf(false)
        private set
    var saveError: FailureText? by mutableStateOf(null)
        private set

    init {
        // ⛔ THE SAVED ADDRESS BEATS THE DRAFT. Step 4 (or the web) can change the address after this
        // step saved it, and this step writes the address back whole, so opening on the draft's place
        // would show stale values and a save would put them back.
        val place = if (wizard.data?.businessFacts?.obj("address") == null) {
            wizard.draft
        } else {
            SetupBusinessDraft.initial(wizard.data, null, wizard.workspaceName, wizard.appDefault)
        }
        country = place.country
        subdivision = place.subdivision
        city = place.city
    }

    /** The read or the save in flight; see the type's header. */
    val isBusy: Boolean get() = isReading || isSaving

    val subdivisions: List<SetupPlaces.Subdivision>? get() = SetupPlaces.subdivisions(country)

    val zone: SetupZone? get() = if (country.isEmpty()) null else SetupPlaces.zone(country, subdivision)

    /**
     * The time zone a save writes: the stored one while the place (country, province or state, and
     * city) is still the stored place, otherwise the place's own, as on a first save.
     *
     * ⛔ STEP 4'S CHOICE SURVIVES A SAVE HERE. This step tells the owner the zone can be changed in
     * step 4, so saving this step again over an unchanged place must not put the place's default back.
     */
    val timezone: String?
        get() {
            val facts = wizard.data?.businessFacts
            val stored = facts?.str("timezone").orEmpty()
            val saved = SetupFacts.address(facts)
            val samePlace = saved.country.trim().uppercase() == country &&
                saved.region.trim().uppercase() == subdivision.trim().uppercase() &&
                saved.city.trim().lowercase() == city.trim().lowercase()
            return if (stored.isNotEmpty() && samePlace) stored else zone?.timezone
        }

    /** A country change drops the province or state, which belonged to the old one. */
    fun chooseCountry(next: String) {
        if (next == country) return
        country = next
        subdivision = ""
    }

    // ── Checking ────────────────────────────────────────────────────────────────

    val nameMissing: Boolean get() = businessName.isBlank()
    val countryMissing: Boolean get() = country.isEmpty()
    val subdivisionMissing: Boolean get() = subdivisions != null && subdivision.isEmpty()
    val cityMissing: Boolean get() = city.isBlank()

    val isValid: Boolean get() = !nameMissing && !countryMissing && !subdivisionMissing && !cityMissing

    // ── The website read ────────────────────────────────────────────────────────

    fun readWebsite() {
        val url = websiteUrl.trim()
        if (isBusy || url.isEmpty()) return
        isReading = true
        wizard.beginStepWrite()
        notice = null
        wizard.scope.launch {
            try {
                val locale = if (wizard.appLanguage == AppLanguage.FR) "fr" else "en"
                when (val result = wizard.deps.setup.lookUp(wizard.workspaceId, url, locale)) {
                    is ApiResult.Success -> adoptLookup(result.value.draft)
                    is ApiResult.Failure -> notice = SetupBusinessNotice.Failed(result.toFailureText())
                }
            } finally {
                isReading = false
                wizard.endStepWrite()
            }
        }
    }

    private suspend fun adoptLookup(found: SetupLookupDraft) {
        val hoursFound = !found.weeklyHours.isNullOrEmpty()
        val nothingFound = found.services.isEmpty() && found.faqs.isEmpty()
        if (nothingFound && !hoursFound && found.address == null) {
            notice = SetupBusinessNotice.Nothing
            return
        }
        fillAddress(found.address)
        val facts = wizard.data?.businessFacts
        val patch = buildJsonObject {
            if (found.services.isNotEmpty()) {
                val stored = SetupFacts.rows(facts, "services")
                val rows = SetupFacts.appending(found.services.map(::row), stored, "name", SetupFacts.SERVICES)
                put("services", JsonArray(rows))
            }
            if (found.faqs.isNotEmpty()) {
                val stored = SetupFacts.rows(facts, "faqs")
                val rows = SetupFacts.appending(found.faqs.map(::row), stored, "question", SetupFacts.FAQS)
                put("faqs", JsonArray(rows))
            }
            // ⛔ NO HOURS, EVER, from here: see the type's header.
        }
        if (patch.isNotEmpty()) {
            when (val result = wizard.deps.setup.save(wizard.workspaceId, businessFacts = patch)) {
                is ApiResult.Success -> wizard.adopt(result.value)
                is ApiResult.Failure -> {
                    notice = SetupBusinessNotice.Failed(result.toFailureText())
                    return
                }
            }
        }
        notice = SetupBusinessNotice.Found(found.services.size, found.faqs.size, hoursFound, found.address != null)
    }

    /** The draft's address fills EMPTY fields only; the owner confirms it by saving the step. */
    private fun fillAddress(address: JsonObject?) {
        if (address == null) return
        val line1 = address.str("line1")
        if (street.isBlank() && line1 != null) street = line1
        val foundCity = address.str("city")
        if (city.isBlank() && foundCity != null) city = foundCity
        val foundCountry = address.str("country").orEmpty()
        val foundRegion = address.str("region").orEmpty().uppercase()
        if (country.isEmpty() && foundCountry in SetupPlaces.countries) {
            country = foundCountry
            subdivision = subdivisionOf(foundRegion, foundCountry)
        } else if (subdivision.isEmpty() && foundCountry == country) {
            subdivision = subdivisionOf(foundRegion, country)
        }
    }

    private fun subdivisionOf(code: String, country: String): String {
        val list = SetupPlaces.subdivisions(country) ?: return code
        return list.firstOrNull { it.code == code }?.code.orEmpty()
    }

    private fun row(service: SetupDraftService): JsonObject = buildJsonObject {
        put("name", service.name)
        service.price?.let { put("price", it) }
        put("enabled", service.enabled)
    }

    private fun row(faq: SetupDraftFaq): JsonObject = buildJsonObject {
        put("question", faq.question)
        put("answer", faq.answer)
        put("enabled", faq.enabled)
    }

    fun dismissNotice() {
        notice = null
    }

    // ── The save ────────────────────────────────────────────────────────────────

    /** Saves the facts, renames the workspace when the name changed, keeps the draft, and marks the step done. */
    fun save() {
        showErrors = true
        if (!isValid || isBusy) return
        if (faqsFull) {
            saveError = FailureText(
                message = UiText.Resource(R.string.setup_business_what_faqs_full, SetupFacts.FAQS),
                retryable = false,
            )
            return
        }
        isSaving = true
        wizard.beginStepWrite()
        saveError = null
        wizard.scope.launch {
            try {
                if (saveFactsAndName()) {
                    val kept = SetupBusinessDraft(
                        businessName.trim(),
                        country,
                        subdivision.trim(),
                        city.trim(),
                        language.wire
                    )
                    wizard.updateDraft(kept)
                    wizard.complete(SetupStep.BUSINESS, SETUP_STEP_DONE)
                }
            } finally {
                isSaving = false
                wizard.endStepWrite()
            }
        }
    }

    private suspend fun saveFactsAndName(): Boolean {
        when (val result = wizard.deps.setup.save(wizard.workspaceId, businessFacts = factsPatch())) {
            is ApiResult.Success -> wizard.adopt(result.value)
            is ApiResult.Failure -> {
                saveError = result.toFailureText()
                return false
            }
        }
        val name = businessName.trim()
        if (name == wizard.workspaceName.trim()) return true
        return when (val result = wizard.deps.members.rename(wizard.workspaceId, name)) {
            is ApiResult.Success -> {
                // ⛔ THE ECHO, NOT THE REQUEST: the service trims before it stores.
                wizard.renamed(result.value ?: name)
                true
            }
            is ApiResult.Failure -> {
                saveError = result.toFailureText()
                false
            }
        }
    }

    /**
     * The address always (city and country are required), the street only when typed, the zone when
     * the place implies one, and the "what you do" question in front of the rest.
     *
     * ⛔ THE STORED POSTAL CODE GOES BACK WITH THE ADDRESS. This step has no field for it, and the
     * service replaces the section whole, so leaving it out would erase what step 4 (or the web) stored.
     */
    fun factsPatch(): JsonObject {
        val stored = SetupFacts.rows(wizard.data?.businessFacts, "faqs")
        val others = stored.filterNot(::isWhat)
        val postalCode = SetupFacts.address(wizard.data?.businessFacts).postalCode.trim()
        return buildJsonObject {
            timezone?.let { put("timezone", it) }
            put(
                "address",
                buildJsonObject {
                    if (street.isNotBlank()) put("line1", street.trim())
                    put("city", city.trim())
                    if (subdivision.isNotBlank()) put("region", subdivision.trim())
                    if (postalCode.isNotEmpty()) put("postalCode", postalCode)
                    put("country", country)
                },
            )
            val faqs: List<JsonElement>? = when {
                what.isNotBlank() -> {
                    // ⛔ THE OWNER'S SWITCH IS KEPT: a "what you do" question switched off in step 4
                    // stays off. Only a new one starts on, because typing it is the confirmation.
                    val current = stored.firstOrNull(::isWhat)
                    val mine = buildJsonObject {
                        put("question", SetupWire.whatQuestion(language))
                        put("answer", what.trim().take(SetupFacts.FAQ_ANSWER))
                        put("enabled", current?.let { it["enabled"] == JsonPrimitive(true) } ?: true)
                    }
                    // ⛔ NO SILENT DROP AT THE CAP: [faqsFull] refuses the save before this runs.
                    listOf(mine) + others
                }
                others.size != stored.size -> others
                else -> null
            }
            faqs?.let { put("faqs", JsonArray(it)) }
        }
    }

    /**
     * True when "what you do" would be a new question on a list already at [SetupFacts.FAQS]. An
     * existing "what you do" question is replaced, so it always fits.
     *
     * ⛔ A REFUSAL, NEVER A TRUNCATION: cutting the list to the cap would delete the last stored
     * question without a word.
     */
    val faqsFull: Boolean
        get() = what.isNotBlank() &&
            SetupFacts.rows(wizard.data?.businessFacts, "faqs").count { !isWhat(it) } >= SetupFacts.FAQS

    private fun isWhat(faq: JsonObject): Boolean = faq.str("question") in SetupWire.whatQuestions
}
