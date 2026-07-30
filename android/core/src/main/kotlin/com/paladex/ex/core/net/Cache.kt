package com.paladex.ex.core.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory TTL cache for provider responses.
 *
 * Scraped pages are slow and rate-limited, so re-fetching one because the user
 * rotated the phone is not acceptable. Memory-only by design: on Android the
 * process outlives every screen that would want the data, and a price cache is
 * not worth persisting past a restart.
 */
class TtlCache(private val defaultTtlSeconds: Long = 3_600) {

    private data class Entry(val value: Any?, val expiresAt: Long)

    private val entries = mutableMapOf<String, Entry>()
    private val guard = Mutex()

    data class Result<T>(val value: T, val cached: Boolean)

    /** Run [block] unless a fresh cached value exists under [key]. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(
        key: String,
        ttlSeconds: Long = defaultTtlSeconds,
        block: suspend () -> T,
    ): Result<T> {
        // A non-positive TTL means caching off, which is what tests want and
        // what makes scrape debugging bearable.
        if (ttlSeconds <= 0) return Result(block(), false)

        guard.withLock {
            entries[key]?.let { if (it.expiresAt > System.currentTimeMillis()) return Result(it.value as T, true) }
        }

        // Deliberately outside the lock: a slow scrape must not block reads of
        // unrelated keys. Two racing misses just fetch twice, which is fine.
        val value = block()

        guard.withLock {
            entries[key] = Entry(value, System.currentTimeMillis() + ttlSeconds * 1_000)
        }
        return Result(value, false)
    }

    suspend fun clear() = guard.withLock { entries.clear() }
}
