package com.productforge.payments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the recovery rules with a scripted gateway, so each call's outcome is exact.
 * The key assertion in most tests: how many times was submit() called?
 */
class PaymentServiceTest {

    /** A gateway that plays back scripted outcomes and records every call. */
    private class ScriptedGateway(
        submits: List<() -> TransactionResponse>,
        lookups: List<() -> TransactionResponse?> = emptyList(),
    ) : PaymentGateway {
        private val submitScript = ArrayDeque(submits)
        private val lookupScript = ArrayDeque(lookups)
        val submittedKeys = mutableListOf<String>()
        val lookedUpKeys = mutableListOf<String>()
        val requests = mutableListOf<TransactionRequest>()

        override fun submit(request: TransactionRequest, idempotencyKey: String): TransactionResponse {
            submittedKeys += idempotencyKey
            requests += request
            return submitScript.removeFirst().invoke()
        }

        override fun findByIdempotencyKey(idempotencyKey: String): TransactionResponse? {
            lookedUpKeys += idempotencyKey
            return lookupScript.removeFirst().invoke()
        }
    }

    private fun response(status: TransactionStatus = TransactionStatus.APPROVED, type: TransactionType = TransactionType.SALE) =
        TransactionResponse("TXN-9", type, status, 1234, "USD", authCode = "A9", declineReason = if (status == TransactionStatus.DECLINED) "Insufficient funds" else null)

    private val timeout: () -> Nothing = { throw GatewayException.Timeout() }
    private val serverError: () -> Nothing = { throw GatewayException.ServerError(503) }
    private val offline: () -> Nothing = { throw GatewayException.NetworkUnavailable() }
    private val rejected: () -> Nothing = { throw GatewayException.Rejected(422, "Bad request") }

    private val log = mutableListOf<String>()
    private fun service(gateway: PaymentGateway, attempts: Int = 3) =
        PaymentService(gateway, attempts, newIdempotencyKey = { "key-1" }, log = { log += it })

    @Test fun `approved on the first try`() {
        val gateway = ScriptedGateway(listOf({ response() }))
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Approved
        assertFalse(result.recovered)
        assertEquals("key-1", result.idempotencyKey)
        assertEquals(1, gateway.submittedKeys.size)
        assertTrue(gateway.lookedUpKeys.isEmpty())
        assertEquals(TransactionType.SALE, gateway.requests.single().type)
        assertEquals(1234L, gateway.requests.single().amountMinor)
    }

    @Test fun `declined is reported as declined, not as an error`() {
        val gateway = ScriptedGateway(listOf({ response(TransactionStatus.DECLINED) }))
        val result = service(gateway).sale(Money(1234))
        result as PaymentResult.Declined
        assertEquals("Insufficient funds", result.response.declineReason)
        assertFalse(result.recovered)
    }

    @Test fun `timeout after the charge is recovered by lookup, without charging again`() {
        val gateway = ScriptedGateway(submits = listOf(timeout), lookups = listOf({ response() }))
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Approved
        assertTrue(result.recovered)
        assertEquals("submit must be called exactly once", 1, gateway.submittedKeys.size)
        assertEquals(listOf("key-1"), gateway.lookedUpKeys)
        assertTrue(log.any { it.contains("Not resending") })
    }

    @Test fun `a recovered decline stays a decline`() {
        val gateway = ScriptedGateway(listOf(timeout), listOf({ response(TransactionStatus.DECLINED) }))
        val result = service(gateway).sale(Money(1234))
        result as PaymentResult.Declined
        assertTrue(result.recovered)
    }

    @Test fun `5xx before the charge is resent with the same key`() {
        val gateway = ScriptedGateway(submits = listOf(serverError, { response() }), lookups = listOf({ null }))
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Approved
        assertTrue(result.recovered)
        assertEquals(listOf("key-1", "key-1"), gateway.submittedKeys)
    }

    @Test fun `offline fails fast, is safe to retry, and never looks anything up`() {
        val gateway = ScriptedGateway(listOf(offline))
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Failed
        assertTrue(result.safeToRetry)
        assertTrue(gateway.lookedUpKeys.isEmpty())
    }

    @Test fun `a rejected request fails and isn't retried`() {
        val gateway = ScriptedGateway(listOf(rejected))
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Failed
        assertFalse(result.safeToRetry)
        assertEquals(1, gateway.submittedKeys.size)
    }

    @Test fun `gateway down ends as unknown after the set number of checks, with no extra charges`() {
        val gateway = ScriptedGateway(submits = listOf(timeout), lookups = listOf(timeout, serverError, timeout))
        val result = service(gateway, attempts = 3).sale(Money(1234))

        result as PaymentResult.Unknown
        assertEquals("key-1", result.idempotencyKey)
        assertTrue(result.reason.contains("don't charge again"))
        assertEquals(1, gateway.submittedKeys.size)
        assertEquals(3, gateway.lookedUpKeys.size)
    }

    @Test fun `a flaky connection during recovery is retried until the lookup succeeds`() {
        val gateway = ScriptedGateway(submits = listOf(timeout), lookups = listOf(offline, { response() }))
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Approved
        assertEquals(2, gateway.lookedUpKeys.size)
        assertEquals(1, gateway.submittedKeys.size)
    }

    @Test fun `a resend that times out is checked again rather than resent blindly`() {
        val gateway = ScriptedGateway(
            submits = listOf(timeout, timeout),
            lookups = listOf({ null }, { response() }),
        )
        val result = service(gateway).sale(Money(1234))

        result as PaymentResult.Approved
        assertEquals(listOf("key-1", "key-1"), gateway.submittedKeys)
        assertEquals(2, gateway.lookedUpKeys.size)
    }

    @Test fun `a rejected resend during recovery fails cleanly`() {
        val gateway = ScriptedGateway(submits = listOf(serverError, rejected), lookups = listOf({ null }))
        val result = service(gateway).sale(Money(1234))
        result as PaymentResult.Failed
        assertFalse(result.safeToRetry)
    }

    @Test fun `refund and void reference the original transaction`() {
        val gateway = ScriptedGateway(
            listOf({ response(type = TransactionType.REFUND) }, { response(type = TransactionType.VOID) }),
        )
        val service = service(gateway)
        service.refund("TXN-1", Money(500))
        service.void("TXN-1", Money(1234))

        assertEquals(TransactionType.REFUND, gateway.requests[0].type)
        assertEquals("TXN-1", gateway.requests[0].originalTransactionId)
        assertEquals(500L, gateway.requests[0].amountMinor)
        assertEquals(TransactionType.VOID, gateway.requests[1].type)
    }

    @Test fun `refund and void need an original transaction ID`() {
        val service = service(ScriptedGateway(emptyList()))
        assertThrows(IllegalArgumentException::class.java) { service.refund(" ", Money(100)) }
        assertThrows(IllegalArgumentException::class.java) { service.void("", Money(100)) }
    }

    @Test fun `each payment gets a new idempotency key by default`() {
        val gateway = ScriptedGateway(listOf({ response() }, { response() }))
        val service = PaymentService(gateway)
        service.sale(Money(100))
        service.sale(Money(100))
        assertEquals(2, gateway.submittedKeys.toSet().size)
    }

    @Test fun `at least one recovery attempt is required`() {
        assertThrows(IllegalArgumentException::class.java) { PaymentService(ScriptedGateway(emptyList()), 0) }
    }
}
