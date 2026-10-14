package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.ui.test.assertIsDisplayed
import com.distronode.districtai.core.data.MembersRepository
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.plural
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAccess
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminRoutes
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The event types list and one event type, as a member sees them, and their two routes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingEventTypesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun list(
        rows: List<SchedulingEventType>,
        canManage: Boolean = true,
        publicHost: String? = "acme-book.example",
    ) = SchedulingEventTypesUiState(SectionState.Ready(rows), SchedulingAccess(canManage, publicHost))

    private val bar = SchedulingEventTypeBar(
        onEdit = { calls += "edit:${it.slug}" },
        onManage = { calls += "manage:${it.slug}" },
        onHosts = { calls += "hosts:${it.slug}" },
        onQuestions = { calls += "questions:${it.slug}" },
    )

    @Test
    fun `rows carry duration, location, state and the booking page, and open the event type`() {
        val rows = listOf(
            schedulingEventType().copy(locationType = "livekit"),
            schedulingEventType(slug = "off", isActive = false).copy(locationType = "zoom"),
            schedulingEventType(slug = "gone").copy(archived = true),
            schedulingEventType(slug = "hidden", isPublic = false).copy(locationType = ""),
        )
        composeRule.setContent {
            DistrictTheme {
                SchedulingEventTypesScreen(
                    state = list(rows),
                    onBack = { calls += "back" },
                    onRefresh = { calls += "refresh" },
                    onOpen = { calls += "open:${it.slug}" },
                    onCreate = { calls += "create" },
                )
            }
        }
        composeRule.onNodeWithText("30 min · Built-in video").assertIsDisplayed()
        composeRule.onNodeWithText("30 min · zoom").assertIsDisplayed()
        val notSet = str(R.string.scheduling_admin_event_types_location_not_set)
        assertEquals(2, composeRule.onAllNodes(hasText("30 min · $notSet")).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("https://acme-book.example/book/intro").assertIsDisplayed()
        val notPublic = str(R.string.scheduling_admin_event_types_not_public)
        listOf("off", "gone", "hidden").forEach { slug ->
            composeRule.onNode(hasTestTag("$SCHEDULING_EVENT_TYPES_PAGE_TAG-$slug")).assertExists()
        }
        assertEquals(3, composeRule.onAllNodes(hasText(notPublic)).fetchSemanticsNodes().size)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_state_inactive)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_state_archived)).assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_EVENT_TYPES_ROW_TAG-intro").performClick()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPES_CREATE_TAG).performClick()
        assertEquals(listOf("open:intro", "create"), calls)
    }

    @Test
    fun `loading, failed, empty for a manager and a viewer, and no host means not public`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingEventTypesScreen(SchedulingEventTypesUiState(), {}, {}, {}, {})
                SchedulingEventTypesScreen(list(emptyList()), {}, {}, {}, {})
                SchedulingEventTypesScreen(
                    list(listOf(schedulingEventType(slug = "v")), canManage = false, publicHost = null),
                    {},
                    {},
                    {},
                    {},
                )
                // ⚠️ Drawn last, so it is on top where the retry button is pressed.
                SchedulingEventTypesScreen(
                    SchedulingEventTypesUiState(SectionState.Failed(FailureText(UiText.Literal("List down")))),
                    {},
                    { calls += "retry" },
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPES_LOADING_TAG).assertExists()
        composeRule.onNodeWithText("List down").assertExists()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPES_EMPTY_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_not_public)).assertExists()
        assertEquals(
            1,
            composeRule.onAllNodes(hasTestTag(SCHEDULING_EVENT_TYPES_CREATE_TAG)).fetchSemanticsNodes().size,
        )
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `one event type states its facts, its hosts and questions, and offers the four writes`() {
        val hosts = listOf(
            schedulingHost(),
            schedulingHost(userId = "u2", name = "", role = "observer", archived = true),
        )
        val questions = listOf(
            schedulingQuestion(required = true),
            schedulingQuestion(id = "q2", label = "Size", type = "slider"),
        )
        composeRule.setContent {
            DistrictTheme {
                SchedulingEventTypeScreen(
                    SchedulingEventTypeUiState(
                        eventType = SectionState.Ready(fullEventType()),
                        hosts = SectionState.Ready(hosts),
                        questions = SectionState.Ready(questions),
                        canManage = true,
                    ),
                    slug = "intro",
                    onBack = {},
                    onRefresh = {},
                    bar = bar,
                )
            }
        }
        composeRule.onNodeWithText("Intro call").assertExists()
        composeRule.onNodeWithText("A first conversation.").assertExists()
        composeRule.onNodeWithText("15 min").assertExists()
        composeRule.onNodeWithText("1 Main St").assertExists()
        composeRule.onNodeWithText(plural(R.plurals.scheduling_admin_event_types_days_ahead, 30)).assertExists()
        composeRule.onNodeWithText("5 min").assertExists()
        composeRule.onNodeWithText("10 min").assertExists()
        composeRule.onNodeWithText("60 min").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_location_in_person)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_role_required)).assertExists()
        composeRule.onNodeWithText("u2@example.com").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_host_archived, "observer")).assertExists()
        val text = str(R.string.scheduling_admin_event_types_question_type_text)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_question_required, text)).assertExists()
        composeRule.onNodeWithText("slider").assertExists()
        listOf(
            SCHEDULING_EVENT_TYPE_EDIT_TAG,
            SCHEDULING_EVENT_TYPE_MANAGE_TAG,
            SCHEDULING_EVENT_TYPE_HOSTS_TAG,
            SCHEDULING_EVENT_TYPE_QUESTIONS_TAG,
        ).forEach { composeRule.onNodeWithTag(it).performClick() }
        assertEquals(listOf("edit:intro", "manage:intro", "hosts:intro", "questions:intro"), calls)
    }

    @Test
    fun `loading, failed and empty reads, a sparse row, and no writes for a viewer`() {
        val sparse = schedulingEventType(slug = "sparse").copy(
            description = "",
            locationValue = "",
            bufferBeforeMinutes = 0,
            bufferAfterMinutes = 0,
        )
        composeRule.setContent {
            DistrictTheme {
                SchedulingEventTypeScreen(SchedulingEventTypeUiState(), "loading-slug", {}, {}, bar)
                SchedulingEventTypeScreen(
                    SchedulingEventTypeUiState(
                        eventType = SectionState.Failed(FailureText(UiText.Literal("Row down"))),
                        hosts = SectionState.Failed(FailureText(UiText.Literal("Hosts down"))),
                        questions = SectionState.Ready(emptyList()),
                        canManage = true,
                    ),
                    "failed-slug",
                    {},
                    {},
                    bar,
                )
                SchedulingEventTypeScreen(
                    SchedulingEventTypeUiState(
                        eventType = SectionState.Ready(sparse),
                        hosts = SectionState.Ready(emptyList()),
                        questions = SectionState.Ready(emptyList()),
                    ),
                    "sparse",
                    {},
                    {},
                    bar,
                )
                SchedulingEventTypeScreen(
                    SchedulingEventTypeUiState(
                        eventType = SectionState.Ready(schedulingEventType(slug = "bare")),
                        hosts = SectionState.Failed(FailureText(UiText.Literal("Bare hosts down"))),
                        questions = SectionState.Failed(FailureText(UiText.Literal("Bare questions down"))),
                    ),
                    "bare",
                    {},
                    {},
                    bar,
                )
            }
        }
        composeRule.onNodeWithText("loading-slug").assertExists()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_HOSTS_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_QUESTIONS_LOADING_TAG).assertExists()
        composeRule.onNodeWithText("Row down").assertExists()
        composeRule.onNodeWithText("Hosts down").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_no_hosts)).assertExists()
        assertEquals(
            2,
            composeRule.onAllNodes(hasTextExactly(str(R.string.scheduling_admin_event_types_no_questions)))
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_fact_interval)).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_EDIT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the list route reads on appearance, opens a row and the create dialog`() {
        val rig = SchedulingAdminTestRig(canManage = true)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingItems(listOf(schedulingEventType())))
        composeRule.setContent { DistrictTheme { SchedulingEventTypesRoute(deps(rig)) } }
        composeRule.waitUntil { rows(SCHEDULING_EVENT_TYPES_CREATE_TAG) > 0 }
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.waitUntil { rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_LIST).size == 2 }
        composeRule.waitUntil { rows(SCHEDULING_EVENT_TYPES_CREATE_TAG) > 0 }
        composeRule.onNodeWithTag("$SCHEDULING_EVENT_TYPES_ROW_TAG-intro").performClick()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPES_CREATE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_EDITOR_TAG).assertExists()
        assertEquals(listOf("navigate:${SchedulingAdminRoutes.eventType("ws", "intro")}"), calls)
    }

    @Test
    fun `the event type route opens each write, and a delete that landed goes back`() {
        val rig = SchedulingAdminTestRig(canManage = true)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_GET, fullEventType())
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_HOSTS_GET, SchedulingItems(listOf(schedulingHost())))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST, SchedulingItems(listOf(schedulingQuestion())))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_DELETE, SchedulingNoContent(ok = true))
        composeRule.setContent { DistrictTheme { SchedulingEventTypeRoute(deps(rig), "intro") } }
        composeRule.waitUntil { rows(SCHEDULING_EVENT_TYPE_EDIT_TAG) > 0 }
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.waitUntil { rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_GET).size == 2 }
        composeRule.waitUntil { rows(SCHEDULING_EVENT_TYPE_EDIT_TAG) > 0 }
        listOf(
            SCHEDULING_EVENT_TYPE_EDIT_TAG to SCHEDULING_EVENT_TYPE_EDITOR_TAG,
            SCHEDULING_EVENT_TYPE_HOSTS_TAG to SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_TAG,
            SCHEDULING_EVENT_TYPE_QUESTIONS_TAG to SCHEDULING_EVENT_TYPE_QUESTIONS_DIALOG_TAG,
        ).forEach { (button, dialog) ->
            composeRule.onNodeWithTag(button).performClick()
            composeRule.waitUntil { rows(dialog) > 0 }
            composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
            composeRule.waitUntil { rows(dialog) == 0 }
        }
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_MANAGE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_ACTIONS_DELETE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_EVENT_TYPE_DELETE_CONFIRM_TAG).performClick()
        composeRule.waitUntil { calls.contains("back") }
    }

    private fun deps(rig: SchedulingAdminTestRig) = SchedulingAdminDeps(
        admin = rig.adminRepository,
        scheduling = rig.scheduling,
        workspaceId = "ws",
        sessionEpoch = 0,
        onBack = { calls += "back" },
        navigate = { calls += "navigate:$it" },
        openUrl = { true },
        members = MembersRepository(rig.district),
    )

    private fun rows(tag: String): Int = composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
