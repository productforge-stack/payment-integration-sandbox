package com.productforge.payments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Tests the HTTP layer against a real local HTTP server. */
class RestPaymentGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: RestPaymentGateway

    private val client = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .build()

    private val sale = TransactionRequest(TransactionType.SALE, 1234, "USD")

    private val approvedJson = """
        {"transactionId":"TXN-1","type":"SALE","status":"APPROVED","amountMinor":1234,
         "currency":"USD","authCode":"A0001","newField":"ignored"}
    """.trimIndent()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        gateway = RestPaymentGateway(server.url("/api/").toString(), "test-key", client)
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test fun `submit sends JSON, the idempotency key and the API key`() {
        server.enqueue(MockResponse().setBody(approvedJson))

        val response = gateway.submit(sale, "key-123")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/transactions", recorded.path)
        assertEquals("key-123", recorded.getHeader("Idempotency-Key"))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("SALE", body["type"]!!.jsonPrimitive.content)
        assertEquals("1234", body["amountMinor"]!!.jsonPrimitive.content)

        assertEquals("TXN-1", response.transactionId)
        assertEquals(TransactionStatus.APPROVED, response.status)
        assertEquals("A0001", response.authCode)
    }

    @Test fun `a declined transaction is a normal response, not an error`() {
        server.enqueue(
            MockResponse().setBody(
                """{"transactionId":"TXN-2","type":"SALE","status":"DECLINED","amountMinor":1234,"currency":"USD","declineReason":"Do not honor"}""",
            ),
        )
        val response = gateway.submit(sale, "k")
        assertEquals(TransactionStatus.DECLINED, response.status)
        assertEquals("Do not honor", response.declineReason)
    }

    @Test fun `4xx is rejected, and the outcome is known`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"Bad amount"}"""))
        val e = assertThrows(GatewayException.Rejected::class.java) { gateway.submit(sale, "k") }
        assertEquals(422, e.httpCode)
        assertTrue(e.detail.contains("Bad amount"))
        assertTrue(!e.outcomeUnknown)
    }

    @Test fun `5xx means the outcome is unknown`() {
        server.enqueue(MockResponse().setResponseCode(502))
        val e = assertThrows(GatewayException.ServerError::class.java) { gateway.submit(sale, "k") }
        assertEquals(502, e.httpCode)
        assertTrue(e.outcomeUnknown)
    }

    @Test fun `an unreadable 200 is treated as unknown, not as approved`() {
        server.enqueue(MockResponse().setBody("<html>proxy error</html>"))
        val e = assertThrows(GatewayException.ServerError::class.java) { gateway.submit(sale, "k") }
        assertTrue(e.outcomeUnknown)
    }

    @Test fun `no response in time is a timeout with unknown outcome`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = assertThrows(GatewayException.Timeout::class.java) { gateway.submit(sale, "k") }
        assertTrue(e.outcomeUnknown)
    }

    @Test fun `a dropped connection after sending is unknown, never assumed unsent`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val e = assertThrows(GatewayException::class.java) { gateway.submit(sale, "k") }
        assertTrue(e.outcomeUnknown)
    }

    @Test fun `connection refused means nothing was sent`() {
        val url = server.url("/").toString()
        server.shutdown()
        val offline = RestPaymentGateway(url, "test-key", client)
        val e = assertThrows(GatewayException.NetworkUnavailable::class.java) { offline.submit(sale, "k") }
        assertTrue(!e.outcomeUnknown)
    }

    @Test fun `unknown host means nothing was sent`() {
        val offline = RestPaymentGateway("https://no-such-host.invalid", "test-key", client)
        assertThrows(GatewayException.NetworkUnavailable::class.java) { offline.submit(sale, "k") }
    }

    @Test fun `lookup by idempotency key returns the transaction`() {
        server.enqueue(MockResponse().setBody(approvedJson))
        val found = gateway.findByIdempotencyKey("key 1/2")
        assertEquals("TXN-1", found!!.transactionId)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("key 1/2", recorded.requestUrl!!.queryParameter("idempotencyKey"))
    }

    @Test fun `lookup returns null when the gateway never received the key`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(gateway.findByIdempotencyKey("missing"))
    }

    @Test fun `lookup errors are reported, not swallowed`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(GatewayException.ServerError::class.java) { gateway.findByIdempotencyKey("k") }
    }
}
