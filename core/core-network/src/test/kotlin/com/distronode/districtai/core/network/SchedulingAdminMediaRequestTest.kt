package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scheduling-admin route that is not the RPC: a multipart upload.
 *
 * ⛔ THE WORKSPACE TRAVELS IN THE **QUERY** ON THE UPLOAD, WHICH IS THE OPPOSITE OF EVERY OTHER
 * MULTIPART ROUTE IN THIS CLIENT, AND IT IS THE SERVER'S RULE RATHER THAN A STYLE CHOICE: the
 * workspace has to be readable before `req.formData()` so `requireWorkspaceRole` runs ahead of a
 * multipart parse of a body up to Cloudflare's 100 MB. A field list copied from `uploadMedia`
 * leaves that guard with null while the URL looks perfectly correct — so this file asserts the
 * query AND the field set, not one of them.
 */
class SchedulingAdminMediaRequestTest {

    private lateinit var server: MockWebServer
    private lateinit var refreshApi: FakeRefreshApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        refreshApi = FakeRefreshApi().apply { rotating() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun adminApi(): SchedulingAdminApi = HttpSchedulingAdminApi(testApiClient(server, refreshApi))

    @Test
    fun `an upload puts the workspace in the query and the target in a form field`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"ok":true,"data":{"logo_url":"https://x.test/logo.png"}}""",
            ),
        )

        val result = adminApi().uploadSchedulingImage(
            workspaceId = "ws-1",
            target = SchedulingAdminUploadTarget.LOGO,
            fileName = "logo.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3, 4),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/scheduling/admin/upload", recorded.url.encodedPath)

        // ⛔ THE QUERY IS THE GUARD'S INPUT. Without it the route answers 400 with the URL looking
        // entirely correct.
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))

        val body = recorded.body?.utf8().orEmpty()
        assertTrue("the target is a form field", body.contains("name=\"target\""))
        assertTrue(body.contains("logo"))

        // ⛔ THE FILE PART IS NAMED `file` AND IS RENAMED AT THE FAR END. The fork reads `logo`,
        // `banner` and `avatar`; our route translates, so a part named after the target is
        // "Missing file field" — which reads like a client that sent no body at all.
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"logo.png\""))
        assertTrue(body.contains("image/png"))

        // ⚠️ AND THE WORKSPACE IS **NOT** ALSO A FIELD. Sending it twice would be harmless today
        // and is exactly the copy-paste that makes the next reader believe the field is what the
        // guard reads.
        assertTrue(
            "the workspace must not be duplicated into the form",
            !body.contains("name=\"workspaceId\""),
        )

        val data = (result as ApiResult.Success).value as SchedulingAdminEnvelope.Data
        assertEquals("https://x.test/logo.png", data.value.logoUrl)
        assertEquals("https://x.test/logo.png", data.value.publishedUrl)
    }

    @Test
    fun `an avatar upload names its own target and answers its own key`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"ok":true,"data":{"avatar_url":"https://x.test/me.png"}}""",
            ),
        )

        val result = adminApi().uploadSchedulingImage(
            workspaceId = "ws-1",
            target = SchedulingAdminUploadTarget.AVATAR,
            fileName = "me.png",
            mimeType = "image/png",
            bytes = byteArrayOf(9),
        )

        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("avatar"))

        val data = (result as ApiResult.Success).value as SchedulingAdminEnvelope.Data
        assertEquals("https://x.test/me.png", data.value.avatarUrl)
        assertEquals(null, data.value.logoUrl)
    }

    @Test
    fun `an upload the scheduler refuses is a 200 refusal, not a decode failure`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"ok":false,"failure":"instance_unavailable","status":502}""",
            ),
        )

        val result = adminApi().uploadSchedulingImage(
            workspaceId = "ws-1",
            target = SchedulingAdminUploadTarget.BANNER,
            fileName = "b.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1),
        )

        val refusal = (result as ApiResult.Success).value as SchedulingAdminEnvelope.Refusal
        assertEquals("instance_unavailable", refusal.failure)
        assertEquals(502, refusal.status)
    }

    @Test
    fun `a 415 on an upload arrives as an HTTP failure this client could not have predicted`() =
        runTest {
            // ⚠️ THE FORK SNIFFS THE FIRST 512 BYTES rather than trusting the declared type, so a
            // real SVG renamed `.png` and declared `image/png` fails HERE and not on the device.
            server.enqueue(MockResponse(code = 415, body = """{"error":"unsupported_type"}"""))

            val result = adminApi().uploadSchedulingImage(
                workspaceId = "ws-1",
                target = SchedulingAdminUploadTarget.LOGO,
                fileName = "logo.png",
                mimeType = "image/png",
                bytes = byteArrayOf(1),
            )

            val failure = result as ApiResult.HttpFailure
            assertEquals(415, failure.status)
            assertEquals("unsupported_type", failure.message)
        }
}
