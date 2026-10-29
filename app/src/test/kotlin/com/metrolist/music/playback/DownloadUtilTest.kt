package com.metrolist.music.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUtilTest {
    @Test
    fun `completed downloads are not prepared again`() {
        assertFalse(shouldPrepareDownload(Download.STATE_COMPLETED))
        assertTrue(shouldPrepareDownload(Download.STATE_FAILED))
        assertTrue(shouldPrepareDownload(null))
    }

    @Test
    fun `downloadArtworkUrls keeps distinct song and album covers`() {
        assertEquals(
            listOf("song-cover", "album-cover"),
            downloadArtworkUrls("song-cover", "album-cover"),
        )
        assertEquals(
            listOf("song-cover"),
            downloadArtworkUrls("song-cover", "song-cover"),
        )
        assertEquals(emptyList<String>(), downloadArtworkUrls("", null))
    }

    @Test
    fun `calculateDownloadProgressPercentage computes accurate percentage from bytes and content length`() {
        assertEquals(50f, calculateDownloadProgressPercentage(bytesDownloaded = 50L, contentLength = 100L), 0.001f)
        assertEquals(100f, calculateDownloadProgressPercentage(bytesDownloaded = 100L, contentLength = 100L), 0.001f)
        assertEquals(0f, calculateDownloadProgressPercentage(bytesDownloaded = 0L, contentLength = 100L), 0.001f)
        assertEquals(25.5f, calculateDownloadProgressPercentage(bytesDownloaded = 255L, contentLength = 1000L), 0.001f)
        // Fallback to percentDownloaded when contentLength is unknown
        assertEquals(75f, calculateDownloadProgressPercentage(bytesDownloaded = 0L, contentLength = -1L, percentDownloaded = 75f), 0.001f)
        // Defaults to 0 when both are unknown
        assertEquals(0f, calculateDownloadProgressPercentage(bytesDownloaded = 0L, contentLength = -1L, percentDownloaded = -1f), 0.001f)
    }

    @Test
    fun `mapDownloadIdToEpisodeId bridges various download ID formats to episode ID`() {
        // Direct ID match
        assertEquals("dQw4w9WgXcQ", mapDownloadIdToEpisodeId("dQw4w9WgXcQ"))

        // YouTube watch URL with v query param
        assertEquals("dQw4w9WgXcQ", mapDownloadIdToEpisodeId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", mapDownloadIdToEpisodeId("https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ&index=2"))

        // YouTube short URL
        assertEquals("dQw4w9WgXcQ", mapDownloadIdToEpisodeId("https://youtu.be/dQw4w9WgXcQ"))

        // YouTube embed URL
        assertEquals("dQw4w9WgXcQ", mapDownloadIdToEpisodeId("https://www.youtube.com/embed/dQw4w9WgXcQ"))

        // Stream URL or arbitrary URL containing candidate ID
        assertEquals("ep12345", mapDownloadIdToEpisodeId("https://googlevideo.com/playback/ep12345/audio.mp4", listOf("ep12345")))

        // Blank input
        assertEquals("", mapDownloadIdToEpisodeId(""))
    }

    @Test
    fun `formatDownloadSpeed formats speed in MB per second accurately`() {
        assertEquals("1.0 MB/s", formatDownloadSpeed(1024.0 * 1024.0))
        assertEquals("2.5 MB/s", formatDownloadSpeed(2.5 * 1024.0 * 1024.0))
        assertEquals("0.0 MB/s", formatDownloadSpeed(0.0))
    }

    @Test
    fun `formatDownloadEta formats remaining time correctly`() {
        // Unknown or zero speed
        assertEquals("ETA: --", formatDownloadEta(-1L, 1024.0 * 1024.0))
        assertEquals("ETA: --", formatDownloadEta(1024L, 0.0))
        // 0 remaining
        assertEquals("ETA: 0s", formatDownloadEta(0L, 1024.0 * 1024.0))
        // Seconds
        assertEquals("ETA: 30s", formatDownloadEta(30L * 1024 * 1024, 1024.0 * 1024.0))
        // Minutes and seconds
        assertEquals("ETA: 1m 30s", formatDownloadEta(90L * 1024 * 1024, 1024.0 * 1024.0))
        // Hours and minutes
        assertEquals("ETA: 1h 5m", formatDownloadEta(3900L * 1024 * 1024, 1024.0 * 1024.0))
    }
}
