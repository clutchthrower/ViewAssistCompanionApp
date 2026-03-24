package com.msp1974.vacompanion.utils

import com.msp1974.vacompanion.settings.APPConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthUtilsTest {

    @Test
    fun `getHAUrl builds fallback URL from IP and port`() {
        val config = mockk<APPConfig>(relaxed = true)
        every { config.homeAssistantURL } returns ""
        every { config.homeAssistantConnectedIP } returns "192.168.1.12"
        every { config.homeAssistantHTTPPort } returns 8123
        every { config.homeAssistantDashboard } returns ""

        val result = AuthUtils.getHAUrl(config)

        assertEquals("http://192.168.1.12:8123", result)
    }

    @Test
    fun `getHAUrl strips trailing slash and appends dashboard path`() {
        val config = mockk<APPConfig>(relaxed = true)
        every { config.homeAssistantURL } returns "https://ha.example.com/"
        every { config.homeAssistantDashboard } returns "/lovelace/default_view"

        val result = AuthUtils.getHAUrl(config)

        assertEquals("https://ha.example.com/lovelace/default_view", result)
    }

    @Test
    fun `getHAUrl skips dashboard when withDashboardPath is false`() {
        val config = mockk<APPConfig>(relaxed = true)
        every { config.homeAssistantURL } returns "https://ha.example.com/"
        every { config.homeAssistantDashboard } returns "/lovelace/default_view"

        val result = AuthUtils.getHAUrl(config, withDashboardPath = false)

        assertEquals("https://ha.example.com", result)
    }
}
