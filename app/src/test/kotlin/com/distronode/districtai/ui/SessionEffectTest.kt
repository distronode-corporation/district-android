package com.distronode.districtai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THE ENTIRE VALUE OF THIS COMPOSABLE IS THE FIRST TEST HERE: it must NOT fire on entry.
 *
 * A bare `LaunchedEffect(epoch) { reload() }` also runs on first composition, which would reload
 * every screen immediately after its own `init { load() }` — reintroducing, per screen, exactly the
 * doubled cold-start traffic that hoisting the overview ViewModel removed. `overview` fans out
 * across up to four regional databases, so a spurious extra call is the most expensive mistake
 * available here.
 *
 * ⚠️ The baseline is `remember`, not `rememberSaveable`, and that is correct: a configuration
 * change is not a session change. That distinction is not observable from a unit test (Robolectric
 * would need a real activity recreation), so it is asserted only as far as recomposition goes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SessionEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `does not fire on first composition`() {
        var fired = 0
        composeRule.setContent { OnSessionChanged(epoch = 7) { fired++ } }
        composeRule.waitForIdle()

        assertEquals(0, fired)
    }

    @Test
    fun `fires once when the epoch advances past the value observed at entry`() {
        var fired = 0
        var epoch by mutableIntStateOf(0)
        composeRule.setContent { OnSessionChanged(epoch = epoch) { fired++ } }
        composeRule.waitForIdle()

        epoch = 1
        composeRule.waitForIdle()

        assertEquals(1, fired)
    }

    @Test
    fun `fires again on a further advance`() {
        // Sign in, then sign out while the same screen exists: both mean "the identity behind this
        // data changed, re-read".
        var fired = 0
        var epoch by mutableIntStateOf(0)
        composeRule.setContent { OnSessionChanged(epoch = epoch) { fired++ } }
        composeRule.waitForIdle()

        epoch = 1
        composeRule.waitForIdle()
        epoch = 2
        composeRule.waitForIdle()

        assertEquals(2, fired)
    }

    @Test
    fun `a recomposition that does not change the epoch fires nothing`() {
        var fired = 0
        var unrelated by mutableIntStateOf(0)
        composeRule.setContent {
            // Read the unrelated state so this composable actually recomposes.
            val ignored = unrelated
            OnSessionChanged(epoch = 5 + (ignored * 0)) { fired++ }
        }
        composeRule.waitForIdle()

        unrelated = 1
        composeRule.waitForIdle()

        assertEquals(0, fired)
    }

    @Test
    fun `an epoch that returns to the entry value still counts as an advance`() {
        // ⚠️ The comparison is `!=` against the baseline, not `>`. A counter only ever increases in
        // production, so this pins the implemented behaviour rather than asserting a monotonicity
        // this composable does not enforce.
        var fired = 0
        var epoch by mutableIntStateOf(3)
        composeRule.setContent { OnSessionChanged(epoch = epoch) { fired++ } }
        composeRule.waitForIdle()

        epoch = 4
        composeRule.waitForIdle()
        epoch = 3
        composeRule.waitForIdle()

        assertEquals(1, fired)
    }
}
