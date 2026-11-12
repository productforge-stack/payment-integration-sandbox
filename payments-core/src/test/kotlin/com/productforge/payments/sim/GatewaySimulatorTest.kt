package com.productforge.payments.sim

import com.productforge.payments.Money
import com.productforge.payments.PaymentResult
import com.productforge.payments.PaymentService
import com.productforge.payments.TransactionStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End to end: PaymentService -> RestPaymentGateway -> OkHttp -> simulated gateway. */
class GatewaySimulatorTest {

    private var scenario = Scenario.APPROVE
    private val simulator = GatewaySimulator { scenario }
    private val service = PaymentService(Sandbox.gateway(simulator))

    @Test fun `approve charges once`() {
        val result = service.sale(Money.parse("12.34"))
        result as PaymentResult.Approved
        assertEquals(1234L, result.response.amountMinor)
        assertEquals(1, simulator.processedCount)
    }

    @Test fun `decline is a normal answer`() {
        scenario = Scenario.DECLINE
        val result = service.sale(Money.parse("12.34"))
        result as PaymentResult.Declined
        assertEquals("Insufficient funds", result.response.declineReason)
    }

    @Test fun `timeout after charge is recovered with exactly one charge`() {
        scenario = Scenario.TIMEOUT_AFTER_CHARGE
        val result = service.sale(Money.parse("20.00"))
        result as PaymentResult.Approved
        assertTrue(result.recovered)
        assertEquals("the card must be charged once", 1, simulator.processedCount)
    }

    @Test fun `gateway error before charge is resent and charged once`() {
        scenario = Scenario.ERROR_BEFORE_CHARGE
        val result = service.sale(Money.parse("20.00"))
        result as PaymentResult.Approved
        assertTrue(result.recovered)
        assertEquals(1, simulator.processedCount)
    }

    @Test fun `offline fails and charges nothing`() {
        scenario = Scenario.OFFLINE
        val result = service.sale(Money.parse("20.00"))
        result as PaymentResult.Failed
        assertTrue(result.safeToRetry)
        assertEquals(0, simulator.processedCount)
    }

    @Test fun `gateway down ends as unknown`() {
        scenario = Scenario.GATEWAY_DOWN
        val result = service.sale(Money.parse("20.00"))
        assertTrue(result is PaymentResult.Unknown)
    }

    @Test fun `refund and void work against an approved sale`() {
        val sale = service.sale(Money.parse("30.00")) as PaymentResult.Approved
        val id = sale.response.transactionId

        val refund = service.refund(id, Money.parse("10.00"))
        assertTrue(refund is PaymentResult.Approved)
        val void = service.void(id, Money.parse("30.00"))
        assertTrue(void is PaymentResult.Approved)
    }

    @Test fun `refunding more than the sale is rejected`() {
        val sale = service.sale(Money.parse("30.00")) as PaymentResult.Approved
        val result = service.refund(sale.response.transactionId, Money.parse("30.01"))
        result as PaymentResult.Failed
        assertTrue(result.reason.contains("more than the original"))
    }

    @Test fun `refunding an unknown or declined transaction is rejected`() {
        assertTrue(service.refund("TXN-0", Money.parse("1.00")) is PaymentResult.Failed)
        scenario = Scenario.DECLINE
        val declined = service.sale(Money.parse("5.00")) as PaymentResult.Declined
        scenario = Scenario.APPROVE
        assertTrue(service.refund(declined.response.transactionId, Money.parse("1.00")) is PaymentResult.Failed)
    }

    // The simulator also enforces the API contract, so a broken client fails loudly.

    private val raw = OkHttpClient.Builder().addInterceptor(simulator).build()
    private val url = Sandbox.BASE_URL + "/v1/transactions"
    private val body = """{"type":"SALE","amountMinor":100,"currency":"USD"}""".toRequestBody("application/json".toMediaType())

    @Test fun `submit without an idempotency key is a 400`() {
        val response = raw.newCall(Request.Builder().url(url).header("Authorization", "Bearer x").post(body).build()).execute()
        assertEquals(400, response.code)
    }

    @Test fun `submit without an API key is a 401`() {
        val response = raw.newCall(Request.Builder().url(url).header("Idempotency-Key", "k").post(body).build()).execute()
        assertEquals(401, response.code)
    }

    @Test fun `the same key twice returns the first result`() {
        val request = Request.Builder().url(url).header("Idempotency-Key", "same").header("Authorization", "Bearer x").post(body).build()
        val first = raw.newCall(request).execute().body!!.string()
        val second = raw.newCall(request).execute().body!!.string()
        assertEquals(first, second)
        assertEquals(1, simulator.processedCount)
    }

    @Test fun `lookup of an unknown key is a 404 and other methods are refused`() {
        assertEquals(404, raw.newCall(Request.Builder().url("$url?idempotencyKey=nope").build()).execute().code)
        assertEquals(404, raw.newCall(Request.Builder().url(url).build()).execute().code)
        assertEquals(405, raw.newCall(Request.Builder().url(url).delete().build()).execute().code)
    }

    @Test fun `every scenario has a label and description`() {
        Scenario.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
        assertEquals(TransactionStatus.APPROVED, TransactionStatus.valueOf("APPROVED"))
    }
}
