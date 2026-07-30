package com.paladex.ex.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standard trading card: 2.5in × 3.5in. */
private const val CARD_ASPECT = 2.5f / 3.5f

/** Fraction of the frame width the guide box occupies. */
private const val GUIDE_WIDTH_FRACTION = 0.78f

/**
 * Live camera view with a card-shaped guide.
 *
 * Preview and capture are both pinned to 4:3 and the preview is letterboxed
 * rather than cropped, so what you see is exactly what gets captured. That is
 * what lets the crop below be a simple centred fraction: if the two differed,
 * the guide box would not correspond to any fixed region of the captured frame.
 */
@Composable
fun CameraCapture(
    onCapture: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            error = "Camera access denied. Allow it in Settings, or pick a photo instead."
        }
    }

    // Fallback for devices without a usable camera, and for cards you have
    // already photographed.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap == null) error = "Could not read that image." else onCapture(bitmap)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            if (hasPermission) {
                AndroidCameraPreview(imageCapture, lifecycleOwner) { error = it }
            }

            GuideOverlay(Modifier.fillMaxSize())

            if (!hasPermission) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            error ?: "Camera access is needed to scan a card.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Allow camera")
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val full = captureBitmap(context, imageCapture)
                            onCapture(cropToGuide(full))
                        } catch (e: Exception) {
                            error = e.message ?: "Capture failed."
                        }
                    }
                },
                enabled = enabled && hasPermission,
                modifier = Modifier.weight(1f),
            ) { Text("Scan card") }

            OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = enabled) {
                Text("Upload")
            }
        }

        error?.let {
            if (hasPermission) Text(it, color = Bad, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Fill the frame with the card. The name and the number in the bottom corner both need to be readable.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AndroidCameraPreview(
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onError: (String) -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply {
                // Letterbox rather than crop, so the preview and the captured
                // frame cover exactly the same field of view.
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view ->
            val providerFuture = ProcessCameraProvider.getInstance(view.context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build(),
                        )
                        .build()
                        .also { it.surfaceProvider = view.surfaceProvider }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                } catch (e: Exception) {
                    onError(e.message ?: "Could not start the camera.")
                }
            }, ContextCompat.getMainExecutor(view.context))
        },
    )
}

/**
 * The card outline, plus dashed bands marking where OCR actually reads, so the
 * user knows which parts have to be legible.
 */
@Composable
private fun GuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val guideWidth = size.width * GUIDE_WIDTH_FRACTION
        val guideHeight = guideWidth / CARD_ASPECT
        val left = (size.width - guideWidth) / 2f
        val top = (size.height - guideHeight) / 2f

        // Dim outside the guide by drawing four bands around it. Punching a
        // hole with BlendMode.Clear would need an offscreen compositing layer;
        // without one it renders as solid black over the viewfinder.
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, size = androidx.compose.ui.geometry.Size(size.width, top))
        drawRect(
            scrim,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top + guideHeight),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - top - guideHeight),
        )
        drawRect(
            scrim,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(left, guideHeight),
        )
        drawRect(
            scrim,
            topLeft = androidx.compose.ui.geometry.Offset(left + guideWidth, top),
            size = androidx.compose.ui.geometry.Size(size.width - left - guideWidth, guideHeight),
        )

        drawRect(
            Accent.copy(alpha = 0.8f),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(guideWidth, guideHeight),
            style = Stroke(width = 4f),
        )

        // The two OCR bands, matching CardRecogniser's region fractions.
        listOf(0.02f to 0.16f, 0.83f to 0.17f).forEach { (y, height) ->
            drawRect(
                Accent.copy(alpha = 0.35f),
                topLeft = androidx.compose.ui.geometry.Offset(left, top + guideHeight * y),
                size = androidx.compose.ui.geometry.Size(guideWidth, guideHeight * height),
                style = Stroke(width = 2f),
            )
        }
    }
}

/** Take a photo and decode it, applying the sensor's rotation. */
private suspend fun captureBitmap(context: Context, imageCapture: ImageCapture): Bitmap =
    suspendCoroutine { continuation ->
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        continuation.resume(image.decodeRotatedBitmap())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            },
        )
    }

/**
 * Decode the captured JPEG and apply the sensor rotation.
 *
 * Deliberately not named `toBitmap` — CameraX defines its own `ImageProxy`
 * member with that name, and a member always wins over an extension, so the
 * rotation below would silently never run.
 */
private fun ImageProxy.decodeRotatedBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalStateException("Camera returned an undecodable frame.")

    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return decoded

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

/**
 * Crop the captured frame to the guide box.
 *
 * The same centred fraction the overlay draws. Correct because preview and
 * capture share a 4:3 aspect and the preview is letterboxed, so the guide
 * occupies the same relative rect in both.
 */
private fun cropToGuide(source: Bitmap): Bitmap {
    val guideWidth = source.width * GUIDE_WIDTH_FRACTION
    val guideHeight = guideWidth / CARD_ASPECT

    // If the card box would overflow a frame that is not as tall as expected,
    // fall back to fitting by height so we never ask for pixels off the bitmap.
    val height = minOf(guideHeight, source.height.toFloat())
    val width = minOf(guideWidth, height * CARD_ASPECT)

    val left = ((source.width - width) / 2f).toInt().coerceAtLeast(0)
    val top = ((source.height - height) / 2f).toInt().coerceAtLeast(0)

    return Bitmap.createBitmap(
        source,
        left,
        top,
        width.toInt().coerceAtMost(source.width - left),
        height.toInt().coerceAtMost(source.height - top),
    )
}
