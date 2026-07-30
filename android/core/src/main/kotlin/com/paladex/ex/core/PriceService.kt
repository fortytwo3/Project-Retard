package com.paladex.ex.core

import com.paladex.ex.core.net.Http
import com.paladex.ex.core.net.TtlCache
import com.paladex.ex.core.providers.EbayProvider
import com.paladex.ex.core.providers.PokemonTcgProvider
import com.paladex.ex.core.providers.PriceChartingProvider
import com.paladex.ex.core.providers.PriceProvider
import com.paladex.ex.core.providers.errored
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Fans a card out to every price source and reduces the answers to one number.
 *
 * This is the whole application logic; the Android module is a camera and a
 * list of views on top of it.
 */
class PriceService(
    val config: Config = Config(),
    http: Http = Http(config.scrapeMinIntervalMillis),
    private val cache: TtlCache = TtlCache(config.cacheTtlSeconds),
    /** Injectable so tests can supply a client backed by a mock server. */
    val cards: PokemonTcgClient = PokemonTcgClient(http, cache, config),
    providers: List<PriceProvider>? = null,
) {
    private val providers: List<PriceProvider> = providers ?: listOf(
        PokemonTcgProvider(cards),
        PriceChartingProvider(http, cache, config),
        EbayProvider(http, cache, config),
    )

    suspend fun identify(scan: ScanParse): List<CardCandidate> = cards.identify(scan)

    suspend fun search(term: String): List<Card> = cards.search(term)

    /** Drop cached responses so the next report is fetched live. */
    suspend fun clearCache() = cache.clear()

    /**
     * Query every provider for one card. Providers run concurrently and are
     * individually fault-isolated — one site being down or having changed its
     * markup must never take the whole report with it.
     */
    suspend fun buildReport(card: Card): PriceReport = coroutineScope {
        val results = providers
            .map { provider ->
                async {
                    runCatching { provider.fetch(card) }.getOrElse {
                        listOf(errored(provider.id, provider.label, SourceMethod.NONE, it))
                    }
                }
            }
            .flatMap { it.await() }

        PriceReport(
            card = card,
            results = sortResults(results),
            consensus = computeConsensus(results),
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    companion object {
        /** Order sources appear in the UI, most-trusted first. */
        private val DISPLAY_ORDER = listOf("ebay", "tcgplayer", "pricecharting", "cardmarket")

        internal fun sortResults(results: List<ProviderResult>): List<ProviderResult> =
            results.sortedWith(
                // Sources with data always outrank sources without.
                compareBy<ProviderResult> {
                    when (it.status) {
                        ProviderStatus.OK -> 0
                        ProviderStatus.EMPTY -> 1
                        else -> 2
                    }
                }.thenBy {
                    DISPLAY_ORDER.indexOf(it.providerId).let { i -> if (i == -1) 99 else i }
                },
            )

        private fun findPrice(
            results: List<ProviderResult>,
            providerId: String,
            match: Regex,
        ): Double? = results
            .firstOrNull { it.providerId == providerId && it.status == ProviderStatus.OK }
            ?.prices
            ?.firstOrNull { match.containsMatchIn(it.label) && it.amount != null }
            ?.amount

        /**
         * The single headline number.
         *
         * Completed sales beat listed prices, so eBay leads when it has a
         * usable sample. Below five sales the average is too jumpy to trust
         * alone, and gets blended with TCGplayer's market price. Everything
         * below that is a fallback chain through the remaining USD sources —
         * Cardmarket is quoted in euros and is deliberately last, used only
         * when nothing else reported.
         */
        internal fun computeConsensus(results: List<ProviderResult>): Consensus {
            val ebay = results.firstOrNull {
                it.providerId == "ebay" && it.status == ProviderStatus.OK
            }
            val sample = ebay?.soldSummary
            val isRealSold = ebay?.label?.contains("Sold") == true

            val tcgMarket = findPrice(results, "tcgplayer", Regex("Market"))

            if (sample != null && isRealSold && sample.count >= 5) {
                return Consensus(
                    sample.trimmedMean,
                    sample.currency,
                    "Average of ${sample.count} recent eBay sales",
                )
            }

            if (sample != null && isRealSold && tcgMarket != null) {
                val noun = if (sample.count == 1) "sale" else "sales"
                return Consensus(
                    Stats.trimmedMean(listOf(sample.trimmedMean, tcgMarket)),
                    Currency.USD,
                    "Blend of ${sample.count} eBay $noun and TCGplayer market price",
                )
            }

            if (sample != null && isRealSold) {
                val noun = if (sample.count == 1) "sale" else "sales"
                return Consensus(
                    sample.trimmedMean,
                    sample.currency,
                    "Only ${sample.count} recent eBay $noun — treat as rough",
                )
            }

            tcgMarket?.let {
                return Consensus(it, Currency.USD, "TCGplayer market price")
            }

            findPrice(results, "pricecharting", Regex("Ungraded"))?.let {
                return Consensus(it, Currency.USD, "PriceCharting ungraded")
            }

            sample?.let {
                return Consensus(
                    it.trimmedMean,
                    it.currency,
                    "Average eBay asking price — no completed sales available",
                )
            }

            findPrice(results, "cardmarket", Regex("Trend"))?.let {
                return Consensus(it, Currency.EUR, "Cardmarket trend price")
            }

            return Consensus(null, Currency.USD, "No source returned a price for this card")
        }
    }
}
