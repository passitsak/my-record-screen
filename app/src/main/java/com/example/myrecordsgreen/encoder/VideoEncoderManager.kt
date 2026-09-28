package com.example.myrecordsgreen.encoder

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.myrecordsgreen.config.RecordConfig
import kotlin.math.max

class VideoEncoderManager(
    private val config: RecordConfig,
    private val densityDpi: Int
) {
    private var videoEncoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recordingThread: Thread? = null

    fun start(
        mediaProjection: MediaProjection,
        mediaMuxer: MediaMuxer,
        onFormatChanged: (MediaFormat) -> Int,
        onMuxerReady: () -> Boolean,
        onGetStartTimeUs: () -> Long,
        onSetStartTimeUs: (Long) -> Unit,
        isRecording: () -> Boolean
    ) {
        val videoFormat = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setFloat(MediaFormat.KEY_CAPTURE_RATE, config.fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // Set Bitrate Mode based on RecordConfig (CBR or VBR)
            setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.fps.toFloat())
            }
        }

        videoEncoder = try {
            MediaCodec.createEncoderByType(config.mimeType)
        } catch (e: Exception) {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        videoEncoder?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = videoEncoder?.createInputSurface()
        videoEncoder?.start()

        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRecorder",
            config.width,
            config.height,
            densityDpi,
            displayFlags,
            inputSurface,
            null,
            null
        )

        recordingThread = Thread {
            var videoTrackIndex = -1
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (isRecording()) {
                    val outputBufferIndex = videoEncoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = onFormatChanged(videoEncoder!!.outputFormat)
                    } else if (outputBufferIndex >= 0) {
                        val encodedData = videoEncoder?.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null) {
                            if (onMuxerReady() && videoTrackIndex >= 0 && bufferInfo.size > 0) {
                                var startTimeUs = onGetStartTimeUs()
                                if (startTimeUs == 0L) {
                                    startTimeUs = bufferInfo.presentationTimeUs
                                    onSetStartTimeUs(startTimeUs)
                                }
                                bufferInfo.presentationTimeUs = max(0L, bufferInfo.presentationTimeUs - startTimeUs)
                                mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                        videoEncoder?.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoEncoderManager", "Video encoding loop error", e)
            }
        }.apply { start() }
    }

    fun stop() {
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {}

        try {
            recordingThread?.join(1000)
        } catch (e: Exception) {}

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (e: Exception) {}
        videoEncoder = null

        virtualDisplay?.release()
        virtualDisplay = null
    }
}