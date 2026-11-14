package app.zaid.flow.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlowBackupReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val appData = """{"version":2,"timestamp":1,"viewHistory":[],"subscriptions":[]}"""
    private val accounts = """{"accounts":[],"activeId":null}"""
    private val engine = """{"timeVectors":{},"globalVector":{}}"""

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = FlowBackupReader.read(bytes, temp.newFolder())

    @Test
    fun `a backup written by Flow is read as is`() {
        val read =
            read(
                zipOf(
                    "app_data.json" to appData,
                    "engine_brain.json" to engine,
                    "accounts.json" to accounts,
                    "manifest.json" to """{"format":3,"entries":[]}""",
                ),
            )
        assertThat(read.parts.keys)
            .containsAtLeast(BackupPart.APP_DATA, BackupPart.ENGINE, BackupPart.ACCOUNTS_PLAIN, BackupPart.MANIFEST)
        assertThat(read.asEntries().keys).contains("app_data.json")
    }

    @Test
    fun `entries inside a folder and in another case are still found`() {
        val read = read(zipOf("Flow Backup/APP_DATA.JSON" to appData, "Flow Backup/Accounts.json" to accounts))
        assertThat(read.parts.keys).containsExactly(BackupPart.APP_DATA, BackupPart.ACCOUNTS_PLAIN)
    }

    @Test
    fun `an unfamiliar name is recognised by what is inside`() {
        val read = read(zipOf("library_export.json" to appData, "signins.json" to accounts, "brain.json" to engine))
        assertThat(read.parts.keys).containsAtLeast(BackupPart.APP_DATA, BackupPart.ACCOUNTS_PLAIN, BackupPart.ENGINE)
    }

    @Test
    fun `a bare app data file is accepted`() {
        assertThat(read(appData.toByteArray()).parts.keys).containsExactly(BackupPart.APP_DATA)
    }

    @Test
    fun `a bare app data file with a byte order mark is accepted`() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + appData.toByteArray()
        assertThat(read(withBom).parts.keys).containsExactly(BackupPart.APP_DATA)
    }

    @Test
    fun `a gzip wrapped backup is opened`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(zipOf("app_data.json" to appData)) } }
        assertThat(read(gz.toByteArray()).parts.keys).containsExactly(BackupPart.APP_DATA)
    }

    @Test
    fun `a zip inside a zip is opened`() {
        val inner = zipOf("app_data.json" to appData)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("flow_master_backup.zip"))
            zip.write(inner)
            zip.closeEntry()
        }
        assertThat(read(out.toByteArray()).parts.keys).containsExactly(BackupPart.APP_DATA)
    }

    @Test
    fun `an encrypted accounts part is told apart by its header`() {
        val blob = BackupCrypto.encrypt("""{"accounts":[]}""".toByteArray(), "pw")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("app_data.json"))
            zip.write(appData.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("accounts.enc"))
            zip.write(blob)
            zip.closeEntry()
        }
        val read = read(out.toByteArray())
        assertThat(read.parts.keys).containsAtLeast(BackupPart.APP_DATA, BackupPart.ACCOUNTS_ENCRYPTED)
        assertThat(read.parts[BackupPart.ACCOUNTS_ENCRYPTED]).isEqualTo(blob)
    }

    @Test
    fun `bytes are kept exactly as stored so manifest checksums still match`() {
        val read = read(zipOf("app_data.json" to appData))
        assertThat(sha256Hex(read.parts.getValue(BackupPart.APP_DATA))).isEqualTo(sha256Hex(appData.toByteArray()))
    }

    @Test
    fun `a file with nothing from Flow lists what it held`() {
        val error =
            assertThrows(UnrecognizedBackupException::class.java) {
                read(zipOf("holiday.jpg" to "not json", "notes.txt" to "hello"))
            }
        assertThat(error.found).containsExactly("holiday.jpg", "notes.txt")
    }

    @Test
    fun `junk bytes are refused`() {
        assertThrows(UnrecognizedBackupException::class.java) { read(byteArrayOf(1, 2, 3, 4, 5)) }
    }
}
