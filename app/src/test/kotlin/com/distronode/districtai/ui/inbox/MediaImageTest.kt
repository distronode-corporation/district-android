package com.distronode.districtai.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One thumbnail, driven by a loader that answers in the test.
 *
 * The thread screen's own tests cover the loading and failed states; these cover the image that
 * arrives, and a row whose URL changes under it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
// ⚠️ Native graphics: the legacy mode cannot allocate the bitmap a loaded thumbnail is.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaImageTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    @Test
    fun `a decoded image replaces the placeholder, and a tap opens its url`() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            DistrictTheme {
                MediaImage(url = FIRST, loader = { ImageBitmap(4, 4) }, onOpen = { opened += it })
            }
        }

        composeRule.onNodeWithContentDescription(THREAD_MEDIA_LOADING_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(THREAD_MEDIA_FAILED_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(THREAD_MEDIA_DESCRIPTION).assertIsDisplayed().performClick()

        assertEquals(listOf(FIRST), opened)
    }

    @Test
    fun `a new url is fetched and the old one is not fetched again`() {
        val loads = mutableListOf<String>()
        var url by mutableStateOf(FIRST)
        composeRule.setContent {
            DistrictTheme {
                MediaImage(
                    url = url,
                    loader = { requested ->
                        loads += requested
                        ImageBitmap(4, 4)
                    },
                    onOpen = {},
                )
            }
        }
        composeRule.waitForIdle()

        url = SECOND
        composeRule.waitForIdle()

        assertEquals(listOf(FIRST, SECOND), loads)
    }

    @Test
    fun `a loader replaced under an image already shown is not asked for it again`() {
        // ⚠️ The fetch is keyed on the URL alone: a recomposition that hands in a new loader object
        // (every screen rebuild does) must not refetch a thumbnail that is already on screen.
        val firstLoads = mutableListOf<String>()
        val secondLoads = mutableListOf<String>()
        var loader by mutableStateOf(
            MediaImageLoader { url ->
                firstLoads += url
                ImageBitmap(4, 4)
            },
        )
        composeRule.setContent {
            DistrictTheme { MediaImage(url = FIRST, loader = loader, onOpen = {}) }
        }
        composeRule.waitForIdle()

        loader = MediaImageLoader { url ->
            secondLoads += url
            null
        }
        composeRule.waitForIdle()

        assertEquals(listOf(FIRST), firstLoads)
        assertEquals(emptyList<String>(), secondLoads)
        composeRule.onNodeWithContentDescription(THREAD_MEDIA_FAILED_DESCRIPTION).assertDoesNotExist()
    }

    private companion object {
        const val FIRST = "https://www.distronode.test/api/media/1"
        const val SECOND = "https://www.distronode.test/api/media/2"
    }
}
