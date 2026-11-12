package com.productforge.payments

import kotlinx.serialization.Serializable

enum class TransactionType { SALE, REFUND, VOID }

enum class TransactionStatus { APPROVED, DECLINED }

/** The JSON body sent to POST /v1/transactions. */
@Serializable
data class TransactionRequest(
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val originalTransactionId: String? = null,
)

/** The gateway's answer for one transaction. */
@Serializable
data class TransactionResponse(
    val transactionId: String,
    val type: TransactionType,
    val status: TransactionStatus,
    val amountMinor: Long,
    val currency: String,
    val authCode: String? = null,
    val declineReason: String? = null,
)

/**
 * Why a call to the gateway failed. The split that matters is whether the gateway
 * might have processed the request, because that decides whether a retry is safe.
 */
sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The request may have reached the gateway. The outcome is unknown. */
    class Timeout(cause: Throwable? = null) :
        GatewayException("The gateway didn't answer in time", cause)

    /** The gateway failed (HTTP 5xx). The outcome is unknown. */
    class ServerError(val httpCode: Int) :
        GatewayException("The gateway returned HTTP $httpCode")

    /** The request never left the device, so nothing was processed. */
    class NetworkUnavailable(cause: Throwable? = null) :
        GatewayException("Couldn't connect to the gateway", cause)

    /** The gateway rejected the request (HTTP 4xx). Retrying the same request won't help. */
    class Rejected(val httpCode: Int, val detail: String) :
        GatewayException("The gateway rejected the request (HTTP $httpCode): $detail")

    /** True when the gateway may have processed the request before the failure. */
    val outcomeUnknown: Boolean get() = this is Timeout || this is ServerError
}
