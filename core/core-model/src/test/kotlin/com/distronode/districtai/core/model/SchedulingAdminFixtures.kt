package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals

/**
 * How the nine scheduling-admin contract classes read a fixture.
 *
 * ⛔ EVERY ONE OF THE 47 FIXTURES IS AN ENVELOPE, AND UNWRAPPING IT IS PART OF THE CONTRACT RATHER
 * THAN A CONVENIENCE. The bytes on disk are `{"ok":true,"data":…}`; a test that decoded only the
 * inner object would pass against a server that had stopped sending the flag, and the flag is what
 * tells a refusal from an answer. [data] therefore decodes through
 * [SchedulingAdminSuccess] and asserts `ok` on the way past.
 *
 * ⚠️ IT REUSES [ContractFixtures]'s DECODER RATHER THAN BUILDING ONE, so the whole scheduling-admin
 * surface cannot become strict in one class and lenient in another. `ignoreUnknownKeys = false` is
 * the entire mechanism; relaxing it to make a failure go away retires the gate silently.
 */
internal object SchedulingAdminFixtures {

    /** The `data` of a committed envelope fixture, decoded strictly. */
    fun <T> data(name: String, serializer: KSerializer<T>): T {
        val envelope = ContractFixtures.json.decodeFromString(
            SchedulingAdminSuccess.serializer(serializer),
            ContractFixtures.read(name),
        )
        assertEquals("$name must be a success envelope", true, envelope.ok)
        return envelope.data
    }

    /**
     * Prove a fixture survives BOTH encodings.
     *
     * ⛔ THE NULLS ARE WHAT THIS CATCHES AND NO DECODE ASSERTION CAN. The terse encoding omits a
     * null where the server sent one explicitly, and the verbose one writes a null where the
     * server omitted the key; a field whose default swallowed the difference (an empty string for
     * a reason, say) decodes and re-encodes to something else, and only the round trip sees it.
     */
    fun <T> roundTrips(name: String, serializer: KSerializer<T>) {
        val decoded = data(name, serializer)
        val verbose = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val terse = kotlinx.serialization.json.Json {
            encodeDefaults = false
            explicitNulls = false
        }
        assertEquals(
            "$name must survive a round trip through an explicit-nulls encoding",
            decoded,
            ContractFixtures.json.decodeFromString(
                serializer,
                verbose.encodeToString(serializer, decoded),
            ),
        )
        assertEquals(
            "$name must survive a round trip in the server's own omit-defaults shape",
            decoded,
            ContractFixtures.json.decodeFromString(
                serializer,
                terse.encodeToString(serializer, decoded),
            ),
        )
    }
}
