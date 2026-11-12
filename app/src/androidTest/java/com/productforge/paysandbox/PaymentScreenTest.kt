package com.productforge.paysandbox

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an Android emulator in CI: real app, real Compose UI, simulated gateway. */
@RunWith(AndroidJUnit4::class)
class PaymentScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The log is a lazy list, so an entry below the fold isn't composed until it's scrolled to,
     * and the payment finishes on a background thread. Keep trying to scroll to it until it exists.
     */
    private fun scrollUntilShown(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithTag("screen").performScrollToNode(matcher) }.isSuccess
        }
    }

    private fun scrollUntilShown(text: String) = scrollUntilShown(hasText(text, substring = true))

    @Test
    fun approvedSaleEnablesRefundAndVoid() {
        compose.onNodeWithTag("refund").assertIsNotEnabled()

        compose.onNodeWithTag("amount").performTextReplacement("12.34")
        compose.onNodeWithTag("sale").performClick()

        scrollUntilShown("Sale $12.34")
        compose.onNodeWithText("Approved").assertExists()

        scrollUntilShown(hasTestTag("refund"))
        compose.onNodeWithTag("refund").assertIsEnabled()
        compose.onNodeWithTag("void").assertIsEnabled()
    }

    @Test
    fun timeoutAfterChargeIsRecoveredWithoutASecondCharge() {
        compose.onNodeWithTag("scenario_TIMEOUT_AFTER_CHARGE").performClick()
        scrollUntilShown(hasTestTag("sale"))
        compose.onNodeWithTag("sale").performClick()

        scrollUntilShown("charged once")
        scrollUntilShown("Not resending")
        compose.onNodeWithText("Not resending", substring = true).assertExists()
    }

    @Test
    fun invalidAmountShowsAnErrorAndSendsNothing() {
        compose.onNodeWithTag("amount").performTextReplacement("1.005")
        compose.onNodeWithTag("sale").performClick()

        compose.onNodeWithText("Amounts can't have fractions of a cent").assertExists()
        compose.onNodeWithText("Transaction log").assertDoesNotExist()
    }

    @Test
    fun gatewayDownAsksForReconciliationInsteadOfRetrying() {
        compose.onNodeWithTag("scenario_GATEWAY_DOWN").performClick()
        scrollUntilShown(hasTestTag("sale"))
        compose.onNodeWithTag("sale").performClick()

        scrollUntilShown("Needs reconciliation")
        // The summary, not the step log, is what the clerk acts on.
        scrollUntilShown("before charging again")
        compose.onNodeWithText("before charging again", substring = true).assertExists()
    }

    @Test
    fun voidAfterSale() {
        compose.onNodeWithTag("sale").performClick()
        scrollUntilShown("Refund and Void act on")
        compose.onNodeWithTag("void").performClick()

        scrollUntilShown("Void TXN-")
        scrollUntilShown(hasTestTag("void"))
        compose.onNodeWithTag("void").assertIsNotEnabled()
    }
}
