package com.example.myrecordsgreen.helper

object AudioMixer {
    fun mixPcm16Bit(src1: ByteArray, src2: ByteArray, dst: ByteArray, size: Int) {
        for (i in 0 until size step 2) {
            if (i + 1 < size) {
                val sample1 = (src1[i].toInt() and 0xFF) or (src1[i + 1].toInt() shl 8)
                val s1 = sample1.toShort()

                val sample2 = (src2[i].toInt() and 0xFF) or (src2[i + 1].toInt() shl 8)
                val s2 = sample2.toShort()

                var mixed = s1 + s2
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE.toInt()
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE.toInt()

                dst[i] = (mixed and 0xFF).toByte()
                dst[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            }
        }
    }
}