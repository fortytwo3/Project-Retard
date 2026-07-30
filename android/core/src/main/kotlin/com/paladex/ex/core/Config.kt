package com.paladex.ex.core

/**
 * Runtime configuration.
 *
 * On Android there are no environment variables, so this is a value the app
 * builds from its settings store and hands to [PriceService]. Every field is
 * optional: the default instance runs entirely on the free pokemontcg.io API
 * plus HTML scraping.
 */
data class Config(
    /** Raises pokemontcg.io from ~1k to 20k requests/day. */
    val pokemonTcgApiKey: String = "",

    /** Paid PriceCharting API token. Falls back to scraping when blank. */
    val priceChartingToken: String = "",

    val ebayClientId: String = "",
    val ebayClientSecret: String = "",
    /**
     * eBay gates completed-sales data behind a separate business application.
     * Only set this once they have granted the app buy.marketplace.insights.
     */
    val ebayMarketplaceInsightsEnabled: Boolean = false,
    val ebayMarketplaceId: String = "EBAY_US",

    /** Master switch for the HTML-scraping fallbacks. */
    val scrapeEnabled: Boolean = true,
    /** Minimum milliseconds between requests to the same host. Keep polite. */
    val scrapeMinIntervalMillis: Long = 1_500,

    /** Provider cache lifetime. Zero disables caching. */
    val cacheTtlSeconds: Long = 3_600,
) {
    val hasEbayCredentials: Boolean
        get() = ebayClientId.isNotBlank() && ebayClientSecret.isNotBlank()
}

/** One row on the "sources" screen: what a provider will do, and why. */
data class Capability(
    val id: String,
    val label: String,
    val method: SourceMethod,
    val active: Boolean,
    val detail: String,
)

/**
 * Which strategy each provider will use under [config]. Reports only the
 * resulting mode — never the key values themselves.
 */
fun describeCapabilities(config: Config): List<Capability> = listOf(
    Capability(
        id = "pokemontcg",
        label = "TCGplayer + Cardmarket (via pokemontcg.io)",
        method = SourceMethod.API,
        active = true,
        detail = if (config.pokemonTcgApiKey.isNotBlank()) {
            "Using your API key (20k requests/day)."
        } else {
            "Using the free anonymous tier (~1k requests/day). Add a key to raise it."
        },
    ),
    Capability(
        id = "pricecharting",
        label = "PriceCharting",
        method = if (config.priceChartingToken.isNotBlank()) SourceMethod.API else SourceMethod.SCRAPE,
        active = config.priceChartingToken.isNotBlank() || config.scrapeEnabled,
        detail = when {
            config.priceChartingToken.isNotBlank() -> "Using the paid API token."
            config.scrapeEnabled -> "No token set — reading the public product page instead."
            else -> "No token, and scraping is off. This source is disabled."
        },
    ),
    Capability(
        id = "ebay",
        label = "eBay recently sold",
        method = when {
            config.hasEbayCredentials && config.ebayMarketplaceInsightsEnabled -> SourceMethod.API
            config.scrapeEnabled -> SourceMethod.SCRAPE
            else -> SourceMethod.NONE
        },
        active = (config.hasEbayCredentials && config.ebayMarketplaceInsightsEnabled) || config.scrapeEnabled,
        detail = when {
            config.hasEbayCredentials && config.ebayMarketplaceInsightsEnabled ->
                "Using the Marketplace Insights API for completed sales."
            config.hasEbayCredentials ->
                "Credentials found but Marketplace Insights is off, so sold data comes from the public page. Browse API supplies active listings."
            config.scrapeEnabled ->
                "No eBay credentials — reading the sold-listings search page."
            else -> "No credentials, and scraping is off. This source is disabled."
        },
    ),
)
