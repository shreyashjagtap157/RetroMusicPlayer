package code.name.monkey.retromusic.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsManagerTest {

    @Test
    fun testFetchLyricsFromLocal() {
        val lyricsManager = LyricsManager()
        val title = "Test Song"
        val artist = "Test Artist"

        // Assuming a test LRC file exists in the "lyrics" directory
        val lyrics = lyricsManager.fetchLyricsFromLocal(title, artist)
        assertNotNull("Lyrics should be fetched successfully", lyrics)
    }

    @Test
    fun testParseLrcContent() {
        val lyricsManager = LyricsManager()
        val lrcContent = """
            [00:12.00]Line 1 lyrics
            [00:34.00]Line 2 lyrics
        """.trimIndent()

        val parsedLyrics = lyricsManager.parseLrcContent(lrcContent)
        assertEquals("Line 1 lyrics", parsedLyrics["00:12.00"])
        assertEquals("Line 2 lyrics", parsedLyrics["00:34.00"])
    }
}