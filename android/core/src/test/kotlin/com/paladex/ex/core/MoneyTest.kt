package com.paladex.ex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {

    @Test
    fun `reads an eBay US price cell`() {
        assertEquals(Money.Parsed(124.99, Currency.USD), Money.parse("US \$124.99"))
    }

    @Test
    fun `strips thousands separators`() {
        assertEquals(Money.Parsed(1250.0, Currency.USD), Money.parse("\$1,250.00"))
    }

    @Test
    fun `reads a pound price`() {
        assertEquals(Money.Parsed(89.5, Currency.GBP), Money.parse("£89.50"))
    }

    @Test
    fun `treats a trailing comma-two-digits as a decimal separator`() {
        assertEquals(Money.Parsed(43.5, Currency.EUR), Money.parse("43,50 €"))
    }

    @Test
    fun `handles European grouping and decimals together`() {
        assertEquals(Money.Parsed(1250.0, Currency.EUR), Money.parse("€1.250,00"))
    }

    @Test
    fun `recognises a bare currency code`() {
        assertEquals(Money.Parsed(43.5, Currency.EUR), Money.parse("EUR 43.50"))
    }

    @Test
    fun `returns null when there is no number`() {
        assertNull(Money.parse("Best offer accepted"))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse(null))
    }

    @Test
    fun `takes the low end of a price range`() {
        assertEquals(10.0, Money.parsePriceCell("\$10.00 to \$25.00")?.amount)
    }

    @Test
    fun `formats a USD amount`() {
        assertEquals("\$124.50", Money.format(124.5, Currency.USD))
    }

    @Test
    fun `renders a dash for a missing amount`() {
        assertEquals("—", Money.format(null))
    }

    @Test
    fun `finds a PSA grade`() {
        assertEquals("PSA 10", Money.detectGrade("Charizard ex 054/165 PSA 10 GEM MINT"))
    }

    @Test
    fun `normalises grade spacing and casing`() {
        assertEquals("PSA 10", Money.detectGrade("charizard psa10 mint"))
    }

    @Test
    fun `reads half grades`() {
        assertEquals("BGS 9.5", Money.detectGrade("Pikachu BGS 9.5"))
    }

    @Test
    fun `recognises an explicitly ungraded listing`() {
        assertEquals("Ungraded", Money.detectGrade("Charizard 4/102 raw near mint"))
    }

    @Test
    fun `returns null when no grade is mentioned`() {
        assertNull(Money.detectGrade("Charizard 4/102 Base Set"))
    }
}
