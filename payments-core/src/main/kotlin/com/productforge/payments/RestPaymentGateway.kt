package com.productforge.payments

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * A [PaymentGateway] over REST and JSON.
 *
 *   POST /v1/transactions                       submit, with an Idempotency-Key header
 *   GET  /v1/transactions?idempotencyKey={key}  look up, 404 if never received
 */
class RestPaymentGateway(
    baseUrl: String,
    private val apiKey: String,
    client: OkHttpClient,
) : PaymentGateway {

    // OkHttp can silently resend a request after some connection failures. For payments,
    // every retry has to be a deliberate decision made by PaymentService, so turn that off.
    private val client: OkHttpClient = client.newBuilder().retryOnConnectionFailure(false).build()

    private val transactionsUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
        .newBuilder().addPathSegments("v1/transactions").build()

    override fun submit(request: TransactionRequest, idempotencyKey: String): TransactionResponse {
        val body = json.encodeToString(TransactionRequest.serializer(), request).toRequestBody(JSON)
        val httpRequest = Request.Builder()
            .url(transactionsUrl)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return execute(httpRequest) { response -> parse(response) }
    }

    override fun findByIdempotencyKey(idempotencyKey: String): TransactionResponse? {
        val url = transactionsUrl.newBuilder().addQueryParameter("idempotencyKey", idempotencyKey).build()
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return execute(httpRequest) { response ->
            if (response.code == 404) null else parse(response)
        }
    }

    private fun <T> execute(request: Request, onResponse: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: ConnectException) {
            throw GatewayException.NetworkUnavailable(e)
        } catch (e: UnknownHostException) {
            throw GatewayException.NetworkUnavailable(e)
        } catch (e: InterruptedIOException) {
            // Includes SocketTimeoutException: the request may already be at the gateway.
            throw GatewayException.Timeout(e)
        } catch (e: IOException) {
            // Connection dropped mid-exchange. Treat as unknown, never as "not sent".
            throw GatewayException.Timeout(e)
        }
        return response.use(onResponse)
    }

    private fun parse(response: Response): TransactionResponse {
        val text = response.body?.string().orEmpty()
        when (response.code) {
            in 200..299 -> Unit
            in 400..499 -> throw GatewayException.Rejected(response.code, text.ifBlank { response.message })
            else -> throw GatewayException.ServerError(response.code)
        }
        return try {
            json.decodeFromString(TransactionResponse.serializer(), text)
        } catch (e: SerializationException) {
            // A 2xx we can't read means we don't know what happened.
            throw GatewayException.ServerError(response.code)
        }
    }

    companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        internal val JSON = "application/json; charset=utf-8".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
