package com.smilesnap.app.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Extracts frames from a video and scores each one for smile quality.
 *
 * Uses MediaMetadataRetriever for frame extraction and [SmileAnalyzer]
 * for ML Kit-based scoring. Emits progress updates as a Flow.
 *
 * Deduplication: Uses OPTION_CLOSEST for true frame extraction and
 * compares perceptual hashes to ensure all top candidates are visually
 * distinct frames (fixes the keyframe-snapping duplicate bug).
 */
class VideoProcessor(private val context: Context) {

    private val analyzer = SmileAnalyzer()

    data class ProcessingProgress(
        val framesChecked: Int,
        val totalFrames: Int,
        val percentComplete: Float,
        val currentBestScore: Float,
        val topCandidates: List<FrameScore>
    )

    /**
     * Process a video URI and emit progress updates.
     *
     * @param videoUri  URI of the video (content:// or file://)
     * @param topN      Number of top candidates to keep (default 10)
     * @param sampleIntervalMs  Time between sampled frames in ms (default ~285ms = ~3.5 fps)
     */
    fun processVideo(
        videoUri: Uri,
        topN: Int = 10,
        sampleIntervalMs: Long = 285L
    ): Flow<ProcessingProgress> = flow {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs == 0L) {
                emit(ProcessingProgress(0, 0, 1f, 0f, emptyList()))
                return@flow
            }

            val totalSamples = (durationMs / sampleIntervalMs).toInt().coerceAtLeast(1)
            val candidates = mutableListOf<FrameScore>()
            // Track perceptual hashes of frames already in candidates to skip dupes
            val seenHashes = mutableSetOf<Long>()
            var framesChecked = 0

            var timeUs = 0L
            val endUs = durationMs * 1000L

            while (timeUs < endUs) {
                // OPTION_CLOSEST gets the actual nearest frame, not just keyframes.
                // This is slower but gives truly distinct frames instead of duplicates.
                val bitmap: Bitmap? = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (bitmap != null) {
                    val hash = perceptualHash(bitmap)

                    // Skip if this frame is visually identical to one we've already scored
                    if (isDuplicate(hash, seenHashes)) {
                        bitmap.recycle()
                    } else {
                        val frameIndex = (timeUs / 1000L / sampleIntervalMs).toInt()
                        val result = analyzer.analyzeFrame(
                            bitmap = bitmap,
                            frameIndex = frameIndex,
                            timestampMs = timeUs / 1000L
                        )

                        if (result.compositeScore > 0f) {
                            seenHashes.add(hash)
                            candidates.add(result)
                            // Keep extra buffer beyond topN so we have room after dedup
                            candidates.sortByDescending { it.compositeScore }
                            while (candidates.size > topN + 10) {
                                val removed = candidates.removeAt(candidates.lastIndex)
                                removed.bitmap?.recycle()
                            }
                        } else {
                            bitmap.recycle()
                        }
                    }
                }

                framesChecked++
                val progress = (timeUs.toFloat() / endUs).coerceAtMost(1f)

                emit(
                    ProcessingProgress(
                        framesChecked = framesChecked,
                        totalFrames = totalSamples,
                        percentComplete = progress,
                        currentBestScore = candidates.firstOrNull()?.compositeScore ?: 0f,
                        topCandidates = candidates.take(topN).toList()
                    )
                )

                timeUs += sampleIntervalMs * 1000L  // convert ms -> μs
            }

            // Final emit with completed results
            emit(
                ProcessingProgress(
                    framesChecked = framesChecked,
                    totalFrames = totalSamples,
                    percentComplete = 1f,
                    currentBestScore = candidates.firstOrNull()?.compositeScore ?: 0f,
                    topCandidates = candidates.take(topN).toList()
                )
            )

        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Compute a fast perceptual hash (difference hash / dHash).
     * Resizes the image to 9x8 grayscale, then compares adjacent pixels
     * to produce a 64-bit hash. Similar images produce identical or
     * near-identical hashes.
     */
    private fun perceptualHash(bitmap: Bitmap): Long {
        // Scale down to 9x8 for a 64-bit hash
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(9 * 8)
        small.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        small.recycle()

        // Convert to grayscale values
        val gray = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Build hash: for each row, compare pixel[x] < pixel[x+1]
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (gray[y * 9 + x] < gray[y * 9 + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /**
     * Check if a hash is a duplicate of any seen hash.
     * Uses Hamming distance <= 5 to catch near-identical frames
     * (e.g. slight compression differences from the same moment).
     */
    private fun isDuplicate(hash: Long, seenHashes: Set<Long>): Boolean {
        for (seen in seenHashes) {
            val xor = hash xor seen
            val hammingDist = java.lang.Long.bitCount(xor)
            if (hammingDist <= 5) return true
        }
        return false
    }

    fun close() {
        analyzer.close()
    }
}
