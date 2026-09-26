package com.distronode.districtai.ui.desk

import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.model.DeskBrandName
import com.distronode.districtai.core.model.DeskLogoRemovalResponse
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.inbox.AttachmentReader
import com.distronode.districtai.ui.inbox.PickedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The desk settings form.
 *
 * ⛔ THE ASSERTION THIS FILE IS FOR: a save sends ONLY what changed. The route merges per field, so
 * a client posting its whole form state becomes the writer of values it may have read before
 * another tab changed them — the `blank_form_overwrites_config` shape, where a form saved after a
 * stale load writes blanks over live configuration.
 *
 * ⛔ AND THE THREE-STATE BRAND NAME, which is the subtlest thing on this screen. An empty box is
 * "absent" if the operator never touched it and an explicit `null` if they emptied it, the two look
 * identical on screen, and only `brandNameEdited` can tell them apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeskSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val stored = DeskSettings(
        enabled = true,
        notifyCustomersByEmail = true,
        publicBrandName = "Ada Plumbing",
        publicLogoUrl = "https://cdn.example/logo.png",
    )

    private fun api(settings: DeskSettings = stored) = FakeDeskApiForUi().apply {
        settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = settings),
        )
    }

    private class FakeReader(private val picked: PickedAttachment?) : AttachmentReader {
        var reads = 0
        override suspend fun read(uri: String): PickedAttachment? {
            reads++
            return picked
        }
    }

    private fun viewModel(
        api: FakeDeskApiForUi,
        reader: AttachmentReader = FakeReader(
            PickedAttachment("logo.png", "image/png", byteArrayOf(1, 2, 3)),
        ),
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = DeskSettingsViewModel(
        DeskRepository(api) { "key" },
        attachments = reader,
        workspaceId = "ws-1",
        role = role,
    )

    private fun content(model: DeskSettingsViewModel) =
        model.state.value as DeskSettingsUiState.Content

    @Test
    fun `a viewer sends no read`() = runTest {
        val api = api()

        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(model.canUse)
        assertEquals(0, api.settingsReads)
    }

    @Test
    fun `the form is seeded from the stored row and is clean`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        val state = content(model)
        assertEquals("Ada Plumbing", state.brandName)
        assertTrue(state.enabled)
        assertFalse("a freshly loaded form has nothing to save", state.dirty)
    }

    @Test
    fun `a clean form sends no request at all`() = runTest {
        // ⛔ THE ROUTE ANSWERS 400 FOR AN EMPTY PATCH, deliberately, because an empty body is always
        // a client bug. There is nothing useful a save with nothing to say could do.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()

        assertTrue(api.patches.isEmpty())
    }

    @Test
    fun `a save sends ONLY the toggle that moved`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.setNotify(false)
        model.save()
        advanceUntilIdle()

        val patch = api.patches.single()
        assertEquals(false, patch.notifyCustomersByEmail)
        // ⛔ AN OMITTED KEY IS PRESERVED SERVER-SIDE. Sending `enabled` here would write a value this
        // screen read before the operator opened it.
        assertNull(patch.enabled)
        assertNull(patch.publicBrandName)
    }

    @Test
    fun `emptying the brand name sends Clear, which is the explicit null`() = runTest {
        // ⛔ THE ESCAPE HATCH, REACHED IN EXACTLY ONE PLACE. `null` in the patch would be DROPPED by
        // the encoder and mean "leave it alone"; `Clear` sends a literal null and falls the
        // customers' page back to the workspace's own name.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.editBrandName("")
        model.save()
        advanceUntilIdle()

        assertEquals(DeskBrandName.Clear, api.patches.single().publicBrandName)
    }

    @Test
    fun `a whitespace-only brand name is also Clear rather than a stored space`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.editBrandName("   ")
        model.save()
        advanceUntilIdle()

        assertEquals(DeskBrandName.Clear, api.patches.single().publicBrandName)
    }

    @Test
    fun `a new brand name sends Set`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.editBrandName("Ada & Sons")
        model.save()
        advanceUntilIdle()

        assertEquals(DeskBrandName.Set("Ada & Sons"), api.patches.single().publicBrandName)
    }

    @Test
    fun `typing the stored name back is NOT a change`() = runTest {
        // ⚠️ Editing a box and undoing it must not send a write. Without this, an operator who
        // tapped into the field and tapped out would re-write a value nobody changed.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.editBrandName("Ada Plumbing")

        assertFalse(content(model).brandNameChanged)
        assertFalse(content(model).dirty)
    }

    @Test
    fun `an untouched empty box on a workspace with no stored name is not a change`() = runTest {
        // ⛔ THE CASE `brandNameEdited` EXISTS FOR. A stored null and an untouched empty box look
        // identical, and without the flag every save on such a workspace would send an explicit
        // null to clear a column that is already null.
        val api = api(stored.copy(publicBrandName = null))
        val model = viewModel(api)
        advanceUntilIdle()

        model.setEnabled(false)
        model.save()
        advanceUntilIdle()

        assertNull(api.patches.single().publicBrandName)
    }

    @Test
    fun `the save adopts the echo whole and resets the edited flag`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.Success(
            DeskSettingsResponse(
                success = true,
                settings = stored.copy(publicBrandName = "Ada & Sons"),
            ),
        )
        model.editBrandName("Ada & Sons")
        model.save()
        advanceUntilIdle()

        val state = content(model)
        // ⚠️ THE ECHO, NOT THE FORM. The write returns the stored row, so this screen needs no
        // re-read and must not trust what it sent.
        assertEquals("Ada & Sons", state.stored.publicBrandName)
        assertFalse(state.brandNameEdited)
        assertFalse(state.dirty)
    }

    @Test
    fun `a failed save keeps the form so nothing typed is lost`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.RateLimited("Too many settings changes in the last hour.")
        model.editBrandName("Ada & Sons")
        model.save()
        advanceUntilIdle()

        val state = content(model)
        assertEquals("Ada & Sons", state.brandName)
        assertFalse(state.saving)
        assertTrue(state.saveFailure != null)
    }

    // ── The logo ─────────────────────────────────────────────────────────────

    @Test
    fun `an unreadable pick spends no request and says so separately`() = runTest {
        // ⚠️ "There was nothing to read" is not a rejected type or size — a revoked grant, a file
        // the provider deleted between the pick and the read, or a cloud item that failed to
        // download. It needs different words, and no upload is spent on it.
        val api = api()
        val model = viewModel(api, reader = FakeReader(null))
        advanceUntilIdle()

        model.uploadLogo("content://nothing")
        advanceUntilIdle()

        assertEquals(0, api.logoUploads)
        val state = content(model)
        assertFalse(state.logoBusy)
        assertTrue(state.logoFailure != null)
    }

    @Test
    fun `an upload sends the bytes and adopts the echoed settings`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.Success(
            DeskSettingsResponse(
                success = true,
                settings = stored.copy(publicLogoUrl = "https://cdn.example/new.png"),
            ),
        )
        model.uploadLogo("content://picked")
        advanceUntilIdle()

        assertEquals(listOf("image/png"), api.uploadedMimeTypes)
        assertEquals("https://cdn.example/new.png", content(model).stored.publicLogoUrl)
    }

    @Test
    fun `an upload refusal keeps the server's own status and sentence`() = runTest {
        // ⛔ NO LOCAL TYPE OR SIZE PRE-CHECK. The route's refusals are five different statuses with
        // five different meanings, and an SVG is sniffed from the BYTES rather than trusted from
        // the header — which no local check could reproduce.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.HttpFailure(415, "We cannot host that image type.")
        model.uploadLogo("content://picked")
        advanceUntilIdle()

        assertEquals(1, api.logoUploads)
        assertTrue(content(model).logoFailure != null)
    }

    @Test
    fun `deleting reports objectRemoved false as a retained file`() = runTest {
        // ⛔ THE TAKEDOWN'S SECOND HALF. The logo is off the customers' page either way; "the stored
        // file may still answer its old link" is a thing an operator taking something down deserves
        // to be told rather than have swallowed.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        api.logoRemovalResult = ApiResult.Success(
            DeskLogoRemovalResponse(
                success = true,
                settings = stored.copy(publicLogoUrl = null),
                objectRemoved = false,
            ),
        )
        model.deleteLogo()
        advanceUntilIdle()

        val state = content(model)
        assertNull(state.stored.publicLogoUrl)
        assertTrue(state.logoObjectRetained)
    }

    @Test
    fun `a clean delete does not raise the retained warning`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        api.logoRemovalResult = ApiResult.Success(
            DeskLogoRemovalResponse(
                success = true,
                settings = stored.copy(publicLogoUrl = null),
                objectRemoved = true,
            ),
        )
        model.deleteLogo()
        advanceUntilIdle()

        assertFalse(content(model).logoObjectRetained)
    }

    @Test
    fun `deleting a logo that does not exist sends nothing`() = runTest {
        val api = api(stored.copy(publicLogoUrl = null))
        val model = viewModel(api)
        advanceUntilIdle()

        model.deleteLogo()
        advanceUntilIdle()

        assertEquals(0, api.logoDeletes)
    }

    @Test
    fun `a viewer neither uploads nor deletes`() = runTest {
        val api = api()
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.uploadLogo("content://picked")
        model.deleteLogo()
        model.save()
        advanceUntilIdle()

        assertEquals(0, api.logoUploads)
        assertEquals(0, api.logoDeletes)
        assertTrue(api.patches.isEmpty())
    }

    @Test
    fun `a failed load is Failed rather than a blank form`() = runTest {
        // ⛔ A BLANK FORM SAVED AFTER A FAILED LOAD IS THE `blank_form_overwrites_config` BUG. The
        // only safe answer is not to render a form at all.
        val api = api().apply { settingsResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is DeskSettingsUiState.Failed)

        model.save()
        advanceUntilIdle()
        assertTrue(api.patches.isEmpty())
    }

    // ── Guards: nothing is sent from a state that cannot use it ─────────────

    @Test
    fun `edits, saves and logo actions before the form has loaded send nothing`() = runTest {
        val api = api().apply { settingsResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setEnabled(false)
        model.editBrandName("Ada & Sons")
        model.uploadLogo("content://picked")
        model.deleteLogo()
        advanceUntilIdle()

        assertTrue(model.state.value is DeskSettingsUiState.Failed)
        assertEquals(0, api.logoUploads)
        assertEquals(0, api.logoDeletes)
    }

    @Test
    fun `a second save while the first is in flight sends nothing more`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.setNotify(false)
        model.save()
        model.save()
        advanceUntilIdle()

        assertEquals(1, api.patches.size)
    }

    @Test
    fun `a second upload or a delete while an upload is in flight sends nothing more`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.uploadLogo("content://picked")
        model.uploadLogo("content://picked")
        model.deleteLogo()
        advanceUntilIdle()

        assertEquals(1, api.logoUploads)
        assertEquals(0, api.logoDeletes)
    }

    @Test
    fun `a failed delete keeps the logo and says why`() = runTest {
        val api = api().apply { logoRemovalResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()

        model.deleteLogo()
        advanceUntilIdle()

        val state = content(model)
        assertEquals("https://cdn.example/logo.png", state.stored.publicLogoUrl)
        assertFalse(state.logoBusy)
        assertTrue(state.logoFailure != null)
        assertFalse(state.logoObjectRetained)
    }

    @Test
    fun `retryOrNoop replays a failed load and leaves a loaded form alone`() = runTest {
        val api = api().apply { settingsResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.Success(DeskSettingsResponse(success = true, settings = stored))
        model.retryOrNoop()
        advanceUntilIdle()
        assertEquals(2, api.settingsReads)

        model.retryOrNoop()
        advanceUntilIdle()
        assertEquals(2, api.settingsReads)
        assertEquals("Ada Plumbing", content(model).brandName)
    }

    @Test
    fun `the factory builds a model for the workspace it was given`() = runTest {
        val api = api()
        val model = DeskSettingsViewModel.factory(
            DeskRepository(api) { "key" },
            FakeReader(null),
            "ws-1",
            WorkspaceRole.CLIENT,
        ).create(DeskSettingsViewModel::class.java)
        advanceUntilIdle()

        assertTrue(model.canUse)
        assertEquals(1, api.settingsReads)
    }
}
