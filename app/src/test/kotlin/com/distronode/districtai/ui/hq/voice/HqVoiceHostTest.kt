package com.distronode.districtai.ui.hq.voice

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.HqVoiceRepository
import com.distronode.districtai.core.media.HqVoiceEngineEvent
import com.distronode.districtai.core.media.HqVoiceEngineFactory
import com.distronode.districtai.core.network.ApiResult
import androidx.compose.ui.test.performTouchInput
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The voice bar wired to its session: the permission is launched only when the session asks, once,
 * and the screen going away ends the session.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HqVoiceHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = FakeHqVoiceApi()
    private val hq = FakeDistrictApi()
    private val engines = mutableListOf<FakeHqVoiceEngine>()
    private var granted = true
    private val launched = mutableListOf<String>()
    private val audio = object : HqVoiceAudio {
        override fun isBusy() = false
        override fun hasMicrophonePermission() = false
    }

    /** Answers every permission request at once, with [granted], and records what was asked. */
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launched += input.toString()
            dispatchResult(requestCode, granted)
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    private val session = HqVoiceSession(
        workspaceId = "ws-1",
        canConfirm = true,
        repository = HqVoiceRepository(api, hq),
        engineFactory = HqVoiceEngineFactory { _, key -> FakeHqVoiceEngine(key).also { engines += it } },
        audio = audio,
        scope = scope,
    )

    @After
    fun tearDown() = scope.cancel()

    private var shown by mutableStateOf(true)

    private fun render(history: List<HqTurn> = listOf(HqTurn("user", "Hi"))) {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                DistrictTheme {
                    if (shown) HqVoiceHost(session, "ca", { history }, onOpenSettings = { launched += "settings" })
                }
            }
        }
    }

    @Test
    fun `the microphone is asked for once, after Start, and a grant joins the room`() {
        render()

        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals("nothing asked at the Talk tap", emptyList<String>(), launched)
        assertEquals(listOf(HqTurn("user", "Hi")), api.tokenRequests.single().third)
        assertEquals("en", api.tokenRequests.single().second)
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), launched)
        assertEquals(HqVoicePhase.Live, session.state.value.phase)
        assertEquals(0, session.state.value.microphoneRequest)
        assertEquals(1, engines.size)
    }

    @Test
    fun `a refused microphone discards the session`() {
        granted = false
        render()

        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.MicrophoneDenied), session.state.value.phase)
        assertEquals(listOf("token", "discard:s1"), api.calls)
    }

    @Test
    fun `the screen going away ends the live session, and every control reaches the session`() {
        render()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_CANCEL_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_MUTE_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        engines.single().propose("p1")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_CONFIRM_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        engines.single().propose("p2")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_CANCEL_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        shown = false
        composeRule.waitForIdle()

        assertEquals("settings", launched.first())
        assertEquals(
            listOf("token", "discard:s1", "token", "context", "cancel:p2", "end:s1"),
            api.calls,
        )
        assertEquals(listOf(true, false), engines.single().microphone)
        assertEquals("p1", hq.hqConfirms.single().confirm.proposalId)
    }

    @Test
    fun `Hold to talk reaches a push-to-talk session`() {
        api.tokenResult = ApiResult.Success(FakeHqVoiceApi.CREDENTIAL.copy(micMode = "push"))
        render()
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).performTouchInput { down(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(listOf(true, false), engines.single().microphone)
    }

    @Test
    fun `a new session for the same screen is wired afresh, and the old one ended`() {
        val second = HqVoiceSession(
            workspaceId = "ws-2",
            canConfirm = false,
            repository = HqVoiceRepository(api, hq),
            engineFactory = HqVoiceEngineFactory { _, key -> FakeHqVoiceEngine(key).also { engines += it } },
            audio = audio,
            scope = scope,
        )
        var current by mutableStateOf(session)
        var turns by mutableStateOf(listOf(HqTurn("user", "Hi")))
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                DistrictTheme { HqVoiceHost(current, null, { turns }, onOpenSettings = {}) }
            }
        }
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        current = second
        turns = emptyList()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        assertEquals("the first session's notice is given back", HqVoicePhase.Idle, session.state.value.phase)
        assertEquals(HqVoicePhase.Live, second.state.value.phase)
        assertEquals(emptyList<HqTurn>(), api.tokenRequests.last().third)
    }

    private fun FakeHqVoiceEngine.propose(id: String) = emit(
        HqVoiceEngineEvent.Packet(
            """{"type":"HQ_PROPOSAL","proposalId":"$id","tool":"t","args":{},"summary":"S $id"}""".encodeToByteArray(),
        ),
    )
}
