package com.example.myrecordsgreen

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerLetterbox: Spinner
    private lateinit var spinnerCodec: Spinner
    private lateinit var spinnerBitrate: Spinner
    private lateinit var spinnerBitrateMode: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var spinnerOrientation: Spinner
    private lateinit var spinnerAudio: Spinner
    private lateinit var spinnerNotification: Spinner
    private lateinit var recordingGlowView: RecordingGlowView

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {

            val resPosition = spinnerResolution.selectedItemPosition
            val letterboxPosition = spinnerLetterbox.selectedItemPosition
            val codecPosition = spinnerCodec.selectedItemPosition
            val bitratePosition = spinnerBitrate.selectedItemPosition
            val bitrateModePosition = spinnerBitrateMode.selectedItemPosition
            val fpsPosition = spinnerFps.selectedItemPosition
            val orientationPosition = spinnerOrientation.selectedItemPosition
            val audioPosition = spinnerAudio.selectedItemPosition
            val notifPosition = spinnerNotification.selectedItemPosition

            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = "START"
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("RES_OPTION", resPosition)
                putExtra("LETTERBOX_OPTION", letterboxPosition)
                putExtra("CODEC_OPTION", codecPosition)
                putExtra("BITRATE_OPTION", bitratePosition)
                putExtra("BITRATE_MODE_OPTION", bitrateModePosition)
                putExtra("FPS_OPTION", fpsPosition)
                putExtra("ORIENTATION_OPTION", orientationPosition)
                putExtra("AUDIO_OPTION", audioPosition)
                putExtra("NOTIF_OPTION", notifPosition)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            recordingGlowView.startGlow()

        } else {
            Toast.makeText(this, "Cancelled or permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()

        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = true
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(64, 80, 64, 64)
        }

        // 1. Resolution
        val tvRes = TextView(this).apply { text = "Select Resolution:" }
        spinnerResolution = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "2K Quad HD (2560 x 1440 / 1440p)",
                    "Full HD (1920 x 1080 / 1080p)",
                    "HD (1280 x 720 / 720p)",
                    "Original Screen Native (Full Pixels)",
                    "Original Native (Maintain Aspect Ratio / Ultra Clarity)"
                )
            )
            setSelection(4)
        }

        // 1.1 Letterbox / Padding Option
        val tvLetterbox = TextView(this).apply { text = "Aspect Ratio Handling (Non-Full Screen):" }
        spinnerLetterbox = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "Stretch to Fit (Fill Screen)",
                    "Letterbox / Black Bars (Maintain Aspect Ratio - Recommended)"
                )
            )
            setSelection(0)
        }

        // 2. Codec
        val tvCodec = TextView(this).apply { text = "Select Video Codec:" }
        spinnerCodec = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "H.264 / AVC (Universal Compatibility)",
                    "H.265 / HEVC (Best Quality & Compression)"
                )
            )
            setSelection(1)
        }

        // 3. Orientation
        val tvOrientation = TextView(this).apply { text = "Select Recording Orientation:" }
        spinnerOrientation = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "Landscape (Recommended for Gaming / MLBB)",
                    "Portrait (Standard Mobile Use)",
                    "Auto (Follow Screen Orientation)"
                )
            )
        }

        // 4. Audio Source
        val tvAudio = TextView(this).apply { text = "Select Audio Source:" }
        spinnerAudio = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "🎮 Internal Audio Only (App/Game Audio)",
                    "🎙️🎮 Internal Audio + Microphone",
                    "🔇 Mute (No Audio)"
                )
            )
        }

        // 5. Bitrate
        val tvBitrate = TextView(this).apply { text = "Select Video Bitrate:" }
        spinnerBitrate = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "48 Mbps (Ultra Clear)",
                    "44 Mbps",
                    "40 Mbps",
                    "36 Mbps",
                    "32 Mbps (High Quality for 2K)",
                    "28 Mbps",
                    "20 Mbps (High Standard)",
                    "16 Mbps",
                    "12 Mbps (Standard)",
                    "8 Mbps (Low Storage)"
                )
            )
            setSelection(0)
        }

        // 5.1 Bitrate Mode
        val tvBitrateMode = TextView(this).apply { text = "Select Bitrate Mode:" }
        spinnerBitrateMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "🔒 Constant Bitrate (CBR - High Quality / Stable Bitrate)",
                    "⚡ Variable Bitrate (VBR / Adaptive - Dynamic Bitrate)"
                )
            )
            setSelection(0) // Default: CBR
        }

        // 6. FPS
        val tvFps = TextView(this).apply { text = "Select Frame Rate (FPS):" }
        spinnerFps = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "60 FPS (Smooth Gamer Standard - Recommended)",
                    "120 FPS (Ultra High Smoothness)",
                    "90 FPS (High Smoothness)",
                    "30 FPS (Power Saving)"
                )
            )
        }

        // 7. Notification Option
        val tvNotification = TextView(this).apply { text = "Notification Banner Style:" }
        spinnerNotification = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "Show Standard Notification (Recommended)",
                    "Hide Notification (Silent / Uninterrupted Gameplay)"
                )
            )
        }

        val btnStart = Button(this).apply {
            text = "Start Recording (1s Delay)"
            textSize = 18f
            setPadding(32, 20, 32, 20)
            setOnClickListener { startScreenCapture() }
        }

        val btnStop = Button(this).apply {
            text = "Stop Recording"
            textSize = 18f
            setPadding(32, 20, 32, 20)
            setOnClickListener { stopScreenCapture() }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 12) }

        contentLayout.addView(tvRes, params)
        contentLayout.addView(spinnerResolution, params)
        contentLayout.addView(tvLetterbox, params)
        contentLayout.addView(spinnerLetterbox, params)
        contentLayout.addView(tvCodec, params)
        contentLayout.addView(spinnerCodec, params)
        contentLayout.addView(tvOrientation, params)
        contentLayout.addView(spinnerOrientation, params)
        contentLayout.addView(tvAudio, params)
        contentLayout.addView(spinnerAudio, params)
        contentLayout.addView(tvBitrate, params)
        contentLayout.addView(spinnerBitrate, params)
        contentLayout.addView(tvBitrateMode, params)
        contentLayout.addView(spinnerBitrateMode, params)
        contentLayout.addView(tvFps, params)
        contentLayout.addView(spinnerFps, params)
        contentLayout.addView(tvNotification, params)
        contentLayout.addView(spinnerNotification, params)
        contentLayout.addView(btnStart, params)
        contentLayout.addView(btnStop, params)

        scrollView.addView(contentLayout)

        recordingGlowView = RecordingGlowView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(scrollView)
        rootLayout.addView(recordingGlowView)

        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        if (isMyServiceRunning(RecordService::class.java)) {
            recordingGlowView.startGlow()
        } else {
            recordingGlowView.stopGlow()
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
        }
    }

    private fun startScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopScreenCapture() {
        val serviceIntent = Intent(this, RecordService::class.java).apply {
            action = "STOP"
        }
        startService(serviceIntent)
        recordingGlowView.stopGlow()
    }

    private fun isMyServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}