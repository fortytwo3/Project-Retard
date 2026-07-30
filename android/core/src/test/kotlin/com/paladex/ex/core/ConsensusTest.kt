package com.paladex.ex.core

import com.paladex.ex.core.providers.PriceChartingProvider
import com.paladex.ex.core.providers.marketplaceQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsensusTest {

    private fun result(
        providerId: String,
        label: String = providerId,
        status: ProviderStatus = ProviderStatus.OK,
        prices: List<PricePoint> = emptyList(),
        soldSummary: SoldSummary? = null,
    ) = ProviderResult(
        providerId = providerId,
        label = label,
        status = status,
        method = SourceMethod.API,
        prices = prices,
        soldSummary = soldSummary,
    )

    private fun summary(count: Int, mean: Double) = SoldSummary(
        count = count,
        currency = Currency.USD,
        min = mean * 0.8,
        max = mean * 1.2,
        median = mean,
        trimmedMean = mean,
        trendPct = null,
        windowStart = null,
        windowEnd = null,
    )

    private val tcgMarket = PricePoint("Holofoil — Market", 355.25, Currency.USD)

    @Test
    fun `prefers a healthy sample of completed eBay sales`() {
        val consensus = PriceService.computeConsensus(
            listOf(
                result("ebay", "eBay — Recently Sold", soldSummary = summary(20, 400.0)),
                result("tcgplayer", prices = listOf(tcgMarket)),
            ),
        )

        assertEquals(400.0, consensus.amount)
        assertTrue(consensus.basis.contains("20 recent eBay sales"))
    }

    @Test
    fun `blends a thin eBay sample with the TCGplayer market price`() {
        val consensus = PriceService.computeConsensus(
            listOf(
                result("ebay", "eBay — Recently Sold", soldSummary = summary(2, 400.0)),
                result("tcgplayer", prices = listOf(PricePoint("Holofoil — Market", 300.0))),
            ),
        )

        assertEquals(350.0, consensus.amount)
        assertTrue(consensus.basis.contains("Blend"))
    }

    @Test
    fun `does not treat active asking prices as sales`() {
        val consensus = PriceService.computeConsensus(
            listOf(
                result("ebay", "eBay — Active Listings", soldSummary = summary(30, 900.0)),
                result("tcgplayer", prices = listOf(tcgMarket)),
            ),
        )

        assertEquals(355.25, consensus.amount)
        assertEquals("TCGplayer market price", consensus.basis)
    }

    @Test
    fun `falls back to PriceCharting ungraded`() {
        val consensus = PriceService.computeConsensus(
            listOf(result("pricecharting", prices = listOf(PricePoint("Ungraded", 88.0)))),
        )

        assertEquals(88.0, consensus.amount)
        assertEquals("PriceCharting ungraded", consensus.basis)
    }

    @Test
    fun `reports honestly when nothing returned a price`() {
        val consensus = PriceService.computeConsensus(
            listOf(result("tcgplayer", status = ProviderStatus.EMPTY)),
        )
        assertNull(consensus.amount)
    }

    @Test
    fun `ignores prices from a provider that errored`() {
        val consensus = PriceService.computeConsensus(
            listOf(result("tcgplayer", status = ProviderStatus.ERROR, prices = listOf(tcgMarket))),
        )
        assertNull(consensus.amount)
    }

    @Test
    fun `puts sources with data ahead of sources without`() {
        val sorted = PriceService.sortResults(
            listOf(
                result("cardmarket", status = ProviderStatus.EMPTY),
                result("tcgplayer"),
            ),
        )
        assertEquals(listOf("tcgplayer", "cardmarket"), sorted.map { it.providerId })
    }

    @Test
    fun `leads with eBay when several sources have data`() {
        val sorted = PriceService.sortResults(
            listOf(result("cardmarket"), result("tcgplayer"), result("ebay")),
        )
        assertEquals("ebay", sorted.first().providerId)
    }

    @Test
    fun `marketplace query includes the collector number`() {
        val card = Card(
            id = "sv3pt5-54",
            name = "Charizard ex",
            number = "054",
            printedTotal = 165,
            setId = "sv3pt5",
            setName = "151",
        )
        assertEquals("Charizard ex 151 054/165", marketplaceQuery(card))
    }

    // --- PriceCharting page parsing ---

    private val priceCharting = PriceChartingProvider(
        http = com.paladex.ex.core.net.Http(0),
        cache = com.paladex.ex.core.net.TtlCache(0),
        config = Config(cacheTtlSeconds = 0),
    )

    @Test
    fun `converts API cents to dollars`() {
        assertEquals(389.99, priceCharting.centsToDollars(38999))
    }

    @Test
    fun `treats a zero price as absent`() {
        assertNull(priceCharting.centsToDollars(0))
    }

    @Test
    fun `maps the six price cells to card grades`() {
        val html = """
            <div id="used_price"><span class="price">${'$'}88.00</span></div>
            <div id="complete_price"><span class="price">${'$'}140.00</span></div>
            <div id="new_price"><span class="price">${'$'}190.00</span></div>
            <div id="graded_price"><span class="price">${'$'}260.00</span></div>
            <div id="box_only_price"><span class="price">${'$'}320.00</span></div>
            <div id="manual_only_price"><span class="price">${'$'}410.00</span></div>
        """.trimIndent()

        assertEquals(
            listOf(
                "Ungraded" to 88.0,
                "Grade 7" to 140.0,
                "Grade 8" to 190.0,
                "Grade 9" to 260.0,
                "Grade 9.5" to 320.0,
                "PSA 10" to 410.0,
            ),
            priceCharting.parseProductPage(html).map { it.label to it.amount },
        )
    }

    @Test
    fun `skips cells PriceCharting leaves blank`() {
        val html = """
            <div id="used_price"><span class="price">${'$'}88.00</span></div>
            <div id="manual_only_price"><span class="price">-</span></div>
        """.trimIndent()

        assertEquals(listOf("Ungraded" to 88.0), priceCharting.parseProductPage(html).map { it.label to it.amount })
    }
}
