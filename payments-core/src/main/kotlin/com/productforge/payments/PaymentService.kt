package com.productforge.payments

import java.util.UUID

/** What happened to a payment, in terms an app can act on. */
sealed class PaymentResult {
    abstract val idempotencyKey: String

    data class Approved(
        val response: TransactionResponse,
        override val idempotencyKey: String,
        /** True when a timeout or gateway error happened first and the result was recovered. */
        val recovered: Boolean,
    ) : PaymentResult()

    data class Declined(
        val response: TransactionResponse,
        override val idempotencyKey: String,
        val recovered: Boolean,
    ) : PaymentResult()

    /** Nothing was charged. [safeToRetry] says whether trying again could succeed. */
    data class Failed(
        val reason: String,
        override val idempotencyKey: String,
        val safeToRetry: Boolean,
    ) : PaymentResult()

    /**
     * The gateway may or may not have charged the card, and it couldn't be confirmed.
     * Don't retry with a new key: reconcile with this key first.
     */
    data class Unknown(
        val reason: String,
        override val idempotencyKey: String,
    ) : PaymentResult()
}

/**
 * Runs sales, refunds and voids against a [PaymentGateway] without ever double-charging.
 *
 * The rule: when the outcome is unknown (timeout or 5xx), ask the gateway what happened
 * using the same idempotency key before doing anything else. Only resubmit, with the same
 * key, once the gateway confirms it never received the request.
 */
class PaymentService @JvmOverloads constructor(
    private val gateway: PaymentGateway,
    private val maxRecoveryAttempts: Int = 3,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) {
    init {
        require(maxRecoveryAttempts >= 1) { "maxRecoveryAttempts must be at least 1" }
    }

    fun sale(amount: Money): PaymentResult =
        execute(TransactionRequest(TransactionType.SALE, amount.minorUnits, amount.currency))

    fun refund(originalTransactionId: String, amount: Money): PaymentResult {
        require(originalTransactionId.isNotBlank()) { "A refund needs the original transaction ID" }
        return execute(
            TransactionRequest(TransactionType.REFUND, amount.minorUnits, amount.currency, originalTransactionId),
        )
    }

    fun void(originalTransactionId: String, amount: Money): PaymentResult {
        require(originalTransactionId.isNotBlank()) { "A void needs the original transaction ID" }
        return execute(
            TransactionRequest(TransactionType.VOID, amount.minorUnits, amount.currency, originalTransactionId),
        )
    }

    private fun execute(request: TransactionRequest): PaymentResult {
        val key = newIdempotencyKey()
        log("Sending ${request.type} for ${Money(request.amountMinor, request.currency).format()} (key ${key.take(8)})")
        return try {
            toResult(gateway.submit(request, key), key, recovered = false)
        } catch (e: GatewayException) {
            if (e.outcomeUnknown) {
                log("${e.message}. Outcome unknown, checking with the gateway before retrying")
                recover(request, key)
            } else {
                failed(e, key)
            }
        }
    }

    /** Called only when the first attempt's outcome is unknown. */
    private fun recover(request: TransactionRequest, key: String): PaymentResult {
        var lastProblem = "The gateway couldn't be reached"
        repeat(maxRecoveryAttempts) { attempt ->
            val n = attempt + 1
            try {
                val existing = gateway.findByIdempotencyKey(key)
                if (existing != null) {
                    log("Check $n: the gateway already processed it (${existing.status}). Not resending")
                    return toResult(existing, key, recovered = true)
                }
                log("Check $n: the gateway never received it. Resending with the same key")
                return toResult(gateway.submit(request, key), key, recovered = true)
            } catch (e: GatewayException) {
                if (!e.outcomeUnknown && e !is GatewayException.NetworkUnavailable) return failed(e, key)
                lastProblem = e.message ?: lastProblem
                log("Check $n failed: $lastProblem")
            }
        }
        log("Gave up after $maxRecoveryAttempts checks. Reconcile key $key before taking payment again")
        return PaymentResult.Unknown(
            "$lastProblem. The payment may have gone through, so don't charge again until it's reconciled.",
            key,
        )
    }

    private fun toResult(response: TransactionResponse, key: String, recovered: Boolean): PaymentResult =
        when (response.status) {
            TransactionStatus.APPROVED -> {
                log("Approved, transaction ${response.transactionId}, auth ${response.authCode ?: "none"}")
                PaymentResult.Approved(response, key, recovered)
            }
            TransactionStatus.DECLINED -> {
                log("Declined: ${response.declineReason ?: "no reason given"}")
                PaymentResult.Declined(response, key, recovered)
            }
        }

    private fun failed(e: GatewayException, key: String): PaymentResult {
        val safeToRetry = e is GatewayException.NetworkUnavailable
        log("Failed: ${e.message}. Nothing was charged")
        return PaymentResult.Failed(e.message ?: "Request failed", key, safeToRetry)
    }
}
