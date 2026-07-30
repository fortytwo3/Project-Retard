package com.paladex.ex.core.providers

import com.paladex.ex.core.Money
import com.paladex.ex.core.SoldListing
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parser for eBay's sold-listings search results.
 *
 * eBay has run at least three different result-card markups in recent years and
 * A/B tests between them, so every field is read through a list of candidate
 * selectors rather than one. When all of them miss we drop the row rather than
 * emit a listing with a zero price.
 */
object EbayScrape {

    private val ITEM_SELECTOR = listOf(
        "li.s-item",
        "li.s-card",
        ".srp-results .s-item",
        ".su-card-container",
    ).joinToString(", ")

    private val TITLE_SELECTORS = listOf(
        ".s-item__title",
        ".s-card__title",
        ".su-card-container__header .su-styled-text",
        "[role=heading]",
    )

    private val PRICE_SELECTORS = listOf(".s-item__price", ".s-card__price", ".su-styled-text.primary")

    private val DATE_SELECTORS = listOf(
        ".s-item__title--tagblock .POSITIVE",
        ".s-item__caption--signal",
        ".s-card__caption",
        ".su-card-container__caption",
    )

    private val SHIPPING_SELECTORS =
        listOf(".s-item__shipping", ".s-item__logisticsCost", ".s-card__logisticsCost")

    /** eBay pads results with promo cards whose titles are literally these. */
    private val PLACEHOLDER_TITLES = setOf("shop on ebay", "new listing")

    /** The date formats eBay emits, which vary by locale. */
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US),
    )

    private fun firstText(element: Element, selectors: List<String>): String {
        for (selector in selectors) {
            val text = element.select(selector).firstOrNull()?.text()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return ""
    }

    /**
     * eBay writes sold dates as "Sold  3 Mar 2025" or "Sold Mar 3, 2025".
     * Returns an ISO instant, or null when the text is not a date at all.
     */
    fun parseSoldDate(text: String): String? {
        if (text.isBlank()) return null

        val datePart = Regex("""sold\s+(.*)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()
            ?: text.trim()

        if (datePart.isEmpty()) return null

        for (format in DATE_FORMATS) {
            val date = runCatching { LocalDate.parse(datePart, format) }.getOrNull() ?: continue
            val instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()

            // A date more than a day in the future means we parsed something
            // that was not a date.
            if (instant.isAfter(Instant.now().plusSeconds(86_400))) return null
            return instant.toString()
        }

        return null
    }

    fun parseSoldListings(html: String, limit: Int = 60): List<SoldListing> {
        val document = Jsoup.parse(html)
        val listings = mutableListOf<SoldListing>()

        for (element in document.select(ITEM_SELECTOR)) {
            if (listings.size >= limit) break

            val title = firstText(element, TITLE_SELECTORS)
                .replace(Regex("""^new listing\s*""", RegexOption.IGNORE_CASE), "")
                .trim()

            if (title.isEmpty() || title.lowercase() in PLACEHOLDER_TITLES) continue

            val price = Money.parsePriceCell(firstText(element, PRICE_SELECTORS)) ?: continue

            val shipping = firstText(element, SHIPPING_SELECTORS)
            val href = element.select("a[href*=/itm/]").firstOrNull()?.attr("href")
            val image = element.select("img").firstOrNull()
                ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
                ?.ifEmpty { null }

            listings += SoldListing(
                title = title,
                price = price.amount,
                currency = price.currency,
                soldAt = parseSoldDate(firstText(element, DATE_SELECTORS)),
                // Strip eBay's tracking query string so links stay readable.
                url = href?.substringBefore("?"),
                imageUrl = image,
                shippingIncluded = shipping.contains("free", ignoreCase = true),
                detectedGrade = Money.detectGrade(title),
            )
        }

        return listings
    }

    /** Build the sold-and-completed search URL for a query. */
    fun soldSearchUrl(query: String, categoryId: String? = null): String {
        val params = buildList {
            add("_nkw=${URLEncoder.encode(query, "UTF-8")}")
            add("LH_Sold=1")
            add("LH_Complete=1")
            add("_ipg=60")
            // Most recently ended first, so the sample reflects today's market.
            add("_sop=13")
            categoryId?.let { add("_sacat=$it") }
        }
        return "https://www.ebay.com/sch/i.html?${params.joinToString("&")}"
    }
}
