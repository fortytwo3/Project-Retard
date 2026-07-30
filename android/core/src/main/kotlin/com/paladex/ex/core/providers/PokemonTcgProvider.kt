package com.paladex.ex.core.providers

import com.paladex.ex.core.Card
import com.paladex.ex.core.Currency
import com.paladex.ex.core.PokemonTcgClient
import com.paladex.ex.core.PricePoint
import com.paladex.ex.core.ProviderResult
import com.paladex.ex.core.ProviderStatus
import com.paladex.ex.core.SourceMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TCGplayer and Cardmarket prices, both of which pokemontcg.io republishes for
 * free. The only source needing no key and no scraping, so it is the baseline
 * every scan gets.
 */
class PokemonTcgProvider(private val client: PokemonTcgClient) : PriceProvider {
    override val id = "pokemontcg"
    override val label = "pokemontcg.io"

    /** pokemontcg.io keys TCGplayer prices by printing; these are the ones seen in the wild. */
    private val variantLabels = mapOf(
        "normal" to "Normal",
        "holofoil" to "Holofoil",
        "reverseHolofoil" to "Reverse Holofoil",
        "1stEditionHolofoil" to "1st Edition Holofoil",
        "1stEditionNormal" to "1st Edition Normal",
        "unlimitedHolofoil" to "Unlimited Holofoil",
        "unlimited" to "Unlimited",
    )

    private fun JsonObject.number(key: String): Double? =
        runCatching { this[key]?.jsonPrimitive?.double }.getOrNull()

    private fun tcgplayerPrices(prices: JsonObject?): List<PricePoint> {
        if (prices == null) return emptyList()

        return buildList {
            for ((variant, block) in prices) {
                val values = runCatching { block.jsonObject }.getOrNull() ?: continue
                val name = variantLabels[variant] ?: variant

                // `market` is TCGplayer's own estimate of what the card
                // actually trades at, so it leads. `low` follows because it is
                // what you would pay today for the cheapest listed copy.
                values.number("market")?.let {
                    add(PricePoint("$name — Market", it, Currency.USD))
                }
                values.number("low")?.let {
                    add(PricePoint("$name — Low", it, Currency.USD))
                }
                values.number("directLow")?.let {
                    add(PricePoint("$name — Direct Low", it, Currency.USD, "TCGplayer Direct"))
                }
            }
        }
    }

    private fun cardmarketPrices(prices: JsonObject?): List<PricePoint> {
        if (prices == null) return emptyList()

        val rows = listOf(
            Triple("Trend Price", "trendPrice", null),
            Triple("Average Sell Price", "averageSellPrice", null),
            Triple("Lowest Listing", "lowPrice", null),
            Triple("30-day Average", "avg30", "Rolling 30 days"),
            Triple("7-day Average", "avg7", "Rolling 7 days"),
            Triple("1-day Average", "avg1", "Yesterday"),
            Triple("Reverse Holo Trend", "reverseHoloTrend", null),
        )

        return rows.mapNotNull { (label, key, note) ->
            prices.number(key)?.let { PricePoint(label, it, Currency.EUR, note) }
        }
    }

    override suspend fun fetch(card: Card): List<ProviderResult> = try {
        val node = client.fetchCardJson(card.id)

        val tcgplayer = node?.get("tcgplayer")?.jsonObject
        val cardmarket = node?.get("cardmarket")?.jsonObject

        val tcgPrices = tcgplayerPrices(tcgplayer?.get("prices")?.jsonObject)
        val cmPrices = cardmarketPrices(cardmarket?.get("prices")?.jsonObject)

        listOf(
            result(
                "tcgplayer",
                "TCGplayer",
                if (tcgPrices.isNotEmpty()) ProviderStatus.OK else ProviderStatus.EMPTY,
                SourceMethod.API,
            ) {
                copy(
                    prices = tcgPrices,
                    sourceUrl = card.tcgplayerUrl,
                    message = "No TCGplayer pricing published for this card."
                        .takeIf { tcgPrices.isEmpty() },
                )
            },
            result(
                "cardmarket",
                "Cardmarket",
                if (cmPrices.isNotEmpty()) ProviderStatus.OK else ProviderStatus.EMPTY,
                SourceMethod.API,
            ) {
                copy(
                    prices = cmPrices,
                    sourceUrl = card.cardmarketUrl,
                    message = "No Cardmarket pricing published for this card."
                        .takeIf { cmPrices.isEmpty() },
                )
            },
        )
    } catch (e: Exception) {
        listOf(
            errored("tcgplayer", "TCGplayer", SourceMethod.API, e),
            errored("cardmarket", "Cardmarket", SourceMethod.API, e),
        )
    }
}
