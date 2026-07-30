package com.paladex.ex.core

import com.paladex.ex.core.net.Http
import com.paladex.ex.core.net.TtlCache
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * End-to-end exercise of the pipeline — OCR text in, price report out — with
 * every outbound request served by a local MockWebServer.
 *
 * This covers what the unit tests cannot: that the identifier's queries reach
 * the right endpoint, that each provider parses its own upstream shape, that a
 * dead source does not sink the report, and that the consensus picks the number
 * we intend. Requests reach the mock through an OkHttp interceptor that
 * rewrites the host, so the providers keep their real production URLs.
 */
class PipelineTest {

    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    private val pokemonTcgCard = """
        {
          "id": "sv3pt5-54",
          "name": "Charizard ex",
          "number": "54",
          "rarity": "Double Rare",
          "set": {
            "id": "sv3pt5", "name": "151", "series": "Scarlet & Violet",
            "printedTotal": 165, "releaseDate": "2023/09/22"
          },
          "images": {
            "small": "https://images.pokemontcg.io/sv3pt5/54.png",
            "large": "https://images.pokemontcg.io/sv3pt5/54_hires.png"
          },
          "tcgplayer": {
            "url": "https://prices.pokemontcg.io/tcgplayer/sv3pt5-54",
            "prices": {
              "holofoil": {
                "low": 320.5, "mid": 360.0, "high": 500.0,
                "market": 355.25, "directLow": 349.99
              }
            }
          },
          "cardmarket": {
            "url": "https://prices.pokemontcg.io/cardmarket/sv3pt5-54",
            "prices": {
              "averageSellPrice": 310.4, "lowPrice": 289.0,
              "trendPrice": 318.5, "avg30": 305.1
            }
          }
        }
    """.trimIndent()

    private val priceChartingSearch = """
        <html><body>
          <table id="games_table"><tbody>
            <tr><td class="title"><a href="/game/pokemon-151/charizard-ex-54">Charizard ex #54</a></td></tr>
          </tbody></table>
        </body></html>
    """.trimIndent()

    private val priceChartingProduct = """
        <html><body>
          <div id="used_price"><span class="price">${'$'}88.00</span></div>
          <div id="complete_price"><span class="price">${'$'}140.00</span></div>
          <div id="graded_price"><span class="price">${'$'}260.00</span></div>
          <div id="manual_only_price"><span class="price">${'$'}410.00</span></div>
        </body></html>
    """.trimIndent()

    /** Eight sold rows plus one bulk lot the relevance filter must reject. */
    private val ebaySold = buildString {
        append("<ul class=\"srp-results\">")
        listOf(402, 388, 395, 410, 399, 385, 405, 392).forEachIndexed { index, price ->
            append(
                """
                <li class="s-item">
                  <a class="s-item__link" href="https://www.ebay.com/itm/$index"></a>
                  <div class="s-item__title">Charizard ex 054/165 Pokemon 151 NM</div>
                  <span class="s-item__price">${'$'}$price.00</span>
                  <div class="s-item__title--tagblock">
                    <span class="POSITIVE">Sold  Mar ${index + 1}, 2025</span>
                  </div>
                </li>
                """.trimIndent(),
            )
        }
        append(
            """
            <li class="s-item">
              <a class="s-item__link" href="https://www.ebay.com/itm/lot"></a>
              <div class="s-item__title">Charizard ex lot of 12 Pokemon cards bundle</div>
              <span class="s-item__price">${'$'}1200.00</span>
            </li>
            """.trimIndent(),
        )
        append("</ul>")
    }

    private fun ok(body: String, contentType: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(body)

    /**
     * @param priceChartingDown serve a 500 for PriceCharting, simulating an outage.
     * @param ebayBody override the eBay response, e.g. with a bot-check page.
     */
    private fun start(priceChartingDown: Boolean = false, ebayBody: String? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v2/cards/") -> ok("""{"data":$pokemonTcgCard}""", "application/json")
                    path.startsWith("/v2/cards") -> ok("""{"data":[$pokemonTcgCard]}""", "application/json")
                    path.startsWith("/search-products") ->
                        if (priceChartingDown) MockResponse().setResponseCode(500)
                        else ok(priceChartingSearch, "text/html")
                    path.startsWith("/game/") -> ok(priceChartingProduct, "text/html")
                    path.startsWith("/sch/") -> ok(ebayBody ?: ebaySold, "text/html")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    /** Rewrites every outbound request to the mock server, keeping path and query. */
    private fun service(config: Config = Config(cacheTtlSeconds = 0, scrapeMinIntervalMillis = 0)): PriceService {
        val base = server.url("/")
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val rewritten = request.url.newBuilder()
                        .scheme(base.scheme).host(base.host).port(base.port).build()
                    chain.proceed(request.newBuilder().url(rewritten).build())
                },
            )
            .build()

        val http = Http(minIntervalMillis = 0, client = client)
        return PriceService(config = config, http = http, cache = TtlCache(0))
    }

    private val card = Card(
        id = "sv3pt5-54",
        name = "Charizard ex",
        number = "54",
        printedTotal = 165,
        setId = "sv3pt5",
        setName = "151",
    )

    // --- identify ---

    @Test
    fun `turns raw OCR text into the right card`() = runBlocking {
        start()
        val scan = CardTextParser.parse("Charizard ex\n330 HP\nIllus. 5ban Graphics\n054/165  MEW")
        val candidates = service().identify(scan)

        assertTrue(candidates.isNotEmpty())
        assertEquals("sv3pt5-54", candidates.first().card.id)
        assertTrue(candidates.first().score > 0.9)
    }

    @Test
    fun `still finds the card when OCR mangles the name but gets the number`() = runBlocking {
        start()
        val candidates = service().identify(CardTextParser.parse("Chariz@rd ex\n054/165"))
        assertEquals("sv3pt5-54", candidates.firstOrNull()?.card?.id)
    }

    // --- report ---

    @Test
    fun `collects every source and leads with completed eBay sales`() = runBlocking {
        start()
        val report = service().buildReport(card)
        val ids = report.results.map { it.providerId }

        assertTrue(ids.containsAll(listOf("ebay", "tcgplayer", "cardmarket", "pricecharting")))
        // eBay sold data is the most trustworthy signal, so it sorts first.
        assertEquals("ebay", report.results.first().providerId)
    }

    @Test
    fun `reads TCGplayer market and low from the pokemontcg payload`() = runBlocking {
        start()
        val tcg = service().buildReport(card).results.first { it.providerId == "tcgplayer" }

        assertEquals(ProviderStatus.OK, tcg.status)
        assertEquals(355.25, tcg.prices.first { it.label == "Holofoil — Market" }.amount)
        assertEquals(320.5, tcg.prices.first { it.label == "Holofoil — Low" }.amount)
    }

    @Test
    fun `reads Cardmarket prices in euros`() = runBlocking {
        start()
        val cm = service().buildReport(card).results.first { it.providerId == "cardmarket" }

        assertEquals(ProviderStatus.OK, cm.status)
        assertEquals(318.5, cm.prices.first { it.label == "Trend Price" }.amount)
        assertEquals(Currency.EUR, cm.prices.first().currency)
    }

    @Test
    fun `maps PriceCharting columns onto card grades`() = runBlocking {
        start()
        val pc = service().buildReport(card).results.first { it.providerId == "pricecharting" }

        assertEquals(SourceMethod.SCRAPE, pc.method)
        assertEquals(410.0, pc.prices.first { it.label == "PSA 10" }.amount)
        assertEquals(88.0, pc.prices.first { it.label == "Ungraded" }.amount)
    }

    @Test
    fun `drops the bulk lot before averaging sold prices`() = runBlocking {
        start()
        val ebay = service().buildReport(card).results.first { it.providerId == "ebay" }

        assertNotNull(ebay.soldSummary)
        assertEquals(8, ebay.soldSummary.count)
        assertTrue(ebay.soldSummary.max < 500)
        assertTrue(ebay.soldListings.none { it.title.contains("lot of 12") })
    }

    @Test
    fun `bases the headline price on the eBay sales`() = runBlocking {
        start()
        val consensus = service().buildReport(card).consensus

        assertEquals(397.0, consensus.amount!!, 1.0)
        assertTrue(consensus.basis.contains("eBay"))
    }

    @Test
    fun `survives a source going down and still prices the card`() = runBlocking {
        start(priceChartingDown = true)
        val report = service().buildReport(card)
        val pc = report.results.first { it.providerId == "pricecharting" }

        assertEquals(ProviderStatus.ERROR, pc.status)
        assertEquals(397.0, report.consensus.amount!!, 1.0)
    }

    @Test
    fun `falls back to TCGplayer when eBay serves a bot-check page`() = runBlocking {
        start(ebayBody = "<html><body>Pardon our interruption</body></html>")
        val report = service().buildReport(card)
        val ebay = report.results.first { it.providerId == "ebay" }

        assertEquals(ProviderStatus.EMPTY, ebay.status)
        assertEquals(355.25, report.consensus.amount)
        assertEquals("TCGplayer market price", report.consensus.basis)
    }

    @Test
    fun `reports eBay as disabled when scraping is off and there are no credentials`() = runBlocking {
        start()
        val config = Config(scrapeEnabled = false, cacheTtlSeconds = 0, scrapeMinIntervalMillis = 0)
        val ebay = service(config).buildReport(card).results.first { it.providerId == "ebay" }

        assertEquals(ProviderStatus.DISABLED, ebay.status)
    }

    @Test
    fun `search returns cards for the manual fallback`() = runBlocking {
        start()
        val cards = service().search("Charizard")

        assertEquals(1, cards.size)
        assertEquals("Charizard ex", cards.first().name)
    }

    @Test
    fun `an unknown card id yields no card rather than throwing`() = runBlocking {
        start()
        server.shutdown()
        assertNull(service().cards.getCardById("does-not-exist"))
    }
}
