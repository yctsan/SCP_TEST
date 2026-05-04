package com.example.audiotrimcrop

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrimActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var waveform: WaveformView
    private lateinit var seekBar: SeekBar
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnExport: Button
    private lateinit var tvStart: TextView
    private lateinit var tvEnd: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvSelLen: TextView
    private lateinit var tvHint: TextView
    private lateinit var pbLoad: ProgressBar
    private lateinit var pbExport: ProgressBar
    private lateinit var loadingOverlay: View
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView

    // ── Audio ─────────────────────────────────────────────────────────────────
    private var audioUri: Uri? = null
    private var player: MediaPlayer? = null
    private var playing = false
    private var isMp3 = false
    private var playbackSpeed = 1.0f

    // Codec chosen by the user before the file picker opens
    private var pendingCodecOption: CodecOption? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!playing) return
            try {
                val pos = p.currentPosition.toLong()
                waveform.playbackPositionMs = pos
                updateSeekBar(pos)
                if (pos >= waveform.endMs) { stopPlayback(); return }
            } catch (_: IllegalStateException) { return }
            handler.postDelayed(this, 40)
        }
    }

    // ── Codec options ─────────────────────────────────────────────────────────

    private data class CodecOption(
        val codec: AudioTrimmer.OutputCodec,
        val label: String = codec.label,
        val pickerMime: String = codec.pickerMime
    )

    private fun buildCodecOptions(): List<CodecOption> {
        val opts = mutableListOf(
            CodecOption(AudioTrimmer.OutputCodec.AAC_192),
            CodecOption(AudioTrimmer.OutputCodec.AAC_128),
            CodecOption(AudioTrimmer.OutputCodec.AAC_64),
        )
        // Opus encoding requires Android 10+ (API 29)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            opts += CodecOption(AudioTrimmer.OutputCodec.OPUS_128)
            opts += CodecOption(AudioTrimmer.OutputCodec.OPUS_64)
        }
        return opts
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trim)

        audioUri = intent.data ?: run {
            Toast.makeText(this, R.string.err_no_file, Toast.LENGTH_SHORT).show()
            finish(); return
        }

        bindViews()
        loadMetadata()
        loadWaveform()
    }

    private fun bindViews() {
        val btnBack = findViewById<ImageButton>(R.id.btn_back)!!
        btnBack.setOnClickListener { finish() }

        waveform       = findViewById<WaveformView>(R.id.waveform)!!
        seekBar        = findViewById<SeekBar>(R.id.seek_bar)!!
        btnRewind      = findViewById<ImageButton>(R.id.btn_rewind)!!
        btnPlay        = findViewById<ImageButton>(R.id.btn_play)!!
        btnForward     = findViewById<ImageButton>(R.id.btn_forward)!!
        btnExport      = findViewById<Button>(R.id.btn_export)!!
        tvStart        = findViewById<TextView>(R.id.tv_start)!!
        tvEnd          = findViewById<TextView>(R.id.tv_end)!!
        tvTotal        = findViewById<TextView>(R.id.tv_total)!!
        tvFileName     = findViewById<TextView>(R.id.tv_filename)!!
        tvSelLen       = findViewById<TextView>(R.id.tv_sel_len)!!
        tvHint         = findViewById<TextView>(R.id.tv_hint)!!
        pbLoad         = findViewById<ProgressBar>(R.id.pb_load)!!
        pbExport       = findViewById<ProgressBar>(R.id.pb_export)!!
        loadingOverlay = findViewById<View>(R.id.loading_overlay)!!
        seekSpeed      = findViewById<SeekBar>(R.id.seek_speed)!!
        tvSpeed        = findViewById<TextView>(R.id.tv_speed)!!

        setPlaybackControlsEnabled(false)
        btnExport.isEnabled  = false
        seekBar.isEnabled    = false
        seekBar.max          = SEEK_MAX
        seekSpeed.isEnabled  = false

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                playbackSpeed = (progress + 1) * 0.1f
                tvSpeed.text = "%.1f×".format(playbackSpeed)
                if (playing) applyPlaybackSpeed()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnRewind.setOnClickListener  { seekBy(-SKIP_MS) }
        btnPlay.setOnClickListener    { if (playing) pausePlayback() else startPlayback() }
        btnForward.setOnClickListener { seekBy(+SKIP_MS) }
        btnExport.setOnClickListener  { exportAudio() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ms = seekProgressToMs(progress)
                waveform.playbackPositionMs = ms
                if (playing) {
                    try { player?.seekTo(ms.toInt()) } catch (_: Exception) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        waveform.onSeekTo = { ms ->
            if (playing) {
                try { player?.seekTo(ms.toInt()) } catch (_: Exception) {}
            }
            updateSeekBar(ms)
        }

        waveform.onSelectionChanged = { s, e ->
            updateLabels(s, e)
            if (playing) pausePlayback()
            val cur = waveform.playbackPositionMs
            if (cur < s) {
                waveform.playbackPositionMs = s; updateSeekBar(s)
            } else if (cur > e) {
                waveform.playbackPositionMs = e; updateSeekBar(e)
            }
        }
    }

    private fun loadMetadata() {
        val uri = audioUri ?: return
        tvFileName.text = getFileName(uri)
        isMp3 = tvFileName.text.endsWith(".mp3", ignoreCase = true)
        try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(this, uri)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                tvTotal.text = fmt(ms)
            }
        } catch (_: Exception) { tvTotal.text = "--:--" }
    }

    private fun loadWaveform() {
        val uri = audioUri ?: return
        loadingOverlay.visibility = View.VISIBLE
        setPlaybackControlsEnabled(false)
        btnExport.isEnabled = false
        seekBar.isEnabled   = false

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AudioDecoder.decodeWaveform(this@TrimActivity, uri, 300)
            }
            loadingOverlay.visibility = View.GONE

            if (result != null) {
                val (amps, dur) = result
                tvTotal.text = fmt(dur)
                waveform.setWaveformData(amps, dur)
                updateLabels(0L, dur)
                seekBar.progress = 0
                seekBar.isEnabled   = true
                btnExport.isEnabled = true
                setPlaybackControlsEnabled(true)
            } else {
                Toast.makeText(this@TrimActivity, R.string.err_load, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun startPlayback() {
        val uri = audioUri ?: return
        try {
            if (player == null) {
                player = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    prepare()
                }
            }
            val startPos = waveform.playbackPositionMs.coerceIn(waveform.startMs, waveform.endMs)
            player!!.seekTo(startPos.toInt())
            player!!.start()
            applyPlaybackSpeed()
            playing = true
            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            handler.post(tickRunnable)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_playback, Toast.LENGTH_SHORT).show()
        }
    }

    /** Pause: cursor stays at current position so resume continues from the same spot. */
    private fun pausePlayback() {
        playing = false
        handler.removeCallbacks(tickRunnable)
        try {
            val pos = player?.currentPosition?.toLong() ?: waveform.playbackPositionMs
            player?.pause()
            waveform.playbackPositionMs = pos
            updateSeekBar(pos)
        } catch (_: IllegalStateException) {}
        btnPlay.setImageResource(android.R.drawable.ic_media_play)
    }

    /** Full stop called when end of selection is reached. */
    private fun stopPlayback() {
        playing = false
        handler.removeCallbacks(tickRunnable)
        try {
            player?.pause()
            player?.seekTo(waveform.startMs.toInt())
        } catch (_: IllegalStateException) {}
        waveform.playbackPositionMs = waveform.startMs
        updateSeekBar(waveform.startMs)
        btnPlay.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun applyPlaybackSpeed() {
        try {
            player?.playbackParams = PlaybackParams().setSpeed(playbackSpeed)
        } catch (_: Exception) {}
    }

    /** Jump forward or backward by [deltaMs] milliseconds (clamped to [0, duration]). */
    private fun seekBy(deltaMs: Long) {
        val dur = waveform.durationMs
        if (dur == 0L) return
        val newMs = (waveform.playbackPositionMs + deltaMs).coerceIn(0L, dur)
        waveform.playbackPositionMs = newMs
        updateSeekBar(newMs)
        if (playing) {
            try { player?.seekTo(newMs.toInt()) } catch (_: Exception) {}
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun exportAudio() {
        if (waveform.endMs - waveform.startMs < 500) {
            Toast.makeText(this, R.string.err_too_short, Toast.LENGTH_SHORT).show(); return
        }
        if (playing) pausePlayback()

        val options = buildCodecOptions()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_codec)
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                launchFilePicker(options[which])
            }
            .show()
    }

    private fun launchFilePicker(option: CodecOption) {
        pendingCodecOption = option
        val base = getFileName(audioUri!!).substringBeforeLast(".")
        val ts   = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = option.pickerMime
            putExtra(Intent.EXTRA_TITLE, "${base}_trim_$ts.${option.codec.extension}")
        }
        startActivityForResult(intent, REQ_SAVE_FILE)
    }

    private fun performExport(saveUri: Uri, option: CodecOption) {
        val inputUri  = audioUri ?: return
        val trimStart = waveform.startMs * 1000L
        val trimEnd   = waveform.endMs   * 1000L

        releasePlayer()
        setPlaybackControlsEnabled(false)
        btnExport.isEnabled = false
        seekBar.isEnabled   = false
        pbExport.visibility = View.VISIBLE
        pbExport.progress   = 0

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openFileDescriptor(saveUri, "w")?.use { pfd ->
                        AudioTrimmer.trim(
                            this@TrimActivity, inputUri, pfd.fileDescriptor,
                            trimStart, trimEnd, option.codec
                        ) { p -> handler.post { pbExport.progress = p } }
                    } ?: false
                } catch (_: Throwable) { false }
            }

            pbExport.visibility = View.GONE
            setPlaybackControlsEnabled(true)
            btnExport.isEnabled = true
            seekBar.isEnabled   = true

            if (ok) showSuccessDialog(saveUri, option)
            else Toast.makeText(this@TrimActivity, R.string.err_export, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSuccessDialog(saveUri: Uri, option: CodecOption) {
        AlertDialog.Builder(this)
            .setTitle(R.string.export_done)
            .setMessage(getString(R.string.export_saved, getFileName(saveUri), option.label))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_FILE && resultCode == RESULT_OK) {
            val uri    = data?.data ?: return
            val option = pendingCodecOption ?: return
            pendingCodecOption = null
            performExport(uri, option)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setPlaybackControlsEnabled(enabled: Boolean) {
        btnRewind.isEnabled   = enabled
        btnPlay.isEnabled     = enabled
        btnForward.isEnabled  = enabled
        seekSpeed.isEnabled   = enabled
    }

    private fun updateLabels(s: Long, e: Long) {
        tvStart.text  = fmt(s)
        tvEnd.text    = fmt(e)
        tvSelLen.text = getString(R.string.sel_len, fmt(e - s))
    }

    private fun updateSeekBar(posMs: Long) {
        seekBar.progress = msToSeekProgress(posMs)
    }

    private fun msToSeekProgress(ms: Long): Int {
        val dur = waveform.durationMs
        return if (dur > 0) (ms * SEEK_MAX / dur).toInt().coerceIn(0, SEEK_MAX) else 0
    }

    private fun seekProgressToMs(progress: Int): Long {
        val dur = waveform.durationMs
        return if (dur > 0) progress.toLong() * dur / SEEK_MAX else 0L
    }

    private fun fmt(ms: Long): String {
        val m  = ms / 60_000L
        val s  = (ms % 60_000L) / 1000L
        val cs = (ms % 1000L) / 10L
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0) return c.getString(col) ?: "audio"
            }
        }
        return uri.lastPathSegment ?: "audio"
    }

    private fun releasePlayer() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
    }

    override fun onPause() {
        super.onPause()
        if (playing) pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        scope.cancel()
    }

    companion object {
        const val REQ_SAVE_FILE = 3
        private const val SEEK_MAX = 10_000
        private const val SKIP_MS  = 10_000L   // 10 seconds
    }
}
