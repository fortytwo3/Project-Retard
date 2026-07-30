package com.paladex.ex.core.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Non-2xx response from an upstream. Distinguishes "site said no" from "site said nothing". */
class HttpStatusException(val status: Int, val url: String) :
    IOException("HTTP $status for $url")

/**
 * Outbound HTTP with the manners a scraper owes the sites it reads: one request
 * per host at a time, a floor on the gap between them, a real User-Agent, and a
 * hard timeout so a hung socket cannot pin a coroutine open forever.
 */
class Http(
    private val minIntervalMillis: Long = 1_500,
    client: OkHttpClient? = null,
) {
    /** Chrome on macOS. Sites serve bot-flavoured HTML to anything obviously automated. */
    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** One mutex per host, so requests to a single host serialise. */
    private val hostLocks = mutableMapOf<String, Mutex>()
    private val hostLocksGuard = Mutex()
    private val lastRequestAt = mutableMapOf<String, Long>()

    private suspend fun lockFor(host: String): Mutex =
        hostLocksGuard.withLock { hostLocks.getOrPut(host) { Mutex() } }

    /**
     * Serialise work against [host], leaving at least [minIntervalMillis]
     * between consecutive requests.
     */
    private suspend fun <T> throttled(host: String, block: suspend () -> T): T =
        lockFor(host).withLock {
            val since = System.currentTimeMillis() - (lastRequestAt[host] ?: 0L)
            if (since < minIntervalMillis) delay(minIntervalMillis - since)
            try {
                block()
            } finally {
                lastRequestAt[host] = System.currentTimeMillis()
            }
        }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, request.url.toString())
            }
            response.body?.string().orEmpty()
        }
    }

    /**
     * Fetch with retry. Retries only on 429 and 5xx — other 4xx will not
     * improve by asking again.
     */
    private suspend fun withRetry(request: Request, retries: Int): String {
        var last: Exception? = null

        for (attempt in 0..retries) {
            // 1s, 2s, 4s — enough to clear a transient 429 without stalling the UI.
            if (attempt > 0) delay(1_000L shl (attempt - 1))

            try {
                return execute(request)
            } catch (e: HttpStatusException) {
                if (e.status != 429 && e.status < 500) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }

        throw last ?: IOException("Request to ${request.url} failed")
    }

    /**
     * @param immediate skips the per-host throttle. Use for first-party APIs,
     *   not for scraping.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        immediate: Boolean = false,
        retries: Int = 2,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        return if (immediate) {
            withRetry(request, retries)
        } else {
            throttled(request.url.host) { withRetry(request, retries) }
        }
    }

    suspend fun postForm(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
            .header("User-Agent", userAgent)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        return withRetry(request, 1)
    }
}
