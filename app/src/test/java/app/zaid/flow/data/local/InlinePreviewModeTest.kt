package app.zaid.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InlinePreviewModeTest {
    private val wifi = InlinePreviewConditions(online = true, onWifi = true, metered = false, dataSaver = false, powerSave = false)
    private val mobile = wifi.copy(onWifi = false, metered = true)

    @Test
    fun offNeverPlays() {
        assertThat(inlinePreviewAllowed(InlinePreviewMode.OFF, wifi)).isFalse()
        assertThat(inlinePreviewAllowed(InlinePreviewMode.OFF, mobile)).isFalse()
    }

    @Test
    fun wifiOnlyPlaysOnUnmeteredWifiAndNowhereElse() {
        assertThat(inlinePreviewAllowed(InlinePreviewMode.WIFI_ONLY, wifi)).isTrue()
        assertThat(inlinePreviewAllowed(InlinePreviewMode.WIFI_ONLY, mobile)).isFalse()
    }

    @Test
    fun wifiOnlySkipsAWifiNetworkTheUserMarkedMetered() {
        assertThat(inlinePreviewAllowed(InlinePreviewMode.WIFI_ONLY, wifi.copy(metered = true))).isFalse()
    }

    @Test
    fun alwaysPlaysOnMobileData() {
        assertThat(inlinePreviewAllowed(InlinePreviewMode.ALWAYS, mobile)).isTrue()
    }

    @Test
    fun dataSaverDoesNotBlockPreviews() {
        assertThat(inlinePreviewAllowed(InlinePreviewMode.ALWAYS, mobile.copy(dataSaver = true))).isTrue()
        assertThat(inlinePreviewAllowed(InlinePreviewMode.ALWAYS, wifi.copy(dataSaver = true))).isTrue()
        assertThat(inlinePreviewAllowed(InlinePreviewMode.WIFI_ONLY, wifi.copy(dataSaver = true))).isTrue()
    }

    @Test
    fun batterySaverDoesNotBlockPreviews() {
        assertThat(inlinePreviewAllowed(InlinePreviewMode.ALWAYS, mobile.copy(powerSave = true))).isTrue()
        assertThat(inlinePreviewAllowed(InlinePreviewMode.WIFI_ONLY, wifi.copy(powerSave = true))).isTrue()
    }

    @Test
    fun offlineBlocksEveryMode() {
        InlinePreviewMode.entries.forEach { mode ->
            assertThat(inlinePreviewAllowed(mode, wifi.copy(online = false))).isFalse()
        }
    }

    @Test
    fun unknownStoredValueFallsBackToWifiOnly() {
        assertThat(InlinePreviewMode.fromStored(null)).isEqualTo(InlinePreviewMode.WIFI_ONLY)
        assertThat(InlinePreviewMode.fromStored("nonsense")).isEqualTo(InlinePreviewMode.WIFI_ONLY)
        assertThat(InlinePreviewMode.fromStored("ALWAYS")).isEqualTo(InlinePreviewMode.ALWAYS)
    }
}
