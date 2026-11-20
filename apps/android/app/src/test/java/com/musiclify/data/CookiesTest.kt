package com.musiclify.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookiesTest {
    @Test fun keepsOnlyYoutubeCookies() {
        val txt = """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	0	SID	abc
            #HttpOnly_.youtube.com	TRUE	/	TRUE	0	HSID	def
            .google.com	TRUE	/	TRUE	0	NID	nope
        """.trimIndent()
        assertEquals("SID=abc; HSID=def", Prefs.parseCookiesTxt(txt))
    }

    @Test fun returnsNullWhenThereAreNone() {
        assertNull(Prefs.parseCookiesTxt("# empty\n"))
    }
}
