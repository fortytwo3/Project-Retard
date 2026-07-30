package com.paladex.ex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {

    private fun sale(price: Double, soldAt: String? = null) = SoldListing(
        title = "Charizard ex 054/165",
        price = price,
        currency = Currency.USD,
        soldAt = soldAt,
    )

    @Test
    fun `median averages the middle pair for an even count`() {
        assertEquals(2.5, Stats.median(listOf(1.0, 2.0, 3.0, 4.0)))
    }

    @Test
    fun `median takes the middle value for an odd count`() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)))
    }

    @Test
    fun `median of an empty sample is zero`() {
        assertEquals(0.0, Stats.median(emptyList()))
    }

    @Test
    fun `drops a graded copy from a sample of raw sales`() {
        val prices = listOf(20.0, 21.0, 22.0, 19.0, 23.0, 20.0, 4000.0)
        assertTrue(4000.0 !in Stats.removeOutliers(prices))
    }

    @Test
    fun `leaves small samples untouched, since there is no distribution yet`() {
        assertEquals(3, Stats.removeOutliers(listOf(10.0, 5000.0, 12.0)).size)
    }

    @Test
    fun `keeps everything when all values are identical`() {
        val prices = listOf(7.0, 7.0, 7.0, 7.0, 7.0)
        assertEquals(prices, Stats.removeOutliers(prices))
    }

    @Test
    fun `trimmed mean is not dragged upward by a single extreme sale`() {
        val mean = Stats.trimmedMean(listOf(20.0, 21.0, 22.0, 19.0, 23.0, 20.0, 4000.0))
        assertTrue(mean > 19 && mean < 24, "expected a mean near 21, got $mean")
    }

    @Test
    fun `reports a rise when newer sales are higher`() {
        val listings = listOf(
            sale(10.0, "2025-01-01T00:00:00Z"),
            sale(10.0, "2025-01-02T00:00:00Z"),
            sale(10.0, "2025-01-03T00:00:00Z"),
            sale(20.0, "2025-02-01T00:00:00Z"),
            sale(20.0, "2025-02-02T00:00:00Z"),
            sale(20.0, "2025-02-03T00:00:00Z"),
        )
        assertEquals(100.0, Stats.trendPct(listings)!!, 0.5)
    }

    @Test
    fun `trend is null without enough dated sales to be meaningful`() {
        assertNull(Stats.trendPct(listOf(sale(10.0, "2025-01-01T00:00:00Z"))))
    }

    @Test
    fun `trend is null when no sale carries a date`() {
        assertNull(Stats.trendPct(List(10) { sale(10.0) }))
    }

    @Test
    fun `summarises a sample`() {
        val summary = Stats.summarise(listOf(sale(10.0), sale(20.0), sale(30.0)))

        assertNotNull(summary)
        assertEquals(3, summary.count)
        assertEquals(10.0, summary.min)
        assertEquals(30.0, summary.max)
        assertEquals(20.0, summary.median)
        assertEquals(Currency.USD, summary.currency)
    }

    @Test
    fun `summary of an empty sample is null`() {
        assertNull(Stats.summarise(emptyList()))
    }
}
