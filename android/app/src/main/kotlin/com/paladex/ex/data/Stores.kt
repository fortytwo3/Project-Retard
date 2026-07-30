package com.paladex.ex.data

import android.content.Context
import androidx.core.content.edit
import com.paladex.ex.core.Card
import com.paladex.ex.core.Config
import com.paladex.ex.core.HistoryEntry
import com.paladex.ex.core.PriceReport
import kotlinx.serialization.json.Json

/**
 * Scan history, kept on the device.
 *
 * There are no accounts in this app, and a record of what you own is the kind
 * of thing that should not silently end up on someone's server.
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("paladex-history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun write(entries: List<HistoryEntry>) {
        prefs.edit { putString(KEY, json.encodeToString(entries.take(MAX_ENTRIES))) }
    }

    /** Record a scan along with what the card was worth at that moment. */
    fun add(card: Card, report: PriceReport): HistoryEntry {
        val entry = HistoryEntry(
            id = "${card.id}:${System.currentTimeMillis()}",
            cardId = card.id,
            cardName = card.name,
            setName = card.setName,
            imageUrl = card.imageSmall,
            scannedAtMillis = System.currentTimeMillis(),
            valueAtScan = report.consensus.amount,
            currency = report.consensus.currency.name,
        )
        write(listOf(entry) + all())
        return entry
    }

    fun remove(id: String) = write(all().filterNot { it.id == id })

    fun clear() = write(emptyList())

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 500

        /**
         * Total of the saved values. Only sums entries sharing the leading
         * currency, since the app does no FX conversion and a mixed-currency
         * total would be a made-up number.
         */
        fun portfolioValue(entries: List<HistoryEntry>): Portfolio {
            val priced = entries.filter { it.valueAtScan != null }
            if (priced.isEmpty()) return Portfolio(0.0, "USD", 0, entries.size)

            val currency = priced.first().currency
            val matching = priced.filter { it.currency == currency }

            return Portfolio(
                total = matching.sumOf { it.valueAtScan ?: 0.0 },
                currency = currency,
                counted = matching.size,
                skipped = entries.size - matching.size,
            )
        }
    }

    data class Portfolio(val total: Double, val currency: String, val counted: Int, val skipped: Int)
}

/**
 * API keys and scraping preferences. Stored in plain SharedPreferences, which
 * is private to the app — adequate for personal-use marketplace keys, and not
 * where you would put anything that moves money.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("paladex-settings", Context.MODE_PRIVATE)

    fun load(): Config = Config(
        pokemonTcgApiKey = prefs.getString("pokemontcg_key", "").orEmpty(),
        priceChartingToken = prefs.getString("pricecharting_token", "").orEmpty(),
        ebayClientId = prefs.getString("ebay_client_id", "").orEmpty(),
        ebayClientSecret = prefs.getString("ebay_client_secret", "").orEmpty(),
        ebayMarketplaceInsightsEnabled = prefs.getBoolean("ebay_insights", false),
        scrapeEnabled = prefs.getBoolean("scrape_enabled", true),
    )

    fun save(config: Config) = prefs.edit {
        putString("pokemontcg_key", config.pokemonTcgApiKey)
        putString("pricecharting_token", config.priceChartingToken)
        putString("ebay_client_id", config.ebayClientId)
        putString("ebay_client_secret", config.ebayClientSecret)
        putBoolean("ebay_insights", config.ebayMarketplaceInsightsEnabled)
        putBoolean("scrape_enabled", config.scrapeEnabled)
    }
}
