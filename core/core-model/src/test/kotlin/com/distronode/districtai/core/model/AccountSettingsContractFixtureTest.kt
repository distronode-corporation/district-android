package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET` and `POST /api/settings`, read from their generated fixtures.
 *
 * ⚠️ TWO READS BECAUSE `locale` HAS THREE STATES: `fr` (or `en`) chosen, and null for an account
 * that was never asked. The second read is also the account with no workspace, whose subscription
 * carries no `updatedAt` key at all.
 */
class AccountSettingsContractFixtureTest {

    @Test
    fun `the settings read carries a chosen locale, the plan and the region`() {
        val settings = WireMirror.assertMirrors(AccountSettingsResponse.serializer(), "district-settings.json")

        assertEquals("contract@distronode.test", settings.profile.email)
        assertEquals("Contract Operator", settings.profile.displayName)
        assertEquals("America/Toronto", settings.profile.timezone)
        assertEquals("fr", settings.profile.locale)
        assertEquals("VoicePro", settings.subscription.tier)
        assertEquals("active", settings.subscription.status)
        assertEquals("2026-08-15T14:30:00.000Z", settings.subscription.updatedAt)
        assertEquals("ca", settings.region)
    }

    @Test
    fun `an account that never chose reads a null locale, not English`() {
        val settings =
            WireMirror.assertMirrors(AccountSettingsResponse.serializer(), "district-settings-unset.json")

        // ⛔ NULL IS "NEVER ASKED". Reading it as `en` would write a choice nobody made.
        assertNull(settings.profile.locale)
        assertEquals("", settings.profile.displayName)
        assertEquals("Free", settings.subscription.tier)
        assertEquals("none", settings.subscription.status)
        assertNull("no workspace, so no updatedAt key", settings.subscription.updatedAt)
        assertEquals("us", settings.region)
    }

    @Test
    fun `the locale write answers success and the server's sentence`() {
        val saved =
            WireMirror.assertMirrors(AccountSettingsSaveResponse.serializer(), "district-settings-save.json")

        assertTrue(saved.success)
        assertEquals("Settings saved successfully", saved.message)
    }
}
