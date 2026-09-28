package com.example.myrecordsgreen.encoder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.myrecordsgreen.helper.AudioMixer
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

class AudioEncoderManager(
    private val audioOption: Int,
    private val mediaProjection: MediaProjection?
) {
    private var audioEncoder: MediaCodec? = null
    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    
    private var recordThread: Thread? = null
    private var encodeThread: Thread? = null
    @Volatile var isAudioRecording = false

    // A queue for passing PCM data prevents threads from reading audio from blocking threads from recording.
    private val pcmQueue = LinkedBlockingQueue<ByteArray>(20)

    @SuppressLint("MissingPermission")
    fun setup() {
        if (audioOption == 2) return

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBufferSize, 4096)

        try {
            if ((audioOption == 0 || audioOption == 1) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                internalAudioRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            }

            if (audioOption == 1 || audioOption == 3) {
                micAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            val audioFormatEnc = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormatEnc, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioEncoderManager", "Error setting up audio", e)
        }
    }

    fun start(
        mediaMuxer: MediaMuxer,
        onFormatChanged: (MediaFormat) -> Int,
        onMuxerReady: () -> Boolean,
        onGetStartTimeUs: () -> Long,
        onSetStartTimeUs: (Long) -> Unit,
        isRecording: () -> Boolean
    ) {
        if (audioOption == 2) return

        isAudioRecording = true
        pcmQueue.clear()

        // Thread 1: Read PCM data (lightweight and fast; does not interfere with other CPU operations)
        recordThread = Thread {
            try {
                internalAudioRecord?.startRecording()
                micAudioRecord?.startRecording()

                val frameSize = 2048
                val internalBuf = ByteArray(frameSize)
                val micBuf = ByteArray(frameSize)

                while (isRecording() && isAudioRecording) {
                    val mixedBuf = ByteArray(frameSize)
                    var readBytes = 0

                    if (audioOption == 1 && internalAudioRecord != null && micAudioRecord != null) {
                        val internalRead = internalAudioRecord?.read(internalBuf, 0, internalBuf.size) ?: 0
                        val micRead = micAudioRecord?.read(micBuf, 0, micBuf.size) ?: 0
                        readBytes = max(internalRead, micRead)

                        if (readBytes > 0) {
                            AudioMixer.mixPcm16Bit(internalBuf, micBuf, mixedBuf, readBytes)
                        }
                    } else if (internalAudioRecord != null && (audioOption == 0 || audioOption == 1)) {
                        readBytes = internalAudioRecord?.read(mixedBuf, 0, mixedBuf.size) ?: 0
                    } else if (micAudioRecord != null && (audioOption == 3 || audioOption == 1)) {
                        readBytes = micAudioRecord?.read(mixedBuf, 0, mixedBuf.size) ?: 0
                    }

                    if (readBytes > 0) {
                        // Send to the queue so another thread can immediately process it.
                        pcmQueue.offer(mixedBuf)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioEncoderManager", "Record thread error", e)
            }
        }.apply { start() }

        // Thread 2: Retrieve PCM from the queue and encode it into the MediaMuxer.
        encodeThread = Thread {
            var audioTrackIndex = -1
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                while (isRecording() && isAudioRecording) {
                    val pcmData = pcmQueue.poll()
                    if (pcmData != null) {
                        val inputBufferIndex = audioEncoder?.dequeueInputBuffer(0) ?: -1
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = audioEncoder?.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(pcmData)

                            val presentationTimeUs = System.nanoTime() / 1000
                            audioEncoder?.queueInputBuffer(inputBufferIndex, 0, pcmData.size, presentationTimeUs, 0)
                        }
                    }

                    var outputBufferIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            audioTrackIndex = onFormatChanged(audioEncoder!!.outputFormat)
                        } else {
                            val encodedData = audioEncoder?.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null && onMuxerReady() && audioTrackIndex >= 0 && bufferInfo.size > 0) {
                                var startTimeUs = onGetStartTimeUs()
                                if (startTimeUs == 0L) {
                                    startTimeUs = bufferInfo.presentationTimeUs
                                    onSetStartTimeUs(startTimeUs)
                                }
                                bufferInfo.presentationTimeUs = max(0L, bufferInfo.presentationTimeUs - startTimeUs)
                                mediaMuxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                            }
                            audioEncoder?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                        outputBufferIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    }

                    if (pcmData == null) {
                        Thread.sleep(2)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioEncoderManager", "Encode thread error", e)
            } finally {
                try {
                    internalAudioRecord?.stop()
                    internalAudioRecord?.release()
                } catch (e: Exception) {}
                try {
                    micAudioRecord?.stop()
                    micAudioRecord?.release()
                } catch (e: Exception) {}
            }
        }.apply { start() }
    }

    fun stop() {
        isAudioRecording = false
        try {
            recordThread?.join(500)
            encodeThread?.join(500)
        } catch (e: Exception) {}

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {}
        audioEncoder = null
    }
}