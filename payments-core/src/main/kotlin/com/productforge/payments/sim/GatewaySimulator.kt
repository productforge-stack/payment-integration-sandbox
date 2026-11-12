package com.productforge.payments.sim

import com.productforge.payments.RestPaymentGateway
import com.productforge.payments.TransactionRequest
import com.productforge.payments.TransactionResponse
import com.productforge.payments.TransactionStatus
import com.productforge.payments.TransactionType
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Failure modes a real integration has to survive. */
enum class Scenario(val label: String, val description: String) {
    APPROVE("Approve", "The gateway approves the transaction."),
    DECLINE("Decline", "The issuer declines: insufficient funds."),
    TIMEOUT_AFTER_CHARGE(
        "Timeout after charge",
        "The gateway charges the card, but the response never arrives.",
    ),
    ERROR_BEFORE_CHARGE(
        "Gateway error before charge",
        "The gateway returns HTTP 503 without processing, then recovers.",
    ),
    OFFLINE("Device offline", "The request can't leave the device."),
    GATEWAY_DOWN("Gateway down", "Every call times out, so the outcome can't be confirmed."),
}

/**
 * An OkHttp interceptor that answers like a payment gateway's REST API, so the app's real
 * HTTP and JSON code runs end to end with no server. Behaviour is chosen by [scenario].
 * Like a real gateway, it processes each idempotency key once.
 */
class GatewaySimulator(private val scenario: () -> Scenario) : Interceptor {

    private val byKey = ConcurrentHashMap<String, TransactionResponse>()
    private val byId = ConcurrentHashMap<String, TransactionResponse>()
    private val failedOnce = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicInteger(1000)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** How many transactions the simulated gateway has charged or refunded. Used by tests. */
    val processedCount: Int get() = byKey.size

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = scenario()
        when (current) {
            Scenario.OFFLINE -> throw ConnectException("Network is unreachable (simulated)")
            Scenario.GATEWAY_DOWN -> throw SocketTimeoutException("timeout (simulated)")
            else -> Unit
        }
        return when (request.method) {
            "GET" -> lookup(chain, request.url.queryParameter("idempotencyKey"))
            "POST" -> submit(chain, current)
            else -> respond(chain, 405, """{"error":"Method not allowed"}""")
        }
    }

    private fun lookup(chain: Interceptor.Chain, key: String?): Response {
        val found = key?.let { byKey[it] } ?: return respond(chain, 404, """{"error":"Not found"}""")
        return respond(chain, 200, json.encodeToString(TransactionResponse.serializer(), found))
    }

    private fun submit(chain: Interceptor.Chain, current: Scenario): Response {
        val request = chain.request()
        val key = request.header(RestPaymentGateway.IDEMPOTENCY_HEADER)
            ?: return respond(chain, 400, """{"error":"Idempotency-Key header is required"}""")
        if (request.header("Authorization")?.startsWith("Bearer ") != true) {
            return respond(chain, 401, """{"error":"Missing API key"}""")
        }

        // Same key again: return the original answer, never charge twice.
        byKey[key]?.let { return respond(chain, 200, json.encodeToString(TransactionResponse.serializer(), it)) }

        if (current == Scenario.ERROR_BEFORE_CHARGE && failedOnce.add(key)) {
            return respond(chain, 503, """{"error":"Service unavailable"}""")
        }

        val buffer = Buffer().also { request.body?.writeTo(it) }
        val body = json.decodeFromString(TransactionRequest.serializer(), buffer.readUtf8())

        if (body.type != TransactionType.SALE) {
            val original = body.originalTransactionId?.let { byId[it] }
            if (original == null || original.status != TransactionStatus.APPROVED) {
                return respond(chain, 422, """{"error":"Original transaction not found or not approved"}""")
            }
            if (body.amountMinor > original.amountMinor) {
                return respond(chain, 422, """{"error":"Amount is more than the original transaction"}""")
            }
        }

        val id = "TXN-${nextId.getAndIncrement()}"
        val declined = current == Scenario.DECLINE && body.type == TransactionType.SALE
        val response = TransactionResponse(
            transactionId = id,
            type = body.type,
            status = if (declined) TransactionStatus.DECLINED else TransactionStatus.APPROVED,
            amountMinor = body.amountMinor,
            currency = body.currency,
            authCode = if (declined) null else "A${id.takeLast(4)}",
            declineReason = if (declined) "Insufficient funds" else null,
        )
        byKey[key] = response
        byId[id] = response

        if (current == Scenario.TIMEOUT_AFTER_CHARGE && failedOnce.add(key)) {
            // Charged, but the caller never hears about it.
            throw SocketTimeoutException("timeout after processing (simulated)")
        }
        return respond(chain, 200, json.encodeToString(TransactionResponse.serializer(), response))
    }

    private fun respond(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(JSON))
            .build()

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
