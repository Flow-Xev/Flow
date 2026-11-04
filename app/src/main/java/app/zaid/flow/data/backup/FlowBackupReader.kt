package app.zaid.flow.data.backup

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** The parts a Flow backup can hold, whatever the file they came in is called. */
internal enum class BackupPart(
    /** The name Flow itself writes the part under; everything downstream works with these. */
    val canonicalName: String,
) {
    APP_DATA("app_data.json"),
    ENGINE("engine_brain.json"),
    MUSIC_BRAIN("music_brain.json"),
    RECAP("recap_stats.json"),
    FAVOURITE_ARTISTS("favourite_artists.json"),
    ACCOUNTS_PLAIN(ACCOUNTS_PLAIN_ENTRY),
    ACCOUNTS_ENCRYPTED(ACCOUNTS_ENTRY),
    MANIFEST(MANIFEST_ENTRY),
}

/** A backup file that was opened but holds nothing Flow can restore; [found] is what it did hold. */
class UnrecognizedBackupException(
    val found: List<String>,
    detail: String,
) : Exception(detail)

/** What a backup file turned out to contain, with every part filed under the name Flow uses. */
internal class ReadBackup(
    val parts: Map<BackupPart, ByteArray>,
    /** The names of everything found in the file, for telling the person what was in it. */
    val found: List<String>,
) {
    /** The parts keyed by canonical entry name, the shape the restore code works on. */
    fun asEntries(): Map<String, ByteArray> = parts.entries.associate { (part, bytes) -> part.canonicalName to bytes }

    val hasRestorableData: Boolean get() = parts.keys.any { it != BackupPart.MANIFEST }
}

/**
 * Opens anything Flow has ever written as a backup, or that looks like one, and sorts what is
 * inside into [BackupPart]s. The older reader only accepted a ZIP whose entries had exactly the
 * names of the current format, so a backup that was re-zipped by a file manager, wrapped in a
 * folder, written by another version, or whose ZIP the streaming reader cannot handle was
 * rejected as "not a Flow backup" even though everything in it was fine.
 *
 * Accepted: a ZIP (entries in any folder, any case, names recognised or content sniffed; ZIPs the
 * streaming reader cannot read are retried with the random-access reader), a gzip-compressed
 * file, a ZIP inside a ZIP, and a bare JSON file (an app-data export, an engine export, ...).
 */
internal object FlowBackupReader {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_DEPTH = 3

    /**
     * @param workDir where a temporary copy may be written for the random-access ZIP fallback.
     * @throws UnrecognizedBackupException when nothing restorable is inside.
     */
    fun read(
        open: () -> InputStream?,
        workDir: File,
    ): ReadBackup {
        val bytes =
            open()?.use { it.readCapped() }
                ?: throw UnrecognizedBackupException(emptyList(), "Could not open the file")
        val parts = LinkedHashMap<BackupPart, ByteArray>()
        val found = ArrayList<String>()
        collect(bytes, "", parts, found, workDir, depth = 0)
        if (parts.keys.none { it != BackupPart.MANIFEST }) {
            throw UnrecognizedBackupException(found, "Nothing in this file is a Flow backup part")
        }
        return ReadBackup(parts, found)
    }

    internal fun read(
        bytes: ByteArray,
        workDir: File,
    ): ReadBackup = read({ ByteArrayInputStream(bytes) }, workDir)

    private fun collect(
        bytes: ByteArray,
        label: String,
        parts: MutableMap<BackupPart, ByteArray>,
        found: MutableList<String>,
        workDir: File,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || bytes.isEmpty()) return
        val body = bytes.withoutBom()
        when {
            body.isZip() -> {
                collectZip(body, parts, found, workDir, depth)
            }

            body.isGzip() -> {
                val inflated = runCatching { GZIPInputStream(ByteArrayInputStream(body)).use { it.readCapped() } }.getOrNull()
                if (inflated != null) collect(inflated, label, parts, found, workDir, depth + 1)
            }

            else -> {
                collectLoose(label.ifEmpty { "backup" }, body, parts, found)
            }
        }
    }

    private fun collectZip(
        zipBytes: ByteArray,
        parts: MutableMap<BackupPart, ByteArray>,
        found: MutableList<String>,
        workDir: File,
        depth: Int,
    ) {
        val entries = readZipStreaming(zipBytes) ?: readZipRandomAccess(zipBytes, workDir) ?: return
        entries.forEach { (path, data) ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            if (path.startsWith("__MACOSX/") || name.startsWith("._") || name.isEmpty()) return@forEach
            found += path
            if (data.withoutBom().let { it.isZip() || it.isGzip() }) {
                collect(data, path, parts, found, workDir, depth + 1)
            } else {
                collectLoose(name, data, parts, found)
            }
        }
    }

    /** One file that is not an archive: filed by what it is called, or by what is in it. */
    private fun collectLoose(
        name: String,
        data: ByteArray,
        parts: MutableMap<BackupPart, ByteArray>,
        found: MutableList<String>,
    ) {
        if (found.none { it == name || it.endsWith("/$name") }) found += name
        val part = classify(name, data) ?: return
        // Stored exactly as read: the manifest's checksums were taken over these very bytes.
        parts.putIfAbsent(part, data)
    }

    internal fun classify(
        fileName: String,
        data: ByteArray,
    ): BackupPart? {
        if (BackupCrypto.isEncrypted(data)) return BackupPart.ACCOUNTS_ENCRYPTED
        val name = fileName.lowercase().substringAfterLast('/').substringAfterLast('\\')
        val stem = name.substringBeforeLast('.').replace(Regex("[^a-z0-9]"), "")
        val byName =
            when {
                stem == "manifest" -> BackupPart.MANIFEST
                stem.startsWith("accounts") -> BackupPart.ACCOUNTS_PLAIN
                stem.contains("musicbrain") -> BackupPart.MUSIC_BRAIN
                stem.contains("favourite") || stem.contains("favorite") -> BackupPart.FAVOURITE_ARTISTS
                stem.contains("recap") -> BackupPart.RECAP
                stem.contains("enginebrain") || stem == "engine" || stem.contains("flowengine") -> BackupPart.ENGINE
                stem.contains("appdata") || stem.startsWith("flowbackup") -> BackupPart.APP_DATA
                else -> null
            }
        val keys = topLevelKeys(data)
        // The name says what a file should be; the contents decide whether it can be trusted.
        if (byName != null && (keys != null || byName == BackupPart.ENGINE || byName == BackupPart.MUSIC_BRAIN)) return byName
        return sniff(keys)
    }

    private fun sniff(keys: Set<String>?): BackupPart? {
        if (keys == null) return null
        return when {
            "entries" in keys && "format" in keys -> BackupPart.MANIFEST
            "accounts" in keys && "activeId" in keys -> BackupPart.ACCOUNTS_PLAIN
            "artistAffinity" in keys && "trackPlays" in keys -> BackupPart.MUSIC_BRAIN
            "globalVector" in keys && "timeVectors" in keys -> BackupPart.ENGINE
            APP_DATA_KEYS.any { it in keys } -> BackupPart.APP_DATA
            "version" in keys && "timestamp" in keys -> BackupPart.APP_DATA
            else -> null
        }
    }

    private val APP_DATA_KEYS =
        setOf(
            "viewHistory",
            "subscriptions",
            "searchHistory",
            "likedVideos",
            "playlists",
            "playlistVideos",
            "subscriptionGroups",
            "settings",
            "contentPreferences",
        )

    /** The names at the top of a JSON object, without reading the (possibly huge) values; null if it is not one. */
    private fun topLevelKeys(data: ByteArray): Set<String>? =
        runCatching {
            val text = data.withoutBom()
            val first = text.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
            if (first != '{') return@runCatching null
            val reader = JsonReader(InputStreamReader(ByteArrayInputStream(text), Charsets.UTF_8))
            reader.setStrictness(Strictness.LENIENT)
            val keys = LinkedHashSet<String>()
            reader.beginObject()
            while (reader.hasNext() && reader.peek() == JsonToken.NAME) {
                keys += reader.nextName()
                reader.skipValue()
            }
            keys
        }.getOrNull()

    /** ZipInputStream: fast, but it cannot read stored entries that carry a data descriptor. */
    private fun readZipStreaming(bytes: ByteArray): List<Pair<String, ByteArray>>? =
        runCatching {
            val out = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) out += entry.name to zip.readCapped()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** ZipFile: reads the central directory, so it copes with archives written by other tools. */
    private fun readZipRandomAccess(
        bytes: ByteArray,
        workDir: File,
    ): List<Pair<String, ByteArray>>? {
        val temp =
            runCatching {
                workDir.mkdirs()
                File.createTempFile("flow_restore_", ".zip", workDir).also { it.writeBytes(bytes) }
            }.getOrNull() ?: return null
        return try {
            ZipFile(temp)
                .use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .map { entry -> entry.name to zip.getInputStream(entry).use { it.readCapped() } }
                        .toList()
                }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private fun InputStream.readCapped(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw IllegalStateException("Backup is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ByteArray.isZip(): Boolean = size > 3 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()

    private fun ByteArray.isGzip(): Boolean = size > 2 && this[0] == 0x1F.toByte() && this[1] == 0x8B.toByte()

    private fun ByteArray.withoutBom(): ByteArray =
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) copyOfRange(3, size) else this
}
