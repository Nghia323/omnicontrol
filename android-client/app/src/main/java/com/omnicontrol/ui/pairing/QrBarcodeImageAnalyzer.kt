package com.omnicontrol.ui.pairing

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class QrBarcodeImageAnalyzer(
    private val onQrDetected: (rawText: String) -> Unit
) : ImageAnalysis.Analyzer {
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)
    private val isPaused = AtomicBoolean(false)

    @Volatile private var lastAnalyzedText: String? = null
    @Volatile private var lastAnalyzedAtMs: Long = 0L

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        if (!paused) lastAnalyzedText = null
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isPaused.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (isPaused.get()) return@addOnSuccessListener
                val rawValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    ?: return@addOnSuccessListener
                val now = SystemClock.elapsedRealtime()
                if (rawValue == lastAnalyzedText && now - lastAnalyzedAtMs < 1200L) {
                    return@addOnSuccessListener
                }
                lastAnalyzedText = rawValue
                lastAnalyzedAtMs = now
                onQrDetected(rawValue)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        scanner.close()
    }
}
