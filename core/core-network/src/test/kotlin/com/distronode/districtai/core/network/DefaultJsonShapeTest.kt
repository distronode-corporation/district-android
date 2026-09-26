package com.distronode.districtai.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production encoder, held to the copy of it that the model tests encode with.
 *
 * WHY A COPY EXISTS. :core:core-model's `WireMirror.terse` stands in for [DistrictApiClient.DEFAULT_JSON]
 * when it checks that every DTO's omit-defaults encoding writes nothing the server did not send. That
 * module sits below this one, so its tests cannot name DEFAULT_JSON and declare
 * `Json { encodeDefaults = false; explicitNulls = false }` instead. If DEFAULT_JSON changed how it
 * encodes (defaults written, nulls written, a naming strategy, a class discriminator), every model
 * test would still pass against a shape the app no longer sends.
 *
 * WHAT "THE SAME" MEANS. DEFAULT_JSON also sets `ignoreUnknownKeys = true`, which only affects
 * decoding and is deliberately absent from the copy. With that one setting put back to its default,
 * DEFAULT_JSON's whole configuration must equal the copy's, every setting compared.
 */
class DefaultJsonShapeTest {

    /** The declaration `WireMirror.terse` carries in :core:core-model. Change the two together. */
    private val wireMirrorTerse = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `the production encoder is the model tests' terse encoder plus one decode-only setting`() {
        val production = DistrictApiClient.DEFAULT_JSON.configuration
        assertTrue("DEFAULT_JSON's one decode-only setting", production.ignoreUnknownKeys)

        val encodingOnly = Json(from = DistrictApiClient.DEFAULT_JSON) { ignoreUnknownKeys = false }

        // JsonConfiguration has no equals; its toString names every setting and its value.
        assertEquals(wireMirrorTerse.configuration.toString(), encodingOnly.configuration.toString())
    }
}
