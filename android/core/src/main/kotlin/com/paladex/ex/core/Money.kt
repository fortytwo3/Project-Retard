package com.paladex.ex.core

import java.text.NumberFormat
import java.util.Locale

/** Parsing and formatting of the price strings scraped off marketplace pages. */
object Money {

    private val SYMBOLS = mapOf('$' to Currency.USD, '€' to Currency.EUR, '£' to Currency.GBP)

    private val CURRENCY_CODE = Regex("""\b(USD|EUR|GBP)\b""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d[\d.,\s]*\d|\d""")
    private val GRADE = Regex(
        """\b(PSA|BGS|CGC|SGC|ACE|TAG)\s*[-:]?\s*(10|[1-9](?:\.5)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RAW = Regex("""\b(raw|ungraded)\b""", RegexOption.IGNORE_CASE)

    data class Parsed(val amount: Double, val currency: Currency)

    /**
     * Rewrite a number's separators into plain `1234.56` form.
     *
     * Both conventions turn up — US sources write "1,250.00", European ones
     * write "1.250,00" — and the same character means opposite things in each:
     *
     *  - When both separators appear, the rightmost is the decimal point and
     *    the other is grouping.
     *  - When only one appears, it is a decimal point if followed by one or two
     *    digits, and grouping otherwise. That is what separates "43,50" (forty
     *    three and a half) from "1,250" (one thousand two hundred and fifty).
     */
    internal fun normaliseSeparators(raw: String): String {
        val lastDot = raw.lastIndexOf('.')
        val lastComma = raw.lastIndexOf(',')
        if (lastDot == -1 && lastComma == -1) return raw

        val decimalIndex = maxOf(lastDot, lastComma)
        val trailingDigits = raw.length - decimalIndex - 1
        val bothPresent = lastDot != -1 && lastComma != -1
        val isDecimal = bothPresent || trailingDigits in 1..2

        if (!isDecimal) return raw.replace(Regex("""[.,]"""), "")

        val whole = raw.substring(0, decimalIndex).replace(Regex("""[.,]"""), "")
        return "$whole.${raw.substring(decimalIndex + 1)}"
    }

    /**
     * Pull a price out of free-form text such as "US $124.99", "£89.00" or
     * "EUR 43,50". Returns null when the text holds no recognisable amount.
     */
    fun parse(input: String?): Parsed? {
        if (input.isNullOrBlank()) return null
        val text = input.replace(Regex("""\s+"""), " ").trim()

        val currency = CURRENCY_CODE.find(text)?.let {
            Currency.valueOf(it.groupValues[1].uppercase())
        } ?: text.firstNotNullOfOrNull { SYMBOLS[it] } ?: Currency.USD

        val number = NUMBER.find(text)?.value ?: return null
        val amount = normaliseSeparators(number.replace(" ", "")).toDoubleOrNull() ?: return null

        return Parsed(amount, currency)
    }

    /**
     * Price cells sometimes hold a range ("$10.00 to $25.00"). Take the low
     * end, which is the conservative read for a sold price.
     */
    fun parsePriceCell(text: String?): Parsed? {
        if (text.isNullOrBlank()) return null
        val low = text.split(Regex("""\s+to\s+""", RegexOption.IGNORE_CASE)).firstOrNull() ?: text
        return parse(low)?.takeIf { it.amount > 0 }
    }

    fun format(amount: Double?, currency: Currency = Currency.USD): String {
        if (amount == null || !amount.isFinite()) return "—"
        val format = NumberFormat.getCurrencyInstance(Locale.US).apply {
            this.currency = java.util.Currency.getInstance(currency.name)
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return format.format(amount)
    }

    /**
     * Grades appear in eBay titles in a dozen shapes: "PSA 10", "psa10",
     * "CGC 9.5", "BGS 9 Mint". Normalising the recognisable ones lets sold
     * listings be split by grade — a PSA 10 and a raw copy are different
     * markets, and averaging them together produces a meaningless number.
     */
    fun detectGrade(title: String): String? {
        GRADE.find(title)?.let {
            return "${it.groupValues[1].uppercase()} ${it.groupValues[2]}"
        }
        return if (RAW.containsMatchIn(title)) "Ungraded" else null
    }
}
