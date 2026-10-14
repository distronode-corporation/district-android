package com.distronode.districtai.core.network.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryProtocolTest {

    @Test
    fun `backoff doubles from two seconds with equal jitter, to the cap`() {
        assertEquals(1_000L, TelemetryProtocol.backoffMillis(failures = 1, jitter = 0.0))
        assertEquals(2_000L, TelemetryProtocol.backoffMillis(failures = 1, jitter = 1.0))
        assertEquals(3_000L, TelemetryProtocol.backoffMillis(failures = 2, jitter = 0.5))
        // A count below one reads as the first retry.
        assertEquals(1_000L, TelemetryProtocol.backoffMillis(failures = 0, jitter = 0.0))
        assertEquals(60_000L, TelemetryProtocol.backoffMillis(failures = 10_000, jitter = 1.0))
        assertEquals(30_000L, TelemetryProtocol.backoffMillis(failures = 10_000, jitter = 0.0))
    }

    @Test
    fun `backoff clamps jitter and reads a non-finite one as the middle`() {
        assertEquals(1_000L, TelemetryProtocol.backoffMillis(failures = 1, jitter = -3.0))
        assertEquals(2_000L, TelemetryProtocol.backoffMillis(failures = 1, jitter = 7.0))
        assertEquals(1_500L, TelemetryProtocol.backoffMillis(failures = 1, jitter = Double.NaN))
    }

    @Test
    fun `renewal comes a minute before expiry, within its floor and ceiling`() {
        assertEquals(840_000L, TelemetryProtocol.renewalDelayMillis(expiresAt = 900_000, now = 0))
        assertEquals(
            TelemetryProtocol.RENEWAL_FLOOR_MILLIS,
            TelemetryProtocol.renewalDelayMillis(expiresAt = 1_000, now = 0)
        )
        assertEquals(
            TelemetryProtocol.RENEWAL_CEILING_MILLIS,
            TelemetryProtocol.renewalDelayMillis(expiresAt = Long.MAX_VALUE / 2, now = 0),
        )
    }

    @Test
    fun `only a compact JWT alphabet is presentable`() {
        assertTrue(TelemetryProtocol.isPresentable("eyJ.abc-_9.Z"))
        assertFalse(TelemetryProtocol.isPresentable(""))
        assertFalse(TelemetryProtocol.isPresentable("a,b"))
        assertFalse(TelemetryProtocol.isPresentable("a b"))
        assertFalse(TelemetryProtocol.isPresentable("é"))
    }

    @Test
    fun `the version is offered first and the credential second`() {
        assertEquals(
            listOf("distronode.telemetry.v1", "distronode.token.t0k"),
            TelemetryProtocol.offeredSubprotocols("t0k"),
        )
    }

    @Test
    fun `a wss address gains the workspace and loses its fragment`() {
        assertEquals(
            SocketUrl.Accepted("wss://telemetry.example.com/ws/telemetry?region=eu&workspaceId=ws_1"),
            TelemetryProtocol.socketUrl("wss://telemetry.example.com/ws/telemetry?region=eu#frag", "ws_1"),
        )
        assertEquals(
            SocketUrl.Accepted("WSS://h/ws?workspaceId=ws_1"),
            TelemetryProtocol.socketUrl("WSS://h/ws?", "ws_1"),
        )
    }

    @Test
    fun `the workspace id is percent-encoded so it cannot add a parameter`() {
        assertEquals(
            SocketUrl.Accepted("wss://h/ws?workspaceId=a%26admin%3D1%20%C3%A9-._~"),
            TelemetryProtocol.socketUrl("wss://h/ws", "a&admin=1 é-._~"),
        )
    }

    @Test
    fun `plain ws is accepted only to this machine`() {
        listOf("ws://localhost:8080/ws", "ws://[::1]:8080/ws", "ws://127.0.0.1/ws", "ws://127.255.0.9/ws").forEach {
            assertTrue(it, TelemetryProtocol.socketUrl(it, "w") is SocketUrl.Accepted)
        }
        listOf("ws://example.com/ws", "ws://127.0.0.a1/ws", "ws://127.example/ws", "ws://128.0.0.1/ws")
            .forEach {
                assertEquals(
                    it,
                    SocketUrl.Refused(TelemetryEndpointError.INSECURE),
                    TelemetryProtocol.socketUrl(it, "w")
                )
            }
    }

    @Test
    fun `a missing, unparseable, hostless or non-socket address is refused`() {
        assertEquals(SocketUrl.Refused(TelemetryEndpointError.MISSING), TelemetryProtocol.socketUrl("", "w"))
        // `URI` gives an all-digit host that is not a valid IPv4 address no host at all.
        listOf("wss://bad host/ws", "wss:///ws", "https://h/ws", "//h/ws", "ws://127.0.0.300/ws").forEach {
            assertEquals(it, SocketUrl.Refused(TelemetryEndpointError.INVALID), TelemetryProtocol.socketUrl(it, "w"))
        }
    }
}
