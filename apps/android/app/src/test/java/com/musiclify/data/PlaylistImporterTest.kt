package com.musiclify.data

import com.musiclify.data.PlaylistImporter.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistImporterTest {
    @Test
    fun recognisesSpotifyLinks() {
        assertEquals(Link.Spotify("playlist", "37i9dQZF1DXcBWIGoYBM5M"),
            PlaylistImporter.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"))
        assertEquals(Link.Spotify("album", "4aawyAB9vmqN3uQ7FjRGTy"),
            PlaylistImporter.parse("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy"))
    }

    @Test
    fun recognisesYouTubeLinks() {
        assertEquals(Link.YouTube("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"),
            PlaylistImporter.parse("https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
        assertEquals(Link.YouTube("RDCLAK5uy_abc"),
            PlaylistImporter.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDCLAK5uy_abc"))
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(PlaylistImporter.parse("https://open.spotify.com/track/123"))
        assertNull(PlaylistImporter.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(PlaylistImporter.parse("hello"))
    }
}
