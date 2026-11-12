package com.productforge.payments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {

    @Test fun `parses dollars and cents into minor units`() {
        assertEquals(1234L, Money.parse("12.34").minorUnits)
        assertEquals(500L, Money.parse("$5").minorUnits)
        assertEquals(100000L, Money.parse("1,000.00").minorUnits)
        assertEquals(10L, Money.parse(" 0.10 ").minorUnits)
    }

    @Test fun `avoids floating point error`() {
        // 0.1 + 0.2 as doubles is 0.30000000000000004
        val sum = Money.parse("0.10").minorUnits + Money.parse("0.20").minorUnits
        assertEquals(30L, sum)
    }

    @Test fun `rejects fractions of a cent instead of rounding`() {
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1.005") }
    }

    @Test fun `accepts trailing zeros beyond two places`() {
        assertEquals(150L, Money.parse("1.5000").minorUnits)
    }

    @Test fun `rejects zero, negative, blank and text`() {
        listOf("0", "-1.00", "", "   ", "abc", "12.3.4").forEach { input ->
            assertThrows("Expected \"$input\" to be rejected", IllegalArgumentException::class.java) { Money.parse(input) }
        }
    }

    @Test fun `formats for display`() {
        assertEquals("$12.34", Money(1234).format())
        assertEquals("$0.05", Money(5).format())
        assertEquals("9.99 EUR", Money(999, "EUR").format())
    }

    @Test fun `validates currency codes`() {
        assertThrows(IllegalArgumentException::class.java) { Money(100, "usd") }
        assertThrows(IllegalArgumentException::class.java) { Money(100, "DOLLARS") }
        assertThrows(IllegalArgumentException::class.java) { Money(-1) }
    }
}
