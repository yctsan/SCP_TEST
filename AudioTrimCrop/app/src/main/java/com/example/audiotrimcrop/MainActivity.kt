package com.example.audiotrimcrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            openTrimActivity(it)
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchPicker()
        else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle VIEW intents from file managers / other apps
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openTrimActivity(intent.data!!)
            return
        }

        findViewById<Button>(R.id.btn_open_file).setOnClickListener {
            checkPermissionAndPick()
        }
    }

    private fun checkPermissionAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED -> launchPicker()
                else -> requestPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            launchPicker()
        }
    }

    private fun launchPicker() {
        pickAudio.launch(
            arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/*")
        )
    }

    private fun openTrimActivity(uri: Uri) {
        startActivity(
            Intent(this, TrimActivity::class.java).apply { data = uri }
        )
    }
}
