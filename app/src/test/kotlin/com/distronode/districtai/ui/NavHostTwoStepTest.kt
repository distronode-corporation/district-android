// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.ui.settings.SETTINGS_TWO_STEP_DESCRIPTION

/**
 * Two-step verification is a screen in this app, reached from the account settings.
 *
 * ⛔ IN THE APP, NOT A BROWSER HAND-OFF. Turning it on shows the secret and the recovery codes, which
 * this app covers from screenshots and the app switcher; a hand-off to the web would show them in a
 * browser tab this app cannot cover.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostTwoStepTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `the settings screen opens two-step verification in the app`() {
        harness.installBrowser()
        harness.render()
        harness.navigate(Routes.SETTINGS)

        harness.tap(SETTINGS_TWO_STEP_DESCRIPTION)

        assertEquals(Routes.TWO_STEP, harness.route())
        assertTrue("nothing opens in a browser", harness.started.isEmpty())
    }
}
