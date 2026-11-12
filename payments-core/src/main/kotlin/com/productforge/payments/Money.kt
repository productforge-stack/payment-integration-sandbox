package com.productforge.payments

import java.math.BigDecimal

/**
 * An amount of money held in minor units (cents), never as a floating-point number.
 * 0.1 + 0.2 != 0.3 in floating point, which is how rounding bugs reach a cardholder's statement.
 */
data class Money(val minorUnits: Long, val currency: String = "USD") {

    init {
        require(minorUnits >= 0) { "Amount can't be negative" }
        require(currency.length == 3 && currency.all { it.isUpperCase() }) {
            "Currency must be a 3-letter ISO 4217 code, such as USD"
        }
    }

    /** Formats for display, for example "$12.34" or "12.34 EUR". */
    fun format(): String {
        val major = BigDecimal.valueOf(minorUnits, 2).toPlainString()
        return if (currency == "USD") "$$major" else "$major $currency"
    }

    companion object {
        /**
         * Parses an amount typed by a person, such as "12.34" or "$5".
         * Rejects fractions of a cent rather than rounding them silently.
         */
        @JvmStatic
        @JvmOverloads
        fun parse(text: String, currency: String = "USD"): Money {
            val cleaned = text.trim().removePrefix("$").replace(",", "")
            require(cleaned.isNotEmpty()) { "Enter an amount" }
            val decimal = cleaned.toBigDecimalOrNull() ?: throw IllegalArgumentException("\"$text\" isn't an amount")
            require(decimal.signum() > 0) { "Amount must be more than zero" }
            require(decimal.stripTrailingZeros().scale() <= 2) { "Amounts can't have fractions of a cent" }
            return Money(decimal.movePointRight(2).longValueExact(), currency)
        }
    }
}
