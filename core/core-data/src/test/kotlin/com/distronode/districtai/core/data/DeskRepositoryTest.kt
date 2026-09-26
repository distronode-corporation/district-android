package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.DeskBrandName
import com.distronode.districtai.core.model.DeskLogoRemovalResponse
import com.distronode.districtai.core.model.DeskMessage
import com.distronode.districtai.core.model.DeskReplyResponse
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.DeskSettingsResponse
import com.distronode.districtai.core.model.DeskTicketCreateResponse
import com.distronode.districtai.core.model.DeskTicketDetail
import com.distronode.districtai.core.model.DeskTicketDraft
import com.distronode.districtai.core.model.DeskTicketResponse
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketStatusResponse
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.core.model.DeskTicketsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DeskApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk's data layer.
 *
 * ⛔ THREE THINGS THIS FILE PROTECTS, in order of how badly each reads when it fails:
 * 1. **A structurally empty 200 must not read as "no customer has ever contacted you."** Every field
 *    of every response DTO defaults, so `{}` decodes into a well-formed empty queue.
 * 2. **A blank form box must reach the wire as ABSENT, not `""`.** The route's `.email()` fails on
 *    an empty string and takes the whole create down with a message naming two fields that were
 *    both filled in.
 * 3. **An empty settings patch must never be sent.** The route answers 400 for one, deliberately.
 */
class DeskRepositoryTest {

    private val settings = DeskSettings(
        enabled = true,
        notifyCustomersByEmail = true,
        publicBrandName = "Ada Plumbing",
        publicLogoUrl = "https://cdn.example/logo.png",
    )

    private val ticket = DeskTicketSummary(
        id = "tkt_1",
        reference = 42,
        displayReference = "T-42",
        subject = "Leaking tap",
        status = "open",
        source = "voice-call",
    )

    // ── Envelope drift, on every call ────────────────────────────────────────

    @Test
    fun `a 200 that does not affirm success is drift, not an empty queue`() = runTest {
        // ⛔ THE ONE THAT LIES. `DeskTicketsResponse()` is a well-formed "no tickets", which an
        // operator reads as their customers' correspondence having been lost.
        val api = FakeDeskApi().apply { ticketsResult = ApiResult.Success(DeskTicketsResponse()) }

        assertTrue(DeskRepository(api).tickets("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `an empty queue on a success envelope is a real answer`() = runTest {
        val api = FakeDeskApi().apply {
            ticketsResult = ApiResult.Success(DeskTicketsResponse(success = true))
        }

        val result = DeskRepository(api).tickets("ws-1")

        assertEquals(emptyList<DeskTicketSummary>(), (result as ApiResult.Success).value)
    }

    @Test
    fun `settings that affirm success with no settings object are drift`() = runTest {
        // ⚠️ A `settings: null` here would otherwise decode into a DISABLED desk, sending an
        // operator to turn on something already on.
        val api = FakeDeskApi().apply {
            settingsResult = ApiResult.Success(DeskSettingsResponse(success = true))
        }

        assertTrue(DeskRepository(api).settings("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a read failure is passed through untouched, never converted to a disabled desk`() =
        runTest {
            val api = FakeDeskApi().apply {
                settingsResult = ApiResult.Forbidden("Forbidden: Insufficient workspace privileges")
            }

            val result = DeskRepository(api).settings("ws-1")

            assertTrue(result is ApiResult.Forbidden)
        }

    // ── The create's trimming, which is the one that 400s ────────────────────

    @Test
    fun `a blank optional is trimmed to NULL before it reaches the client`() = runTest {
        val api = FakeDeskApi().apply {
            createResult = ApiResult.Success(
                DeskTicketCreateResponse(success = true, ticket = ticket),
            )
        }

        DeskRepository(api).createTicket(
            workspaceId = "ws-1",
            draft = DeskTicketDraft(
                subject = "  Leaking tap  ",
                message = "  It drips.  ",
                requesterName = "   ",
                requesterEmail = "",
                requesterPhone = "+14165550142",
            ),
        )

        val sent = api.createDrafts.single()
        // ⛔ `requesterEmail: ""` FAILS `.email()` AND TAKES THE WHOLE OBJECT DOWN. The 400 it
        // answers names the subject and the description, which were both filled in — so the failure
        // points at the wrong fields and is close to undebuggable from the screen.
        assertNull(sent.requesterName)
        assertNull(sent.requesterEmail)
        assertEquals("+14165550142", sent.requesterPhone)
        // ⚠️ The two required fields are trimmed but kept.
        assertEquals("Leaking tap", sent.subject)
        assertEquals("It drips.", sent.message)
    }

    @Test
    fun `a deduplicated create is a SUCCESS carrying no ticket`() = runTest {
        // ⚠️ The ticket exists; we simply have no row to show. Reporting this as a failure would
        // make an operator raise a second one.
        val api = FakeDeskApi().apply {
            createResult = ApiResult.Success(
                DeskTicketCreateResponse(success = true, deduplicated = true),
            )
        }

        val result = DeskRepository(api).createTicket(
            "ws-1",
            DeskTicketDraft(subject = "s", message = "m"),
        )

        assertNull((result as ApiResult.Success).value)
    }

    @Test
    fun `an idempotency key is minted PER CALL, so two submits get two keys`() = runTest {
        // ⛔ PER SUBMIT, NOT PER SCREEN. A key held across submits would swallow the operator's
        // second, genuinely different ticket as a duplicate — the exact inverse of the protection.
        var next = 0
        val api = FakeDeskApi().apply {
            createResult = ApiResult.Success(
                DeskTicketCreateResponse(success = true, ticket = ticket),
            )
        }
        val repository = DeskRepository(api) { "key-${next++}" }

        repository.createTicket("ws-1", DeskTicketDraft(subject = "a", message = "m"))
        repository.createTicket("ws-1", DeskTicketDraft(subject = "b", message = "m"))

        assertEquals(listOf("key-0", "key-1"), api.createKeys)
    }

    // ── The settings patch guard ─────────────────────────────────────────────

    @Test
    fun `an empty patch is refused here rather than being sent for the route to 400`() = runTest {
        val api = FakeDeskApi()

        val result = DeskRepository(api).saveSettings("ws-1", DeskSettingsPatch())

        assertTrue(result is ApiResult.DecodeFailure)
        assertTrue("no request may be sent", api.patches.isEmpty())
    }

    @Test
    fun `a patch clearing the brand name IS a change and is sent`() = runTest {
        val api = FakeDeskApi().apply {
            settingsResult = ApiResult.Success(
                DeskSettingsResponse(success = true, settings = settings.copy(publicBrandName = null)),
            )
        }

        val result = DeskRepository(api).saveSettings(
            "ws-1",
            DeskSettingsPatch(publicBrandName = DeskBrandName.Clear),
        )

        assertEquals(DeskBrandName.Clear, api.patches.single().publicBrandName)
        // ⚠️ THE ECHO IS ADOPTED. The write returns the stored row, so the screen needs no re-read.
        assertNull((result as ApiResult.Success).value.publicBrandName)
    }

    // ── The logo takedown's two halves ───────────────────────────────────────

    @Test
    fun `deleteLogo returns the whole response so objectRemoved survives`() = runTest {
        // ⛔ A 200 MEANS THE COLUMN WAS CLEARED, WHICH IS WHAT TAKES THE IMAGE OFF THE PAGE.
        // `objectRemoved: false` means the stored file survived and may still answer its old link.
        // Collapsing this to the settings row would discard the only field that says so.
        val api = FakeDeskApi().apply {
            logoRemovalResult = ApiResult.Success(
                DeskLogoRemovalResponse(
                    success = true,
                    settings = settings.copy(publicLogoUrl = null),
                    objectRemoved = false,
                ),
            )
        }

        val result = DeskRepository(api).deleteLogo("ws-1")

        assertEquals(false, (result as ApiResult.Success).value.objectRemoved)
        assertNull(result.value.settings!!.publicLogoUrl)
    }

    @Test
    fun `a logo upload returns the settings the server stored, and a failure passes through`() = runTest {
        val api = FakeDeskApi().apply {
            settingsResult = ApiResult.Success(DeskSettingsResponse(success = true, settings = settings))
        }
        assertEquals(
            ApiResult.Success(settings),
            DeskRepository(api).uploadLogo("ws-1", "logo.png", "image/png", byteArrayOf(1)),
        )

        val tooLarge = ApiResult.HttpFailure(status = 413, message = "Logo too large.")
        api.settingsResult = tooLarge
        assertEquals(tooLarge, DeskRepository(api).uploadLogo("ws-1", "logo.png", "image/png", byteArrayOf(1)))
    }

    @Test
    fun `a logo removal with no settings is drift, and a failed one is passed through`() = runTest {
        val empty = FakeDeskApi().apply {
            logoRemovalResult = ApiResult.Success(DeskLogoRemovalResponse(success = true))
        }
        assertTrue(DeskRepository(empty).deleteLogo("ws-1") is ApiResult.DecodeFailure)

        val refused = FakeDeskApi().apply {
            logoRemovalResult = ApiResult.Success(DeskLogoRemovalResponse(success = false, settings = settings))
        }
        assertTrue(DeskRepository(refused).deleteLogo("ws-1") is ApiResult.DecodeFailure)

        val offline = ApiResult.NetworkFailure(IOException("offline"))
        val failed = FakeDeskApi().apply { logoRemovalResult = offline }
        assertEquals(offline, DeskRepository(failed).deleteLogo("ws-1"))
    }

    @Test
    fun `every settings, ticket and reply call refuses a 200 that does not affirm success`() = runTest {
        // ⛔ Each of these bodies decodes with its payload present, so only the envelope says the
        // server did not do what was asked. A refusal read as success would show a stale state.
        val api = FakeDeskApi().apply {
            settingsResult = ApiResult.Success(DeskSettingsResponse(success = false, settings = settings))
            ticketResult = ApiResult.Success(DeskTicketResponse(success = false, ticket = DeskTicketDetail()))
            createResult = ApiResult.Success(DeskTicketCreateResponse(success = false, ticket = ticket))
            replyResult = ApiResult.Success(DeskReplyResponse(success = false))
            statusResult = ApiResult.Success(DeskTicketStatusResponse(success = false, ticket = ticket))
        }
        val repository = DeskRepository(api)

        assertTrue(repository.settings("ws-1") is ApiResult.DecodeFailure)
        assertTrue(
            repository.saveSettings("ws-1", DeskSettingsPatch(enabled = false)) is ApiResult.DecodeFailure,
        )
        assertTrue(
            repository.uploadLogo("ws-1", "logo.png", "image/png", byteArrayOf(1)) is ApiResult.DecodeFailure,
        )
        assertTrue(repository.ticket("ws-1", "tkt_1") is ApiResult.DecodeFailure)
        assertTrue(
            repository.createTicket("ws-1", DeskTicketDraft(subject = "Leak", message = "Drips")) is
                ApiResult.DecodeFailure,
        )
        assertTrue(repository.reply("ws-1", "tkt_1", "Tuesday.") is ApiResult.DecodeFailure)
        assertTrue(repository.setStatus("ws-1", "tkt_1", DeskTicketStatus.RESOLVED) is ApiResult.DecodeFailure)
    }

    // ── The reply's three shapes ─────────────────────────────────────────────

    @Test
    fun `a normal reply carries the echoed ticket, the message and notified`() = runTest {
        val api = FakeDeskApi().apply {
            replyResult = ApiResult.Success(
                DeskReplyResponse(
                    success = true,
                    ticket = ticket.copy(status = "waiting"),
                    message = DeskMessage(id = "m2", authorType = "team", body = "Tuesday."),
                    notified = true,
                ),
            )
        }

        val reply = (DeskRepository(api).reply("ws-1", "tkt_1", " Tuesday. ") as ApiResult.Success)
            .value

        assertEquals("waiting", reply.ticket!!.status)
        assertEquals(true, reply.notified)
        // ⚠️ Trimmed on the way out, so trailing whitespace does not reach a customer's inbox.
        assertEquals("Tuesday.", api.replyBodies.single())
    }

    @Test
    fun `a degraded replay is a success whose notified is NULL rather than false`() = runTest {
        // ⛔ NULL IS "WE DO NOT KNOW", NEVER "NO". Telling an operator their customer was not
        // emailed, when the truth is that the cached payload has left Redis, is a claim about
        // someone else's inbox that we cannot support.
        val api = FakeDeskApi().apply {
            replyResult = ApiResult.Success(
                DeskReplyResponse(success = true, deduplicated = true),
            )
        }

        val reply = (DeskRepository(api).reply("ws-1", "tkt_1", "hi") as ApiResult.Success).value

        assertTrue(reply.deduplicated)
        assertNull(reply.ticket)
        assertNull(reply.message)
        assertNull(reply.notified)
    }

    @Test
    fun `a status change that affirms success with no ticket is drift`() = runTest {
        // ⚠️ The echoed ticket is the only thing that carries `resolvedAt`, which the server stamps
        // and clears. Without it a screen would have to guess, and guessing wrong makes every
        // figure computed from it plausible and wrong.
        val api = FakeDeskApi().apply {
            statusResult = ApiResult.Success(DeskTicketStatusResponse(success = true))
        }

        val result = DeskRepository(api).setStatus("ws-1", "tkt_1", DeskTicketStatus.RESOLVED)

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `the status enum reaches the api unchanged`() = runTest {
        val api = FakeDeskApi().apply {
            statusResult = ApiResult.Success(
                DeskTicketStatusResponse(success = true, ticket = ticket.copy(status = "resolved")),
            )
        }

        DeskRepository(api).setStatus("ws-1", "tkt_1", DeskTicketStatus.RESOLVED)

        assertEquals(listOf(DeskTicketStatus.RESOLVED), api.statuses)
    }

    @Test
    fun `a ticket detail that affirms success with no ticket is drift`() = runTest {
        val api = FakeDeskApi().apply {
            ticketResult = ApiResult.Success(DeskTicketResponse(success = true))
        }

        assertTrue(DeskRepository(api).ticket("ws-1", "tkt_1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a ticket detail passes the thread through`() = runTest {
        val api = FakeDeskApi().apply {
            ticketResult = ApiResult.Success(
                DeskTicketResponse(
                    success = true,
                    ticket = DeskTicketDetail(
                        id = "tkt_1",
                        messages = listOf(DeskMessage(id = "m1", authorType = "customer")),
                    ),
                ),
            )
        }

        val detail = (DeskRepository(api).ticket("ws-1", "tkt_1") as ApiResult.Success).value

        assertEquals(listOf("m1"), detail.messages.map { it.id })
    }

    @Test
    fun `blankToNull is the single place a blank becomes absent`() {
        assertNull(blankToNull(null))
        assertNull(blankToNull(""))
        assertNull(blankToNull("   "))
        assertEquals("ada@example.test", blankToNull("  ada@example.test  "))
    }
}

/**
 * ⚠️ ITS OWN FAKE RATHER THAN AN ENTRY ON `FakeDistrictApi`. [DeskApi] is a separate interface from
 * `DistrictApi` — see the ⛔ on `HttpDeskApi` — so a desk fake has nothing to add to that class, and
 * keeping it here means this file can be read without it.
 */
private class FakeDeskApi : DeskApi {
    var settingsResult: ApiResult<DeskSettingsResponse> =
        ApiResult.Success(DeskSettingsResponse(success = true, settings = DeskSettings()))
    var logoRemovalResult: ApiResult<DeskLogoRemovalResponse> =
        ApiResult.Success(DeskLogoRemovalResponse(success = true, settings = DeskSettings()))
    var ticketsResult: ApiResult<DeskTicketsResponse> =
        ApiResult.Success(DeskTicketsResponse(success = true))
    var ticketResult: ApiResult<DeskTicketResponse> =
        ApiResult.Success(DeskTicketResponse(success = true, ticket = DeskTicketDetail()))
    var createResult: ApiResult<DeskTicketCreateResponse> =
        ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = DeskTicketSummary()))
    var replyResult: ApiResult<DeskReplyResponse> =
        ApiResult.Success(DeskReplyResponse(success = true))
    var statusResult: ApiResult<DeskTicketStatusResponse> =
        ApiResult.Success(DeskTicketStatusResponse(success = true, ticket = DeskTicketSummary()))

    val patches = mutableListOf<DeskSettingsPatch>()
    val createDrafts = mutableListOf<DeskTicketDraft>()
    val createKeys = mutableListOf<String?>()
    val replyBodies = mutableListOf<String>()
    val statuses = mutableListOf<DeskTicketStatus>()

    override suspend fun deskSettings(workspaceId: String) = settingsResult

    override suspend fun saveDeskSettings(workspaceId: String, patch: DeskSettingsPatch) =
        settingsResult.also { patches += patch }

    override suspend fun uploadDeskLogo(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) = settingsResult

    override suspend fun deleteDeskLogo(workspaceId: String) = logoRemovalResult

    override suspend fun deskTickets(workspaceId: String, status: DeskTicketStatus?) = ticketsResult

    override suspend fun createDeskTicket(
        workspaceId: String,
        draft: DeskTicketDraft,
        idempotencyKey: String?,
    ) = createResult.also {
        createDrafts += draft
        createKeys += idempotencyKey
    }

    override suspend fun deskTicket(workspaceId: String, ticketId: String) = ticketResult

    override suspend fun replyToDeskTicket(
        workspaceId: String,
        ticketId: String,
        message: String,
        idempotencyKey: String?,
    ) = replyResult.also { replyBodies += message }

    override suspend fun setDeskTicketStatus(
        workspaceId: String,
        ticketId: String,
        status: DeskTicketStatus,
    ) = statusResult.also { statuses += status }
}
