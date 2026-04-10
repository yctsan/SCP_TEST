package com.example.audiotrimcrop

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Trims audio files to [startUs]..[endUs] (microseconds).
 *
 * Strategy:
 *  - AAC/M4A  → direct frame copy via MediaExtractor + MediaMuxer (lossless, fast)
 *  - MP3/other → decode to PCM with MediaCodec, re-encode to AAC, mux to M4A
 *
 * Output is always an .m4a container. MP3 inputs are re-encoded (slight quality
 * difference is unavoidable without a native MP3 muxer).
 */
object AudioTrimmer {

    /** Trim to a [File] (convenience wrapper). */
    fun trim(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startUs: Long,
        endUs: Long,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        return FileOutputStream(outputFile).use { fos ->
            trim(context, inputUri, fos.fd, startUs, endUs, onProgress)
        }
    }

    /** Trim, writing output to an already-opened [FileDescriptor] (e.g. from ACTION_CREATE_DOCUMENT). */
    fun trim(
        context: Context,
        inputUri: Uri,
        outputFd: FileDescriptor,
        startUs: Long,
        endUs: Long,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
        } catch (e: Exception) {
            return false
        }

        var trackIdx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i; fmt = f; break
            }
        }
        if (trackIdx < 0 || fmt == null) { extractor.release(); return false }

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val isAac = mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/mp4a-latm"

        return if (isAac) {
            directCopy(extractor, trackIdx, fmt, outputFd, startUs, endUs, onProgress)
        } else {
            extractor.release()
            transcode(context, inputUri, outputFd, startUs, endUs, onProgress)
        }
    }

    // ── Direct copy (AAC / M4A) ──────────────────────────────────────────────

    private fun directCopy(
        extractor: MediaExtractor,
        trackIdx: Int,
        fmt: MediaFormat,
        out: FileDescriptor,
        startUs: Long,
        endUs: Long,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
        extractor.selectTrack(trackIdx)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrack = muxer.addTrack(fmt)
        muxer.start()

        val buf = ByteBuffer.allocate(512 * 1024)
        val info = MediaCodec.BufferInfo()
        val span = (endUs - startUs).coerceAtLeast(1L)
        var firstPts = Long.MIN_VALUE

        try {
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                if (firstPts == Long.MIN_VALUE) firstPts = pts

                info.offset = 0
                info.size = n
                info.presentationTimeUs = pts - firstPts
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buf, info)

                onProgress?.invoke(((pts - startUs) * 99 / span).toInt().coerceIn(0, 99))
                extractor.advance()
            }
        } finally {
            muxer.stop(); muxer.release(); extractor.release()
        }
        onProgress?.invoke(100)
        return true
    }

    // ── Transcode (MP3 → decode PCM → encode AAC → M4A) ─────────────────────

    private fun transcode(
        context: Context,
        inputUri: Uri,
        out: FileDescriptor,
        startUs: Long,
        endUs: Long,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
        // ── Decode phase ──────────────────────────────────────────────────────
        val extractor = MediaExtractor()
        extractor.setDataSource(context, inputUri, null)

        var trackIdx = -1; var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i; fmt = f; break
            }
        }
        if (trackIdx < 0 || fmt == null) { extractor.release(); return false }

        extractor.selectTrack(trackIdx)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(fmt, null, null, 0)
        decoder.start()

        var sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        // Collect PCM chunks only for the requested range
        data class Chunk(val data: ByteArray, val ptsUs: Long)
        val pcmChunks = mutableListOf<Chunk>()
        var inputDone = false; var outputDone = false
        val info = MediaCodec.BufferInfo()
        val span = (endUs - startUs).coerceAtLeast(1L)

        while (!outputDone) {
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val pts = extractor.sampleTime
                    if (pts < 0 || pts > endUs) {
                        decoder.queueInputBuffer(idx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val b = decoder.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(b, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, n, pts, 0)
                            extractor.advance()
                        }
                    }
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx >= 0 -> {
                    val ob = decoder.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0 &&
                        info.presentationTimeUs >= startUs) {
                        val d = ByteArray(info.size); ob.get(d)
                        pcmChunks.add(Chunk(d, info.presentationTimeUs - startUs))
                        onProgress?.invoke(
                            ((info.presentationTimeUs - startUs) * 49 / span)
                                .toInt().coerceIn(0, 49)
                        )
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    decoder.outputFormat.also { nf ->
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) outputDone = true
            }
        }
        decoder.stop(); decoder.release(); extractor.release()

        if (pcmChunks.isEmpty()) return false

        // ── Encode phase ──────────────────────────────────────────────────────
        val encoderFmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encoderFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1; var muxerStarted = false

        // Flatten PCM to a single buffer
        val totalBytes = pcmChunks.sumOf { it.data.size }
        val allPcm = ByteArray(totalBytes)
        var wp = 0
        for (c in pcmChunks) { c.data.copyInto(allPcm, wp); wp += c.data.size }
        pcmChunks.clear()

        val frameBytes = 1024 * channels * 2   // AAC frame = 1024 PCM samples
        var readPos = 0
        var encInputDone = false; var encOutputDone = false
        val encInfo = MediaCodec.BufferInfo()

        while (!encOutputDone) {
            if (!encInputDone) {
                val idx = encoder.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    if (readPos >= allPcm.size) {
                        encoder.queueInputBuffer(idx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encInputDone = true
                    } else {
                        val toWrite = minOf(frameBytes, allPcm.size - readPos)
                        val ib = encoder.getInputBuffer(idx)!!
                        ib.clear(); ib.put(allPcm, readPos, toWrite)
                        val pts = readPos.toLong() / (channels * 2) * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(idx, 0, toWrite, pts, 0)
                        readPos += toWrite
                        onProgress?.invoke(
                            50 + (readPos.toLong() * 49 / allPcm.size).toInt().coerceIn(0, 49)
                        )
                    }
                }
            }
            val outIdx = encoder.dequeueOutputBuffer(encInfo, 10_000L)
            when {
                outIdx >= 0 -> {
                    val ob = encoder.getOutputBuffer(outIdx)
                    val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxerStarted && ob != null && encInfo.size > 0 && !isConfig)
                        muxer.writeSampleData(muxTrack, ob, encInfo)
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        encOutputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (encInputDone) encOutputDone = true
            }
        }
        encoder.stop(); encoder.release()
        if (muxerStarted) { muxer.stop() }
        muxer.release()

        onProgress?.invoke(100)
        return muxerStarted
    }
}
