package com.metrolist.music.playback

import com.metrolist.music.db.entities.EpisodeEntity
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime

class PodcastDownloadWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun calculateChunks(contentLength: Long): List<Pair<Long, Long>> {
        val chunkSize = contentLength / 4
        return (0 until 4).map { i ->
            val start = i * chunkSize
            val end = if (i == 3) contentLength - 1 else (i + 1) * chunkSize - 1
            start to end
        }
    }

    @Test
    fun `chunks partition file perfectly without gaps or overlaps`() {
        val testLengths = listOf(4L, 100L, 101L, 102L, 103L, 1024L, 1048576L)

        for (length in testLengths) {
            val chunks = calculateChunks(length)
            assertEquals(4, chunks.size)

            // Chunk 0 starts at 0
            assertEquals(0L, chunks[0].first)

            // Each chunk connects to the next
            for (i in 0 until 3) {
                assertEquals("Chunk $i end and Chunk ${i + 1} start must be contiguous",
                    chunks[i].second + 1, chunks[i + 1].first)
            }

            // Chunk 3 ends at length - 1
            assertEquals(length - 1, chunks[3].second)

            // Total bytes covered equals length
            val totalBytes = chunks.sumOf { (start, end) -> end - start + 1 }
            assertEquals(length, totalBytes)
        }
    }

    @Test
    fun `concurrent write to pre-allocated RandomAccessFile preserves chunk integrity`() = runBlocking {
        val totalLength = 4096
        val chunks = calculateChunks(totalLength.toLong())
        val tempFile = tempFolder.newFile("test_podcast.tmp")

        val randomAccessFile = RandomAccessFile(tempFile, "rw")
        randomAccessFile.setLength(totalLength.toLong())

        val expectedBytes = ByteArray(totalLength)
        for (i in 0 until totalLength) {
            expectedBytes[i] = (i % 256).toByte()
        }

        val fileLock = Any()

        val deferreds = chunks.map { (start, end) ->
            async(Dispatchers.IO) {
                val chunkSize = (end - start + 1).toInt()
                val chunkData = ByteArray(chunkSize)
                System.arraycopy(expectedBytes, start.toInt(), chunkData, 0, chunkSize)

                // Concurrently write chunk
                synchronized(fileLock) {
                    randomAccessFile.seek(start)
                    randomAccessFile.write(chunkData)
                }
            }
        }

        deferreds.awaitAll()
        randomAccessFile.close()

        val writtenBytes = tempFile.readBytes()
        assertEquals(totalLength, writtenBytes.size)
        assertArrayEquals(expectedBytes, writtenBytes)
    }

    @Test
    fun `episode entity supports localPath file URI and EpisodeEntity alias`() {
        val now = LocalDateTime.now()
        val episode: EpisodeEntity = SongEntity(
            id = "ep_123",
            title = "Episode 1: The Beginning",
            isEpisode = true,
            isDownloaded = true,
            dateDownload = now,
            localPath = "file:///data/user/0/com.metrolist.music/files/audio/ep_123",
        )

        assertTrue(episode.isEpisode)
        assertTrue(episode.isDownloaded)
        assertEquals(now, episode.dateDownload)
        assertEquals("file:///data/user/0/com.metrolist.music/files/audio/ep_123", episode.localPath)
    }

    private fun buildRangeUrl(url: String, start: Long, end: Long): String {
        val rangeParam = "range=$start-$end"
        return when {
            "&range=" in url -> url.replace(Regex("&range=[^&]+"), "&$rangeParam")
            "?range=" in url -> url.replace(Regex("\\?range=[^&]+"), "?$rangeParam")
            "?" in url -> "$url&$rangeParam"
            else -> "$url?$rangeParam"
        }
    }

    private fun formatEta(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return "Calculating..."
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    @Test
    fun `buildRangeUrl correctly injects or replaces range parameter to bypass throttling`() {
        val rawUrl = "https://rr3---sn-4g5edn6r.googlevideo.com/videoplayback?expire=123&ei=abc"
        assertEquals(
            "https://rr3---sn-4g5edn6r.googlevideo.com/videoplayback?expire=123&ei=abc&range=0-1000",
            buildRangeUrl(rawUrl, 0, 1000),
        )

        val existingRangeUrl = "https://googlevideo.com/playback?expire=123&range=0-99999&ei=abc"
        assertEquals(
            "https://googlevideo.com/playback?expire=123&range=500-1000&ei=abc",
            buildRangeUrl(existingRangeUrl, 500, 1000),
        )
    }

    @Test
    fun `formatEta produces human readable countdown`() {
        assertEquals("Calculating...", formatEta(null))
        assertEquals("Calculating...", formatEta(0))
        assertEquals("15s", formatEta(15))
        assertEquals("1m 15s", formatEta(75))
        assertEquals("1h 2m", formatEta(3720))
    }

    @Test
    fun `formatSpeed produces clean speed metrics`() {
        assertEquals("500 B/s", formatSpeed(500))
        assertEquals("50 KB/s", formatSpeed(50 * 1024))
        assertEquals("4.5 MB/s", formatSpeed((4.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `segment slicing covers chunk range completely without gaps`() {
        val chunkStart = 25_000_000L
        val chunkEnd = 50_000_000L
        val segmentSize = 4 * 1024 * 1024L

        var currentPos = chunkStart
        val segments = mutableListOf<Pair<Long, Long>>()
        while (currentPos <= chunkEnd) {
            val subEnd = minOf(currentPos + segmentSize - 1, chunkEnd)
            segments.add(currentPos to subEnd)
            currentPos = subEnd + 1
        }

        assertEquals(chunkStart, segments.first().first)
        assertEquals(chunkEnd, segments.last().second)
        for (i in 0 until segments.size - 1) {
            assertEquals(segments[i].second + 1, segments[i + 1].first)
        }
        val totalBytes = segments.sumOf { it.second - it.first + 1 }
        assertEquals(chunkEnd - chunkStart + 1, totalBytes)
    }
}

