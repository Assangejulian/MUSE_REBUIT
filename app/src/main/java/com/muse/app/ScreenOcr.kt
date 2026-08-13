package com.muse.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.muse.agent.OcrHit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ScreenOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun read(bitmap: Bitmap): List<OcrHit> {
        val (scaled, factor) = downscale(bitmap, 1280)
        val image = InputImage.fromBitmap(scaled, 0)
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { text -> if (cont.isActive) cont.resume(text) }
                .addOnFailureListener { err -> if (cont.isActive) cont.resumeWithException(err) }
        }
        if (scaled !== bitmap) scaled.recycle()
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val text = line.text.replace('\n', ' ').trim()
            if (text.isEmpty()) {
                null
            } else {
                OcrHit(
                    text = text,
                    cx = (box.centerX() * factor).toInt(),
                    cy = (box.centerY() * factor).toInt(),
                )
            }
        }
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Pair<Bitmap, Float> {
        val edge = maxOf(src.width, src.height)
        if (edge <= maxEdge) return src to 1f
        val factor = edge.toFloat() / maxEdge
        val w = (src.width / factor).toInt().coerceAtLeast(1)
        val h = (src.height / factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true) to factor
    }
}
