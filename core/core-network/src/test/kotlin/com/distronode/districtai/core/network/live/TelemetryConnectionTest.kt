package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.model.TelemetryTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.live.TelemetryConnection.Phase
import com.distronode.districtai.core.network.live.TelemetryConnectionCommand as Cmd
import com.distronode.districtai.core.network.live.TelemetryConnectionEvent as Ev
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryConnectionTest {

    private val token = TelemetryTokenResponse(
        success = true,
        token = "a.b.c",
        expiresAt = 900_000,
        wsUrl = "wss://t.example/ws/telemetry",
    )
    private val url = "wss://t.example/ws/telemetry?workspaceId=ws_1"
    private val offered = listOf("distronode.telemetry.v1", "distronode.token.a.b.c")

    private fun connection(broadcast: Boolean = true) = TelemetryConnection("ws_1", broadcast)

    /** Start, mint and open: the connection is open at [now] on attempt 1. */
    private fun open(conn: TelemetryConnection, now: Long = 0): List<Cmd> {
        conn.handle(Ev.Start, 0, 0.0)
        conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        return conn.handle(Ev.Opened("distronode.telemetry.v1", conn.attempt), now, 0.0)
    }

    @Test
    fun `start mints, and a second start does nothing`() {
        val conn = connection()
        assertEquals(listOf(Cmd.Mint(1)), conn.handle(Ev.Start, 0, 0.0))
        assertEquals(Phase.Minting, conn.phase)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Start, 0, 0.0))
    }

    @Test
    fun `a minted credential opens the socket with a handshake deadline`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        assertEquals(
            listOf(Cmd.Open(url, offered, 1), Cmd.Schedule(TelemetryTimer.CONNECT, 15_000, 1)),
            conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0),
        )
        assertEquals(Phase.Connecting(900_000), conn.phase)
    }

    @Test
    fun `a stale mint, or one outside minting, changes nothing`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Minted(ApiResult.Success(token), 7), 0, 0.0))
        conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0))
    }

    @Test
    fun `an unusable credential ends the connection`() {
        listOf(token.copy(success = false), token.copy(token = "a,b")).forEach { bad ->
            val conn = connection()
            conn.handle(Ev.Start, 0, 0.0)
            assertEquals(
                listOf(
                    Cmd.CancelTimers,
                    Cmd.Emit(TelemetryUpdate.Ended(TelemetryLiveError.InvalidGrant)),
                    Cmd.Finish,
                ),
                conn.handle(Ev.Minted(ApiResult.Success(bad), 1), 0, 0.0),
            )
            assertEquals(Phase.Ended, conn.phase)
        }
    }

    @Test
    fun `an unusable address ends the connection`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        val commands = conn.handle(Ev.Minted(ApiResult.Success(token.copy(wsUrl = "ws://example.com/ws")), 1), 0, 0.0)
        assertEquals(
            Cmd.Emit(TelemetryUpdate.Ended(TelemetryLiveError.Endpoint(TelemetryEndpointError.INSECURE))),
            commands[1],
        )
    }

    @Test
    fun `a transient mint failure backs off, and a final one ends`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        val failure = ApiResult.NetworkFailure(IOException("offline"))
        assertEquals(
            listOf(
                Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Mint(failure))),
                Cmd.Schedule(TelemetryTimer.BACKOFF, 1_000, 1),
            ),
            conn.handle(Ev.Minted(failure, 1), 0, 0.0),
        )
        assertEquals(Phase.Waiting, conn.phase)
        assertEquals(listOf(Cmd.Mint(2)), conn.handle(Ev.TimerFired(TelemetryTimer.BACKOFF, 1), 1_000, 0.0))

        val forbidden = ApiResult.Forbidden("no")
        assertEquals(
            Cmd.Emit(TelemetryUpdate.Ended(TelemetryLiveError.Mint(forbidden))),
            conn.handle(Ev.Minted(forbidden, 2), 0, 0.0)[1],
        )
    }

    @Test
    fun `opening reports connected, arms renewal and silence, and sends the ops`() {
        val conn = connection(broadcast = false)
        conn.handle(Ev.SubscribeTranscript("call_b"), 0, 0.0)
        conn.handle(Ev.SubscribeTranscript("call_a"), 0, 0.0)
        assertEquals(setOf("call_a", "call_b"), conn.transcriptSubscriptions)
        assertEquals(
            listOf(
                Cmd.Emit(TelemetryUpdate.Connected),
                Cmd.Schedule(TelemetryTimer.RENEWAL, 840_000, 1),
                Cmd.Schedule(TelemetryTimer.SILENCE, 90_000, 1),
                Cmd.Send("""{"op":"socket.mode","v":1,"broadcast":false}""", 1),
                Cmd.Send("""{"op":"transcript.subscribe","v":1,"callId":"call_a"}""", 1),
                Cmd.Send("""{"op":"transcript.subscribe","v":1,"callId":"call_b"}""", 1),
            ),
            open(conn),
        )
        assertEquals(Phase.Open(0, 0), conn.phase)
    }

    @Test
    fun `a broadcast socket sends no mode`() {
        val commands = open(connection(broadcast = true))
        assertTrue(commands.none { it is Cmd.Send })
    }

    @Test
    fun `a server that selects another protocol is refused`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        assertEquals(
            listOf(
                Cmd.Close(1),
                Cmd.CancelTimers,
                Cmd.Emit(TelemetryUpdate.Ended(TelemetryLiveError.ProtocolMismatch)),
                Cmd.Finish,
            ),
            conn.handle(Ev.Opened("distronode.token.a.b.c", 1), 0, 0.0),
        )
    }

    @Test
    fun `a stale handshake is closed, not ignored`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        assertEquals(listOf(Cmd.Close(9)), conn.handle(Ev.Opened("distronode.telemetry.v1", 9), 0, 0.0))
        conn.handle(Ev.Stop, 0, 0.0)
        assertEquals(listOf(Cmd.Close(1)), conn.handle(Ev.Opened("distronode.telemetry.v1", 1), 0, 0.0))
    }

    @Test
    fun `a failed handshake backs off, and a stale one is ignored`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.OpenFailed("x", 4), 0, 0.0))
        assertEquals(
            Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Network("refused"))),
            conn.handle(Ev.OpenFailed("refused", 1), 0, 0.0)[0],
        )
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.OpenFailed("again", 1), 0, 0.0))
    }

    @Test
    fun `the handshake deadline closes and backs off, and fires for nothing once open`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        conn.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        val commands = conn.handle(Ev.TimerFired(TelemetryTimer.CONNECT, 1), 15_000, 0.0)
        assertEquals(Cmd.Close(1), commands[0])
        assertEquals(Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.ConnectTimedOut)), commands[1])

        val other = connection()
        open(other)
        assertEquals(emptyList<Cmd>(), other.handle(Ev.TimerFired(TelemetryTimer.CONNECT, 1), 15_000, 0.0))
        assertEquals(emptyList<Cmd>(), other.handle(Ev.TimerFired(TelemetryTimer.SILENCE, 5), 15_000, 0.0))
    }

    @Test
    fun `a text frame is decoded for the workspace, and a heartbeat only counts as hearing`() {
        val conn = connection()
        open(conn)
        val text = contract("telemetry-event-transcript-ended.json")
        val commands = conn.handle(Ev.Received(TelemetrySocketFrame.Text(text), 1), 10, 0.0)
        assertTrue(
            ((commands.single() as Cmd.Emit).update as TelemetryUpdate.Message).message is TelemetryMessage.Transcript
        )
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Received(TelemetrySocketFrame.Heartbeat, 1), 20, 0.0))
        assertEquals(Phase.Open(0, 20), conn.phase)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Received(TelemetrySocketFrame.Heartbeat, 3), 30, 0.0))
    }

    @Test
    fun `nothing is received outside an open socket`() {
        val conn = connection()
        conn.handle(Ev.Start, 0, 0.0)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Received(TelemetrySocketFrame.Heartbeat, 1), 0, 0.0))
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.Broken("x", 1), 0, 0.0))
    }

    @Test
    fun `4401 mints a new credential, and 4403 stops`() {
        val conn = connection()
        open(conn)
        val unauthorized = conn.handle(Ev.Received(TelemetrySocketFrame.Closed(4401, "expired"), 1), 1_000, 0.0)
        assertEquals(Cmd.Close(1), unauthorized[0])
        assertEquals(
            Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Unauthorized("expired"))),
            unauthorized[1]
        )
        // The credential still had 14 minutes; 4401 throws it away regardless.
        assertEquals(listOf(Cmd.Mint(2)), conn.handle(Ev.TimerFired(TelemetryTimer.BACKOFF, 1), 2_000, 0.0))

        val other = connection()
        open(other)
        assertEquals(
            listOf(
                Cmd.Close(1),
                Cmd.CancelTimers,
                Cmd.Emit(TelemetryUpdate.Ended(TelemetryLiveError.Forbidden("member"))),
                Cmd.Finish,
            ),
            other.handle(Ev.Received(TelemetrySocketFrame.Closed(4403, "member"), 1), 0, 0.0),
        )
    }

    @Test
    fun `another close reuses a credential with time left`() {
        val conn = connection()
        open(conn)
        val commands = conn.handle(Ev.Received(TelemetrySocketFrame.Closed(1001, "going away"), 1), 1_000, 0.0)
        assertEquals(
            Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Closed(1001, "going away"))),
            commands[1]
        )
        assertEquals(
            listOf(Cmd.Open(url, offered, 2), Cmd.Schedule(TelemetryTimer.CONNECT, 15_000, 2)),
            conn.handle(Ev.TimerFired(TelemetryTimer.BACKOFF, 1), 2_000, 0.0),
        )
    }

    @Test
    fun `a socket that stayed up resets the failure count`() {
        val conn = connection()
        open(conn)
        conn.handle(Ev.Broken("reset", 1), 1_000, 0.0)
        assertEquals(1, conn.failures)
        conn.handle(Ev.TimerFired(TelemetryTimer.BACKOFF, 1), 2_000, 0.0)
        conn.handle(Ev.Opened("distronode.telemetry.v1", 2), 2_000, 0.0)
        val commands = conn.handle(Ev.Broken("reset", 2), 40_000, 0.0)
        assertEquals(1, conn.failures)
        assertEquals(Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Network("reset"))), commands[0])
    }

    @Test
    fun `renewal of an established socket goes again at once on a new credential`() {
        val conn = connection()
        open(conn)
        val commands = conn.handle(Ev.TimerFired(TelemetryTimer.RENEWAL, 1), 840_000, 0.0)
        assertEquals(
            listOf(
                Cmd.Close(1),
                Cmd.Emit(TelemetryUpdate.Reconnecting(0, TelemetryDisconnect.Renewal)),
                Cmd.Schedule(TelemetryTimer.BACKOFF, 0, 1),
            ),
            commands,
        )
        assertEquals(listOf(Cmd.Mint(2)), conn.handle(Ev.TimerFired(TelemetryTimer.BACKOFF, 1), 840_000, 0.0))
    }

    @Test
    fun `renewal of a socket that did not settle backs off`() {
        val conn = connection()
        open(conn)
        val commands = conn.handle(Ev.TimerFired(TelemetryTimer.RENEWAL, 1), 5_000, 0.0)
        assertEquals(Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Renewal)), commands[1])
    }

    @Test
    fun `the silence watchdog re-arms for what is left, then closes a silent socket`() {
        val conn = connection()
        open(conn)
        conn.handle(Ev.Received(TelemetrySocketFrame.Heartbeat, 1), 60_000, 0.0)
        assertEquals(
            listOf(Cmd.Schedule(TelemetryTimer.SILENCE, 60_000, 1)),
            conn.handle(Ev.TimerFired(TelemetryTimer.SILENCE, 1), 90_000, 0.0),
        )
        val commands = conn.handle(Ev.TimerFired(TelemetryTimer.SILENCE, 1), 150_000, 0.0)
        assertEquals(Cmd.Close(1), commands[0])
        // Open for 150 s, so stable: the count resets and this is the first failure again.
        assertEquals(Cmd.Emit(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Silent)), commands[1])
    }

    @Test
    fun `stop closes what is open, ends what is not, and runs once`() {
        val opened = connection()
        open(opened)
        assertEquals(
            listOf(Cmd.Close(1), Cmd.CancelTimers, Cmd.Emit(TelemetryUpdate.Ended(null)), Cmd.Finish),
            opened.handle(Ev.Stop, 0, 0.0),
        )
        assertEquals(emptyList<Cmd>(), opened.handle(Ev.Stop, 0, 0.0))

        val idle = connection()
        assertEquals(
            listOf(Cmd.CancelTimers, Cmd.Emit(TelemetryUpdate.Ended(null)), Cmd.Finish),
            idle.handle(Ev.Stop, 0, 0.0)
        )

        val connecting = connection()
        connecting.handle(Ev.Start, 0, 0.0)
        connecting.handle(Ev.Minted(ApiResult.Success(token), 1), 0, 0.0)
        assertEquals(Cmd.Close(1), connecting.handle(Ev.Stop, 0, 0.0).first())
    }

    @Test
    fun `subscriptions are sent while open, remembered while closed, and validated`() {
        val conn = connection()
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.SubscribeTranscript("call_a"), 0, 0.0))
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.SubscribeTranscript("bad id"), 0, 0.0))
        open(conn)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.SubscribeTranscript("call_a"), 0, 0.0))
        assertEquals(
            listOf(Cmd.Send("""{"op":"transcript.subscribe","v":1,"callId":"call_b"}""", 1)),
            conn.handle(Ev.SubscribeTranscript("call_b"), 0, 0.0),
        )
        assertEquals(
            listOf(Cmd.Send("""{"op":"transcript.subscribe","v":1,"callId":"call_b"}""", 1)),
            conn.handle(Ev.ResubscribeTranscript("call_b"), 0, 0.0),
        )
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.ResubscribeTranscript("call_z"), 0, 0.0))
        assertEquals(
            listOf(Cmd.Send("""{"op":"transcript.unsubscribe","v":1,"callId":"call_b"}""", 1)),
            conn.handle(Ev.UnsubscribeTranscript("call_b"), 0, 0.0),
        )
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.UnsubscribeTranscript("call_b"), 0, 0.0))
        assertEquals(setOf("call_a"), conn.transcriptSubscriptions)
    }

    @Test
    fun `a backoff timer outside waiting, or a stale one, does nothing`() {
        val conn = connection()
        open(conn)
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.TimerFired(TelemetryTimer.BACKOFF, 1), 0, 0.0))
        assertEquals(emptyList<Cmd>(), conn.handle(Ev.TimerFired(TelemetryTimer.RENEWAL, 2), 0, 0.0))
    }

    @Test
    fun `transient failures are the ones a later try may fix`() {
        assertTrue(TelemetryConnection.isTransient(ApiResult.NetworkFailure(IOException())))
        assertTrue(TelemetryConnection.isTransient(ApiResult.RateLimited("slow")))
        assertTrue(TelemetryConnection.isTransient(ApiResult.RegionsDegraded("x", listOf("eu"))))
        assertTrue(TelemetryConnection.isTransient(ApiResult.HttpFailure(503, "x")))
        assertFalse(TelemetryConnection.isTransient(ApiResult.HttpFailure(400, "x")))
        assertFalse(TelemetryConnection.isTransient(ApiResult.Unauthorized(null)))
        assertFalse(TelemetryConnection.isTransient(ApiResult.Forbidden("x")))
        assertFalse(TelemetryConnection.isTransient(ApiResult.NotFound("x")))
        assertFalse(TelemetryConnection.isTransient(ApiResult.DecodeFailure(IllegalStateException(), "")))
    }
}
