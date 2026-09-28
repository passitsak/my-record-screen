package com.example.myrecordsgreen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.myrecordsgreen.config.RecordConfig
import com.example.myrecordsgreen.encoder.AudioEncoderManager
import com.example.myrecordsgreen.encoder.VideoEncoderManager
import java.util.Timer
import java.util.TimerTask

class RecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    @Volatile private var isRecording = false
    private var videoUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null

    private var timer: Timer? = null
    private var secondsRecorded = 0

    private var mediaMuxer: MediaMuxer? = null
    @Volatile private var muxerStarted = false
    private var expectedTracksCount = 1
    private var addedTracksCount = 0

    @Volatile private var startTimeUs: Long = 0L

    private var notifOptionGlobal = 0

    private var videoEncoderManager: VideoEncoderManager? = null
    private var audioEncoderManager: AudioEncoderManager? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val resultCode = intent.getIntExtra("RESULT_CODE", -1)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }

            notifOptionGlobal = intent.getIntExtra("NOTIF_OPTION", 0)

            if (resultCode != Activity.RESULT_OK || data == null) {
                showToast("Screen recording permission denied")
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundNotification("Preparing screen recording...")

            Handler(Looper.getMainLooper()).postDelayed({
                startRecordingProcess(resultCode, data, intent)
            }, 1000)

        } else if (action == "STOP") {
            stopRecording()
        }
        return START_STICKY
    }

    private fun startRecordingProcess(resultCode: Int, data: Intent, intent: Intent) {
        try {
            val config = RecordConfig.fromIntent(intent, resources.displayMetrics)

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                showToast("Failed to start MediaProjection")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            showToast("Recording started: ${config.width}x${config.height} | ${config.fps} FPS")

            val filename = "REC_${System.currentTimeMillis()}.mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MyRecordings")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (videoUri == null) {
                showToast("Failed to create video file")
                stopSelf()
                return
            }

            pfd = resolver.openFileDescriptor(videoUri!!, "rw")
            val currentPfd = pfd ?: run {
                showToast("Failed to open File Descriptor")
                stopSelf()
                return
            }

            mediaMuxer = MediaMuxer(currentPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            addedTracksCount = 0
            startTimeUs = 0L
            expectedTracksCount = if (config.audioOption != 2) 2 else 1
            isRecording = true

            // 1. Setup Audio (if requested)
            if (config.audioOption != 2) {
                audioEncoderManager = AudioEncoderManager(config.audioOption, mediaProjection).apply {
                    setup()
                }
            }

            // 2. Start Video Encoder
            videoEncoderManager = VideoEncoderManager(config, resources.displayMetrics.densityDpi)
            videoEncoderManager?.start(
                mediaProjection = mediaProjection!!,
                mediaMuxer = mediaMuxer!!,
                onFormatChanged = { format -> addMuxerTrack(format) },
                onMuxerReady = { muxerStarted },
                onGetStartTimeUs = { startTimeUs },
                onSetStartTimeUs = { time -> synchronized(this) { if (startTimeUs == 0L) startTimeUs = time } },
                isRecording = { isRecording }
            )

            // 3. Start Audio Encoder Loop (if requested)
            if (config.audioOption != 2) {
                audioEncoderManager?.start(
                    mediaMuxer = mediaMuxer!!,
                    onFormatChanged = { format -> addMuxerTrack(format) },
                    onMuxerReady = { muxerStarted },
                    onGetStartTimeUs = { startTimeUs },
                    onSetStartTimeUs = { time -> synchronized(this) { if (startTimeUs == 0L) startTimeUs = time } },
                    isRecording = { isRecording }
                )
            }

            startTimer()

        } catch (e: Exception) {
            Log.e("ScreenRecord", "Error starting recording", e)
            showToast("Recording error occurred")
            stopRecording()
        }
    }

    @Synchronized
    private fun addMuxerTrack(format: MediaFormat): Int {
        val muxer = mediaMuxer ?: return -1
        val trackIndex = muxer.addTrack(format)
        addedTracksCount++

        if (addedTracksCount >= expectedTracksCount && !muxerStarted) {
            muxer.start()
            muxerStarted = true
        }
        return trackIndex
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        val totalSeconds = secondsRecorded
        stopTimer()

        updateNotification("Saving video file...")

        Thread {
            try {
                videoEncoderManager?.stop()
                audioEncoderManager?.stop()

                try {
                    mediaProjection?.unregisterCallback(projectionCallback)
                } catch (e: Exception) {}
                mediaProjection?.stop()
                mediaProjection = null

                try {
                    if (muxerStarted) {
                        mediaMuxer?.stop()
                    }
                    mediaMuxer?.release()
                } catch (e: Exception) {
                    Log.e("ScreenRecord", "Error stopping muxer", e)
                }
                mediaMuxer = null

                pfd?.close()
                pfd = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && videoUri != null) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(videoUri!!, contentValues, null, null)
                }

                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val formattedTime = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
                showToast("Recording completed! ($formattedTime)")

            } catch (e: Exception) {
                Log.e("ScreenRecord", "Error in stop thread", e)
                showToast("Failed to save video file")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun startTimer() {
        secondsRecorded = 0
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                secondsRecorded++
                val minutes = secondsRecorded / 60
                val seconds = secondsRecorded % 60
                val timeString = String.format("%02d:%02d", minutes, seconds)
                updateNotification("Recording... $timeString")
            }
        }, 0, 1000)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun startForegroundNotification(statusText: String) {
        val channelId = "screen_record_channel_v18"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isHidden = notifOptionGlobal == 1
        val importance = if (isHidden) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen Recording", importance).apply {
                if (isHidden) {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            }
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, RecordService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = createNotification(channelId, statusText, stopPendingIntent, isHidden)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val channelId = "screen_record_channel_v18"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val stopIntent = Intent(this, RecordService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isHidden = notifOptionGlobal == 1
        val notification = createNotification(channelId, statusText, stopPendingIntent, isHidden)
        manager.notify(101, notification)
    }

    private fun createNotification(channelId: String, statusText: String, stopPendingIntent: PendingIntent, isHidden: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Recorder")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)

        if (isHidden) {
            builder.priority = NotificationCompat.PRIORITY_MIN
            builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }

        return builder.build()
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}