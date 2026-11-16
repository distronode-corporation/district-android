package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.SETUP_STEP_TODO
import com.distronode.districtai.core.model.SetupProgress
import com.distronode.districtai.core.model.SetupSteps
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.l10n.AppLanguage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The setup wizard's six steps, in the order the service and the web list them, by wire name.
 */
enum class SetupStep(val wire: String) {
    BUSINESS("business"),
    NUMBER("number"),
    RECEPTIONIST("receptionist"),
    CALLERS("callers"),
    CALLS("calls"),
    GOLIVE("golive"),
}

/** The stored state of [step]: `todo`, `done`, `skipped`, or whatever newer value the service sent. */
fun SetupSteps.state(step: SetupStep): String = when (step) {
    SetupStep.BUSINESS -> business
    SetupStep.NUMBER -> number
    SetupStep.RECEPTIONIST -> receptionist
    SetupStep.CALLERS -> callers
    SetupStep.CALLS -> calls
    SetupStep.GOLIVE -> golive
}

/**
 * Where a stored progress opens the wizard, and the moves between steps.
 *
 * ⛔ THE STEP STATES ARE THE SERVICE'S AND THIS MIRRORS THE WEB'S READING OF THEM: the wizard opens
 * on the first step still `todo`, or on the first step when none is. A state this build does not
 * know is NOT `todo`, so a fourth state added server-side cannot send an owner back to a step they
 * finished.
 *
 * ⚠️ EVERY STEP SAVES AS IT GOES, SO "FINISH LATER" LOSES NOTHING THE STEP SAVED. The next open
 * resumes from what the service holds, on any device, the web included.
 */
object SetupFlow {
    val steps: List<SetupStep> = SetupStep.entries

    fun firstOpen(progress: SetupProgress?): SetupStep =
        steps.firstOrNull { progress?.steps?.state(it) == SETUP_STEP_TODO } ?: SetupStep.BUSINESS

    fun next(after: SetupStep): SetupStep? = steps.getOrNull(after.ordinal + 1)

    fun previous(before: SetupStep): SetupStep? = steps.getOrNull(before.ordinal - 1)
}

/**
 * Why the wizard is not run for this person, as opposed to a failure to reach it.
 *
 * ⛔ OWNER ONLY, AND THE OWNER IS THE WORKSPACE'S `ownerId`, NOT A ROLE. Every route under `setup`
 * answers a member or an admin with 403, so a 403 here is the ordinary answer for anyone else and is
 * explained, never shown as an error with a retry. A workspace from before the wizard answers 409
 * `setup_not_available`, and a 200 with no progress means the same thing.
 */
enum class SetupRefusal {
    NOT_OWNER,
    NOT_AVAILABLE,
    ;

    companion object {
        fun from(failure: ApiResult.Failure): SetupRefusal? = when {
            failure is ApiResult.Forbidden -> NOT_OWNER
            failure is ApiResult.HttpFailure && failure.status == CONFLICT && failure.code == NOT_AVAILABLE_CODE ->
                NOT_AVAILABLE
            else -> null
        }

        private const val CONFLICT = 409
        private const val NOT_AVAILABLE_CODE = "setup_not_available"
    }
}

/** The language the receptionist speaks, as step 1 offers it. */
enum class SetupLanguage(val wire: String) {
    EN("en"),
    FR("fr"),
    ;

    /** The persona `language` this choice is saved as when nothing is stored. */
    val personaDefault: String get() = if (this == FR) "fr-CA" else "en-US"

    companion object {
        /** "fr-CA" is French, "en-US" English, anything else null (the web's `localeOfPersonaLanguage`). */
        fun ofPersona(personaLanguage: String?): SetupLanguage? {
            val lower = personaLanguage.orEmpty().lowercase()
            return when {
                lower.startsWith("fr") -> FR
                lower.startsWith("en") -> EN
                else -> null
            }
        }

        fun ofWire(wire: String): SetupLanguage = if (wire == FR.wire) FR else EN

        /** The app's own language, for a workspace whose persona names none. */
        fun of(app: AppLanguage): SetupLanguage = if (app == AppLanguage.FR) FR else EN

        /**
         * The persona `language` step 3 saves (the web's `personaLanguageToSave`).
         *
         * ⛔ A STORED LANGUAGE IS KEPT UNLESS THE OWNER CHOSE THE OTHER FAMILY: step 1 offers only
         * English and French, so a receptionist set to German in settings must not come back as
         * `en-US`. A stored language outside both families is kept when the owner left step 1 on the
         * app's language, which is where it opened.
         */
        fun toSave(stored: String?, chosen: SetupLanguage, appDefault: SetupLanguage): String {
            val kept = stored.orEmpty().trim()
            val family = ofPersona(kept)
            val keep = kept.isNotEmpty() && (family == chosen || (family == null && chosen == appDefault))
            return if (keep) kept else chosen.personaDefault
        }
    }
}

/**
 * What step 1 learned that steps 2 and 3 need before a read could give it back: the name, the place,
 * and the language (which only step 3 stores, in the persona).
 *
 * ⚠️ KEPT ON THIS DEVICE PER WORKSPACE ([SetupDraftStore]), so the language chosen in step 1 survives
 * "Finish later" and a relaunch until step 3 writes it.
 */
data class SetupBusinessDraft(
    val businessName: String = "",
    /** ISO 3166-1 alpha-2, or "" when not chosen yet. */
    val country: String = "",
    val subdivision: String = "",
    val city: String = "",
    /** [SetupLanguage.wire]. */
    val language: String = SetupLanguage.EN.wire,
) {
    val setupLanguage: SetupLanguage get() = SetupLanguage.ofWire(language)

    companion object {
        /** The web's `initialBusinessDraft`. */
        fun initial(
            data: DistrictSetupResponse?,
            personaLanguage: String?,
            workspaceName: String,
            appDefault: SetupLanguage,
        ): SetupBusinessDraft {
            val address = data?.businessFacts?.obj("address")
            val country = address?.str("country") ?: SetupPlaces.defaultCountry(data?.region)
            val stored = address?.str("region").orEmpty()
            val list = SetupPlaces.subdivisions(country)
            val subdivision = if (list != null) {
                list.firstOrNull { it.code == stored.uppercase() }?.code.orEmpty()
            } else {
                stored
            }
            return SetupBusinessDraft(
                businessName = if (SetupWire.isDefaultWorkspaceName(workspaceName)) "" else workspaceName,
                country = country,
                subdivision = subdivision,
                city = address?.str("city").orEmpty(),
                language = (SetupLanguage.ofPersona(personaLanguage) ?: appDefault).wire,
            )
        }
    }
}

/**
 * Values the wizard writes into the business's own data, which are therefore chosen by the
 * RECEPTIONIST'S language rather than the app's and are not in the catalog.
 *
 * ⛔ THEY MATCH THE WEB'S BYTE FOR BYTE, because the web reads them back: step 1's question is how
 * either client finds the "what you do" answer it wrote, and the starter greetings are how both tell
 * an untouched greeting from the owner's own.
 */
object SetupWire {
    fun whatQuestion(language: SetupLanguage): String =
        if (language == SetupLanguage.FR) "Que fait l'entreprise?" else "What does the business do?"

    val whatQuestions: Set<String> = SetupLanguage.entries.map(::whatQuestion).toSet()

    /** The starter persona's name, for a persona that holds none. */
    const val FALLBACK_NAME: String = "Ava"

    /** The two provisioned starter greetings. */
    val starterGreetings: Set<String> = setOf(
        "Thanks for calling. This is Ava. How can I help you today?",
        "Merci de votre appel. Ici Ava. Comment puis-je vous aider aujourd'hui?",
    )

    /** The web's step 3 template, in the receptionist's language. */
    fun greeting(business: String, name: String, language: SetupLanguage): String {
        val trimmed = business.trim()
        val french = language == SetupLanguage.FR
        return when {
            french && trimmed.isNotEmpty() -> "Merci d'avoir appelé $trimmed, ici $name. Comment puis-je vous aider?"
            french -> "Merci d'avoir appelé, ici $name. Comment puis-je vous aider?"
            trimmed.isNotEmpty() -> "Thanks for calling $trimmed, this is $name. How can I help?"
            else -> "Thanks for calling, this is $name. How can I help?"
        }
    }

    /** The name a workspace is provisioned with when its buyer gave no business name. */
    fun isDefaultWorkspaceName(name: String): Boolean = name.trim().endsWith("'s Communications Workspace")

    /**
     * The web's `toE164`: an E.164 number, or a ten- or eleven-digit North American one in a `us` or
     * `ca` workspace; null for anything else.
     */
    fun e164(raw: String, region: String?): String? {
        val compact = raw.filterNot { it in " ().-" }
        val northAmerican = region == "us" || region == "ca"
        return when {
            E164.matches(compact) -> compact
            northAmerican && TEN_DIGITS.matches(compact) -> "+1$compact"
            northAmerican && ELEVEN_DIGITS.matches(compact) -> "+$compact"
            else -> null
        }
    }

    private val E164 = Regex("^\\+[0-9]{8,15}$")
    private val TEN_DIGITS = Regex("^[2-9][0-9]{9}$")
    private val ELEVEN_DIGITS = Regex("^1[2-9][0-9]{9}$")
}

/** A string member, or null when it is absent or not a string. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** An object member, or null when it is absent or not an object. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
