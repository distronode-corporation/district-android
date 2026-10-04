package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.VoiceStudioResponse
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The committed Voice Studio fixture, decoded strictly, for the repository and screen tests.
 *
 * ⛔ THE SERVER'S OWN ANSWER RATHER THAN A HAND-BUILT ONE. A Studio read is a large object whose
 * parts must agree with each other (a recipe's chain names catalogue models, a voice list names a
 * mouth the catalogue has); a hand-built copy would agree with whatever the test author assumed.
 * Tests derive variants from this one with `copy`.
 *
 * ⚠️ THE MODULE READING IT MUST SET `district.contracts.dir` on its test task, as core-model,
 * core-network, core-data and app do.
 */
object VoiceStudioFixture {

    private val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    val studio: VoiceStudioResponse by lazy {
        val dir = System.getProperty("district.contracts.dir")
        check(!dir.isNullOrBlank()) { "district.contracts.dir is not set on this module's test task" }
        strict.decodeFromString(
            VoiceStudioResponse.serializer(),
            File(dir, "district-voice-studio.json").readText(),
        )
    }
}
