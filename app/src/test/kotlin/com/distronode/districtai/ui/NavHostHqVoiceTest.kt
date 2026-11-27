// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.hq.HqMessage
import com.distronode.districtai.ui.hq.HqRole
import com.distronode.districtai.ui.hq.voice.HQ_VOICE_BAR_DESCRIPTION
import com.distronode.districtai.ui.hq.voice.HQ_VOICE_SETTINGS_DESCRIPTION
import com.distronode.districtai.ui.hq.voice.HQ_VOICE_SETTINGS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.hq.voice.hqVoiceOptionTag
import com.distronode.districtai.ui.hq.voice.HqVoiceSetting
import com.distronode.districtai.ui.hq.voiceHistory
import com.distronode.districtai.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** District HQ by voice in the navigation graph: the voice bar on HQ, and its settings screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostHqVoiceTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `HQ carries the voice bar, whose settings open the member's own voice settings`() {
        harness.render()
        harness.navigate(Routes.hq("ws-1", WorkspaceRole.VIEWER))
        harness.awaitDescription(HQ_VOICE_BAR_DESCRIPTION)

        harness.tap(HQ_VOICE_SETTINGS_DESCRIPTION)
        harness.awaitDescription(HQ_VOICE_SETTINGS_ROOT_DESCRIPTION)
        assertEquals(Routes.HQ_VOICE_SETTINGS, harness.route())
        assertEquals("ws-1", harness.argument(ARG_WORKSPACE_ID))
        harness.awaitDescription(hqVoiceOptionTag(HqVoiceSetting.VOICE, "Puck"))

        harness.bumpEpoch()
        assertEquals("a session change re-reads the settings", 2, harness.hqVoice.calls.count { it == "settings" })

        harness.back()
        assertEquals(Routes.HQ, harness.route())
        composeRule.onNodeWithTag(HQ_VOICE_BAR_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the agent is told the console's own turns, never the app's receipts`() {
        val messages = listOf(
            HqMessage(HqRole.OPERATOR, UiText.Literal("How many calls?")),
            HqMessage(HqRole.CONSOLE, UiText.Literal("Nine.")),
            HqMessage(HqRole.CONSOLE, UiText.Resource(R.string.hq_applied)),
        )

        assertEquals(listOf(HqTurn("user", "How many calls?"), HqTurn("model", "Nine.")), messages.voiceHistory())
    }

    @Test
    fun `the settings route is the template's shape`() {
        assertEquals(Routes.HQ_VOICE_SETTINGS.split("/").size, Routes.hqVoiceSettings("ws/1").split("/").size)
    }
}
