package com.msp1974.vacompanion.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioDSP {

    fun audioLevel(audioBuffer: ByteArray): Float {
        val shortArray = byteArrayToShortArray(audioBuffer)
        val floatArray = normaliseAudioBuffer(shortArray)
        return floatArray.map { i -> abs(i) }.average().toFloat()
    }

    fun audioLevel(audioBuffer: FloatArray): Float {
        return audioBuffer.map { i -> abs(i) }.average().toFloat()
    }
    
    fun reduceVolume(audioBuffer: ByteArray, reductionFactor: Float): ByteArray {
        val shortArray = byteArrayToShortArray(audioBuffer)
        for (i in shortArray.indices) {
            shortArray[i] = (shortArray[i] * reductionFactor).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteBuffer(shortArray)
    }

    fun normaliseAudioBuffer(audioBuffer: ShortArray): FloatArray {
        val floatBuffer = audioBuffer.map { (it.toFloat() / 32768.0f) }.toFloatArray()
        return floatBuffer
    }

    fun shortArrayToByteBuffer(audioBuffer: ShortArray): ByteArray {
        val byteBuffer = ByteArray(audioBuffer.size * 2)
        for (i in audioBuffer.indices) {
            val value: Int = audioBuffer[i].toInt()
            byteBuffer[i * 2] = (value and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = (value shr 8).toByte()
        }
        return byteBuffer
    }

    fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortArray = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray)
            .order(ByteOrder.LITTLE_ENDIAN) // Or BIG_ENDIAN depending on your data source
            .asShortBuffer()
            .get(shortArray)
        return shortArray
    }

    fun byteArrayToFloatArray(byteArray: ByteArray): FloatArray {
        val shortArray = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray)

        val floatArray = FloatArray(shortArray.size)
        for (i in shortArray.indices) {
            floatArray[i] = shortArray[i].toFloat() / 32768.0f
        }
        return floatArray
    }

    fun floatArrayToByteBuffer(audioBuffer: FloatArray): ByteArray {
        val byteBuffer = ByteArray(audioBuffer.size * 2)
        for (i in audioBuffer.indices) {
            val value: Int = (audioBuffer[i] * 32768.0f).toInt()
            byteBuffer[i * 2] = (value and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = (value shr 8).toByte()
        }
        return byteBuffer
    }
}
