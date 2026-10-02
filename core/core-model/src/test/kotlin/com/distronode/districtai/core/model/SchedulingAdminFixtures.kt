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
     *
     * ⚠️ The encodings are [WireMirror]'s pair, the one copy every contract class uses; only the
     * envelope unwrap is specific to this surface.
     */
    fun <T> roundTrips(name: String, serializer: KSerializer<T>) {
        WireMirror.assertRoundTrips(serializer, data(name, serializer), name)
    }
}
