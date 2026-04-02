package com.example.audiotrimcrop

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrimActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var waveform: WaveformView
    private lateinit var btnPlay: ImageButton
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

    // ── Audio ─────────────────────────────────────────────────────────────────
    private var audioUri: Uri? = null
    private var player: MediaPlayer? = null
    private var playing = false
    private var isMp3 = false

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!playing) return
            try {
                val pos = p.currentPosition.toLong()
                waveform.playbackPositionMs = pos
                if (pos >= waveform.endMs) { stopPlayback(); return }
            } catch (_: IllegalStateException) { return }
            handler.postDelayed(this, 40)
        }
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
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }

        waveform      = findViewById(R.id.waveform)
        btnPlay       = findViewById(R.id.btn_play)
        btnExport     = findViewById(R.id.btn_export)
        tvStart       = findViewById(R.id.tv_start)
        tvEnd         = findViewById(R.id.tv_end)
        tvTotal       = findViewById(R.id.tv_total)
        tvFileName    = findViewById(R.id.tv_filename)
        tvSelLen      = findViewById(R.id.tv_sel_len)
        tvHint        = findViewById(R.id.tv_hint)
        pbLoad        = findViewById(R.id.pb_load)
        pbExport      = findViewById(R.id.pb_export)
        loadingOverlay = findViewById(R.id.loading_overlay)

        btnPlay.isEnabled   = false
        btnExport.isEnabled = false

        btnPlay.setOnClickListener   { if (playing) stopPlayback() else startPlayback() }
        btnExport.setOnClickListener { exportAudio() }

        waveform.onSelectionChanged = { s, e ->
            updateLabels(s, e)
            if (playing) stopPlayback()
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
        btnPlay.isEnabled          = false
        btnExport.isEnabled        = false

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
                btnPlay.isEnabled   = true
                btnExport.isEnabled = true
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
            player!!.seekTo(waveform.startMs.toInt())
            player!!.start()
            playing = true
            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            handler.post(tickRunnable)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_playback, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        playing = false
        handler.removeCallbacks(tickRunnable)
        try {
            player?.pause()
            player?.seekTo(waveform.startMs.toInt())
        } catch (_: IllegalStateException) {}
        waveform.playbackPositionMs = -1L
        btnPlay.setImageResource(android.R.drawable.ic_media_play)
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun exportAudio() {
        val uri = audioUri ?: return
        if (waveform.endMs - waveform.startMs < 500) {
            Toast.makeText(this, R.string.err_too_short, Toast.LENGTH_SHORT).show(); return
        }
        if (playing) stopPlayback()
        releasePlayer()

        btnExport.isEnabled = false
        btnPlay.isEnabled   = false
        pbExport.visibility = View.VISIBLE
        pbExport.progress   = 0

        val outFile = buildOutputFile()

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                AudioTrimmer.trim(
                    this@TrimActivity, uri, outFile,
                    waveform.startMs * 1000L,
                    waveform.endMs   * 1000L
                ) { p -> handler.post { pbExport.progress = p } }
            }

            pbExport.visibility = View.GONE
            btnExport.isEnabled = true
            btnPlay.isEnabled   = true

            if (ok && outFile.exists() && outFile.length() > 0) {
                showSuccessDialog(outFile)
            } else {
                outFile.delete()
                Toast.makeText(this@TrimActivity, R.string.err_export, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildOutputFile(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        dir.mkdirs()
        val base = getFileName(audioUri!!).substringBeforeLast(".")
        val ts   = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        return File(dir, "${base}_trim_$ts.m4a")
    }

    private fun showSuccessDialog(f: File) {
        val note = if (isMp3) "\n\n${getString(R.string.mp3_note)}" else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.export_done)
            .setMessage(getString(R.string.export_saved, f.name) + note)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateLabels(s: Long, e: Long) {
        tvStart.text  = fmt(s)
        tvEnd.text    = fmt(e)
        tvSelLen.text = getString(R.string.sel_len, fmt(e - s))
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
        if (playing) stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        scope.cancel()
    }
}
