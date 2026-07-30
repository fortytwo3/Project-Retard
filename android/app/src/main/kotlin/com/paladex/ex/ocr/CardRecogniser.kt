package com.paladex.ex.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.paladex.ex.core.CardTextParser
import com.paladex.ex.core.ScanParse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR via ML Kit.
 *
 * Nothing leaves the phone: the recogniser runs against a bundled model, and
 * only the handful of characters the parser extracts are ever sent anywhere.
 */
class CardRecogniser {

    private val recogniser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * The two places worth reading, as fractions of the cropped card.
     *
     * Recognising the whole card wastes time on attack and flavour text, and
     * gives the name-ranking heuristic dozens of extra lines to be wrong about.
     * The name band and the bottom strip are where the fields we need are
     * printed on every modern layout.
     */
    private val regions = listOf(
        "title" to Rect(0.02f, 0.02f, 0.96f, 0.16f),
        "footer" to Rect(0.02f, 0.83f, 0.96f, 0.17f),
    )

    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)

    private fun crop(source: Bitmap, rect: Rect): Bitmap {
        val x = (source.width * rect.x).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * rect.y).toInt().coerceIn(0, source.height - 1)
        val width = (source.width * rect.width).toInt().coerceAtLeast(1)
            .coerceAtMost(source.width - x)
        val height = (source.height * rect.height).toInt().coerceAtLeast(1)
            .coerceAtMost(source.height - y)

        return Bitmap.createBitmap(source, x, y, width, height)
    }

    private suspend fun recognise(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            recogniser.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { continuation.resume(it.text) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    /**
     * OCR a cropped card image and parse it into searchable fields.
     *
     * @param onProgress called with a short status for the scanning overlay.
     */
    suspend fun scan(card: Bitmap, onProgress: (String) -> Unit = {}): ScanParse {
        val texts = mutableListOf<String>()

        for ((name, rect) in regions) {
            onProgress("Reading $name…")

            val region = crop(card, rect)
            val text = try {
                recognise(region)
            } finally {
                // createBitmap may return the source itself for a full-size
                // rect; never recycle the caller's bitmap.
                if (region !== card) region.recycle()
            }

            if (text.isNotBlank()) texts += text.trim()
        }

        // ML Kit does not expose a confidence score on the Latin recogniser, so
        // the parser is told there is none rather than being handed a fake one.
        return CardTextParser.parse(texts.joinToString("\n"), confidence = null)
    }

    fun close() = recogniser.close()
}
