package com.smilesnap.app.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicConvolve3x3
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min

/**
 * Scores a single image frame for smile quality and sharpness.
 *
 * Uses Google ML Kit for face/smile detection and a Laplacian-based
 * metric for blur detection — mirroring the original Python algorithm
 * but with much better smile accuracy from ML Kit's probability scores.
 */
class SmileAnalyzer {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Analyze a single bitmap frame. Returns a [FrameScore].
     */
    suspend fun analyzeFrame(bitmap: Bitmap, frameIndex: Int, timestampMs: Long): FrameScore {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces: List<Face> = try {
            detector.process(image).await()
        } catch (e: Exception) {
            emptyList()
        }

        if (faces.isEmpty()) {
            return FrameScore(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                compositeScore = 0f,
                numFaces = 0,
                faceDetails = emptyList(),
                bitmap = null
            )
        }

        val faceDetails = faces.map { face -> scoreFace(face, bitmap) }
        var totalScore = faceDetails.sumOf { it.faceScore.toDouble() }.toFloat()

        // Bonus for multiple faces ALL smiling (same logic as Python version)
        val smilingFaces = faceDetails.count { it.smileProbability > 0.5f }
        if (faceDetails.size > 1 && smilingFaces == faceDetails.size) {
            totalScore *= 1f + 0.15f * (smilingFaces - 1)
        }

        return FrameScore(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            compositeScore = totalScore,
            numFaces = faceDetails.size,
            faceDetails = faceDetails,
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        )
    }

    private fun scoreFace(face: Face, bitmap: Bitmap): FaceDetail {
        val smileProb = face.smilingProbability ?: 0f

        // Sharpness: compute Laplacian variance on the face region
        val bounds = face.boundingBox
        val safeBounds = Rect(
            bounds.left.coerceAtLeast(0),
            bounds.top.coerceAtLeast(0),
            bounds.right.coerceAtMost(bitmap.width),
            bounds.bottom.coerceAtMost(bitmap.height)
        )

        val sharpness = if (safeBounds.width() > 0 && safeBounds.height() > 0) {
            computeSharpness(bitmap, safeBounds)
        } else {
            0f
        }
        val sharpnessNorm = min(sharpness / 500f, 1f)

        // Composite per-face score (0–100 scale, matching Python version)
        //   Smile probability:  0-50 points  (ML Kit gives 0.0–1.0)
        //   Sharpness:          0-25 points
        //   Face size bonus:    0-25 points  (bigger face = likely closer/better shot)
        val faceSizeRatio = (safeBounds.width().toFloat() * safeBounds.height()) /
                (bitmap.width.toFloat() * bitmap.height)
        val faceSizeNorm = min(faceSizeRatio * 10f, 1f) // rough normalisation

        val faceScore = smileProb * 50f + sharpnessNorm * 25f + faceSizeNorm * 25f

        return FaceDetail(
            boundingBox = safeBounds,
            smileProbability = smileProb,
            sharpness = sharpness,
            faceSizeRatio = faceSizeRatio,
            faceScore = faceScore
        )
    }

    /**
     * Compute a sharpness metric using Laplacian-like variance.
     * Higher = sharper (less blurry). Same concept as cv2.Laplacian().var()
     */
    private fun computeSharpness(bitmap: Bitmap, region: Rect): Float {
        // Crop to face region
        val cropped = Bitmap.createBitmap(
            bitmap, region.left, region.top, region.width(), region.height()
        )

        // Convert to grayscale pixel array
        val w = cropped.width
        val h = cropped.height
        val pixels = IntArray(w * h)
        cropped.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = FloatArray(w * h) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }

        // Apply 3x3 Laplacian kernel: [0,1,0; 1,-4,1; 0,1,0]
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val laplacian = (
                        gray[idx - w] +          // top
                        gray[idx - 1] +           // left
                        -4f * gray[idx] +         // center
                        gray[idx + 1] +           // right
                        gray[idx + w]             // bottom
                        ).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0f

        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        cropped.recycle()
        return variance.toFloat()
    }

    fun close() {
        detector.close()
    }
}

/** Score result for a single video frame. */
data class FrameScore(
    val frameIndex: Int,
    val timestampMs: Long,
    val compositeScore: Float,
    val numFaces: Int,
    val faceDetails: List<FaceDetail>,
    val bitmap: Bitmap?
)

/** Per-face breakdown within a frame. */
data class FaceDetail(
    val boundingBox: Rect,
    val smileProbability: Float,
    val sharpness: Float,
    val faceSizeRatio: Float,
    val faceScore: Float
)
