package app.zaid.flow.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    private val secret = """{"accounts":[]}""".toByteArray()

    @Test
    fun `sealed data opens with the same password`() {
        val sealed = BackupCrypto.encrypt(secret, "correct horse")

        assertThat(BackupCrypto.isEncrypted(sealed)).isTrue()
        assertThat(BackupCrypto.decrypt(sealed, "correct horse")).isEqualTo(secret)
    }

    @Test
    fun `the sealed bytes do not contain the plain text`() {
        val sealed = BackupCrypto.encrypt(secret, "correct horse")

        assertThat(String(sealed, Charsets.ISO_8859_1)).doesNotContain("accounts")
    }

    @Test
    fun `a wrong password is refused`() {
        val sealed = BackupCrypto.encrypt(secret, "correct horse")

        assertThrows(WrongBackupPasswordException::class.java) { BackupCrypto.decrypt(sealed, "battery staple") }
    }

    @Test
    fun `a changed byte is refused`() {
        val sealed = BackupCrypto.encrypt(secret, "correct horse")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()

        assertThrows(WrongBackupPasswordException::class.java) { BackupCrypto.decrypt(sealed, "correct horse") }
    }

    @Test
    fun `two sealings of the same data differ`() {
        val first = BackupCrypto.encrypt(secret, "correct horse")
        val second = BackupCrypto.encrypt(secret, "correct horse")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `checksums tell changed data apart`() {
        assertThat(sha256Hex("a".toByteArray())).isNotEqualTo(sha256Hex("b".toByteArray()))
        assertThat(sha256Hex("a".toByteArray())).isEqualTo(sha256Hex("a".toByteArray()))
    }
}
