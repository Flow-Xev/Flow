package app.zaid.flow.ui.screens.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldsFullSessionTest {
    @Test
    fun acceptsASessionWithSignatureKeyAndSessionId() {
        assertTrue("YSC=a; SAPISID=b; SID=c".holdsFullSession())
        assertTrue("__Secure-3PAPISID=b; __Secure-3PSID=c".holdsFullSession())
    }

    @Test
    fun rejectsAJarMissingEitherHalf() {
        assertFalse("SAPISID=b; YSC=a".holdsFullSession())
        assertFalse("SID=c; YSC=a".holdsFullSession())
        assertFalse("".holdsFullSession())
    }

    @Test
    fun matchesNamesNotValues() {
        assertFalse("YSC=SAPISID; VISITOR=SID".holdsFullSession())
    }
}
