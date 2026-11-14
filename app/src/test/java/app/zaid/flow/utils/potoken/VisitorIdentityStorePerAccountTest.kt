package app.zaid.flow.utils.potoken

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisitorIdentityStorePerAccountTest {
    private val values = HashMap<String, Any?>()

    private val prefs =
        mockk<SharedPreferences>(relaxed = true).also { p ->
            every { p.getString(any(), any()) } answers { values[firstArg()] as String? ?: secondArg() }
            every { p.getLong(any(), any()) } answers { values[firstArg()] as Long? ?: secondArg() }
            every { p.contains(any()) } answers { values.containsKey(firstArg()) }
            every { p.edit() } answers {
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); editor }
                every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
                every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
                editor
            }
        }

    private val store =
        VisitorIdentityStore(
            mockk<Context>(relaxed = true).also { every { it.getSharedPreferences(any(), any()) } returns prefs },
        )

    private val now = 1_000_000_000L

    @Test
    fun eachAccountKeepsItsOwnVisitor() {
        store.saveForAccount("a", "visitor-a", now)
        store.saveForAccount("b", "visitor-b", now)
        assertEquals("visitor-a", store.restoreForAccount("a", now + 1))
        assertEquals("visitor-b", store.restoreForAccount("b", now + 1))
    }

    @Test
    fun aStaleVisitorIsNotReused() {
        store.saveForAccount("a", "visitor-a", now)
        assertNull(store.restoreForAccount("a", now + VisitorIdentityAge.MAX_AGE_MS))
    }

    @Test
    fun anUnchangedVisitorKeepsItsOriginalAge() {
        store.saveForAccount("a", "visitor-a", now)
        store.saveForAccount("a", "visitor-a", now + VisitorIdentityAge.MAX_AGE_MS - 1)
        assertNull(store.restoreForAccount("a", now + VisitorIdentityAge.MAX_AGE_MS))
    }

    @Test
    fun forgettingAnAccountDropsItsVisitor() {
        store.saveForAccount("a", "visitor-a", now)
        store.forgetAccount("a")
        assertNull(store.restoreForAccount("a", now + 1))
    }

    @Test
    fun changesAreFollowedIntoTheActiveAccount() {
        store.activeAccountId = "a"
        store.save("rotated", now)
        assertEquals("rotated", store.restoreActiveAccount(now + 1))
        store.save(null, now + 2)
        assertNull(store.restoreActiveAccount(now + 3))
    }
}
