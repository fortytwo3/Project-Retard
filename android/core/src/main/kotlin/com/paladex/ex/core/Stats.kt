package com.paladex.ex.core

import java.time.Instant
import java.time.format.DateTimeParseException

/** Statistics over sold-listing samples. */
object Stats {

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = (sorted.size - 1) * q
        val base = pos.toInt()
        val rest = pos - base
        val next = sorted.getOrNull(base + 1) ?: return sorted[base]
        return sorted[base] + rest * (next - sorted[base])
    }

    /**
     * Discard values outside the usual 1.5×IQR fences.
     *
     * This matters more for cards than for most datasets: a search for
     * "Charizard 4/102" surfaces a $12 damaged copy and a $4000 graded one
     * alongside forty ordinary sales, and a plain mean lands somewhere nobody
     * would ever trade at. Below five samples there is no distribution to speak
     * of, so pass them through untouched.
     */
    fun removeOutliers(values: List<Double>): List<Double> {
        if (values.size < 5) return values.toList()

        val sorted = values.sorted()
        val q1 = quantile(sorted, 0.25)
        val q3 = quantile(sorted, 0.75)
        val iqr = q3 - q1
        if (iqr == 0.0) return sorted

        val kept = sorted.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }
        return kept.ifEmpty { sorted }
    }

    fun trimmedMean(values: List<Double>): Double {
        val kept = removeOutliers(values)
        return if (kept.isEmpty()) 0.0 else kept.sum() / kept.size
    }

    private fun parseInstant(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * Percent change between the older and newer halves of a date-sorted
     * sample. Null when there are too few dated sales to say anything honest.
     */
    fun trendPct(listings: List<SoldListing>): Double? {
        val dated = listings
            .mapNotNull { listing -> listing.soldAt?.let { parseInstant(it) }?.let { it to listing } }
            .sortedBy { it.first }
            .map { it.second }

        if (dated.size < 6) return null

        val mid = dated.size / 2
        val older = trimmedMean(dated.take(mid).map { it.price })
        val newer = trimmedMean(dated.drop(mid).map { it.price })

        if (older == 0.0) return null
        return ((newer - older) / older) * 100
    }

    fun summarise(listings: List<SoldListing>): SoldSummary? {
        if (listings.isEmpty()) return null

        val prices = listings.map { it.price }.filter { it.isFinite() && it > 0 }
        if (prices.isEmpty()) return null

        val dates = listings.mapNotNull { it.soldAt }.sorted()

        return SoldSummary(
            count = prices.size,
            currency = listings.first().currency,
            min = prices.min(),
            max = prices.max(),
            median = median(prices),
            trimmedMean = trimmedMean(prices),
            trendPct = trendPct(listings),
            windowStart = dates.firstOrNull(),
            windowEnd = dates.lastOrNull(),
        )
    }
}
