package app.zaid.flow.discord

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiscordFossIsolationTest {
    @Test
    fun `foss classpath excludes functional Discord implementation`() {
        val forbiddenClasses =
            listOf(
                "app.zaid.flow.discord.DiscordTokenStore",
                "app.zaid.flow.discord.DiscordAuthTokens",
                "app.zaid.flow.discord.DiscordPlaybackSource",
                "app.zaid.flow.discord.DiscordPresenceCoordinator",
                "app.zaid.flow.discord.KizzyDiscordPresenceTransport",
                "app.zaid.flow.discord.KizzyGatewayProtocol",
            )

        forbiddenClasses.forEach { className ->
            assertThat(runCatching { Class.forName(className) }.isFailure).isTrue()
        }
    }

    @Test
    fun `foss runtime reports Discord unavailable`() {
        assertThat(DiscordPresenceRuntime.settingsState.value.isAvailable).isFalse()
        assertThat(DiscordPresenceRuntime.settingsState.value.isEnabled).isFalse()
        assertThat(DiscordPresenceRuntime.settingsState.value.summary)
            .isEqualTo(DiscordSettingsSummary.UNAVAILABLE)
    }
}
