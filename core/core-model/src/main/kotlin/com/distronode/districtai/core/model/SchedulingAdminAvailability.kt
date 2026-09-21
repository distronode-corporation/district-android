package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

/**
 * A weekly availability rule: a window that repeats forever.
 *
 * ⛔ A RULE AND AN OVERRIDE ARE NOT LAYERS OF ONE THING, and reading one to answer a question
 * about the other gives a plausible wrong answer. "Is this host free on the 24th" is BOTH, and
 * neither op answers it alone — `eventTypes.slots` is the op that composes them.
 *
 * ⚠️ [eventTypeId] IS EXPLICITLY NULL FOR A TENANCY-WIDE RULE, which the fixture's second row
 * sends. Null means "every event type", not "no event type", so a screen that filtered it out
 * would hide the rule that governs everything.
 *
 * ⚠️ [startTime] AND [endTime] ARE `HH:mm` LOCAL WALL CLOCK, never instants. They carry no zone;
 * the host's [SchedulingMe.timezone] is what places them.
 */
@Serializable
data class SchedulingAvailabilityRule(
    val id: String,
    @SerialName("event_type_id") val eventTypeId: String? = null,
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

/**
 * A dated exception to the weekly rules.
 *
 * ⛔ [isAvailable] FLIPS THE MEANING OF [startTime] AND [endTime] RATHER THAN MERELY LABELLING
 * THE ROW. `true` with times is "these hours instead of the usual ones"; `false` is a day off and
 * sends both times as explicit `null`. A client that read the times without the flag would offer
 * bookings on a day somebody has blocked out.
 *
 * ⚠️ [groupId] IS ABSENT — NOT NULL — ON A SINGLE-DAY OVERRIDE, which is why it is the key the
 * union below discriminates on being unreliable. It is present only when the row came from a
 * date RANGE, and deleting the group is the op that removes all of them at once.
 */
@Serializable
data class SchedulingAvailabilityOverride(
    val id: String,
    val date: String,
    @SerialName("is_available") val isAvailable: Boolean,
    val reason: String,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("group_id") val groupId: String? = null,
)

/**
 * The summary `availability.overrides.create` answers when it was given an `end_date`.
 *
 * ⚠️ [days] IS INCLUSIVE OF BOTH ENDS — the fixture's 2026-12-24 to 2026-12-31 is 8, not 7 — so
 * nothing here should recompute it from the dates.
 */
@Serializable
data class SchedulingOverrideGroup(
    @SerialName("group_id") val groupId: String,
    val reason: String,
    val start: String,
    val end: String,
    val days: Int,
)

/**
 * `availability.overrides.create` answers TWO shapes from one op, and this is the union.
 *
 * ⛔ ONE OP, TWO RESPONSES, DECIDED BY WHETHER THE REQUEST CARRIED AN `end_date`. A single day
 * answers the row it created; a range answers a group summary and creates as many rows as there
 * are days. A caller that assumed the row shape gets a decode failure on every range, and one
 * that assumed the group shape gets one on every single day.
 *
 * ⛔ DISCRIMINATED ON A KEY, NOT BY TRYING THE ROW FIRST AND FALLING BACK, WHICH IS A DELIBERATE
 * DIVERGENCE FROM THE iOS CLIENT. A try-then-fall-back reports the SECOND shape's decode error
 * when both fail, so a body that is neither is blamed on the group schema — and under a lenient
 * decoder (which is what ships, `ignoreUnknownKeys = true`) the ordering is the only thing
 * keeping a group from decoding as something it is not. [RANGE_ONLY_KEY] appears on one shape and
 * cannot appear on the other, so the choice is made on evidence rather than on order.
 *
 * ⛔ NOT ANNOTATED `@Serializable`, AND THE COMPANION'S [serializer] IS HAND-WRITTEN. Every caller
 * passes a serializer explicitly (the RPC is generic over the response type), so the annotation
 * would buy only implicit resolution for a type nothing embeds — and `@Serializable(with = …)` on a
 * sealed INTERFACE is the one place the plugin's generated companion is easy to be wrong about.
 * Writing the two lines removes the question.
 */
sealed interface SchedulingOverrideCreated {
    /** One day: the row that was created. */
    data class Single(val row: SchedulingAvailabilityOverride) : SchedulingOverrideCreated

    /** A date range: the group summary, and as many rows as there are days. */
    data class Range(val group: SchedulingOverrideGroup) : SchedulingOverrideCreated

    companion object {
        /** The key-discriminated serializer. See [SchedulingOverrideCreatedSerializer]. */
        fun serializer(): KSerializer<SchedulingOverrideCreated> = SchedulingOverrideCreatedSerializer
    }
}

/**
 * ⚠️ THE DESCRIPTOR IS [JsonElement]'S BECAUSE THE WIRE VALUE IS WHATEVER THE OP ANSWERED. There
 * is no stable class shape to describe here, and inventing one would describe only whichever arm
 * was written first.
 *
 * ⛔ JSON ONLY, ASSERTED RATHER THAN ASSUMED. This serializer reads the raw element to pick an
 * arm, which no other format exposes; a non-JSON decoder reaching here is a programming error and
 * says so instead of silently taking the first branch.
 */
object SchedulingOverrideCreatedSerializer : KSerializer<SchedulingOverrideCreated> {

    /**
     * ⛔ `days` AND NOT `group_id`. Both appear on a range summary, and `group_id` ALSO appears on
     * a single-day row that came from an earlier range — so discriminating on it would read a row
     * as a summary and lose the date, the availability flag and the times with it.
     */
    private const val RANGE_ONLY_KEY = "days"

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): SchedulingOverrideCreated {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("An override-create response can only be read from JSON.")
        val element = input.decodeJsonElement()
        val keys = (element as? JsonObject)?.keys
            ?: throw SerializationException("An override-create response must be an object.")
        return if (RANGE_ONLY_KEY in keys) {
            SchedulingOverrideCreated.Range(
                input.json.decodeFromJsonElement(SchedulingOverrideGroup.serializer(), element),
            )
        } else {
            SchedulingOverrideCreated.Single(
                input.json.decodeFromJsonElement(SchedulingAvailabilityOverride.serializer(), element),
            )
        }
    }

    override fun serialize(encoder: Encoder, value: SchedulingOverrideCreated) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("An override-create response can only be written as JSON.")
        val element = when (value) {
            is SchedulingOverrideCreated.Single ->
                output.json.encodeToJsonElement(SchedulingAvailabilityOverride.serializer(), value.row)

            is SchedulingOverrideCreated.Range ->
                output.json.encodeToJsonElement(SchedulingOverrideGroup.serializer(), value.group)
        }
        output.encodeJsonElement(element)
    }
}
