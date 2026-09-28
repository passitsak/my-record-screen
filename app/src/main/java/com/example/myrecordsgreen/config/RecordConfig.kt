package com.example.myrecordsgreen.config

import android.content.Intent
import android.media.MediaCodecInfo
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RecordConfig(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val bitrateMb: Int,
    val fps: Int,
    val audioOption: Int,
    val codecOption: Int,
    val mimeType: String,
    val audioStr: String,
    val codecStr: String,
    val bitrateMode: Int,      // 2 = CBR (Constant), 1 = VBR (Adaptive/Variable)
    val bitrateModeStr: String // Descriptive mode string
) {
    companion object {
        fun fromIntent(intent: Intent, metrics: DisplayMetrics): RecordConfig {
            val resOption = intent.getIntExtra("RES_OPTION", 0)
            val letterboxOption = intent.getIntExtra("LETTERBOX_OPTION", 0)
            val bitrateOption = intent.getIntExtra("BITRATE_OPTION", 4)
            val fpsOption = intent.getIntExtra("FPS_OPTION", 0)
            val orientationOption = intent.getIntExtra("ORIENTATION_OPTION", 0)
            val audioOption = intent.getIntExtra("AUDIO_OPTION", 0)
            val codecOption = intent.getIntExtra("CODEC_OPTION", 0)
            
            // Retrieve BITRATE_MODE_OPTION (0 = Constant / CBR, 1 = Adaptive / VBR)
            val bitrateModeOption = intent.getIntExtra("BITRATE_MODE_OPTION", 0)

            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            val finalWidth: Int
            val finalHeight: Int

            if (resOption == 3 || resOption == 4) {
                when (orientationOption) {
                    0 -> {
                        finalWidth = max(screenW, screenH)
                        finalHeight = min(screenW, screenH)
                    }
                    1 -> {
                        finalWidth = min(screenW, screenH)
                        finalHeight = max(screenW, screenH)
                    }
                    else -> {
                        finalWidth = screenW
                        finalHeight = screenH
                    }
                }
            } else {
                val (presetLong, presetShort) = when (resOption) {
                    0 -> Pair(2560, 1440)
                    1 -> Pair(1920, 1080)
                    2 -> Pair(1280, 720)
                    else -> Pair(2560, 1440)
                }

                val targetLong: Int
                val targetShort: Int

                if (letterboxOption == 0) {
                    val realLong = max(screenW, screenH)
                    val realShort = min(screenW, screenH)
                    val screenAspectRatio = realLong.toFloat() / realShort.toFloat()

                    targetShort = presetShort
                    targetLong = (presetShort * screenAspectRatio).roundToInt()
                } else {
                    targetLong = presetLong
                    targetShort = presetShort
                }

                when (orientationOption) {
                    0 -> {
                        finalWidth = max(targetLong, targetShort)
                        finalHeight = min(targetLong, targetShort)
                    }
                    1 -> {
                        finalWidth = min(targetLong, targetShort)
                        finalHeight = max(targetLong, targetShort)
                    }
                    else -> {
                        if (screenW > screenH) {
                            finalWidth = max(targetLong, targetShort)
                            finalHeight = min(targetLong, targetShort)
                        } else {
                            finalWidth = min(targetLong, targetShort)
                            finalHeight = max(targetLong, targetShort)
                        }
                    }
                }
            }

            val encWidth = (finalWidth + 15) / 16 * 16
            val encHeight = (finalHeight + 15) / 16 * 16

            val bitrateMb = when (bitrateOption) {
                0 -> 48; 1 -> 44; 2 -> 40; 3 -> 36; 4 -> 32
                5 -> 28; 6 -> 20; 7 -> 16; 8 -> 12; 9 -> 8
                else -> 32
            }
            val bitrate = bitrateMb * 1024 * 1024

            val fps = when (fpsOption) {
                0 -> 60; 1 -> 120; 2 -> 90; 3 -> 30
                else -> 60
            }

            val audioStr = when (audioOption) {
                0 -> "Internal Audio"; 1 -> "Internal + Mic"; 2 -> "Mute"; 3 -> "Microphone"
                else -> "Unknown"
            }

            val codecStr = if (codecOption == 1) "HEVC (H.265)" else "AVC (H.264)"
            val mimeType = if (codecOption == 1) android.media.MediaFormat.MIMETYPE_VIDEO_HEVC else android.media.MediaFormat.MIMETYPE_VIDEO_AVC

            // Set Bitrate Mode and display string
            val bitrateMode = if (bitrateModeOption == 1) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            }

            val bitrateModeStr = if (bitrateModeOption == 1) "Adaptive (VBR)" else "Constant (CBR)"

            return RecordConfig(
                width = encWidth,
                height = encHeight,
                bitrate = bitrate,
                bitrateMb = bitrateMb,
                fps = fps,
                audioOption = audioOption,
                codecOption = codecOption,
                mimeType = mimeType,
                audioStr = audioStr,
                codecStr = codecStr,
                bitrateMode = bitrateMode,
                bitrateModeStr = bitrateModeStr
            )
        }
    }
}