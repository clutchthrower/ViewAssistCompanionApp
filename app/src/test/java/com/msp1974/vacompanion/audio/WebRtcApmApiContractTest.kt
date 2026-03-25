package com.msp1974.vacompanion.audio

import com.viewassist.webrtc.WebRtcApm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcApmApiContractTest {

    @Test
    // Regression guard: modern upstream APM path does not expose a meaningful
    // "mobile mode" toggle, so this legacy knob must stay out of our public API.
    fun `WebRtcApm Config does not expose legacy aecMobileMode`() {
        val fieldNames = WebRtcApm.Config::class.java.declaredFields.map { it.name }
        assertFalse(fieldNames.contains("aecMobileMode"))
        assertTrue(fieldNames.contains("aecEnabled"))
    }

    @Test
    fun `NativeApm create and reconfigure signatures match no-mobile-mode contract`() {
        val nativeApm = Class.forName(
            "com.viewassist.webrtc.NativeApm",
            false,
            javaClass.classLoader,
        )
        val create = nativeApm.declaredMethods.firstOrNull { it.name == "nativeCreate" }
        val reconfigure = nativeApm.declaredMethods.firstOrNull { it.name == "nativeReconfigure" }

        assertNotNull(create)
        assertNotNull(reconfigure)
        // create: aec + ns + nsLevel + agc + hpf + ts + vad
        assertTrue(create!!.parameterCount == 7)
        // reconfigure: handle + aec + ns + nsLevel + agc + hpf + ts + vad
        assertTrue(reconfigure!!.parameterCount == 8)
    }
}
