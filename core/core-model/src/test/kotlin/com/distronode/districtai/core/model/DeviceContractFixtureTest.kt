package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-management half of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` RATHER THAN MORE METHODS ON IT. That class reached
 * detekt's LargeClass ceiling as endpoints accumulated, and the healthy answer to a class that has
 * grown too large is another class, not a raised threshold. The strict decoder and the fixture
 * loader are shared through [ContractFixtures] precisely so this split cannot make one of the two
 * lenient — that would silently retire the gate for whichever endpoints lived here.
 *
 * ⛔ THE FOUR ROUTES BEHIND THESE FIXTURES ARE THE ONLY ONES OUTSIDE `/api/district/`. They sit
 * under `/api/auth/native/`, a PUBLIC prefix in proxy.ts, so each route's own guard is its entire
 * access control. Their SHAPES need pinning for exactly the same reason as every district route:
 * this module decodes them with `ignoreUnknownKeys = false`, so a renamed field would ship green
 * on the server and fail on a phone.
 */
class DeviceContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    @Test
    fun `device list decodes and covers BOTH nullability branches`() {
        val response = json.decodeFromString<DeviceListResponse>(fixture("district-devices.json"))

        assertEquals(true, response.success)
        assertEquals("the fixture must carry more than one device", 2, response.devices.size)

        // ⛔ THE REGRESSION GUARD THIS FIXTURE EXISTS FOR. `deviceName` is `String | null`
        // server-side because the field is optional on token exchange, and `lastUsedAt` is
        // `Date | null` because the server stamps it only when a refresh token ROTATES — so a
        // session in its first ten minutes has never been stamped, which is EVERY session the
        // moment it is created. A fixture regenerated against fully-populated rows would let
        // these be typed non-null, and the row that would then throw in production is the most
        // ordinary one there is: a phone that just signed in.
        assertTrue(
            "fixture must cover a device WITH a name",
            response.devices.any { it.deviceName != null },
        )
        assertTrue(
            "fixture must cover a device with NO name (the null branch)",
            response.devices.any { it.deviceName == null },
        )
        assertTrue(
            "fixture must cover a device that HAS refreshed",
            response.devices.any { it.lastUsedAt != null },
        )
        assertTrue(
            "fixture must cover a device that has NEVER refreshed (the null branch)",
            response.devices.any { it.lastUsedAt == null },
        )

        // ⚠️ Both platforms, so nothing starts assuming this account is Android-only — the same
        // account can hold an iOS install, and `platform` is a plain string on the wire.
        assertEquals(setOf("android", "ios"), response.devices.map { it.platform }.toSet())

        // ⚠️ `createdAt` is never null server-side (it is a non-optional column), so a blank one
        // would mean the DTO's default had silently absorbed a missing key.
        assertTrue(
            "createdAt must be present on every row",
            response.devices.all { it.createdAt.isNotBlank() },
        )
    }

    @Test
    fun `both revoke routes answer the same shape`() {
        // ⛔ ONE DTO FOR TWO ROUTES, WHICH IS ONLY SAFE WHILE THEIR SHAPES AGREE. The per-device
        // revoke and revoke-all are different operations with different scopes, and the client
        // decodes both with `DeviceRevokeResponse`; if either grew a field, this is what fails.
        val one = json.decodeFromString<DeviceRevokeResponse>(fixture("district-device-revoke.json"))
        val all = json.decodeFromString<DeviceRevokeResponse>(fixture("district-revoke-all.json"))

        assertEquals(true, one.success)
        assertEquals(1, one.revoked)
        assertEquals(true, all.success)
        assertEquals(2, all.revoked)
    }

    @Test
    fun `the sign-out route confirms nothing beyond success`() {
        // ⛔ THE NON-CONFIRMATION PROPERTY, PINNED AS A SHAPE. `/api/auth/native/revoke` is
        // unauthenticated by necessity — the refresh token IS the credential — and it answers 200
        // identically whether the token existed or not, deliberately declining to be an existence
        // oracle. A `revoked` count or an echo of what was retired would break that, and this is
        // the cheapest place it shows up. The client's retry contract also depends on the body
        // carrying nothing it has to read: 200-or-4xx means discard the token, 5xx means keep it.
        val raw = fixture("district-native-revoke.json")
        val decoded = json.decodeFromString<DeviceRevokeResponse>(raw)

        assertEquals(true, decoded.success)
        // The count is the DTO's DEFAULT, not a value the route sent — the key is absent.
        assertEquals("the sign-out route must not report a count", 0, decoded.revoked)
        assertFalse(
            "a `revoked` key here would be a non-confirmation leak",
            raw.contains("revoked"),
        )
    }

    @Test
    fun `device fixtures survive a round trip in both encodings`() {
        // ⛔ THE DEVICE LIST ESPECIALLY. Its second row has `deviceName` and `lastUsedAt` BOTH
        // null, which the terse encoding writes as ABSENT — the same shape the server produces for
        // a device that has never refreshed. A default that swallowed either (an empty string for
        // the name, say) would decode and re-encode to something different, which is what this
        // catches and what no decode assertion above can.
        WireMirror.assertFixtureRoundTrips(DeviceListResponse.serializer(), "district-devices.json")
        WireMirror.assertFixtureRoundTrips(DeviceRevokeResponse.serializer(), "district-device-revoke.json")
        WireMirror.assertFixtureRoundTrips(DeviceRevokeResponse.serializer(), "district-revoke-all.json")
    }

    @Test
    fun `an unmodelled field on a device row is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS FILE'S DECODER TOO. `ContractFixtureTest` has the
        // same assertion, and duplicating it is deliberate: the two classes now share one `Json`
        // instance, so if this ever passes, the shared decoder has been relaxed and BOTH halves of
        // the gate have quietly stopped protecting anything.
        val withExtraField = """{"success":true,"devices":[{"deviceId":"d1","deviceName":null,""" +
            """"platform":"android","lastUsedAt":null,"createdAt":"2026-08-15T00:00:00.000Z",""" +
            """"brandNewServerField":"boom"}]}"""

        val failure = runCatching { json.decodeFromString<DeviceListResponse>(withExtraField) }
        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}
