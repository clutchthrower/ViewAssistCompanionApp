package com.msp1974.vacompanion.utils

import android.net.Uri
import com.msp1974.vacompanion.settings.APPConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUtilsTest {
    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun mockAuthResponseUri(
        authority: String = AuthUtils.CLIENT_URL,
        state: String? = null,
        code: String? = null
    ) {
        val mockUri = mockk<Uri>()
        every { mockUri.authority } returns authority
        every { mockUri.getQueryParameter("state") } returns state
        every { mockUri.getQueryParameter("code") } returns code

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockUri
    }

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

    @Test
    fun `validateAuthResponse is true for matching client and state`() {
        AuthUtils.state = "expected-state"
        mockAuthResponseUri(state = "expected-state", code = "abc123")

        val isValid = AuthUtils.validateAuthResponse(
            "http://vaca.homeassistant?state=expected-state&code=abc123"
        )

        assertTrue(isValid)
    }

    @Test
    fun `validateAuthResponse is false for mismatched state`() {
        AuthUtils.state = "expected-state"
        mockAuthResponseUri(state = "wrong-state", code = "abc123")

        val isValid = AuthUtils.validateAuthResponse(
            "http://vaca.homeassistant?state=wrong-state&code=abc123"
        )

        assertFalse(isValid)
    }

    @Test
    fun `getReturnAuthCode returns code when response is valid`() {
        AuthUtils.state = "expected-state"
        mockAuthResponseUri(state = "expected-state", code = "abc123")

        val code = AuthUtils.getReturnAuthCode(
            "http://vaca.homeassistant?state=expected-state&code=abc123"
        )

        assertEquals("abc123", code)
    }

    @Test
    fun `getReturnAuthCode returns empty string for invalid response`() {
        AuthUtils.state = "expected-state"
        mockAuthResponseUri(state = "wrong-state", code = "abc123")

        val code = AuthUtils.getReturnAuthCode(
            "http://vaca.homeassistant?state=wrong-state&code=abc123"
        )

        assertEquals("", code)
    }
}
