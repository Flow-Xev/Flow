package app.zaid.flow.player.sabr

import app.zaid.flow.player.sabr.core.SabrSessionState
import app.zaid.flow.player.sabr.proto.SabrContextSendingPolicy
import app.zaid.flow.player.sabr.proto.SabrContextUpdate
import app.zaid.flow.utils.protobuf.ProtobufWriter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SabrContextPolicyTest {
    @Test
    fun `packed context policy activates stops and discards types`() {
        val encoded =
            ProtobufWriter.encode {
                writeBytes(1, byteArrayOf(2, 3))
                writeInt32(2, 2)
                writeBytes(3, byteArrayOf(4))
            }
        val state =
            SabrSessionState().apply {
                updateFromContextUpdate(SabrContextUpdate(type = 2, value = byteArrayOf(2)))
                updateFromContextUpdate(SabrContextUpdate(type = 3, value = byteArrayOf(3)))
                updateFromContextUpdate(SabrContextUpdate(type = 4, value = byteArrayOf(4)))
            }

        state.updateFromContextSendingPolicy(SabrContextSendingPolicy.decode(encoded))

        assertThat(state.activeSabrContexts().map { it.type }).containsExactly(3)
        assertThat(state.unsentSabrContextTypes()).containsExactly(2)
    }
}
