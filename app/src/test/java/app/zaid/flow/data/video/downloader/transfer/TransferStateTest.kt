package app.zaid.flow.data.video.downloader.transfer

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun stream(
        itag: Int,
        size: Long,
        fileSize: Long = size,
    ) = TransferStream(StreamRole.VIDEO, "u", folder.newFile().apply { writeBytes(ByteArray(fileSize.toInt())) }, itag, size)

    @Test
    fun `a saved block map comes back from disk`() {
        val original = stream(137, 100)
        original.completedBlocks.addAll(listOf(0, 2))
        original.partialBlockBytes[1] = 17L
        val file = folder.root.resolve("state.json")

        TransferState.write(file, TransferState.of(TransferJob("v", listOf(original), 1, "ua")))
        val restored = stream(137, 100)
        val didRestore = TransferState.read(file)!!.restoreInto(listOf(restored))

        assertThat(didRestore).isTrue()
        assertThat(restored.completedBlocks).containsExactly(0, 2)
        assertThat(restored.partialBlockBytes).containsExactly(1, 17L)
    }

    @Test
    fun `blocks never land on a different stream or a resized part`() {
        val saved = TransferState(listOf(TransferState.StreamState(StreamRole.VIDEO, 137, 100, listOf(0), emptyMap())))

        val otherItag = stream(248, 100)
        val otherSize = stream(137, 200)
        val shortFile = stream(137, 100, fileSize = 10)

        assertThat(saved.restoreInto(listOf(otherItag))).isFalse()
        assertThat(saved.restoreInto(listOf(otherSize))).isFalse()
        assertThat(saved.restoreInto(listOf(shortFile))).isFalse()
        assertThat(otherItag.completedBlocks + otherSize.completedBlocks + shortFile.completedBlocks).isEmpty()
    }


    @Test
    fun `sequential stream resumes only when the file is exactly its contiguous prefix`() {
        val blockSize = RangeDownloader.BLOCK_SIZE
        val savedFile = folder.newFile().apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(blockSize + 17L) }
        }
        val savedStream = TransferStream(StreamRole.VIDEO, "u", savedFile, 137, blockSize * 3, sequential = true)
        savedStream.completedBlocks.add(0)
        savedStream.partialBlockBytes[1] = 17L
        val stateFile = folder.root.resolve("sequential-state.json")
        TransferState.write(stateFile, TransferState.of(TransferJob("v", listOf(savedStream), 1, "ua")))

        val resumedFile = folder.root.resolve("resumed.part").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(blockSize + 17L) }
        }
        val resumed = TransferStream(StreamRole.VIDEO, "u", resumedFile, 137, blockSize * 3, sequential = true)
        assertThat(TransferState.read(stateFile)!!.restoreInto(listOf(resumed))).isTrue()
        assertThat(resumed.completedBlocks).containsExactly(0)
        assertThat(resumed.partialBlockBytes).containsExactly(1, 17L)

        val sparseFile = folder.root.resolve("sparse.part").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(blockSize * 3) }
        }
        val sparse = TransferStream(StreamRole.VIDEO, "u", sparseFile, 137, blockSize * 3, sequential = true)
        assertThat(TransferState.read(stateFile)!!.restoreInto(listOf(sparse))).isFalse()
        assertThat(sparse.completedBlocks).isEmpty()
    }

    @Test
    fun `an unreadable state file restores nothing`() {
        val file = folder.newFile().apply { writeText("{broken") }

        assertThat(TransferState.read(file)).isNull()
    }
}
