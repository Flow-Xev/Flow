package app.zaid.flow.data.backup

import androidx.annotation.StringRes
import app.zaid.flow.R
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The version written into a master backup's manifest. Older files have no manifest at all. */
internal const val MASTER_FORMAT_VERSION = 3

internal const val MANIFEST_ENTRY = "manifest.json"

/** Accounts sealed with a password. Only older backups have it; new ones use [ACCOUNTS_PLAIN_ENTRY]. */
internal const val ACCOUNTS_ENTRY = "accounts.enc"

/** The signed-in accounts as JSON, written by current backups. */
internal const val ACCOUNTS_PLAIN_ENTRY = "accounts.json"

/**
 * One independently restorable part of a master backup. The restore screen lists the ones a file
 * contains, so a backup can be restored in part.
 */
enum class BackupSection(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    LIBRARY(R.string.backup_section_library, R.string.backup_section_library_desc),
    SETTINGS(R.string.backup_section_settings, R.string.backup_section_settings_desc),
    VIDEO_ENGINE(R.string.backup_section_video_engine, R.string.backup_section_video_engine_desc),
    MUSIC_ENGINE(R.string.backup_section_music_engine, R.string.backup_section_music_engine_desc),
    STATS(R.string.backup_section_stats, R.string.backup_section_stats_desc),
    ACCOUNTS(R.string.backup_section_accounts, R.string.backup_section_accounts_desc),
    ;

    companion object {
        /** What a restore applies when the caller does not choose: everything except sign-ins. */
        val DEFAULT: Set<BackupSection> = entries.toSet() - ACCOUNTS
    }
}

/** How much of each kind of data a backup holds, for the preview before restoring. */
data class BackupCounts(
    val history: Int = 0,
    val searches: Int = 0,
    val subscriptions: Int = 0,
    val playlists: Int = 0,
    val likes: Int = 0,
    val notes: Int = 0,
)

data class ManifestEntry(
    val name: String = "",
    val size: Long = 0L,
    val sha256: String = "",
)

/** Written last into a master backup; lets a restore check the file and preview it before touching anything. */
data class BackupManifest(
    val format: Int = MASTER_FORMAT_VERSION,
    val appVersion: String = "",
    val createdAt: Long = 0L,
    val device: String = "",
    val entries: List<ManifestEntry> = emptyList(),
    val counts: BackupCounts = BackupCounts(),
    val accountCount: Int = 0,
)

/** What reading a backup file found, before anything is restored. */
data class BackupInspection(
    val createdAt: Long,
    val appVersion: String,
    val device: String,
    val sections: Set<BackupSection>,
    val counts: BackupCounts,
    val accountCount: Int,
    /** True for backups from older versions, whose accounts were locked with a password. */
    val accountsEncrypted: Boolean = false,
    /** Entries whose contents no longer match the checksum recorded when the backup was made. */
    val damagedEntries: List<String>,
    /** The sections those entries belong to; they cannot be restored, the rest can. */
    val damagedSections: Set<BackupSection>,
    /** False for backups made before manifests existed; they restore, but cannot be verified. */
    val verified: Boolean,
) {
    val isIntact: Boolean get() = damagedEntries.isEmpty()
    val hasAccounts: Boolean get() = BackupSection.ACCOUNTS in sections
}

/** The parts a master backup is made of besides the app data and the video engine. */
class MasterExtras(
    val musicBrain: ByteArray? = null,
    val recap: ByteArray? = null,
    val favouriteArtists: ByteArray? = null,
    /** The accounts as JSON; they are restored on this device without any password. */
    val accounts: ByteArray? = null,
    val accountCount: Int = 0,
)

class WrongBackupPasswordException : Exception("wrong_password")

class DamagedBackupException(
    val entries: List<String>,
) : Exception("damaged_backup")

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Password protection for the one part of a backup that holds sign-in sessions. The key comes from
 * the password with PBKDF2; the data is sealed with AES-256-GCM, so a wrong password or a changed
 * byte is detected instead of producing garbage.
 */
object BackupCrypto {
    private val MAGIC = "FLOWENC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun isEncrypted(blob: ByteArray): Boolean = blob.size > MAGIC.size && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(
        plain: ByteArray,
        password: String,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    /** @throws WrongBackupPasswordException when the password is wrong or the data was changed. */
    fun decrypt(
        blob: ByteArray,
        password: String,
    ): ByteArray {
        if (!isEncrypted(blob) || blob.size < MAGIC.size + SALT_BYTES + IV_BYTES + 1) {
            throw IllegalArgumentException("Not an encrypted backup part")
        }
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = blob.copyOfRange(MAGIC.size + SALT_BYTES, MAGIC.size + SALT_BYTES + IV_BYTES)
        val body = blob.copyOfRange(MAGIC.size + SALT_BYTES + IV_BYTES, blob.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        } catch (_: AEADBadTagException) {
            throw WrongBackupPasswordException()
        } catch (_: BadPaddingException) {
            // Some providers report a failed GCM tag as a plain BadPaddingException.
            throw WrongBackupPasswordException()
        }
    }

    private fun key(
        password: String,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
