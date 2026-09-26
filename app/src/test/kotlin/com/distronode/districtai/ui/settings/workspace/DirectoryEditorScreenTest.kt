package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.DirectoryEntry
import com.distronode.districtai.core.model.DirectoryField
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the transfer directory editor draws — and the two things it must never draw: an editable
 * field when the configuration did not load, and a save whose wording hides that it is a
 * replacement.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, for the reason `CapabilitiesScreenTest` states: two entries with two boxes
// each plus the add row do not fit a phone-sized Robolectric display, and `assertIsDisplayed`
// checks visible bounds rather than presence.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class DirectoryEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stored = Json.parseToJsonElement(
        """
        [
          {"name":"Ops desk","phoneNumber":"+14165550177"},
          {"name":"On-call engineer","phoneNumber":"+14165550166","extension":"402"}
        ]
        """.trimIndent(),
    )

    private fun ready(directory: kotlinx.serialization.json.JsonElement? = stored) =
        DirectoryEditorUiState(
            load = ConfigState.Ready(WorkspaceConfig(callDirectory = directory)),
        )

    /**
     * ⚠️ `LongParameterList` IS SUPPRESSED FOR A TEST HELPER, NOT FOR PRODUCTION CODE. The screen
     * itself is `@Composable` and therefore exempt from the rule by configuration; this helper
     * mirrors its signature one-for-one so a test can name exactly the callback it is asserting on.
     * Collapsing them into a holder object would put the mirror one indirection away from the thing
     * it mirrors, which is how a test ends up wiring a callback the screen no longer has.
     */
    @Suppress("LongParameterList")
    private fun render(
        state: DirectoryEditorUiState,
        onEditNew: (DirectoryField, String) -> Unit = { _, _ -> },
        onAdd: () -> Unit = {},
        onEdit: (Int, DirectoryField, String) -> Unit = { _, _, _ -> },
        onRemove: (Int) -> Unit = {},
        onSave: () -> Unit = {},
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                DirectoryEditorScreen(
                    state = state,
                    onEditNew = onEditNew,
                    onAdd = onAdd,
                    onEdit = onEdit,
                    onRemove = onRemove,
                    onSave = onSave,
                    onRetry = onRetry,
                    onBack = onBack,
                )
            }
        }
    }

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load offers a retry and NOT ONE editable field`() {
        // ⛔ THE ASSERTION THE WHOLE PACKAGE EXISTS FOR, AND IT IS SHARPEST ON THIS SCREEN:
        // `PATCH workspace/directory` writes `callDirectory || []`, so a form rendered from nothing
        // and saved removes every human a live caller could be transferred to.
        render(
            DirectoryEditorUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Could not reach the server."))),
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
            .assertIsDisplayed()

        DIRECTORY_MUTATING_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
        composeRule.onNodeWithContentDescription(directoryFieldDescription(0, DirectoryField.NAME))
            .assertDoesNotExist()
    }

    @Test
    fun `an unmodellable directory explains itself and offers NO retry and NO editor`() {
        // ⛔ A THIRD STATE, AND THE ABSENCE OF A RETRY IS THE POINT. Nothing failed; the stored
        // array simply cannot be rebuilt without losing part of it, so retrying returns the same
        // value and an editor would delete whatever was not modelled.
        render(ready(Json.parseToJsonElement("""["not-an-object"]""")))

        composeRule.onNodeWithContentDescription(DIRECTORY_UNMODELLABLE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
            .assertDoesNotExist()
        DIRECTORY_MUTATING_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
    }

    @Test
    fun `loading draws skeletons and no fields`() {
        render(DirectoryEditorUiState())

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DIRECTORY_ADD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the retry fires the reload`() {
        var retries = 0
        render(
            DirectoryEditorUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Offline."))),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION).performClick()

        assertEquals(1, retries)
    }

    // ── The list ─────────────────────────────────────────────────────────────

    @Test
    fun `every stored entry gets its own editable row`() {
        render(ready())

        composeRule.onNodeWithContentDescription(directoryFieldDescription(0, DirectoryField.NAME))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            directoryFieldDescription(1, DirectoryField.PHONE_NUMBER),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(directoryRemoveDescription(1)).assertIsDisplayed()
    }

    @Test
    fun `remove reports the row it was tapped on`() {
        var removed = -1
        render(ready(), onRemove = { removed = it })

        composeRule.onNodeWithContentDescription(directoryRemoveDescription(1)).performClick()

        assertEquals(1, removed)
    }

    @Test
    fun `an empty directory says the agent has nobody to transfer to`() {
        render(ready(Json.parseToJsonElement("[]")))

        composeRule.onNodeWithContentDescription(DIRECTORY_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `an incomplete row is warned about rather than blocking the save`() {
        val state = ready().copy(
            draft = listOf(DirectoryEntry.newEntry("Ops desk", "")),
        )
        render(state)

        composeRule.onNodeWithContentDescription(DIRECTORY_INCOMPLETE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).assertIsEnabled()
    }

    // ── The add row ──────────────────────────────────────────────────────────

    @Test
    fun `the add button is disabled while only one box is filled`() {
        render(ready().copy(newName = "Night desk"))

        composeRule.onNodeWithContentDescription(DIRECTORY_ADD_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the add button is enabled once both boxes are filled`() {
        // ⚠️ A SEPARATE TEST RATHER THAN A SECOND `setContent` — the compose rule accepts content
        // exactly once per test, and a second call throws in a way that reads as a Compose bug.
        render(ready().copy(newName = "Night desk", newPhoneNumber = "+14165550100"))

        composeRule.onNodeWithContentDescription(DIRECTORY_ADD_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `a rejected add says which boxes are needed`() {
        render(ready().copy(addRejected = true))

        composeRule.onNodeWithContentDescription(DIRECTORY_ADD_REJECTED_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── The confirmation ─────────────────────────────────────────────────────

    @Test
    fun `the save CONFIRMS with the entry count and does not save until confirmed`() {
        // ⛔ THE COUNT IS IN THE WORDING BECAUSE THE SAVE IS A REPLACEMENT. It is the one thing an
        // operator can check against what they meant before the stored array is overwritten.
        var saves = 0
        render(
            ready().copy(draft = listOf(DirectoryEntry.newEntry("Ops desk", "+14165550177"))),
            onSave = { saves += 1 },
        )

        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).performClick()

        assertEquals("the tap must open a confirmation, not save", 0, saves)
        composeRule.onNodeWithText("Replace the transfer directory with 1 entry?", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(DIRECTORY_CONFIRM_DESCRIPTION).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun `the count in the confirmation is the number of entries on screen`() {
        render(
            ready().copy(
                draft = listOf(
                    DirectoryEntry.newEntry("Ops desk", "+14165550177"),
                    DirectoryEntry.newEntry("Night desk", "+14165550100"),
                    DirectoryEntry.newEntry("Reception", "+14165550101"),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).performClick()

        composeRule.onNodeWithText("Replace the transfer directory with 3 entries?", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `an EMPTY save gets DIFFERENT wording that names the consequence`() {
        // ⛔ "REPLACE WITH 0 ENTRIES" IS ARITHMETIC. This is what actually happens, and it is a
        // separate string so neither can be softened into the other.
        render(ready().copy(draft = emptyList()))

        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).performClick()

        composeRule.onNodeWithText("Remove every transfer target?", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "The agent will have nobody to put a caller through to",
            substring = true,
        ).assertIsDisplayed()
        // ⚠️ And it must NOT show the count wording, or the two would be interchangeable.
        composeRule.onNodeWithText("Replace the transfer directory with", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `cancelling the confirmation saves nothing`() {
        var saves = 0
        render(
            ready().copy(draft = emptyList()),
            onSave = { saves += 1 },
        )

        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(DIRECTORY_CANCEL_DESCRIPTION).performClick()

        assertEquals(0, saves)
        composeRule.onNodeWithContentDescription(DIRECTORY_CONFIRM_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the save is disabled when nothing changed`() {
        render(ready())

        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    // ── The banners ──────────────────────────────────────────────────────────

    @Test
    fun `a landed write with a failed re-read reads as stale, not as an error`() {
        // ⛔ NOT DESTRUCTIVE-COLOURED AND NOT WORDED AS A FAILURE. Telling an operator the change
        // did not save invites a second wholesale replace from state the client cannot vouch for.
        render(
            ready().copy(
                save = SaveState.SavedButStale(FailureText(UiText.Literal("Offline."))),
            ),
        )

        composeRule.onNodeWithContentDescription(DIRECTORY_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Saved, but we could not re-read", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a save in flight disables the controls so a second submit cannot race the first`() {
        render(ready().copy(draft = emptyList(), save = SaveState.Saving))

        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DIRECTORY_ADD_DESCRIPTION).assertIsNotEnabled()
    }

    // ── Leaving with pending edits ───────────────────────────────────────────

    private fun edited() = ready().let { it.copy(draft = it.entries.take(1)) }

    @Test
    fun `back with an unsaved list asks first, and keeping editing stays`() {
        // ⛔ A LOST DIRECTORY EDIT IS A LOST LIST OF HUMANS TO TRANSFER TO, typed by hand.
        var backs = 0
        render(edited(), onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KEEP_EDITING_DESCRIPTION)
            .performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertDoesNotExist()
        assertEquals("the screen must not have left", 0, backs)

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `back with nothing pending leaves at once`() {
        var backs = 0
        render(ready(), onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertDoesNotExist()
        assertEquals(1, backs)
    }

    // ── A save in flight ─────────────────────────────────────────────────────

    @Test
    fun `a save in flight locks every row and says it is saving`() {
        render(edited().copy(save = SaveState.Saving))

        composeRule.onNodeWithContentDescription(directoryFieldDescription(0, DirectoryField.NAME))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            directoryFieldDescription(0, DirectoryField.PHONE_NUMBER),
        ).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(directoryRemoveDescription(0)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DIRECTORY_NEW_NAME_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithText("Saving", substring = true).assertIsDisplayed()
    }

    // ── Every control after the list changes under it ────────────────────────

    @Test
    fun `every control reports its own row and field after the screen recomposes`() {
        // ⚠️ THE SCREEN IS REDRAWN FROM A NEW STATE WITH THE SAME CALLBACKS, which is what happens
        // on every keystroke in production. A control that kept a stale index or a stale callback
        // across that redraw would edit the wrong transfer target.
        val calls = mutableListOf<String>()
        val current = mutableStateOf(ready())
        composeRule.setContent {
            DistrictTheme {
                DirectoryEditorScreen(
                    state = current.value,
                    // ⚠️ Each edit is folded back into the state the way the ViewModel does, so a
                    // box shows what was typed rather than snapping back to its old value.
                    onEditNew = { field, value ->
                        calls += "new:$field:$value"
                        current.value = when (field) {
                            DirectoryField.NAME -> current.value.copy(newName = value)
                            DirectoryField.PHONE_NUMBER -> current.value.copy(newPhoneNumber = value)
                        }
                    },
                    onAdd = { calls += "add" },
                    onEdit = { index, field, value ->
                        calls += "edit:$index:$field:$value"
                        val rows = current.value.entries.toMutableList()
                        rows[index] = rows[index].with(field, value)
                        current.value = current.value.copy(draft = rows)
                    },
                    onRemove = { calls += "remove:$it" },
                    onSave = { calls += "save" },
                    onRetry = { calls += "retry" },
                    onBack = { calls += "back" },
                )
            }
        }

        current.value = ready().let {
            it.copy(draft = listOf(it.entries[0].with(DirectoryField.NAME, "Ops (day)"), it.entries[1]))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ops (day)").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(DIRECTORY_NEW_NAME_DESCRIPTION)
            .performTextReplacement("Front desk")
        composeRule.onNodeWithContentDescription(DIRECTORY_NEW_PHONE_DESCRIPTION)
            .performTextReplacement("+14165550100")
        composeRule.onNodeWithContentDescription(directoryFieldDescription(1, DirectoryField.NAME))
            .performTextReplacement("Night desk")
        composeRule.onNodeWithContentDescription(
            directoryFieldDescription(1, DirectoryField.PHONE_NUMBER),
        ).performTextReplacement("+14165550199")
        composeRule.onNodeWithContentDescription(directoryRemoveDescription(1)).performClick()

        // The confirmation, opened and then carried across another redraw before it is accepted.
        composeRule.onNodeWithContentDescription(DIRECTORY_SAVE_DESCRIPTION).performClick()
        current.value = current.value.copy(newName = "Front desk, again")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(DIRECTORY_CONFIRM_DESCRIPTION).performClick()

        assertEquals(
            listOf(
                "new:NAME:Front desk",
                "new:PHONE_NUMBER:+14165550100",
                "edit:1:NAME:Night desk",
                "edit:1:PHONE_NUMBER:+14165550199",
                "remove:1",
                "save",
            ),
            calls,
        )
    }
}
