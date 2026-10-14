package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.NumberLookupInfo
import com.distronode.districtai.core.model.NumberLookupResponse
import com.distronode.districtai.core.model.ProviderStatusEntry
import com.distronode.districtai.core.model.ProviderStatusResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the sixteen number, carrier and registration endpoints put on the wire, and that their
 * answers decode.
 *
 * ⚠️ THE ANSWERS ARE THE ROUTES' OWN SHAPES (`distronode-website/src/app/api/district/workspace/...`),
 * not server-generated fixtures: the monorepo has none for these routes yet, which the hand-back
 * reports. ⛔ The properties pinned here are the ones no other gate sees: where the workspace goes
 * (body on configure, release and the carrier writes; query on the registration routes), the
 * verbs, the boolean that must be a boolean, and the keys that are always sent.
 */
class NumberProvisioningRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = testApiClient(server, FakeRefreshApi().apply { rotating() })

    private fun numbers(): NumberProvisioningApi = HttpNumberProvisioningApi(client())

    private fun carrier(): CarrierAccountApi = HttpCarrierAccountApi(client())

    private fun answer(body: String) = server.enqueue(MockResponse(code = 200, body = body))

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body?.utf8().orEmpty()) as JsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `provider status is a GET that decodes its carriers`() = runTest {
        answer(
            """{"connected":true,"provider":"twilio","providers":{"twilio":{"connected":true,""" +
                """"accountName":"Acme","balance":"12.50","currency":"USD","numberCount":3},""" +
                """"sinch":{"connected":false,"error":"refused"}}}"""
        )
        val result = numbers().providerStatus("ws_1") as ApiResult.Success
        assertEquals(listOf("twilio"), result.value.connectedProviders)
        assertEquals(listOf("sinch"), result.value.refusedProviders)
        assertEquals(
            ProviderStatusEntry(
                connected = true,
                accountName = "Acme",
                balance = "12.50",
                currency = "USD",
                numberCount = 3
            ),
            result.value.providers!!["twilio"]
        )
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/provider/status", recorded.url.encodedPath)
        assertEquals("ws_1", recorded.url.queryParameter("workspaceId"))
        // A workspace with no carrier answers without providers at all.
        answer("""{"connected":false,"provider":null}""")
        assertEquals(ApiResult.Success(ProviderStatusResponse()), numbers().providerStatus("ws_1"))
        assertEquals(emptyList<String>(), ProviderStatusResponse().connectedProviders)
    }

    @Test
    fun `requirements send the country and drop an absent type`() = runTest {
        answer("""{"success":true,"country":"CA","numberType":"local","purchasable":true,"requirements":null}""")
        numbers().numberRequirements("ws_1", "CA", numberType = null, endUserType = null)
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/numbers/requirements", recorded.url.encodedPath)
        assertEquals("CA", recorded.url.queryParameter("country"))
        assertEquals(null, recorded.url.queryParameter("type"))
        answer(
            """{"success":true,"country":"DE","numberType":"mobile","purchasable":false,""" +
                """"requirements":{"isoCountry":"DE","numberType":"mobile","endUserType":"business",""" +
                """"regulationSid":"RN1","friendlyName":"Germany mobile","requirements":[{"kind":"end_user",""" +
                """"name":"Business","requirementName":"business_info","type":"business",""" +
                """"fields":["business_name"]}]}}"""
        )
        val result = numbers().numberRequirements("ws_1", "DE", "mobile", "business") as ApiResult.Success
        assertEquals("Germany mobile", result.value.requirements!!.friendlyName)
        assertEquals("mobile", server.takeRequest().url.queryParameter("type"))
    }

    @Test
    fun `the registration routes take the workspace in the query`() = runTest {
        answer(
            """{"success":true,"registrations":[{"id":"BU1","isoCountry":"DE","numberType":"local",""" +
                """"endUserType":"business","friendlyName":null,"status":"draft","rejectionReasons":[],""" +
                """"submittedAt":null,"reviewedAt":null,"createdAt":"2026-10-01T00:00:00.000Z",""" +
                """"documents":[{"id":"D1","requirementName":"proof","mimeType":"application/pdf","sizeBytes":100,""" +
                """"stored":true,"submitted":false,"updatedAt":"2026-10-01T00:00:00.000Z"}]}],""" +
                """"approvedCountries":["CA"],"platformCountries":["US"]}"""
        )
        val list = numbers().numberRegistrations("ws_1") as ApiResult.Success
        assertEquals("D1", list.value.registrations.single().documents.single().id)
        assertEquals("ws_1", server.takeRequest().url.queryParameter("workspaceId"))

        answer(
            """{"success":true,"registration":{"id":"BU2","isoCountry":"DE","numberType":"local",""" +
                """"endUserType":"business","status":"draft","rejectionReasons":[],"createdAt":"t","documents":[]}}"""
        )
        numbers().createNumberRegistration("ws_1", "DE", "local", endUserType = null, friendlyName = "Berlin")
        val created = server.takeRequest()
        assertEquals("POST", created.method)
        assertEquals("/api/district/workspace/numbers/registrations", created.url.encodedPath)
        assertEquals("ws_1", created.url.queryParameter("workspaceId"))
        val body = created.json()
        assertEquals("DE", body.text("isoCountry"))
        assertEquals("Berlin", body.text("friendlyName"))
        assertFalse(body.containsKey("endUserType"))
        assertFalse("the workspace is not in the body", body.containsKey("workspaceId"))
    }

    @Test
    fun `a document upload is multipart with two fields, and a removal is a DELETE with a body`() = runTest {
        answer(
            """{"success":true,"document":{"id":"D2","requirementName":"proof","mimeType":"application/pdf",""" +
                """"sizeBytes":3,"createdAt":"t"}}"""
        )
        val upload = RegulatoryDocumentUpload("BU1", "proof", "proof.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        numbers().uploadRegistrationDocument("ws_1", upload)
        val sent = server.takeRequest()
        assertEquals("/api/district/workspace/numbers/registrations/documents", sent.url.encodedPath)
        assertEquals("ws_1", sent.url.queryParameter("workspaceId"))
        val multipart = sent.body?.utf8().orEmpty()
        assertTrue(multipart.contains("name=\"bundleId\"") && multipart.contains("name=\"requirementName\""))
        assertFalse(multipart.contains("name=\"workspaceId\""))

        answer("""{"success":true,"documentId":"D2"}""")
        numbers().deleteRegistrationDocument("ws_1", "BU1", "D2")
        val removed = server.takeRequest()
        assertEquals("DELETE", removed.method)
        assertEquals("D2", removed.json().text("documentId"))
    }

    @Test
    fun `a submit always sends its attributes, even empty`() = runTest {
        answer("""{"success":true,"registration":{"id":"BU1","status":"pending-review","submittedAt":"t"}}""")
        numbers().submitNumberRegistration("ws_1", "BU1", emptyMap())
        val body = server.takeRequest().json()
        assertEquals("BU1", body.text("bundleId"))
        assertEquals(JsonObject(emptyMap()), body["endUserAttributes"]?.jsonObject)
        answer("""{"success":true,"registration":{"id":"BU1","status":"pending-review","submittedAt":"t"}}""")
        numbers().submitNumberRegistration("ws_1", "BU1", mapOf("business_name" to "Acme"))
        assertEquals("Acme", server.takeRequest().json()["endUserAttributes"]!!.jsonObject.text("business_name"))
    }

    @Test
    fun `configure and release carry the workspace in the body`() = runTest {
        answer("""{"success":true}""")
        numbers().configureNumber("ws_1", "+14165550142")
        val configured = server.takeRequest()
        assertEquals("/api/district/workspace/numbers/configure", configured.url.encodedPath)
        assertEquals("ws_1", configured.json().text("workspaceId"))

        answer("""{"success":true,"warnings":["The routing rule could not be removed."]}""")
        val released = numbers().releaseNumber("ws_1", "+14165550142") as ApiResult.Success
        assertEquals(listOf("The routing rule could not be removed."), released.value.warnings)
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/numbers/release", recorded.url.encodedPath)
        assertEquals("+14165550142", recorded.json().text("phoneNumber"))
    }

    @Test
    fun `SIP trunks read and create`() = runTest {
        answer(
            """{"success":true,"trunks":[{"id":"T1","name":"PBX","domain":"acme",""" +
                """"ipAccessControlList":["1.2.3.4"],"domainSid":null,"ipAclSid":null,"status":"active"}]}"""
        )
        val trunks = carrier().sipTrunks("ws_1") as ApiResult.Success
        assertEquals("acme", trunks.value.trunks.single().domain)
        assertEquals("/api/district/workspace/sip", server.takeRequest().url.encodedPath)

        answer(
            """{"success":true,"trunk":{"id":"T2","name":"PBX","domain":"acme",""" +
                """"ipAccessControlList":["1.2.3.4"],"status":"active"}}"""
        )
        carrier().createSipTrunk("ws_1", "PBX", "acme", listOf("1.2.3.4", "5.6.7.0/24"))
        val body = server.takeRequest().json()
        assertEquals("ws_1", body.text("workspaceId"))
        assertEquals(2, body["ipAccessControlList"]!!.jsonArray.size)
    }

    @Test
    fun `the passcode sender reads, and is set with a real boolean`() = runTest {
        answer("""{"success":true,"enabled":false}""")
        carrier().verifyService("ws_1")
        assertEquals("/api/district/workspace/verify", server.takeRequest().url.encodedPath)
        answer("""{"success":true,"enabled":true,"verifyServiceSid":"VA1"}""")
        carrier().setVerifyServiceEnabled("ws_1", enabled = true)
        val enabled = server.takeRequest().json()["enabled"]!!.jsonPrimitive
        assertFalse("a boolean, not a string", enabled.isString)
        assertEquals("true", enabled.content)
    }

    @Test
    fun `A2P sends its fields and leaves the empty optionals out`() = runTest {
        answer(
            """{"success":true,"status":"pending","brandSid":"BN1","messagingServiceSid":"MG1","campaignSid":"CM1"}"""
        )
        val request = A2PRegistrationRequest(
            "Acme",
            "CUSTOMER_CARE",
            "Appointment reminders",
            "Hi",
            "Bye",
            website = "acme.test"
        )
        val result = carrier().submitA2PRegistration("ws_1", request) as ApiResult.Success
        assertEquals("pending", result.value.status)
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/a2p", recorded.url.encodedPath)
        val body = recorded.json()
        assertEquals("Acme", body.text("businessName"))
        assertEquals("acme.test", body.text("website"))
        assertFalse(body.containsKey("ein"))
        assertEquals("Bye", body.text("sampleMessage2"))
    }

    @Test
    fun `toll-free always sends its opt-in list`() = runTest {
        answer("""{"success":true,"status":"PENDING_REVIEW","tfvSid":"HH1"}""")
        val request = TollFreeVerificationRequest("+18005550100", "Acme", "Customer Support", emptyList())
        carrier().submitTollFreeVerification("ws_1", request)
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/tfv", recorded.url.encodedPath)
        val body = recorded.json()
        assertEquals(0, body["optInImageUrls"]!!.jsonArray.size)
        assertFalse(body.containsKey("messageContent"))
        answer("""{"success":true,"status":"PENDING_REVIEW","tfvSid":"HH1"}""")
        carrier().submitTollFreeVerification(
            "ws_1",
            request.copy(
                optInImageUrls = listOf("https://acme.test/optin"),
                messageContent = "Hi",
                website = "w",
                optInFlow = "f"
            ),
        )
        val second = server.takeRequest().json()
        assertEquals("https://acme.test/optin", second["optInImageUrls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("f", second.text("optInFlow"))
    }

    @Test
    fun `the lookup is a GET with the number in the query`() = runTest {
        answer(
            """{"success":true,"info":{"valid":true,"phoneNumber":"+14165550142","country":"CA",""" +
                """"type":"mobile","carrier":"Rogers"}}"""
        )
        val result = carrier().lookupNumber("ws_1", "+14165550142")
        assertEquals(
            ApiResult.Success(
                NumberLookupResponse(true, NumberLookupInfo(true, "+14165550142", "CA", "mobile", "Rogers"))
            ),
            result,
        )
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/lookup", recorded.url.encodedPath)
        assertEquals("+14165550142", recorded.url.queryParameter("phoneNumber"))
    }
}
