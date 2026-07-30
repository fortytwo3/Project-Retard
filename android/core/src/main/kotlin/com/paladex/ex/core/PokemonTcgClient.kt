package com.paladex.ex.core

import com.paladex.ex.core.net.Http
import com.paladex.ex.core.net.TtlCache
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Card identification against pokemontcg.io, plus the fuzzy matching that turns
 * a noisy OCR read into a ranked list of candidate printings.
 */
class PokemonTcgClient(
    private val http: Http,
    private val cache: TtlCache,
    private val config: Config,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun headers(): Map<String, String> =
        if (config.pokemonTcgApiKey.isNotBlank()) mapOf("X-Api-Key" to config.pokemonTcgApiKey)
        else emptyMap()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Card metadata is static; only the embedded prices move. */
    private val metadataTtl = 60L * 60 * 12

    private fun parseCard(node: JsonObject): Card? {
        val id = node["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val set = node["set"]?.jsonObject
        val images = node["images"]?.jsonObject

        return Card(
            id = id,
            name = node["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            number = node["number"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            printedTotal = set?.get("printedTotal")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            setId = set?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            setName = set?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            setSeries = set?.get("series")?.jsonPrimitive?.contentOrNull.orEmpty(),
            setReleaseDate = set?.get("releaseDate")?.jsonPrimitive?.contentOrNull,
            rarity = node["rarity"]?.jsonPrimitive?.contentOrNull,
            imageSmall = images?.get("small")?.jsonPrimitive?.contentOrNull.orEmpty(),
            imageLarge = images?.get("large")?.jsonPrimitive?.contentOrNull.orEmpty(),
            tcgplayerUrl = node["tcgplayer"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
            cardmarketUrl = node["cardmarket"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * Raw price blobs are returned alongside the card so the provider can read
     * them without a second round trip.
     */
    suspend fun fetchCardJson(id: String): JsonObject? {
        val url = "$API_BASE/cards/${encode(id)}"
        return try {
            cache.get("ptcg:card:$id", metadataTtl) {
                val body = http.get(url, headers(), immediate = true)
                json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            }.value
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCardById(id: String): Card? = fetchCardJson(id)?.let { parseCard(it) }

    private suspend fun query(q: String, pageSize: Int = 40): List<Card> {
        val url = "$API_BASE/cards?q=${encode(q)}&pageSize=$pageSize&orderBy=-set.releaseDate"
        val body = cache.get("ptcg:$url", metadataTtl) {
            http.get(url, headers(), immediate = true)
        }.value

        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { parseCard(it.jsonObject) }
    }

    /**
     * Find the cards a scan might be showing.
     *
     * Several queries run because the API does not do fuzzy matching: a name
     * with one OCR error returns nothing, so we also search on the collector
     * number alone, which is short enough to usually survive OCR intact.
     * Results are merged and ranked locally.
     */
    suspend fun identify(scan: ScanParse): List<CardCandidate> = coroutineScope {
        val queries = buildList {
            val bareNumber = scan.number?.trimStart('0')?.ifEmpty { scan.number }

            if (scan.name != null && bareNumber != null) {
                add("""name:"${scan.name}" number:"$bareNumber"""")
            }
            if (scan.name != null) {
                add("""name:"${scan.name}"""")
                // Wildcard the last word so a truncated read still hits.
                val words = scan.name.split(" ")
                add("name:" + (words.dropLast(1) + "${words.last()}*").joinToString(" "))
            }
            if (bareNumber != null) {
                add(
                    if (scan.printedTotal != null) {
                        """number:"$bareNumber" set.printedTotal:${scan.printedTotal}"""
                    } else {
                        """number:"$bareNumber""""
                    },
                )
            }
        }

        if (queries.isEmpty()) return@coroutineScope emptyList()

        val results = queries
            .map { q -> async { runCatching { query(q) }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }

        results
            .associateBy { it.id }
            .values
            .map { card -> scoreCard(card, scan) }
            .filter { it.score > 0.35 }
            .sortedByDescending { it.score }
            .take(24)
    }

    /** Free-text search backing the manual-entry fallback. */
    suspend fun search(term: String): List<Card> {
        val cleaned = term.trim().replace("\"", "")
        if (cleaned.length < 2) return emptyList()
        return runCatching { query("""name:"$cleaned*"""", 30) }.getOrDefault(emptyList())
    }

    companion object {
        private const val API_BASE = "https://api.pokemontcg.io/v2"

        internal fun normalise(text: String): String = text
            .lowercase()
            .replace(Regex("""[^a-z0-9 ]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        /** Levenshtein distance, iterative with a single row of state. */
        internal fun editDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            var previous = IntArray(b.length + 1) { it }

            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                }
                previous = current
            }

            return previous[b.length]
        }

        /** 1 for identical strings, 0 for entirely different ones. */
        internal fun similarity(a: String, b: String): Double {
            val longest = maxOf(a.length, b.length)
            if (longest == 0) return 1.0
            return 1.0 - editDistance(a, b).toDouble() / longest
        }

        /** Strip leading zeros so "058" and "58" compare equal. */
        internal fun normaliseNumber(value: String): String =
            value.uppercase().replace(Regex("""^([A-Z]*)0+(\d)"""), "$1$2")

        /**
         * Score a card against what OCR saw. Weighted so the collector number —
         * the most reliably-read field — can carry a match when the name is
         * mangled, while a confident name match alone still surfaces candidates.
         */
        internal fun scoreCard(card: Card, scan: ScanParse): CardCandidate {
            val reasons = mutableListOf<String>()
            var score = 0.0

            scan.name?.let { name ->
                val nameScore = similarity(normalise(card.name), normalise(name))
                score += nameScore * 0.5
                when {
                    nameScore > 0.95 -> reasons += "Exact name match"
                    nameScore > 0.7 -> reasons += "Close name match"
                }
            }

            scan.number?.let { number ->
                if (normaliseNumber(card.number) == normaliseNumber(number)) {
                    score += 0.3
                    reasons += "Card number ${card.number}"
                }
            }

            scan.printedTotal?.let { total ->
                if (card.printedTotal == total) {
                    score += 0.2
                    reasons += "Set size $total matches"
                }
            }

            scan.setCode?.let { code ->
                if (card.setId.uppercase().contains(code)) {
                    score += 0.1
                    reasons += "Set code $code"
                }
            }

            return CardCandidate(card, minOf(score, 1.0), reasons)
        }
    }
}
