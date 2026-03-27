package com.msp1974.vacompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApmTappedExoPlayerFactoryTest {

    @Test
    fun `buildTapAudioProcessors returns tap processor when enabled`() {
        val processors = ApmTappedExoPlayerFactory.buildTapAudioProcessors(enableRenderTap = true)
        assertEquals(1, processors.size)
        assertTrue(processors.first() is ApmTapAudioProcessor)
    }

    @Test
    fun `buildTapAudioProcessors returns empty when disabled`() {
        val processors = ApmTappedExoPlayerFactory.buildTapAudioProcessors(enableRenderTap = false)
        assertTrue(processors.isEmpty())
    }
}
