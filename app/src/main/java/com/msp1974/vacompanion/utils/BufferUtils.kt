package com.msp1974.vacompanion.utils

import android.content.res.AssetManager
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

const val DEFAULT_BUFFER_SIZE = 8192

fun ByteBuffer.fillFrom(src: ByteBuffer): Int {
    val remaining = remaining()
    if (remaining == 0)
        return 0

    val srcRemaining = src.remaining()
    if (srcRemaining <= remaining) {
        put(src)
        return srcRemaining
    } else {
        val currentLimit = src.limit()
        src.limit(src.position() + remaining)
        put(src)
        src.limit(currentLimit)
        return remaining
    }
}

/**
 * Copies as many bytes as possible from this stream to the given ByteBuffer, returning the number of bytes copied.
 */
fun InputStream.copyTo(out: ByteBuffer, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    var bytesCopied: Long = 0
    val buffer = ByteArray(bufferSize)
    var bytes = read(buffer).coerceAtMost(out.remaining())
    while (bytes >= 0 && out.hasRemaining()) {
        out.put(buffer, 0, bytes)
        bytesCopied += bytes
        bytes = read(buffer).coerceAtMost(out.remaining())
    }
    return bytesCopied
}

/**
 * Loads an asset as a memory-mapped ByteBuffer. 
 * Recommended for LiteRT/TFLite models to avoid copying and alignment issues.
 */
fun AssetManager.loadMappedAsset(path: String): ByteBuffer {
    val fileDescriptor = openFd(path)
    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel
    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
}

/**
 * Loads an asset into a direct ByteBuffer.
 */
fun AssetManager.loadAssetAsDirectBuffer(path: String): ByteBuffer {
    val bytes = open(path).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).apply {
        order(ByteOrder.nativeOrder())
        put(bytes)
        rewind()
    }
}
