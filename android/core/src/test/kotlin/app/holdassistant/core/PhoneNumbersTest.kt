package app.holdassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumbersTest {
    @Test fun formats() {
        assertEquals("+18009359935", PhoneNumbers.toE164("1-800-935-9935"))
        assertEquals("+18009359935", PhoneNumbers.toE164("(800) 935-9935"))
        assertEquals("+442071234567", PhoneNumbers.toE164("+44 20 7123 4567"))
        assertNull(PhoneNumbers.toE164("611"))
        assertEquals("(800) 935-9935", PhoneNumbers.pretty("+18009359935"))
    }
}
