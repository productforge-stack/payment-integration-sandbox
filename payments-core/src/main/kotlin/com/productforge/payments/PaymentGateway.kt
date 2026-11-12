package com.productforge.payments

/**
 * The calls an integration needs from a payment gateway.
 *
 * Calls block the calling thread. Android apps call them from a background
 * dispatcher, and Java code can call them directly with no coroutine wrapper.
 */
interface PaymentGateway {

    /**
     * Submits a transaction. The gateway processes each [idempotencyKey] at most once:
     * sending the same key again returns the first result instead of charging twice.
     */
    @Throws(GatewayException::class)
    fun submit(request: TransactionRequest, idempotencyKey: String): TransactionResponse

    /** Looks up a transaction by the key it was sent with. Returns null if the gateway never received it. */
    @Throws(GatewayException::class)
    fun findByIdempotencyKey(idempotencyKey: String): TransactionResponse?
}
