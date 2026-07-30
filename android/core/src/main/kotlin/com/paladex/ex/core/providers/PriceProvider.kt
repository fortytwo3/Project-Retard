package com.paladex.ex.core.providers

import com.paladex.ex.core.Card
import com.paladex.ex.core.ProviderResult
import com.paladex.ex.core.ProviderStatus
import com.paladex.ex.core.SourceMethod

/**
 * A price source. Each returns one or more [ProviderResult]s, because some
 * upstreams cover several marketplaces at once — pokemontcg.io carries both
 * TCGplayer and Cardmarket data in a single response.
 */
interface PriceProvider {
    val id: String
    val label: String
    suspend fun fetch(card: Card): List<ProviderResult>
}

internal fun result(
    providerId: String,
    label: String,
    status: ProviderStatus,
    method: SourceMethod,
    build: ProviderResult.() -> ProviderResult = { this },
): ProviderResult = ProviderResult(
    providerId = providerId,
    label = label,
    status = status,
    method = method,
    fetchedAtMillis = System.currentTimeMillis(),
).build()

/** Convenience for the "this source is switched off" path. */
internal fun disabled(providerId: String, label: String, message: String): ProviderResult =
    result(providerId, label, ProviderStatus.DISABLED, SourceMethod.NONE) { copy(message = message) }

internal fun errored(
    providerId: String,
    label: String,
    method: SourceMethod,
    error: Throwable,
    sourceUrl: String? = null,
): ProviderResult = result(providerId, label, ProviderStatus.ERROR, method) {
    copy(message = error.message ?: error.toString(), sourceUrl = sourceUrl)
}

/**
 * The search phrase used against marketplaces that index by title rather than
 * by card id. Includes the collector number because "Charizard" alone returns
 * thousands of unrelated cards, and the number is what makes a listing
 * unambiguous.
 */
fun marketplaceQuery(card: Card): String = buildList {
    add(card.name)
    add(card.setName)
    if (card.number.isNotBlank()) {
        add(card.printedTotal?.let { "${card.number}/$it" } ?: card.number)
    }
}.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
