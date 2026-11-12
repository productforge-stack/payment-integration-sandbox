package com.productforge.paysandbox

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.productforge.payments.sim.Scenario

@Composable
fun SandboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(controller: PaymentController) {
    val state by controller.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Payment Integration Sandbox") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = controller::onAmountChange,
                    label = { Text("Amount (USD)") },
                    isError = state.amountError != null,
                    supportingText = if (state.amountError != null) {
                        { Text(state.amountError.orEmpty()) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("amount"),
                )
            }
            item { Text("Gateway behaviour", style = MaterialTheme.typography.titleMedium) }
            item {
                Column(Modifier.selectableGroup()) {
                    Scenario.entries.forEach { scenario ->
                        ScenarioRow(scenario, selected = scenario == state.scenario) {
                            controller.onScenarioChange(scenario)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = controller::sale, enabled = !state.busy, modifier = Modifier.testTag("sale")) {
                        Text("Sale")
                    }
                    OutlinedButton(
                        onClick = controller::refund,
                        enabled = !state.busy && state.lastSale != null,
                        modifier = Modifier.testTag("refund"),
                    ) { Text("Refund") }
                    OutlinedButton(
                        onClick = controller::void,
                        enabled = !state.busy && state.lastSale != null,
                        modifier = Modifier.testTag("void"),
                    ) { Text("Void") }
                    if (state.busy) {
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(Modifier.size(24.dp).testTag("busy"), strokeWidth = 3.dp)
                    }
                }
                Text(
                    text = state.lastSale?.let { "Refund and Void act on ${it.transactionId} (${it.amount.format()})" }
                        ?: "Run an approved sale to enable Refund and Void",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.log.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transaction log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = controller::clearLog) { Text("Clear") }
                    }
                }
            }
            items(state.log, key = { it.number }) { entry -> LogCard(entry) }
        }
    }
}

@Composable
private fun ScenarioRow(scenario: Scenario, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp)
            .testTag("scenario_${scenario.name}"),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(scenario.label, fontWeight = FontWeight.SemiBold)
            Text(scenario.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    val accent = when (entry.outcome) {
        Outcome.APPROVED -> Color(0xFF1F7A4D)
        Outcome.DECLINED -> Color(0xFF8A5A00)
        Outcome.FAILED -> Color(0xFF5B6470)
        Outcome.UNKNOWN -> Color(0xFFB42318)
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("log_${entry.number}"),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(entry.title, fontWeight = FontWeight.SemiBold)
            Text(entry.outcome.label, color = accent, fontWeight = FontWeight.Bold)
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(top = 6.dp))
            entry.steps.forEach { step ->
                Text("• $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
