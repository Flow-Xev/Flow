package app.zaid.flow.player.network

import app.zaid.flow.player.network.StreamingNetwork.Kind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamingNetworkTest {
    private fun cell(kbps: Int) = StreamingNetwork.classify(wifi = false, ethernet = false, cellular = true, downstreamKbps = kbps)

    @Test
    fun `wifi wins over cellular`() {
        assertThat(StreamingNetwork.classify(true, false, true, 1)).isEqualTo(Kind.WIFI)
    }

    @Test
    fun `cellular generations follow the reported rate`() {
        assertThat(cell(100)).isEqualTo(Kind.CELL_2G)
        assertThat(cell(2_000)).isEqualTo(Kind.CELL_3G)
        assertThat(cell(30_000)).isEqualTo(Kind.CELL_4G)
        assertThat(cell(150_000)).isEqualTo(Kind.CELL_5G)
        assertThat(cell(0)).isEqualTo(Kind.CELL_4G)
    }

    @Test
    fun `only 3G and 2G are slow cellular`() {
        assertThat(Kind.CELL_3G.isSlowCellular).isTrue()
        assertThat(Kind.CELL_4G.isSlowCellular).isFalse()
        assertThat(Kind.WIFI.isCellular).isFalse()
    }
}
