package com.paladex.ex.core

import kotlinx.serialization.Serializable

/** Shared domain types. Everything the UI renders is normalised into these. */

enum class Currency { USD, EUR, GBP }

/** A card as identified by pokemontcg.io. */
@Serializable
data class Card(
    val id: String,
    val name: String,
    /** Collector number as printed, e.g. "058" or "TG12". */
    val number: String,
    /** Total printed on the card, e.g. 197 from "058/197". */
    val printedTotal: Int? = null,
    val setId: String,
    val setName: String,
    val setSeries: String = "",
    val setReleaseDate: String? = null,
    val rarity: String? = null,
    val imageSmall: String = "",
    val imageLarge: String = "",
    val tcgplayerUrl: String? = null,
    val cardmarketUrl: String? = null,
)

/** A candidate match produced by the identifier, ranked by [score]. */
data class CardCandidate(
    val card: Card,
    /** 0..1, higher is better. */
    val score: Double,
    /** Human-readable reasons the matcher liked this card. */
    val reasons: List<String> = emptyList(),
)

/**
 * One price point. [label] distinguishes printings (Holofoil vs Reverse
 * Holofoil) and grades (PSA 10) — whatever the source reports.
 */
data class PricePoint(
    val label: String,
    /** Null when a source lists the variant but has no price for it. */
    val amount: Double?,
    val currency: Currency = Currency.USD,
    /** Optional context, e.g. "based on 14 sales". */
    val note: String? = null,
)

/** A single completed eBay sale. */
data class SoldListing(
    val title: String,
    val price: Double,
    val currency: Currency = Currency.USD,
    /** ISO date of the sale, when the source exposes one. */
    val soldAt: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    /** True when shipping is bundled into [price]. */
    val shippingIncluded: Boolean = false,
    /** Parsed out of the title when present, e.g. "PSA 10". */
    val detectedGrade: String? = null,
)

/** Summary statistics over a set of sold listings. */
data class SoldSummary(
    val count: Int,
    val currency: Currency,
    val min: Double,
    val max: Double,
    val median: Double,
    /** Mean after discarding statistical outliers — the headline number. */
    val trimmedMean: Double,
    /** Percent change between the oldest and newest halves of the window. */
    val trendPct: Double?,
    val windowStart: String?,
    val windowEnd: String?,
)

enum class ProviderStatus { OK, EMPTY, ERROR, DISABLED }

/** How a provider got its numbers, surfaced in the UI so you can judge them. */
enum class SourceMethod { API, SCRAPE, NONE }

data class ProviderResult(
    val providerId: String,
    val label: String,
    val status: ProviderStatus,
    val method: SourceMethod,
    /** Present when status is ERROR or DISABLED. */
    val message: String? = null,
    /** Page a human can open to verify. */
    val sourceUrl: String? = null,
    val prices: List<PricePoint> = emptyList(),
    /** Only eBay populates these. */
    val soldListings: List<SoldListing> = emptyList(),
    val soldSummary: SoldSummary? = null,
    val fetchedAtMillis: Long = 0L,
    /** True when served from the local cache rather than the network. */
    val cached: Boolean = false,
)

/**
 * The single headline number, plus a plain-English account of where it came
 * from. [amount] is null when no source produced a usable price.
 */
data class Consensus(
    val amount: Double?,
    val currency: Currency,
    val basis: String,
)

data class PriceReport(
    val card: Card,
    val results: List<ProviderResult>,
    val consensus: Consensus,
    val generatedAtMillis: Long,
)

/** What the OCR pass extracts from a captured frame. */
data class ScanParse(
    /** Best guess at the card name. */
    val name: String? = null,
    /** Collector number, e.g. "058". */
    val number: String? = null,
    /** Set total, e.g. 197 from "058/197". */
    val printedTotal: Int? = null,
    /** Set abbreviation if one was legible, e.g. "SVI". */
    val setCode: String? = null,
    /** Everything OCR saw, for debugging and manual fallback. */
    val rawText: String = "",
    /** Mean OCR confidence, 0..100. Null when the recogniser does not report one. */
    val confidence: Float? = null,
) {
    /** Nothing to search on — the UI drops the user into manual entry. */
    val isEmpty: Boolean get() = name == null && number == null
}

/** A card the user saved, with the price at the time they scanned it. */
@Serializable
data class HistoryEntry(
    val id: String,
    val cardId: String,
    val cardName: String,
    val setName: String,
    val imageUrl: String,
    val scannedAtMillis: Long,
    val valueAtScan: Double? = null,
    val currency: String = "USD",
)
