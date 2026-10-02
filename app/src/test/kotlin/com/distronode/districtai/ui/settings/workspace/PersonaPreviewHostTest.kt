package com.distronode.districtai.ui.settings.workspace

import android.Manifest
import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.distronode.districtai.core.network.testing.FakePersonaApi

/**
 * The host that owns one audition: the sheet it opens, the microphone prompt it raises, and the
 * form it reads at the tap.
 *
 * ⛔ THE MICROPHONE IS GRANTED UP FRONT HERE, which makes the OS prompt answer synchronously with
 * "granted". That is the path on which a tap becomes a billed session, so it is the one that has to
 * be pinned: exactly one mint, of the form as it stood when Start was pressed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class PersonaPreviewHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val api = FakePersonaApi()

    @Before
    fun grantMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun render(form: () -> PersonaPreviewForm?) {
        composeRule.setContent {
            DistrictTheme {
                PersonaPreviewHost(
                    repository = PersonaOptionsRepository(api),
                    engineFactory = FakeCallEngineFactory(),
                    workspaceId = "ws-host",
                    formProvider = form,
                ) { openPreview ->
                    // The persona screen's own button is a plain callback; this stands in for it.
                    TextButton(
                        onClick = openPreview,
                        modifier = Modifier.semantics { contentDescription = OPEN_BUTTON },
                    ) { Text("Open") }
                }
            }
        }
    }

    @Test
    fun `starting asks for the microphone and then mints one session for the form on screen`() {
        var name = "Ada"
        render { PersonaPreviewForm(name = name) }

        composeRule.onNodeWithContentDescription(OPEN_BUTTON).performClick()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_ROOT_DESCRIPTION).assertIsDisplayed()
        // ⛔ READ AT THE TAP. A value captured when the sheet opened would audition a stale form.
        name = "Bea"
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("ws-host" to PersonaPreviewForm(name = "Bea")), api.previewCalls)
    }

    @Test
    fun `a form that cannot be built starts nothing, and closing the sheet removes it`() {
        render { null }

        composeRule.onNodeWithContentDescription(OPEN_BUTTON).performClick()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_START_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<Pair<String, PersonaPreviewForm>>(), api.previewCalls)

        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_CLOSE_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_ROOT_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(OPEN_BUTTON).assertIsDisplayed()
    }

    private companion object {
        const val OPEN_BUTTON = "test-preview-open"
    }
}
