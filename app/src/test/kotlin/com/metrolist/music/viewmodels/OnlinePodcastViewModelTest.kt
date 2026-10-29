package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.music.playback.DownloadMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlinePodcastViewModelTest {
    private fun episode(id: String, title: String = "Episode $id") = EpisodeItem(
        id = id,
        title = title,
        author = null,
        podcast = null,
        duration = 1800,
        publishDateText = null,
        thumbnail = "https://example.com/$id.jpg",
    )

    @Test
    fun `bridgeDownloadMetrics populates episode id when raw metrics key is URL`() {
        val episodes = listOf(episode("ep123"), episode("ep456"))
        val metrics = DownloadMetrics(progress = 0.42f, speedText = "1.5 MB/s", etaText = "ETA: 20s")
        val rawMetrics = mapOf(
            "https://music.youtube.com/watch?v=ep123" to metrics,
        )

        val bridged = bridgeDownloadMetrics(rawMetrics, episodes)

        // Raw key preserved
        assertEquals(metrics, bridged["https://music.youtube.com/watch?v=ep123"])
        // Mapped episode.id populated
        assertEquals(metrics, bridged["ep123"])
    }

    @Test
    fun `bridgeDownloadMetrics populates episode id when raw metrics key is already episode id`() {
        val episodes = listOf(episode("ep123"))
        val metrics = DownloadMetrics(progress = 0.85f, speedText = "2.1 MB/s", etaText = "ETA: 5s")
        val rawMetrics = mapOf("ep123" to metrics)

        val bridged = bridgeDownloadMetrics(rawMetrics, episodes)

        assertEquals(metrics, bridged["ep123"])
    }

    @Test
    fun `bridgeDownloadMetrics returns empty map when raw metrics is empty`() {
        val episodes = listOf(episode("ep123"))
        val bridged = bridgeDownloadMetrics(emptyMap(), episodes)

        assertEquals(emptyMap<String, DownloadMetrics>(), bridged)
    }
}

