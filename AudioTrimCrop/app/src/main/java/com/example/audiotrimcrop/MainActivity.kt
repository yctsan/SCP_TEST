package com.example.audiotrimcrop

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openTrimActivity(intent.data!!)
            return
        }

        findViewById<Button>(R.id.btn_open_file)!!.setOnClickListener {
            checkPermissionAndPick()
        }
    }

    private fun checkPermissionAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchPicker()
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO), REQ_PERMISSION)
            }
        } else {
            launchPicker()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchPicker()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/*"))
        }
        startActivityForResult(intent, REQ_PICK_AUDIO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                openTrimActivity(uri)
            }
        }
    }

    private fun openTrimActivity(uri: Uri) {
        startActivity(Intent(this, TrimActivity::class.java).apply { data = uri })
    }

    companion object {
        const val REQ_PICK_AUDIO = 1
        const val REQ_PERMISSION = 2
    }
}
