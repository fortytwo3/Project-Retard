package com.paladex.ex.core.providers

import com.paladex.ex.core.Card
import com.paladex.ex.core.Config
import com.paladex.ex.core.Money
import com.paladex.ex.core.PricePoint
import com.paladex.ex.core.ProviderResult
import com.paladex.ex.core.ProviderStatus
import com.paladex.ex.core.SourceMethod
import com.paladex.ex.core.net.Http
import com.paladex.ex.core.net.TtlCache
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * PriceCharting, via the paid API when a token is configured and by reading the
 * public product page when it is not.
 *
 * PriceCharting stores trading cards in the same six price columns it uses for
 * video games, which is why the field names read oddly. The mapping below is
 * fixed and applies to every card product.
 */
class PriceChartingProvider(
    private val http: Http,
    private val cache: TtlCache,
    private val config: Config,
) : PriceProvider {

    override val id = "pricecharting"
    override val label = "PriceCharting"

    private val json = Json { ignoreUnknownKeys = true }

    private object Grades {
        const val LOOSE = "Ungraded"
        const val CIB = "Grade 7"
        const val NEW = "Grade 8"
        const val GRADED = "Grade 9"
        const val BOX_ONLY = "Grade 9.5"
        const val MANUAL_ONLY = "PSA 10"
    }

    /**
     * The query PriceCharting matches best. Its index is keyed on card name
     * plus set name; feeding it the collector number too tends to return
     * nothing at all.
     */
    internal fun searchTerm(card: Card): String =
        "${card.name} ${card.setName} ${card.number}".replace(Regex("""\s+"""), " ").trim()

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    /** API amounts arrive in whole cents. */
    internal fun centsToDollars(value: Long?): Double? =
        if (value != null && value > 0) value / 100.0 else null

    private suspend fun viaApi(card: Card): ProviderResult {
        val url = "$BASE/api/product?t=${encode(config.priceChartingToken)}&q=${encode(searchTerm(card))}"

        val cached = cache.get("pc:api:${card.id}", config.cacheTtlSeconds) {
            json.parseToJsonElement(http.get(url, immediate = true)).jsonObject
        }
        val product: JsonObject = cached.value

        fun cents(key: String): Long? =
            product[key]?.jsonPrimitive?.content?.toLongOrNull()

        val status = product["status"]?.jsonPrimitive?.content
        if (status != null && status != "success") {
            return result(id, label, ProviderStatus.EMPTY, SourceMethod.API) {
                copy(
                    message = "PriceCharting has no product matching \"${searchTerm(card)}\".",
                    cached = cached.cached,
                )
            }
        }

        val prices = listOfNotNull(
            centsToDollars(cents("loose-price"))?.let { PricePoint(Grades.LOOSE, it) },
            centsToDollars(cents("cib-price"))?.let { PricePoint(Grades.CIB, it) },
            centsToDollars(cents("new-price"))?.let { PricePoint(Grades.NEW, it) },
            centsToDollars(cents("graded-price"))?.let { PricePoint(Grades.GRADED, it) },
            centsToDollars(cents("box-only-price"))?.let { PricePoint(Grades.BOX_ONLY, it) },
            centsToDollars(cents("manual-only-price"))?.let { PricePoint(Grades.MANUAL_ONLY, it) },
        )

        val productId = product["id"]?.jsonPrimitive?.content

        return result(
            id,
            label,
            if (prices.isNotEmpty()) ProviderStatus.OK else ProviderStatus.EMPTY,
            SourceMethod.API,
        ) {
            copy(
                prices = prices,
                sourceUrl = productId?.let { "$BASE/game/$it" },
                cached = cached.cached,
                message = "PriceCharting returned a product but no prices for it."
                    .takeIf { prices.isEmpty() },
            )
        }
    }

    /** Find the product page URL by running PriceCharting's own search. */
    private suspend fun findProductUrl(card: Card): String? {
        val searchUrl = "$BASE/search-products?q=${encode(searchTerm(card))}&type=prices"
        val html = http.get(searchUrl)
        val document = Jsoup.parse(html)

        // A search with exactly one hit redirects straight to the product page,
        // in which case the price table is already in front of us.
        if (document.select("#full_price_guide, #price_data").isNotEmpty()) return searchUrl

        val href = document.select("table#games_table tbody tr td.title a").firstOrNull()
            ?.attr("href")
            ?: return null

        return if (href.startsWith("http")) href else "$BASE$href"
    }

    /**
     * Read the six price cells off a product page.
     *
     * The element ids are stable and have been for years; the surrounding
     * markup is not, so we anchor on the id and take whatever text the cell
     * holds.
     */
    internal fun parseProductPage(html: String): List<PricePoint> {
        val document = Jsoup.parse(html)

        val cells = listOf(
            Grades.LOOSE to "#used_price",
            Grades.CIB to "#complete_price",
            Grades.NEW to "#new_price",
            Grades.GRADED to "#graded_price",
            Grades.BOX_ONLY to "#box_only_price",
            Grades.MANUAL_ONLY to "#manual_only_price",
        )

        return cells.mapNotNull { (gradeLabel, selector) ->
            val text = document.select("$selector .price").firstOrNull()?.text()
                ?: document.select(selector).firstOrNull()?.text()

            Money.parse(text)
                ?.takeIf { it.amount > 0 }
                ?.let { PricePoint(gradeLabel, it.amount, it.currency) }
        }
    }

    private suspend fun viaScrape(card: Card): ProviderResult {
        val cached = cache.get("pc:scrape:${card.id}", config.cacheTtlSeconds) {
            val productUrl = findProductUrl(card)
            productUrl to (productUrl?.let { parseProductPage(http.get(it)) } ?: emptyList())
        }
        val (productUrl, prices) = cached.value

        if (productUrl == null) {
            return result(id, label, ProviderStatus.EMPTY, SourceMethod.SCRAPE) {
                copy(
                    message = "No PriceCharting product found for \"${searchTerm(card)}\".",
                    cached = cached.cached,
                )
            }
        }

        return result(
            id,
            label,
            if (prices.isNotEmpty()) ProviderStatus.OK else ProviderStatus.EMPTY,
            SourceMethod.SCRAPE,
        ) {
            copy(
                prices = prices,
                sourceUrl = productUrl,
                cached = cached.cached,
                message = ("Found the product page but could not read any prices from it — " +
                    "PriceCharting may have changed its markup.").takeIf { prices.isEmpty() },
            )
        }
    }

    override suspend fun fetch(card: Card): List<ProviderResult> {
        if (config.priceChartingToken.isNotBlank()) {
            try {
                return listOf(viaApi(card))
            } catch (e: Exception) {
                // A dead token should not cost us the source entirely.
                if (!config.scrapeEnabled) return listOf(errored(id, label, SourceMethod.API, e))
            }
        }

        if (!config.scrapeEnabled) {
            return listOf(
                disabled(
                    id,
                    label,
                    "Add a PriceCharting token, or turn scraping on to read the public product page.",
                ),
            )
        }

        return try {
            listOf(viaScrape(card))
        } catch (e: Exception) {
            listOf(errored(id, label, SourceMethod.SCRAPE, e))
        }
    }

    companion object {
        private const val BASE = "https://www.pricecharting.com"
    }
}
