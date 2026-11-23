package com.musiclify.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerTest {
    @Test
    fun spotsGreatestHitsCompilations() {
        assertTrue(Deezer.looksLikeCompilation("Curtain Call: The Hits"))
        assertTrue(Deezer.looksLikeCompilation("The Very Best Of Fleetwood Mac"))
        assertTrue(Deezer.looksLikeCompilation("Greatest Hits"))
        assertTrue(Deezer.looksLikeCompilation("Wedding Songs 2020"))
        assertFalse(Deezer.looksLikeCompilation("÷ (Deluxe)"))
        assertFalse(Deezer.looksLikeCompilation("The Eminem Show"))
        assertFalse(Deezer.looksLikeCompilation("Whitney")) // "hits" only as a whole word
    }

    @Test
    fun matchesTheSameSongAcrossReleases() {
        assertEquals("loseyourself", Deezer.baseTitle("Lose Yourself (From \"8 Mile\")"))
        assertEquals("stan", Deezer.baseTitle("Stan - Remastered 2009"))
        assertEquals("perfect", Deezer.baseTitle("Perfect"))
        assertEquals(Deezer.baseTitle("Shape of You"), Deezer.baseTitle("Shape Of You [Radio Edit]"))
    }
}
