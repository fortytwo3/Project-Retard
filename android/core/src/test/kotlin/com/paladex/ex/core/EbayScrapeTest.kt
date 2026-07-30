package com.paladex.ex.core

import com.paladex.ex.core.providers.EbayProvider
import com.paladex.ex.core.providers.EbayScrape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EbayScrapeTest {

    /** Markup matching eBay's long-running `s-item` result card. */
    private val legacyHtml = """
        <ul class="srp-results">
          <li class="s-item">
            <div class="s-item__title">Shop on eBay</div>
            <span class="s-item__price">${'$'}20.00</span>
          </li>
          <li class="s-item">
            <a class="s-item__link" href="https://www.ebay.com/itm/123456?hash=abc"></a>
            <div class="s-item__image-wrapper"><img src="https://i.ebayimg.com/a.jpg" /></div>
            <div class="s-item__title">Charizard ex 054/165 151 PSA 10 Gem Mint</div>
            <span class="s-item__price">${'$'}389.99</span>
            <span class="s-item__shipping">Free shipping</span>
            <div class="s-item__title--tagblock"><span class="POSITIVE">Sold  Mar 3, 2025</span></div>
          </li>
          <li class="s-item">
            <a class="s-item__link" href="https://www.ebay.com/itm/789012"></a>
            <div class="s-item__title">Charizard ex 054/165 Pokemon 151 Raw NM</div>
            <span class="s-item__price">${'$'}41.00</span>
            <span class="s-item__shipping">+${'$'}5.00 shipping</span>
            <div class="s-item__title--tagblock"><span class="POSITIVE">Sold  Feb 28, 2025</span></div>
          </li>
        </ul>
    """.trimIndent()

    /** Markup matching the newer `s-card` layout eBay A/B tests against. */
    private val modernHtml = """
        <div class="srp-results">
          <li class="s-card">
            <a href="https://www.ebay.com/itm/555?_trkparms=x"></a>
            <span class="s-card__title">Charizard ex 054/165 SV 151 Special Illustration</span>
            <span class="s-card__price">${'$'}412.50</span>
            <span class="s-card__caption">Sold 12 Mar 2025</span>
          </li>
        </div>
    """.trimIndent()

    @Test
    fun `parses the legacy result markup`() {
        val listings = EbayScrape.parseSoldListings(legacyHtml)

        assertEquals(2, listings.size)
        assertEquals(389.99, listings[0].price)
        assertEquals(Currency.USD, listings[0].currency)
        assertTrue(listings[0].shippingIncluded)
        assertEquals("PSA 10", listings[0].detectedGrade)
        assertEquals("2025-03-03", listings[0].soldAt?.take(10))
    }

    @Test
    fun `strips eBay tracking parameters from item links`() {
        assertEquals("https://www.ebay.com/itm/123456", EbayScrape.parseSoldListings(legacyHtml)[0].url)
    }

    @Test
    fun `skips the Shop on eBay promo row`() {
        assertTrue(EbayScrape.parseSoldListings(legacyHtml).none { it.title == "Shop on eBay" })
    }

    @Test
    fun `marks paid shipping as not included`() {
        assertFalse(EbayScrape.parseSoldListings(legacyHtml)[1].shippingIncluded)
    }

    @Test
    fun `parses the newer card markup too`() {
        val listings = EbayScrape.parseSoldListings(modernHtml)

        assertEquals(1, listings.size)
        assertEquals(412.50, listings[0].price)
        assertEquals("2025-03-12", listings[0].soldAt?.take(10))
    }

    @Test
    fun `returns nothing for a bot-check page rather than throwing`() {
        assertEquals(
            emptyList(),
            EbayScrape.parseSoldListings("<html><body>Pardon our interruption</body></html>"),
        )
    }

    @Test
    fun `honours the limit`() {
        assertEquals(1, EbayScrape.parseSoldListings(legacyHtml, limit = 1).size)
    }

    @Test
    fun `strips the Sold label from a date`() {
        assertEquals("2025-03-03", EbayScrape.parseSoldDate("Sold  Mar 3, 2025")?.take(10))
    }

    @Test
    fun `rejects text that is not a date`() {
        assertNull(EbayScrape.parseSoldDate("Best offer accepted"))
    }

    @Test
    fun `rejects dates in the future`() {
        assertNull(EbayScrape.parseSoldDate("Sold Jan 1, 2999"))
    }

    @Test
    fun `requests sold and completed listings`() {
        val url = EbayScrape.soldSearchUrl("Charizard ex 054/165")

        assertTrue(url.contains("LH_Sold=1"))
        assertTrue(url.contains("LH_Complete=1"))
        assertTrue(url.contains("_nkw=Charizard+ex+054%2F165"))
    }

    // --- relevance filter ---

    private val card = Card(id = "sv3pt5-54", name = "Charizard ex", number = "54", setId = "sv3pt5", setName = "151")

    private fun listing(title: String) = SoldListing(title = title, price = 10.0)

    @Test
    fun `accepts a listing naming the card`() {
        assertTrue(EbayProvider.isPlausibleMatch(listing("Charizard ex 054/165 NM"), card))
    }

    @Test
    fun `rejects a bulk lot`() {
        assertFalse(EbayProvider.isPlausibleMatch(listing("Charizard ex lot of 5 cards"), card))
    }

    @Test
    fun `rejects a you-pick listing`() {
        assertFalse(EbayProvider.isPlausibleMatch(listing("Pokemon 151 you pick Charizard ex"), card))
    }

    @Test
    fun `rejects a listing for a different card`() {
        assertFalse(EbayProvider.isPlausibleMatch(listing("Blastoise ex 009/165"), card))
    }

    @Test
    fun `rejects custom proxies`() {
        assertFalse(EbayProvider.isPlausibleMatch(listing("Charizard ex custom proxy card"), card))
    }
}
