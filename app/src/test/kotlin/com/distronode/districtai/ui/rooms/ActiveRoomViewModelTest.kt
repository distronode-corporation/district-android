package com.distronode.districtai.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.E2eeInfo
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The live room's state machine.
 *
 * ⛔ THE FIVE THINGS THIS SCREEN MUST NOT DO, and none of them is visible from the rendering.
 *   1. It must not treat `Reconnecting` as a failure. A phone handing over between wifi and its
 *      radio does this routinely and the SDK recovers by itself; a UI that tore the call down would
 *      be ending meetings during the ordinary event it was meant to survive.
 *   2. It must not draw the Companion as a participant. The transcription agent joins every meet_
 *      room and publishes no media, so it would be a blank muted tile — and hiding it entirely
 *      would mean people are transcribed with nothing on screen saying so.
 *   3. It must not offer a viewer controls the server will refuse. A viewer's token carries
 *      `canPublish:false`; enabling its microphone is an action that cannot work.
 *   4. It must not connect before the permission answer arrives. Publishing a microphone track
 *      before the user has answered produces a SILENT track rather than an error, which looks like
 *      a working microphone to everyone except the people who cannot hear it.
 *   5. It must not disconnect twice, or fail to disconnect at all. Leaving without disconnecting
 *      strands the participant in the room for everybody else until the server times them out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRoomViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    /**
     * The scope the ViewModel's engine is built with.
     *
     * ⛔ A SCOPE THE TEST OWNS, NOT `runTest`'s `backgroundScope`, AND THIS IS A TRAP WORTH
     * RECORDING RATHER THAN WORKING AROUND SILENTLY. Work launched into `backgroundScope` is NOT
     * run by `advanceUntilIdle()` — measured here, not assumed: the leave path's disconnect simply
     * never executed and the assertion read `expected:<1> but was:<0>`, which looks exactly like a
     * ViewModel that forgot to disconnect. A plain scope on the same test dispatcher is also the
     * closer analogue of production, where this scope is one the ViewModel constructs itself
     * precisely so it can outlive `onCleared`.
     *
     * ⚠️ Cancelled in [tearDown] so a test that never calls `leave` does not leave it running.
     */
    private lateinit var engineScope: CoroutineScope

    @Before
    fun setUp() {
        engineScope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private val roomName = "meet_ws-abc_standup"

    private fun api(
        token: RoomTokenResponse = RoomTokenResponse(
            success = true,
            token = "jwt-abc",
            url = "wss://livekit.test",
        ),
    ) = FakeDistrictApi().apply { roomTokenResult = ApiResult.Success(token) }

    private fun viewModel(
        api: FakeDistrictApi = api(),
        factory: FakeCallEngineFactory = FakeCallEngineFactory(),
        role: WorkspaceRole? = WorkspaceRole.AGENCY,
        scope: CoroutineScope = engineScope,
    ) = ActiveRoomViewModel(
        engineFactory = factory,
        repository = MeetingsRepository(api),
        roomName = roomName,
        role = role,
        webOrigin = "https://www.distronode.test",
        engineScope = scope,
    )

    // ── The join ─────────────────────────────────────────────────────────────

    @Test
    fun `nothing connects until the permission answer arrives`() = runTest {
        // ⛔ THE RACE THIS PREVENTS IS SILENT. Connecting in init and asking for permissions
        // alongside it lets the engine publish a microphone track before the dialog is answered,
        // and Android hands it a track that carries no audio rather than an error.
        val factory = FakeCallEngineFactory()
        val api = api()
        val vm = viewModel(api = api, factory = factory)
        advanceUntilIdle()

        assertTrue("no token may be minted before permissions", api.roomTokenRequests.isEmpty())
        assertTrue("the engine must not be connected", factory.engine.calls.isEmpty())
        assertEquals(CallConnectionState.Idle, vm.state.value.connection)
    }

    @Test
    fun `a granted pair mints one token and connects with it verbatim`() = runTest {
        val factory = FakeCallEngineFactory()
        val api = api()
        val vm = viewModel(api = api, factory = factory)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        // ⛔ THE ROOM NAME REACHES THE SERVER UNCHANGED. Nothing between the destination and the
        // request may rewrite it — the prefix is a billing boundary.
        assertEquals(roomName, api.roomTokenRequests.single().roomName)
        // ⛔ THE url/token PAIR IS USED VERBATIM. A room exists only on the deployment that created
        // it, so a client that substituted a URL would join a bus that never heard of the room.
        assertTrue(factory.engine.calls.contains("connect:wss://livekit.test:jwt-abc"))
        assertEquals(CallConnectionState.Connected, vm.state.value.connection)
    }

    @Test
    fun `the room key reaches the engine VERBATIM, never decoded`() = runTest {
        // ⛔ THE ONE ASSERTION THAT CATCHES A SILENT ESTATE-WIDE BREAK. The server, the web client,
        // the voice agent and this app all feed the same base64 TEXT to their SDK as a passphrase;
        // every SDK UTF-8-encodes it and derives the AES key with PBKDF2. If this client decoded it
        // to 32 raw bytes first, it would join the room, publish happily, and be unable to decrypt
        // anyone — no exception, no log, just a meeting where nobody can hear each other. Equality
        // against the literal is what pins that nothing between the DTO and the SDK touches it.
        val key = "CgAZ6Mja5niqpHLjUH0iEwcJVPHbcGBtE51R056G4OE="
        val factory = FakeCallEngineFactory()
        val vm = viewModel(
            api = api(
                RoomTokenResponse(
                    success = true,
                    token = "jwt-abc",
                    url = "wss://livekit.test",
                    e2ee = E2eeInfo(key = key),
                ),
            ),
            factory = factory,
        )

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertEquals(key, factory.engine.lastE2eeKey)
        assertEquals(CallConnectionState.Connected, vm.state.value.connection)
    }

    @Test
    fun `a token with no e2ee block joins unencrypted rather than refusing`() = runTest {
        // ⛔ THE `call_` ROOMS, WHICH ARE MOST OF THIS ROUTE'S TRAFFIC. A phone call has a SIP leg
        // the carrier delivers unencrypted, so there is no key and there cannot be one. Refusing to
        // join without one would break the supervisor path entirely.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertNull("no key means join unencrypted", factory.engine.lastE2eeKey)
        assertTrue(factory.engine.calls.contains("connect:wss://livekit.test:jwt-abc"))
        assertEquals(CallConnectionState.Connected, vm.state.value.connection)
    }

    @Test
    fun `a blank room key is refused rather than forwarded to the engine`() = runTest {
        // ⛔ BLANK IS NOT "NO ENCRYPTION", AND THAT IS THE WHOLE POINT OF THE GUARD. An empty
        // passphrase derives a perfectly real AES key that no other participant derives, so
        // forwarding it would encrypt everything with a key unique to this handset — the room
        // connects and then nobody can hear anybody, which reads as a media fault and would be
        // debugged against the SFU. Joining unencrypted is the recoverable failure; a private key
        // is not. ⚠️ It still JOINS: refusing the call outright would be worse than joining in the
        // clear, and the server has already decided what this token may do.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(
            api = api(
                RoomTokenResponse(
                    success = true,
                    token = "jwt-abc",
                    url = "wss://livekit.test",
                    e2ee = E2eeInfo(key = "   "),
                ),
            ),
            factory = factory,
        )

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertNull("a blank key must never reach the SDK", factory.engine.lastE2eeKey)
        assertTrue("but the join still happens", factory.engine.calls.contains("connect:wss://livekit.test:jwt-abc"))
    }

    @Test
    fun `a second permission callback does not rejoin a room already joined`() = runTest {
        // ⚠️ A CONFIGURATION CHANGE RE-RUNS THE LAUNCHER'S EFFECT. Re-joining would tear down the
        // media this engine has just established.
        val factory = FakeCallEngineFactory()
        val api = api()
        val vm = viewModel(api = api, factory = factory)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertEquals("exactly one token", 1, api.roomTokenRequests.size)
        assertEquals(
            "exactly one connect",
            1,
            factory.engine.calls.count { it.startsWith("connect:") },
        )
    }

    @Test
    fun `the microphone comes up and the camera does NOT`() = runTest {
        // ⛔ THE ASYMMETRY IS DELIBERATE. Joining muted is how a meeting happens where nobody
        // realises they are inaudible; joining with the camera live is how somebody is seen before
        // they meant to be. Granting the camera permission is consent to USE it, not consent to be
        // on it.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertTrue(factory.engine.calls.contains("mic:true"))
        assertFalse(
            "the camera must not be published on join",
            factory.engine.calls.any { it.startsWith("camera:") },
        )
        assertTrue(vm.state.value.micEnabled)
        assertFalse(vm.state.value.cameraEnabled)
    }

    @Test
    fun `a denied microphone still joins, in listen-only`() = runTest {
        // ⛔ NOT A REFUSAL TO JOIN. Listen-only attendance is exactly the seat the server gives a
        // viewer anyway; refusing over a denied microphone would deny a seat the product offers.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.onPermissionsResult(microphoneGranted = false, cameraGranted = false)
        advanceUntilIdle()

        assertEquals(CallConnectionState.Connected, vm.state.value.connection)
        assertFalse(
            "an unpermitted microphone must not be published",
            factory.engine.calls.contains("mic:true"),
        )
        assertTrue(vm.state.value.permissions.requested)
        assertFalse(vm.state.value.permissions.microphoneGranted)
    }

    @Test
    fun `a failed token request reports the API failure and stays Idle`() = runTest {
        // ⛔ BACK TO Idle, NOT Failed. The engine never connected, so its own flow still reads Idle
        // and would overwrite anything set here; the reason the user needs is the API failure, and
        // leaving the state on Connecting would spin over a join that will never happen.
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Forbidden("not a member of this workspace")
        }
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api = api, factory = factory)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertEquals(CallConnectionState.Idle, vm.state.value.connection)
        assertNotNull(vm.state.value.joinFailure)
        assertTrue("the engine must never have been asked to connect", factory.engine.calls.isEmpty())
    }

    @Test
    fun `an engine that throws is reported, not rethrown into the process`() = runTest {
        // ⚠️ The real engine sets Failed and RETHROWS, so a ViewModel that did not catch would
        // crash the process for a network condition.
        val factory = FakeCallEngineFactory()
        factory.engine.connectFailure = IllegalStateException("media server unreachable")
        val vm = viewModel(factory = factory)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertTrue(vm.state.value.connection is CallConnectionState.Failed)
    }

    // ── Reconnecting ─────────────────────────────────────────────────────────

    @Test
    fun `Reconnecting is carried through as its own state, never as a failure`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        factory.engine.emitConnection(CallConnectionState.Reconnecting)
        advanceUntilIdle()

        assertEquals(CallConnectionState.Reconnecting, vm.state.value.connection)
        // ⛔ AND THE PARTICIPANTS STAY. Blanking the grid during a handover would tell everyone
        // the meeting had emptied while it was still there.
        assertTrue("the room must not be torn down", factory.engine.calls.none { it == "disconnect" })

        factory.engine.emitConnection(CallConnectionState.Connected)
        advanceUntilIdle()
        assertEquals(CallConnectionState.Connected, vm.state.value.connection)
    }

    // ── The Companion ────────────────────────────────────────────────────────

    @Test
    fun `the Companion is removed from the grid and surfaced as a flag`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        factory.engine.emitParticipants(
            listOf(
                MediaParticipant(identity = "user-4f2a", name = "Ada"),
                // The framework's own default identity and kind.
                MediaParticipant(identity = "agent-AJ_abc123", name = null, isAgent = true),
                MediaParticipant(identity = "user-9c11", name = "Grace"),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("user-4f2a", "user-9c11"),
            vm.state.value.participants.map { it.identity },
        )
        // ⛔ NOT MERELY HIDDEN. It is transcribing the conversation, and a person in the room is
        // entitled to know that.
        assertTrue(vm.state.value.companionPresent)
    }

    @Test
    fun `the retired browser-side companion identity is filtered too`() = runTest {
        // ⚠️ MATCHES THE WEB'S `isBotParticipant` EXACTLY, prefix fallback included. Summon no
        // longer mints this identity, but a room built before the metadata cutover can still
        // contain one — and getting the filter wrong on one platform produces a blank tile only
        // phone users see.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        factory.engine.emitParticipants(
            listOf(
                MediaParticipant(identity = "ai-companion-9f2c", name = null, isAgent = false),
                MediaParticipant(identity = "user-4f2a", name = "Ada"),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("user-4f2a"), vm.state.value.participants.map { it.identity })
        assertTrue(vm.state.value.companionPresent)
    }

    @Test
    fun `a room with no agent reports no Companion`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "user-4f2a", name = "Ada")))
        advanceUntilIdle()

        assertFalse(vm.state.value.companionPresent)
        assertEquals(1, vm.state.value.participants.size)
    }

    // ── The viewer seat ──────────────────────────────────────────────────────

    @Test
    fun `a viewer joins but never publishes`() = runTest {
        // ⛔ THE SERVER WOULD REFUSE IT ANYWAY (`canPublish:false` on the token), so the point of
        // this gate is not enforcement — it is not offering an action that cannot work.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory, role = WorkspaceRole.VIEWER)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertFalse(vm.state.value.canPublish)
        assertEquals(CallConnectionState.Connected, vm.state.value.connection)
        assertFalse(
            "a viewer's microphone must never be published on join",
            factory.engine.calls.contains("mic:true"),
        )

        vm.toggleMicrophone()
        vm.toggleCamera()
        advanceUntilIdle()
        assertFalse(factory.engine.calls.any { it.startsWith("mic:") })
        assertFalse(factory.engine.calls.any { it.startsWith("camera:") })
    }

    @Test
    fun `an unrecognised role fails closed to no publishing`() = runTest {
        // ⚠️ A null role is what `WorkspaceRole.fromWire` returns for a corrupted or renamed value
        // in the route. Failing closed means a broken destination offers no privileges rather than
        // defaulting to permissive ones.
        val vm = viewModel(role = null)
        assertFalse(vm.state.value.canPublish)
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    @Test
    fun `the media controls forward to the engine and reflect its state`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        vm.toggleMicrophone()
        advanceUntilIdle()
        assertFalse("the microphone was on after join, so this mutes", vm.state.value.micEnabled)

        vm.toggleCamera()
        advanceUntilIdle()
        assertTrue(vm.state.value.cameraEnabled)

        vm.flipCamera()
        advanceUntilIdle()
        assertTrue(factory.engine.calls.contains("flip"))

        vm.toggleSpeaker()
        assertTrue(vm.state.value.speakerOn)
        assertTrue(factory.engine.calls.contains("speaker:true"))
    }

    @Test
    fun `flip does nothing while the camera is off`() = runTest {
        // ⚠️ Flipping a camera that is not publishing has no visible effect, so an enabled control
        // would report success for an action that did nothing.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        vm.flipCamera()
        advanceUntilIdle()

        assertFalse(factory.engine.calls.contains("flip"))
    }

    @Test
    fun `an unpermitted camera cannot be toggled on`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = false)
        advanceUntilIdle()

        vm.toggleCamera()
        advanceUntilIdle()

        assertFalse(factory.engine.calls.any { it.startsWith("camera:") })
        assertFalse(vm.state.value.cameraEnabled)
    }

    @Test
    fun `every control is a toggle, so a second press undoes the first`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        vm.toggleMicrophone()
        advanceUntilIdle()
        vm.toggleMicrophone()
        vm.toggleCamera()
        advanceUntilIdle()
        vm.toggleCamera()
        vm.toggleSpeaker()
        vm.toggleSpeaker()
        advanceUntilIdle()

        assertTrue("unmuted again", vm.state.value.micEnabled)
        assertFalse("camera off again", vm.state.value.cameraEnabled)
        assertFalse("speaker off again", vm.state.value.speakerOn)
        assertEquals(listOf("camera:true", "camera:false"), factory.engine.calls.filter { it.startsWith("camera:") })
        assertEquals(listOf("speaker:true", "speaker:false"), factory.engine.calls.filter { it.startsWith("speaker:") })
    }

    @Test
    fun `an unpermitted microphone cannot be toggled on, even with publish rights`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = false, cameraGranted = true)
        advanceUntilIdle()

        vm.toggleMicrophone()
        advanceUntilIdle()

        assertTrue(vm.state.value.canPublish)
        assertFalse(factory.engine.calls.any { it.startsWith("mic:") })
        assertFalse(vm.state.value.micEnabled)
    }

    // ── The guest invite ─────────────────────────────────────────────────────

    @Test
    fun `the guest link is the server's path on this app's origin`() = runTest {
        val api = api(
            token = RoomTokenResponse(
                success = true,
                token = "jwt",
                url = "wss://livekit.test",
                guestPath = "/meet/meet_ws-abc_standup?e=1786973400&s=sig",
            ),
        )
        val vm = viewModel(api = api)

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertEquals(
            "https://www.distronode.test/meet/meet_ws-abc_standup?e=1786973400&s=sig",
            vm.state.value.guestLink,
        )
    }

    @Test
    fun `no guest path means no link, and none is synthesised`() = runTest {
        // ⛔ THE SERVER WITHHELD IT ON PURPOSE — the invite grants PUBLISH rights to whoever holds
        // it. A link built from the room name would look like a working invitation to the person
        // sharing it, right up until their guest could not join.
        val vm = viewModel()

        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertNull(vm.state.value.guestLink)
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    @Test
    fun `leave disconnects exactly once and then reports back`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        var left = 0
        vm.leave { left++ }
        advanceUntilIdle()

        assertEquals(1, factory.engine.calls.count { it == "disconnect" })
        assertEquals("the callback runs after the disconnect resolves", 1, left)
    }

    @Test
    fun `a second leave is dropped rather than disconnecting twice`() = runTest {
        // ⛔ THE DOUBLE-TAP. The second disconnect would race the first's cancellation of the
        // engine scope, and the engine's own idempotence is not something this layer may rely on
        // for a control the user can press twice in a second.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        var left = 0
        vm.leave { left++ }
        vm.leave { left++ }
        advanceUntilIdle()

        assertEquals(1, factory.engine.calls.count { it == "disconnect" })
        assertEquals(1, left)
    }

    @Test
    fun `a permission answer arriving after leave does not join the room it left`() = runTest {
        // ⚠️ REACHABLE: the permission dialog is still up when the user backs out, and the answer
        // lands on a ViewModel that has already released its engine.
        val factory = FakeCallEngineFactory()
        val api = api()
        val vm = viewModel(api = api, factory = factory)

        vm.leave {}
        // Before the disconnect has run, the engine still reads Idle: only the release stops a join.
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()
        // And after it, when the engine reads Disconnected.
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        advanceUntilIdle()

        assertTrue("no token is minted for a room already left", api.roomTokenRequests.isEmpty())
        assertFalse(factory.engine.calls.any { it.startsWith("connect:") })
    }

    @Test
    fun `a join cancelled by clearing the screen is not reported as a failed one`() = runTest {
        // ⚠️ `runCatching` CAUGHT THE CANCELLATION and wrote `Failed` with a coroutine-internals
        // message over a teardown. Cancellation now propagates; the release still disconnects.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine()
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ActiveRoomViewModel(
                    engineFactory = CallEngineFactory {
                        object : CallEngine by engine {
                            override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
                                gate.await()
                                engine.connect(url, token, e2eeKeyBase64)
                            }
                        }
                    },
                    repository = MeetingsRepository(api()),
                    roomName = roomName,
                    role = WorkspaceRole.AGENCY,
                    webOrigin = "https://www.distronode.test",
                    engineScope = engineScope,
                ) as T
            },
        )[ActiveRoomViewModel::class.java]
        runCurrent()
        vm.onPermissionsResult(microphoneGranted = true, cameraGranted = true)
        runCurrent()
        assertEquals(CallConnectionState.Connecting, vm.state.value.connection)

        store.clear()
        runCurrent()

        assertFalse(vm.state.value.connection is CallConnectionState.Failed)
        assertEquals(listOf("disconnect"), engine.calls)
    }

    @Test
    fun `the engine is built with the scope that outlives the ViewModel`() = runTest {
        // ⛔ NOT `viewModelScope`, AND THAT IS THE WHOLE TEARDOWN CONTRACT. `onCleared` fires after
        // viewModelScope is cancelled, so a disconnect launched there never reaches the socket and
        // the room keeps the participant until the server times them out — a ghost in everyone
        // else's grid, and a meeting the Companion records as still running.
        val factory = FakeCallEngineFactory()
        viewModel(factory = factory)

        assertEquals("exactly one engine per room", 1, factory.scopes.size)
        assertEquals(engineScope, factory.scopes.single())
    }
}
