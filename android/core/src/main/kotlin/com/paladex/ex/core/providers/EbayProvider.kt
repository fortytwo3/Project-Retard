package com.paladex.ex.core.providers

import com.paladex.ex.core.Card
import com.paladex.ex.core.Config
import com.paladex.ex.core.Currency
import com.paladex.ex.core.Money
import com.paladex.ex.core.PricePoint
import com.paladex.ex.core.ProviderResult
import com.paladex.ex.core.ProviderStatus
import com.paladex.ex.core.SoldListing
import com.paladex.ex.core.SoldSummary
import com.paladex.ex.core.SourceMethod
import com.paladex.ex.core.Stats
import com.paladex.ex.core.net.Http
import com.paladex.ex.core.net.TtlCache
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * eBay completed sales — what the card actually sold for, which is the number
 * that matters most and the one every other source is only estimating.
 *
 * Three strategies, in descending order of fidelity:
 *  1. Marketplace Insights API — real completed sales, but eBay gates access
 *     behind a separate business application.
 *  2. Scraping the sold-listings page — what you would look at yourself, and
 *     the only path to completed sales without that agreement.
 *  3. Browse API — active listings only. Asking prices, clearly labelled.
 */
class EbayProvider(
    private val http: Http,
    private val cache: TtlCache,
    private val config: Config,
) : PriceProvider {

    override val id = "ebay"
    override val label = "eBay"

    private val json = Json { ignoreUnknownKeys = true }

    private var cachedToken: Pair<String, Long>? = null

    private suspend fun accessToken(scope: String): String {
        cachedToken?.let { (token, expiresAt) ->
            if (expiresAt > System.currentTimeMillis()) return token
        }

        val basic = Base64.getEncoder()
            .encodeToString("${config.ebayClientId}:${config.ebayClientSecret}".toByteArray())

        val body = http.postForm(
            url = OAUTH_URL,
            body = "grant_type=client_credentials&scope=${URLEncoder.encode(scope, "UTF-8")}",
            headers = mapOf(
                "Authorization" to "Basic $basic",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )

        val node = json.parseToJsonElement(body).jsonObject
        val token = node["access_token"]!!.jsonPrimitive.content
        val expiresIn = node["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 7200

        // Refresh a minute before it actually lapses.
        cachedToken = token to (System.currentTimeMillis() + (expiresIn - 60) * 1_000)
        return token
    }

    private suspend fun viaInsightsApi(query: String): List<SoldListing> {
        val token = accessToken("https://api.ebay.com/oauth/api_scope/buy.marketplace.insights")

        // Marketplace Insights caps the lookback at 90 days.
        val since = Instant.now().minusSeconds(90L * 86_400).toString()
        val filter = URLEncoder.encode("lastSoldDate:[$since..]", "UTF-8")
        val url = "$INSIGHTS_URL?q=${URLEncoder.encode(query, "UTF-8")}&limit=100&filter=$filter"

        val body = http.get(
            url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-EBAY-C-MARKETPLACE-ID" to config.ebayMarketplaceId,
            ),
            immediate = true,
        )

        val sales = json.parseToJsonElement(body).jsonObject["itemSales"]?.jsonArray
            ?: return emptyList()

        return sales.mapNotNull { element ->
            val sale = element.jsonObject
            val price = sale["lastSoldPrice"]?.jsonObject
            val amount = price?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val title = sale["title"]?.jsonPrimitive?.content ?: return@mapNotNull null

            SoldListing(
                title = title,
                price = amount,
                currency = currencyOf(price["currency"]?.jsonPrimitive?.content),
                soldAt = sale["lastSoldDate"]?.jsonPrimitive?.content,
                url = sale["itemWebUrl"]?.jsonPrimitive?.content,
                imageUrl = sale["image"]?.jsonObject?.get("imageUrl")?.jsonPrimitive?.content,
                detectedGrade = Money.detectGrade(title),
            )
        }
    }

    private suspend fun viaBrowseApi(query: String): List<SoldListing> {
        val token = accessToken("https://api.ebay.com/oauth/api_scope")
        val url = "$BROWSE_URL?q=${URLEncoder.encode(query, "UTF-8")}&limit=50"

        val body = http.get(
            url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-EBAY-C-MARKETPLACE-ID" to config.ebayMarketplaceId,
            ),
            immediate = true,
        )

        val items = json.parseToJsonElement(body).jsonObject["itemSummaries"]?.jsonArray
            ?: return emptyList()

        return items.mapNotNull { element ->
            val item = element.jsonObject
            val price = item["price"]?.jsonObject
            val amount = price?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.content ?: return@mapNotNull null

            SoldListing(
                title = title,
                price = amount,
                currency = currencyOf(price["currency"]?.jsonPrimitive?.content),
                url = item["itemWebUrl"]?.jsonPrimitive?.content,
                imageUrl = item["image"]?.jsonObject?.get("imageUrl")?.jsonPrimitive?.content,
                detectedGrade = Money.detectGrade(title),
            )
        }
    }

    private fun currencyOf(code: String?): Currency =
        runCatching { Currency.valueOf(code ?: "USD") }.getOrDefault(Currency.USD)

    private fun soldPricePoints(summary: SoldSummary?, sold: Boolean): List<PricePoint> {
        if (summary == null) return emptyList()
        return if (sold) {
            listOf(
                PricePoint(
                    "Sold average",
                    summary.trimmedMean,
                    summary.currency,
                    "${summary.count} sales, outliers removed",
                ),
                PricePoint("Sold median", summary.median, summary.currency),
            )
        } else {
            listOf(
                PricePoint(
                    "Asking average",
                    summary.trimmedMean,
                    summary.currency,
                    "${summary.count} active listings",
                ),
            )
        }
    }

    override suspend fun fetch(card: Card): List<ProviderResult> {
        val query = marketplaceQuery(card)
        val browseUrl = EbayScrape.soldSearchUrl(query)

        // 1. Real completed sales, if eBay has granted Insights access.
        if (config.hasEbayCredentials && config.ebayMarketplaceInsightsEnabled) {
            try {
                val cached = cache.get("ebay:insights:${card.id}", config.cacheTtlSeconds) {
                    viaInsightsApi(query)
                }
                val listings = cached.value.filter { isPlausibleMatch(it, card) }

                if (listings.isNotEmpty()) {
                    val summary = Stats.summarise(listings)
                    return listOf(
                        result(id, SOLD_LABEL, ProviderStatus.OK, SourceMethod.API) {
                            copy(
                                sourceUrl = browseUrl,
                                soldListings = listings,
                                soldSummary = summary,
                                cached = cached.cached,
                                prices = soldPricePoints(summary, sold = true),
                            )
                        },
                    )
                }
            } catch (e: Exception) {
                if (!config.scrapeEnabled) {
                    return listOf(errored(id, SOLD_LABEL, SourceMethod.API, e, browseUrl))
                }
            }
        }

        // 2. Scraping the sold-listings page — the default path.
        if (config.scrapeEnabled) {
            try {
                val cached = cache.get("ebay:scrape:${card.id}", config.cacheTtlSeconds) {
                    EbayScrape.parseSoldListings(http.get(browseUrl))
                }
                val listings = cached.value.filter { isPlausibleMatch(it, card) }

                if (listings.isNotEmpty()) {
                    val summary = Stats.summarise(listings)
                    return listOf(
                        result(id, SOLD_LABEL, ProviderStatus.OK, SourceMethod.SCRAPE) {
                            copy(
                                sourceUrl = browseUrl,
                                soldListings = listings,
                                soldSummary = summary,
                                cached = cached.cached,
                                prices = soldPricePoints(summary, sold = true),
                            )
                        },
                    )
                }

                // Report the empty result honestly rather than inventing a
                // number, unless credentials let us fall through to Browse.
                if (!config.hasEbayCredentials) {
                    return listOf(
                        result(id, SOLD_LABEL, ProviderStatus.EMPTY, SourceMethod.SCRAPE) {
                            copy(
                                sourceUrl = browseUrl,
                                cached = cached.cached,
                                message = if (cached.value.isNotEmpty()) {
                                    "Found sold listings but none matched this exact card."
                                } else {
                                    "No recent sold listings found. eBay may also have served a bot-check page."
                                },
                            )
                        },
                    )
                }
            } catch (e: Exception) {
                if (!config.hasEbayCredentials) {
                    return listOf(errored(id, SOLD_LABEL, SourceMethod.SCRAPE, e, browseUrl))
                }
            }
        }

        // 3. Active listings. Asking prices, not sales — labelled as such.
        if (config.hasEbayCredentials) {
            return try {
                val cached = cache.get("ebay:browse:${card.id}", config.cacheTtlSeconds) {
                    viaBrowseApi(query)
                }
                val listings = cached.value.filter { isPlausibleMatch(it, card) }
                val summary = Stats.summarise(listings)

                listOf(
                    result(
                        id,
                        ACTIVE_LABEL,
                        if (listings.isNotEmpty()) ProviderStatus.OK else ProviderStatus.EMPTY,
                        SourceMethod.API,
                    ) {
                        copy(
                            sourceUrl = browseUrl,
                            soldListings = listings,
                            soldSummary = summary,
                            cached = cached.cached,
                            message = "Showing active asking prices — completed sales need Marketplace Insights access.",
                            prices = soldPricePoints(summary, sold = false),
                        )
                    },
                )
            } catch (e: Exception) {
                listOf(errored(id, label, SourceMethod.API, e, browseUrl))
            }
        }

        return listOf(
            disabled(id, SOLD_LABEL, "Turn scraping on, or add eBay API credentials."),
        )
    }

    companion object {
        const val SOLD_LABEL = "eBay — Recently Sold"
        const val ACTIVE_LABEL = "eBay — Active Listings"

        private const val OAUTH_URL = "https://api.ebay.com/identity/v1/oauth2/token"
        private const val INSIGHTS_URL =
            "https://api.ebay.com/buy/marketplace_insights/v1_beta/item_sales/search"
        private const val BROWSE_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search"

        private val JUNK = Regex(
            """\b(lot|bundle|choose|pick|you pick|proxy|custom|repack|mystery|playset|complete set)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val NAME_SUFFIX = Regex("""\s+(ex|gx|v|vmax|vstar)$""", RegexOption.IGNORE_CASE)

        /**
         * Drop listings that matched the search but are not the card.
         *
         * Sellers pack titles with keywords, so a search for one card returns
         * lots, proxies and "choose your card" listings. Requiring the card's
         * own name and rejecting the obvious bulk vocabulary removes most of
         * them; the outlier trim in [Stats.summarise] handles the rest.
         */
        internal fun isPlausibleMatch(listing: SoldListing, card: Card): Boolean {
            val title = listing.title.lowercase()
            val name = card.name.lowercase().replace(NAME_SUFFIX, "").trim()

            if (name.isNotEmpty() && !title.contains(name)) return false
            return !JUNK.containsMatchIn(listing.title)
        }
    }
}
