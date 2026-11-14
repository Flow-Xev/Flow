package app.zaid.flow.ui.screens.sync

import app.zaid.flow.sync.protocol.SyncCollection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncCollectionsTest {
    @Test
    fun `an Android receiver accepts everything the picker lets a sender choose`() {
        assertThat(SyncCollection.ANDROID_SYNCABLE).containsAtLeastElementsIn(COLLECTION_KEYS)
    }
}
