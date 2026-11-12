package com.productforge.paysandbox

import com.productforge.payments.Money
import com.productforge.payments.PaymentResult
import com.productforge.payments.PaymentService
import com.productforge.payments.TransactionType
import com.productforge.payments.sim.GatewaySimulator
import com.productforge.payments.sim.Sandbox
import com.productforge.payments.sim.Scenario
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Outcome(val label: String) {
    APPROVED("Approved"),
    DECLINED("Declined"),
    FAILED("Not charged"),
    UNKNOWN("Needs reconciliation"),
}

/** One finished operation, with every step the integration took to get there. */
data class LogEntry(
    val number: Int,
    val title: String,
    val outcome: Outcome,
    val summary: String,
    val steps: List<String>,
)

data class ApprovedSale(val transactionId: String, val amount: Money)

data class UiState(
    val amountText: String = "25.00",
    val scenario: Scenario = Scenario.APPROVE,
    val amountError: String? = null,
    val busy: Boolean = false,
    val lastSale: ApprovedSale? = null,
    val log: List<LogEntry> = emptyList(),
)

/**
 * Holds the screen state and runs payments off the main thread.
 * Plain Kotlin with an injected scope and dispatcher, so it's unit-tested without a device.
 */
class PaymentController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val simulator = GatewaySimulator { _state.value.scenario }
    private val gateway = Sandbox.gateway(simulator)

    fun onAmountChange(text: String) = _state.update { it.copy(amountText = text, amountError = null) }

    fun onScenarioChange(scenario: Scenario) = _state.update { it.copy(scenario = scenario) }

    fun sale() {
        val amount = parseAmount() ?: return
        run("Sale ${amount.format()}") { service -> service.sale(amount) }
    }

    /** Refunds the amount entered against the last approved sale. */
    fun refund() {
        val sale = _state.value.lastSale ?: return
        val amount = parseAmount() ?: return
        run("Refund ${amount.format()} of ${sale.transactionId}") { service ->
            service.refund(sale.transactionId, amount)
        }
    }

    /** Voids the whole last approved sale. */
    fun void() {
        val sale = _state.value.lastSale ?: return
        run("Void ${sale.transactionId}") { service -> service.void(sale.transactionId, sale.amount) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    private fun parseAmount(): Money? = try {
        Money.parse(_state.value.amountText)
    } catch (e: IllegalArgumentException) {
        _state.update { it.copy(amountError = e.message) }
        null
    }

    private fun run(title: String, operation: (PaymentService) -> PaymentResult) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        scope.launch {
            val steps = mutableListOf<String>()
            val result = withContext(io) {
                operation(PaymentService(gateway, log = { line -> synchronized(steps) { steps += line } }))
            }
            _state.update { current ->
                current.copy(
                    busy = false,
                    lastSale = nextLastSale(current.lastSale, result),
                    log = listOf(entryFor(current.log.size + 1, title, result, steps.toList())) + current.log,
                )
            }
        }
    }

    private fun nextLastSale(previous: ApprovedSale?, result: PaymentResult): ApprovedSale? {
        if (result !is PaymentResult.Approved) return previous
        val response = result.response
        return when (response.type) {
            TransactionType.SALE ->
                ApprovedSale(response.transactionId, Money(response.amountMinor, response.currency))
            else -> null // Refunded or voided: nothing left to act on.
        }
    }

    private fun entryFor(number: Int, title: String, result: PaymentResult, steps: List<String>): LogEntry =
        when (result) {
            is PaymentResult.Approved -> LogEntry(
                number, title, Outcome.APPROVED,
                if (result.recovered) {
                    "Recovered after a failure. ${result.response.transactionId}, charged once."
                } else {
                    "${result.response.transactionId}, auth ${result.response.authCode}"
                },
                steps,
            )
            is PaymentResult.Declined -> LogEntry(
                number, title, Outcome.DECLINED,
                result.response.declineReason ?: "Declined by the issuer", steps,
            )
            is PaymentResult.Failed -> LogEntry(
                number, title, Outcome.FAILED,
                if (result.safeToRetry) "Nothing was sent. Safe to try again." else result.reason,
                steps,
            )
            is PaymentResult.Unknown -> LogEntry(
                number, title, Outcome.UNKNOWN,
                "Couldn't confirm. Reconcile key ${result.idempotencyKey.take(8)} before charging again.",
                steps,
            )
        }
}
