package com.example.audiotrimcrop

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes audio to extract amplitude data for waveform display.
 * Returns a fixed-size float array (values 0..1) plus the total duration in ms.
 */
object AudioDecoder {

    fun decodeWaveform(
        context: Context,
        uri: Uri,
        numBuckets: Int
    ): Pair<FloatArray, Long>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            return null
        }

        // Find first audio track
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return null
        }

        extractor.selectTrack(trackIndex)

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        val durationMs = durationUs / 1000L

        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val totalFrames = max(1L, durationUs * sampleRate / 1_000_000L)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val decoder = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            extractor.release()
            return null
        }

        val buckets = FloatArray(numBuckets)
        var decodedFrames = 0L
        var inputDone = false
        var outputDone = false
        var actualChannels = channels
        val info = MediaCodec.BufferInfo()

        try {
            while (!outputDone) {
                // Feed compressed input
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(10_000L)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain PCM output
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            outBuf.get(data)
                            val frameBytes = actualChannels * 2   // 16-bit PCM
                            if (frameBytes > 0) {
                                val framesInBuf = data.size / frameBytes
                                for (f in 0 until framesInBuf) {
                                    var peak = 0f
                                    for (ch in 0 until actualChannels) {
                                        val b = f * frameBytes + ch * 2
                                        if (b + 1 < data.size) {
                                            val s = (data[b + 1].toInt() shl 8) or
                                                    (data[b].toInt() and 0xFF)
                                            peak = max(peak, abs(s) / 32768f)
                                        }
                                    }
                                    val bucket = ((decodedFrames + f) * numBuckets / totalFrames)
                                        .toInt().coerceIn(0, numBuckets - 1)
                                    buckets[bucket] = max(buckets[bucket], peak)
                                }
                                decodedFrames += framesInBuf
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = decoder.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            actualChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) outputDone = true
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }

        return Pair(smooth(buckets), durationMs)
    }

    /** Light smoothing + minimum bar height so silent gaps still show. */
    private fun smooth(a: FloatArray): FloatArray {
        val out = FloatArray(a.size)
        for (i in a.indices) {
            val lo = (i - 2).coerceAtLeast(0)
            val hi = (i + 2).coerceAtMost(a.size - 1)
            var s = 0f
            for (j in lo..hi) s += a[j]
            out[i] = max(s / (hi - lo + 1), 0.03f)
        }
        return out
    }
}
