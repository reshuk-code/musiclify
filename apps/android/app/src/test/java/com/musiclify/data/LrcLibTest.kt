package com.musiclify.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcLibTest {
    @Test fun parsesTimestampsAndDropsEmptyLines() {
        val lines = LrcLib.parseLrc("[00:01.50] First line\n[01:02.00]\n[01:05.25]  Second   line \nnot a lyric")
        assertEquals(listOf(LyricLine(1500, "First line"), LyricLine(65250, "Second line")), lines)
    }

    @Test fun stripsWordLevelTags() {
        assertEquals("Hello world", LrcLib.parseLrc("[00:10.00]<00:10.00>Hello <00:10.50>world").single().text)
    }

    @Test fun cleanTitleStripsFeaturesAndVersions() {
        assertEquals("One Dance", LrcLib.cleanTitle("One Dance (feat. Wizkid & Kyla)"))
        assertEquals("Hey Jude", LrcLib.cleanTitle("Hey Jude - Remastered 2015"))
        assertEquals("Plain Title", LrcLib.cleanTitle("Plain Title"))
    }

    @Test fun lyricsSurviveAJsonRoundTrip() {
        val lyrics = Lyrics(true, listOf(LyricLine(0, "a"), LyricLine(1234, "b \"quoted\"")))
        assertEquals(lyrics, Lyrics.fromJson(lyrics.toJson()))
    }
}
