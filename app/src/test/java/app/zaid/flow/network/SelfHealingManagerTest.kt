package app.zaid.flow.network

import com.google.common.truth.Truth.assertThat
import io.ktor.client.call.NoTransformationFoundException
import kotlinx.serialization.SerializationException
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SelfHealingManagerTest {
    @Test
    fun `classifies the failures the app actually sees`() {
        assertThat(SelfHealingManager.classify(SerializationException("x"))).isEqualTo(SelfHealingManager.Failure.UNREADABLE_BODY)
        assertThat(SelfHealingManager.classify(SocketTimeoutException())).isEqualTo(SelfHealingManager.Failure.TIMEOUT)
        assertThat(SelfHealingManager.classify(UnknownHostException())).isEqualTo(SelfHealingManager.Failure.NO_NETWORK)
        assertThat(SelfHealingManager.classify(RuntimeException(SocketTimeoutException())))
            .isEqualTo(SelfHealingManager.Failure.TIMEOUT)
    }
}
