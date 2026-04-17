package com.example.audiotrimcrop

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Trims audio files to [startUs]..[endUs] (microseconds).
 *
 * Strategy:
 *  - AAC input + AAC output → direct frame copy (lossless, fast)
 *  - audio/raw (WAV)        → read raw PCM to temp file → encode
 *  - Everything else        → decode PCM to temp file   → encode
 *
 * Using a temp file instead of in-memory buffers prevents OOM on large files.
 */
object AudioTrimmer {

    /** Supported output codecs/formats. */
    enum class OutputCodec(
        val encoderMime: String,
        val bitRate: Int,
        val muxerFormat: Int,
        val extension: String,
        val label: String,
        val pickerMime: String
    ) {
        AAC_192(
            MediaFormat.MIMETYPE_AUDIO_AAC, 192_000,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a",
            "AAC 192 kbps · M4A (best quality)", "audio/mp4"
        ),
        AAC_128(
            MediaFormat.MIMETYPE_AUDIO_AAC, 128_000,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a",
            "AAC 128 kbps · M4A", "audio/mp4"
        ),
        AAC_64(
            MediaFormat.MIMETYPE_AUDIO_AAC, 64_000,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a",
            "AAC 64 kbps · M4A (smaller file)", "audio/mp4"
        ),
        OPUS_128(
            "audio/opus", 128_000,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "ogg",
            "Opus 128 kbps · OGG (excellent quality)", "audio/ogg"
        ),
        OPUS_64(
            "audio/opus", 64_000,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "ogg",
            "Opus 64 kbps · OGG (smallest file)", "audio/ogg"
        ),
    }

    /** Trim to a [File] (convenience wrapper). */
    fun trim(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startUs: Long,
        endUs: Long,
        outputCodec: OutputCodec = OutputCodec.AAC_192,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        return FileOutputStream(outputFile).use { fos ->
            trim(context, inputUri, fos.fd, startUs, endUs, outputCodec, onProgress)
        }
    }

    /** Trim, writing output to an already-opened [FileDescriptor] (e.g. from ACTION_CREATE_DOCUMENT). */
    fun trim(
        context: Context,
        inputUri: Uri,
        outputFd: FileDescriptor,
        startUs: Long,
        endUs: Long,
        outputCodec: OutputCodec = OutputCodec.AAC_192,
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

        val inputMime = fmt.getString(MediaFormat.KEY_MIME)!!
        val inputIsAac = inputMime == MediaFormat.MIMETYPE_AUDIO_AAC || inputMime == "audio/mp4a-latm"
        val outputIsAac = outputCodec.encoderMime == MediaFormat.MIMETYPE_AUDIO_AAC
        val inputIsRaw = inputMime == "audio/raw"

        return when {
            inputIsAac && outputIsAac ->
                directCopy(extractor, trackIdx, fmt, outputFd, startUs, endUs, onProgress)
            inputIsRaw -> {
                // WAV / raw PCM — MediaCodec has no decoder for audio/raw; read samples directly
                val sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                extractRawAndEncode(
                    extractor, trackIdx, sampleRate, channels,
                    context, outputFd, startUs, endUs, outputCodec, onProgress
                )
            }
            else -> {
                extractor.release()
                transcode(context, inputUri, outputFd, startUs, endUs, outputCodec, onProgress)
            }
        }
    }

    // ── Direct copy (AAC → AAC, lossless) ────────────────────────────────────

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

    // ── Raw PCM path (WAV / audio/raw) ────────────────────────────────────────

    private fun extractRawAndEncode(
        extractor: MediaExtractor,
        trackIdx: Int,
        sampleRate: Int,
        channels: Int,
        context: Context,
        out: FileDescriptor,
        startUs: Long,
        endUs: Long,
        outputCodec: OutputCodec,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
        extractor.selectTrack(trackIdx)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val span = (endUs - startUs).coerceAtLeast(1L)
        val tmpFile = File(context.cacheDir, "raw_pcm_${System.currentTimeMillis()}.tmp")
        try {
            FileOutputStream(tmpFile).use { pcmOut ->
                val buf = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    buf.clear()
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    val pts = extractor.sampleTime
                    if (pts > endUs) break
                    pcmOut.write(buf.array(), 0, n)
                    onProgress?.invoke(((pts - startUs) * 49 / span).toInt().coerceIn(0, 49))
                    extractor.advance()
                }
            }
            extractor.release()

            if (tmpFile.length() == 0L) return false
            return encodePcmFromFile(tmpFile, sampleRate, channels, out, outputCodec, onProgress)
        } finally {
            tmpFile.delete()
        }
    }

    // ── Transcode (decode PCM → encode) — streams via temp file ──────────────

    private fun transcode(
        context: Context,
        inputUri: Uri,
        out: FileDescriptor,
        startUs: Long,
        endUs: Long,
        outputCodec: OutputCodec,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
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

        val span = (endUs - startUs).coerceAtLeast(1L)
        val tmpFile = File(context.cacheDir, "pcm_${System.currentTimeMillis()}.tmp")

        try {
            FileOutputStream(tmpFile).use { pcmOut ->
                var inputDone = false; var outputDone = false
                val info = MediaCodec.BufferInfo()

                while (!outputDone) {
                    if (!inputDone) {
                        val idx = decoder.dequeueInputBuffer(10_000L)
                        if (idx >= 0) {
                            val pts = extractor.sampleTime
                            if (pts < 0 || pts > endUs) {
                                decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val b = decoder.getInputBuffer(idx)!!
                                val n = extractor.readSampleData(b, 0)
                                if (n < 0) {
                                    decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                            if (ob != null && info.size > 0 && info.presentationTimeUs >= startUs) {
                                val d = ByteArray(info.size); ob.get(d)
                                pcmOut.write(d)
                                onProgress?.invoke(
                                    ((info.presentationTimeUs - startUs) * 49 / span).toInt().coerceIn(0, 49)
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
            }
        } finally {
            decoder.stop(); decoder.release(); extractor.release()
        }

        if (tmpFile.length() == 0L) { tmpFile.delete(); return false }

        return try {
            encodePcmFromFile(tmpFile, sampleRate, channels, out, outputCodec, onProgress)
        } finally {
            tmpFile.delete()
        }
    }

    // ── Shared PCM-file → encode streaming helper ─────────────────────────────

    private fun encodePcmFromFile(
        pcmFile: File,
        sampleRate: Int,
        channels: Int,
        out: FileDescriptor,
        outputCodec: OutputCodec,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
        val encoderFmt = MediaFormat.createAudioFormat(outputCodec.encoderMime, sampleRate, channels)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, outputCodec.bitRate)
                if (outputCodec.encoderMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                }
            }

        val encoder = MediaCodec.createEncoderByType(outputCodec.encoderMime)
        encoder.configure(encoderFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(out, outputCodec.muxerFormat)
        var muxTrack = -1; var muxerStarted = false

        val totalBytes = pcmFile.length()
        val frameBytes = 1024 * channels * 2
        var bytesConsumed = 0L
        var encInputDone = false; var encOutputDone = false
        val encInfo = MediaCodec.BufferInfo()

        BufferedInputStream(FileInputStream(pcmFile), 256 * 1024).use { pcmIn ->
            while (!encOutputDone) {
                if (!encInputDone) {
                    val idx = encoder.dequeueInputBuffer(10_000L)
                    if (idx >= 0) {
                        val ib = encoder.getInputBuffer(idx)!!
                        ib.clear()
                        val toRead = minOf(frameBytes, ib.capacity())
                        val tmp = ByteArray(toRead)
                        val n = pcmIn.read(tmp, 0, toRead)
                        if (n <= 0) {
                            encoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInputDone = true
                        } else {
                            ib.put(tmp, 0, n)
                            val pts = bytesConsumed * 1_000_000L / (channels.toLong() * 2L * sampleRate)
                            encoder.queueInputBuffer(idx, 0, n, pts, 0)
                            bytesConsumed += n
                            if (totalBytes > 0)
                                onProgress?.invoke(
                                    50 + (bytesConsumed * 49L / totalBytes).toInt().coerceIn(0, 49)
                                )
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(encInfo, 10_000L)
                when {
                    outIdx >= 0 -> {
                        val ob = encoder.getOutputBuffer(outIdx)
                        val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        // AAC codec config is handled by the M4A container — skip it as a sample.
                        // Opus ID header belongs in OGG — pass it through.
                        val skip = isConfig && (outputCodec.encoderMime == MediaFormat.MIMETYPE_AUDIO_AAC)
                        if (muxerStarted && ob != null && encInfo.size > 0 && !skip)
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
        }

        encoder.stop(); encoder.release()
        if (muxerStarted) { muxer.stop() }
        muxer.release()

        onProgress?.invoke(100)
        return muxerStarted
    }
}
