package com.distronode.districtai.ui.settings.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.KB_MODE_INTERNAL
import com.distronode.districtai.core.model.KB_MODE_LINKED
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the knowledge screen draws.
 *
 * ⛔ THE TWO ASSERTIONS THAT MATTER ARE ABOUT THE MODE, NOT ABOUT THE DOCUMENTS. Choosing `linked`
 * starts sending this workspace's questions to Atlassian, so it is confirmed with wording that
 * names the third party — and a mode that could not be READ must not render as a mode that was
 * CHOSEN.
 *
 * ⛔ AND THERE IS A THIRD: a `viewer` reaches this screen. Both reads admit them server-side and all
 * three writes exclude them. A viewer must see the
 * documents and the stored mode with no control of any kind, and the mode must be STATED rather than
 * drawn as a disabled radio group: a greyed-out control still reads as something they could have
 * changed, which on a data-residency setting is the wrong impression to leave.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h4000dp")
class KnowledgeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val documents = listOf(
        KnowledgeDocument(id = "doc-1", title = "Refund policy", status = "ready", chunkCount = 4),
        KnowledgeDocument(id = "doc-2", title = "Service area", status = "processing", chunkCount = 0),
    )

    private fun ready(mode: String? = KB_MODE_INTERNAL, canWrite: Boolean = true) = KnowledgeUiState(
        list = KnowledgeListState.Ready(documents),
        canWrite = canWrite,
        mode = mode,
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
        state: KnowledgeUiState,
        onEditTitle: (String) -> Unit = {},
        onEditContent: (String) -> Unit = {},
        onAdd: () -> Unit = {},
        onDelete: (String) -> Unit = {},
        onSelectMode: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                KnowledgeScreen(
                    state = state,
                    onEditTitle = onEditTitle,
                    onEditContent = onEditContent,
                    onAdd = onAdd,
                    onDelete = onDelete,
                    onSelectMode = onSelectMode,
                    onRetry = onRetry,
                    onBack = onBack,
                )
            }
        }
    }

    // ── The documents ────────────────────────────────────────────────────────

    @Test
    fun `every document gets a row with its status and section count`() {
        render(ready())

        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-1"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-2"))
            .assertIsDisplayed()
        // ⚠️ VERBATIM. `status` is a plain string column, so an unrecognised value renders as
        // itself rather than being mapped to a guess.
        composeRule.onNodeWithText("processing · 0 sections", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("ready · 4 sections", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an empty knowledge base says the agent has nothing of its own to answer from`() {
        render(KnowledgeUiState(list = KnowledgeListState.Ready(emptyList())))

        composeRule.onNodeWithContentDescription(KNOWLEDGE_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed list read offers a retry`() {
        var retries = 0
        render(
            KnowledgeUiState(
                list = KnowledgeListState.Failed(FailureText(UiText.Literal("Offline."))),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `deleting CONFIRMS and names the document`() {
        // ⛔ NOT RECOVERABLE: the chunks cascade and the embeddings have to be bought again.
        var deleted: String? = null
        render(ready(), onDelete = { deleted = it })

        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-1")).performClick()

        assertNull("the tap must open a confirmation, not delete", deleted)
        // ⚠️ The title appears twice once the dialog is up — in the row behind it and in the
        // dialog's body — so the assertion is on the SENTENCE the dialog adds, which is what
        // actually names the consequence.
        composeRule.onNodeWithText("and everything indexed from it are deleted", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(KNOWLEDGE_DELETE_CONFIRM_DESCRIPTION).performClick()
        assertEquals("doc-1", deleted)
    }

    @Test
    fun `cancelling the delete removes nothing`() {
        var deleted: String? = null
        render(ready(), onDelete = { deleted = it })

        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-1")).performClick()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_DELETE_CANCEL_DESCRIPTION).performClick()

        assertNull(deleted)
    }

    // ── The add form ─────────────────────────────────────────────────────────

    @Test
    fun `the add button is disabled until a title and some text are present`() {
        render(ready().copy(draftTitle = "Holiday hours"))

        composeRule.onNodeWithContentDescription(KNOWLEDGE_ADD_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a rejected add says what is missing`() {
        render(ready().copy(addRejected = true))

        composeRule.onNodeWithContentDescription(KNOWLEDGE_ADD_REJECTED_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun `an upload in flight disables the button so a second tap cannot buy a second run`() {
        // ⛔ A SPENDING CONTROL RATHER THAN A LOADING AFFORDANCE.
        render(
            ready().copy(
                draftTitle = "Holiday hours",
                draftContent = "Closed on the 25th.",
                addSave = SaveState.Saving,
            ),
        )

        composeRule.onNodeWithContentDescription(KNOWLEDGE_ADD_DESCRIPTION).assertIsNotEnabled()
    }

    // ── The mode ─────────────────────────────────────────────────────────────

    @Test
    fun `the stored mode is the selected one and both options explain themselves`() {
        render(ready(KB_MODE_LINKED))

        // ⚠️ `assertIsSelected`, not `assertIsOn`: a RadioButton carries the SELECTED semantics
        // property rather than a ToggleableState, and asserting the wrong one fails against a
        // correctly-rendered control.
        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_LINKED))
            .assertIsSelected()
        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_INTERNAL))
            .assertIsNotSelected()
        // ⛔ THE HELP TEXT SAYS THE QUESTION LEAVES, IN THOSE WORDS.
        composeRule.onNodeWithText("questions are sent to Atlassian", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a mode that could not be READ withholds the selector entirely`() {
        // ⛔ RENDERING `internal` AS SELECTED WOULD BE A FALSE CLAIM ABOUT WHERE A CUSTOMER'S
        // QUESTIONS GO, and the radio the operator then leaves alone would look like their choice.
        render(KnowledgeUiState(list = KnowledgeListState.Ready(documents), modeUnavailable = true))

        composeRule.onNodeWithContentDescription(KNOWLEDGE_MODE_UNAVAILABLE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_INTERNAL))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_LINKED))
            .assertDoesNotExist()
    }

    @Test
    fun `choosing linked CONFIRMS and names Atlassian before anything is sent`() {
        var chosen: String? = null
        render(ready(KB_MODE_INTERNAL), onSelectMode = { chosen = it })

        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_LINKED))
            .performClick()

        assertNull("the tap must open a confirmation, not switch", chosen)
        composeRule.onNodeWithText("Send questions to Atlassian?", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(KNOWLEDGE_LINKED_CONFIRM_DESCRIPTION).performClick()
        assertEquals(KB_MODE_LINKED, chosen)
    }

    @Test
    fun `choosing the in-region option needs no confirmation`() {
        // ⚠️ ASYMMETRIC ON PURPOSE. Moving back to `internal` stops sending questions out; there is
        // nothing to warn about, and a confirmation on the safe direction trains people to dismiss
        // the one on the unsafe direction.
        var chosen: String? = null
        render(ready(KB_MODE_LINKED), onSelectMode = { chosen = it })

        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_INTERNAL))
            .performClick()

        assertEquals(KB_MODE_INTERNAL, chosen)
    }

    @Test
    fun `cancelling the linked confirmation changes nothing`() {
        var chosen: String? = null
        render(ready(KB_MODE_INTERNAL), onSelectMode = { chosen = it })

        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_LINKED))
            .performClick()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_LINKED_CANCEL_DESCRIPTION).performClick()

        assertNull(chosen)
    }

    // ── The viewer, whose whole screen is an absence ─────────────────────────

    @Test
    fun `a viewer sees the documents and NOT ONE write control`() {
        render(ready(canWrite = false))

        // The read half, which the route genuinely admits them to.
        composeRule.onNodeWithText("Refund policy", substring = true).assertIsDisplayed()

        // ⛔ EVERY WRITE ON THIS SURFACE EXCLUDES `viewer`: the add buys an embedding run, the
        // delete cascades chunks that have to be paid for again, and the mode switch is a
        // data-residency change. Being offered any of them is an invitation to a 403.
        composeRule.onNodeWithContentDescription(KNOWLEDGE_ADD_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_TITLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_CONTENT_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-1"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-2"))
            .assertDoesNotExist()
    }

    @Test
    fun `a viewer is told which mode is stored, without a radio to tap`() {
        // ⛔ STATED, NOT DISABLED. Five tappable-looking nodes for a role that can never use them
        // would put a data-residency choice one accidental press from looking changeable.
        render(ready(mode = KB_MODE_LINKED, canWrite = false))

        composeRule.onNodeWithContentDescription(KNOWLEDGE_MODE_READ_ONLY_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_LINKED))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_INTERNAL))
            .assertDoesNotExist()
    }

    @Test
    fun `a viewer whose mode read FAILED is told nothing about the mode at all`() {
        // ⛔ THE FAILED-READ BRANCH STILL WINS FOR A VIEWER. "We could not read where your questions
        // go" and "your questions stay in region" are different claims, and the second one must
        // never be rendered from the absence of the first.
        render(
            KnowledgeUiState(
                list = KnowledgeListState.Ready(documents),
                canWrite = false,
                mode = null,
                modeUnavailable = true,
            ),
        )

        composeRule.onNodeWithContentDescription(KNOWLEDGE_MODE_UNAVAILABLE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_MODE_READ_ONLY_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a mutating role keeps the radio group and the add form`() {
        render(ready())

        composeRule.onNodeWithContentDescription(knowledgeModeDescription(KB_MODE_INTERNAL))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_ADD_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(knowledgeDeleteDescription("doc-1"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(KNOWLEDGE_MODE_READ_ONLY_DESCRIPTION)
            .assertDoesNotExist()
    }
}
