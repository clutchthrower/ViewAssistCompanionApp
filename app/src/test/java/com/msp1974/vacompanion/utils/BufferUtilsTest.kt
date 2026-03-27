package com.msp1974.vacompanion.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class BufferUtilsTest {

    @Test
    fun `fillFrom copies all bytes when source fits destination`() {
        val destination = ByteBuffer.allocate(8)
        destination.position(2)
        val source = ByteBuffer.wrap(byteArrayOf(10, 20, 30))

        val copied = destination.fillFrom(source)

        assertEquals(3, copied)
        assertEquals(3, source.position())
        assertEquals(5, destination.position())

        destination.flip()
        val written = ByteArray(destination.remaining())
        destination.get(written)
        assertArrayEquals(byteArrayOf(0, 0, 10, 20, 30), written)
    }

    @Test
    fun `fillFrom only copies remaining destination capacity`() {
        val destination = ByteBuffer.allocate(4)
        destination.position(2)
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

        val copied = destination.fillFrom(source)

        assertEquals(2, copied)
        assertEquals(2, source.position())
        assertEquals(4, destination.position())

        destination.flip()
        val written = ByteArray(destination.remaining())
        destination.get(written)
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), written)
    }

    @Test
    fun `copyTo stops once destination buffer is full`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6))
        val destination = ByteBuffer.allocate(4)

        val copied = input.copyTo(destination, bufferSize = 3)

        assertEquals(4L, copied)
        assertEquals(4, destination.position())
        destination.flip()
        val written = ByteArray(destination.remaining())
        destination.get(written)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), written)
    }

    @Test
    fun `copyTo returns zero for empty source stream`() {
        val input = ByteArrayInputStream(byteArrayOf())
        val destination = ByteBuffer.allocate(5)

        val copied = input.copyTo(destination)

        assertEquals(0L, copied)
        assertEquals(0, destination.position())
    }
}
