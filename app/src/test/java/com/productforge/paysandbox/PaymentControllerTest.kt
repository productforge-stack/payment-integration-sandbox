package com.productforge.paysandbox

import com.productforge.payments.sim.Scenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentControllerTest {

    // Unconfined: each launched payment finishes before the call returns, so tests stay simple.
    private val dispatcher = UnconfinedTestDispatcher()
    private val controller = PaymentController(TestScope(dispatcher), dispatcher)
    private val state get() = controller.state.value

    @Test fun `an approved sale is logged and enables refund and void`() {
        controller.onAmountChange("12.34")
        controller.sale()

        val entry = state.log.single()
        assertEquals(Outcome.APPROVED, entry.outcome)
        assertEquals("Sale $12.34", entry.title)
        assertTrue(entry.steps.isNotEmpty())
        assertNotNull(state.lastSale)
        assertEquals(1234L, state.lastSale!!.amount.minorUnits)
        assertFalse(state.busy)
    }

    @Test fun `a bad amount shows an error and sends nothing`() {
        controller.onAmountChange("12.345")
        controller.sale()

        assertEquals("Amounts can't have fractions of a cent", state.amountError)
        assertTrue(state.log.isEmpty())

        controller.onAmountChange("12.34")
        assertNull("Typing clears the error", state.amountError)
    }

    @Test fun `a timeout after the charge is shown as recovered, charged once`() {
        controller.onScenarioChange(Scenario.TIMEOUT_AFTER_CHARGE)
        controller.sale()

        val entry = state.log.single()
        assertEquals(Outcome.APPROVED, entry.outcome)
        assertTrue(entry.summary.contains("charged once"))
        assertTrue(entry.steps.any { it.contains("Not resending") })
    }

    @Test fun `each outcome maps to the right label`() {
        val expected = mapOf(
            Scenario.DECLINE to Outcome.DECLINED,
            Scenario.OFFLINE to Outcome.FAILED,
            Scenario.GATEWAY_DOWN to Outcome.UNKNOWN,
            Scenario.ERROR_BEFORE_CHARGE to Outcome.APPROVED,
        )
        expected.forEach { (scenario, outcome) ->
            controller.onScenarioChange(scenario)
            controller.sale()
            assertEquals("Scenario $scenario", outcome, state.log.first().outcome)
        }
        assertTrue(state.log.first { it.outcome == Outcome.FAILED }.summary.contains("Safe to try again"))
        assertTrue(state.log.first { it.outcome == Outcome.UNKNOWN }.summary.contains("Reconcile"))
    }

    @Test fun `refund and void use the last approved sale, then clear it`() {
        controller.onAmountChange("30.00")
        controller.sale()
        val saleId = state.lastSale!!.transactionId

        controller.onAmountChange("10.00")
        controller.refund()
        assertEquals("Refund $10.00 of $saleId", state.log.first().title)
        assertEquals(Outcome.APPROVED, state.log.first().outcome)
        assertNull(state.lastSale)

        controller.onAmountChange("5.00")
        controller.sale()
        controller.void()
        assertTrue(state.log.first().title.startsWith("Void "))
        assertEquals(Outcome.APPROVED, state.log.first().outcome)
        assertNull(state.lastSale)
    }

    @Test fun `a refund larger than the sale is refused by the gateway`() {
        controller.onAmountChange("10.00")
        controller.sale()
        controller.onAmountChange("10.01")
        controller.refund()

        assertEquals(Outcome.FAILED, state.log.first().outcome)
        assertNotNull("The sale is still refundable", state.lastSale)
    }

    @Test fun `refund and void do nothing without an approved sale`() {
        controller.refund()
        controller.void()
        assertTrue(state.log.isEmpty())
    }

    @Test fun `a declined sale doesn't become refundable`() {
        controller.onScenarioChange(Scenario.DECLINE)
        controller.sale()
        assertNull(state.lastSale)
    }

    @Test fun `newest entries come first and clear empties the log`() {
        controller.sale()
        controller.onScenarioChange(Scenario.DECLINE)
        controller.sale()
        assertEquals(listOf(2, 1), state.log.map { it.number })

        controller.clearLog()
        assertTrue(state.log.isEmpty())
    }
}
