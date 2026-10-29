/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadMetrics(
    val progress: Float,
    val speedText: String,
    val etaText: String,
)

private data class ProgressSnapshot(
    val bytesDownloaded: Long,
    val timestampMs: Long,
    val speedBytesPerSec: Double = 0.0,
)

@Singleton
class DownloadUtil(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
) {
    private val TAG = "DownloadUtil"
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = StreamUrlCache()
    private val streamHttpClient =
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadPreparations = Semaphore(3)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloadProgress = MutableStateFlow<Map<String, DownloadMetrics>>(emptyMap())
    val downloadMetrics: StateFlow<Map<String, DownloadMetrics>> = downloadProgress.asStateFlow()
    private val progressSnapshots = ConcurrentHashMap<String, ProgressSnapshot>()

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(streamHttpClient),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.let { cachedStream ->
                return@Factory dataSpec.withResolvedStream(cachedStream)
            }
            val cacheGeneration = songUrlCache.generation(mediaId)

            val playbackData = runBlocking(Dispatchers.IO) {
                val song = database.songEntity(mediaId)
                InnerTubeXPlayer.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    contentHints = ContentHints(
                        isExplicit = song?.explicit,
                        isUploaded = song?.isUploaded,
                    ),
                    allowBoundedRange = false,
                )
            }.getOrThrow()
            val format = playbackData.format

            val actualContentLength =
                format.contentLength?.takeIf { it > 0L } ?: run {
                    val request = okhttp3.Request.Builder()
                        .get()
                        .url(playbackData.streamUrl)
                        .apply {
                            playbackData.streamHeaders.forEach { (name, value) ->
                                header(name, value)
                            }
                        }
                        .header("Range", "bytes=0-0")
                        .build()
                    try {
                        streamHttpClient.newCall(request).execute().use { response ->
                            downloadContentLength(
                                statusCode = response.code,
                                contentRange = response.header("Content-Range"),
                                contentLength = response.header("Content-Length"),
                            )
                        }
                    } catch (_: IOException) {
                        null
                    }
                }

            database.query {
                if (actualContentLength != null) {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.substringBefore(";"),
                            codecs =
                                format.mimeType
                                    .substringAfter("codecs=", missingDelimiterValue = "")
                                    .substringBefore(";")
                                    .trim()
                                    .removeSurrounding("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = actualContentLength,
                            loudnessDb = playbackData.audioConfig?.loudnessDb,
                            perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                            playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                        ),
                    )
                } else {
                    deleteFormat(mediaId)
                }

                // Metadata registration only — dateDownload is intentionally NOT set here.
                // It belongs solely to onDownloadChanged()'s STATE_COMPLETED branch below,
                // which only fires once the download has actually finished. Setting it here
                // (at URL-resolve time, i.e. the moment the download merely *starts*) would
                // mark the song as "cached" before a single byte is written.
                val existing = getSongByIdBlocking(mediaId)?.song
                val updatedSong = existing ?: SongEntity(
                    id = mediaId,
                    title = playbackData.videoDetails?.title ?: "Unknown",
                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                    thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                    dateDownload = null,
                    isDownloaded = false
                )

                upsert(updatedSong)
            }

            // googlevideo throttles unbounded requests to roughly playback speed; a range param covering
            // the whole file lets the download run at full speed.
            val streamUrl =
                if (actualContentLength != null && "&range=" !in playbackData.streamUrl) {
                    "${playbackData.streamUrl}&range=0-${actualContentLength - 1}"
                } else {
                    playbackData.streamUrl
                }

            songUrlCache.put(
                mediaId = mediaId,
                url = streamUrl,
                requestHeaders = playbackData.streamHeaders,
                clientName = playbackData.streamClient,
                expiresInSeconds = playbackData.streamExpiresInSeconds,
                requireBoundedRange = playbackData.requireBoundedRange,
                rangeChunkSizeBytes = playbackData.rangeChunkSizeBytes,
                useRangeChunks = playbackData.useRangeChunks,
                expectedGeneration = cacheGeneration,
            )
            dataSpec.withResolvedStream(
                CachedStreamUrl(
                    url = streamUrl,
                    requestHeaders = playbackData.streamHeaders,
                    clientName = playbackData.streamClient,
                    requireBoundedRange = playbackData.requireBoundedRange,
                    rangeChunkSizeBytes = playbackData.rangeChunkSizeBytes,
                    useRangeChunks = playbackData.useRangeChunks,
                ),
            )
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        if (download.state == Download.STATE_FAILED && finalException.isExpiredStreamError()) {
                            songUrlCache.invalidate(download.request.id)
                        }

                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        if (download.state == Download.STATE_DOWNLOADING) {
                            val metrics = calculateDownloadMetrics(download)
                            downloadProgress.update { current ->
                                current.toMutableMap().apply {
                                    updateProgressForDownload(this, download, metrics)
                                    val activeMetrics = this
                                    Log.d("MetricsDebug", "Engine emitting: $activeMetrics")
                                }
                            }
                            startProgressPolling()
                        } else {
                            downloadProgress.update { current ->
                                current.toMutableMap().apply {
                                    removeProgressForDownload(this, download)
                                }
                            }
                            checkStopProgressPolling()
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    removeFromPlayerCache(download.request.id)
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        val downloadId = download.request.id
                        songUrlCache.invalidate(downloadId)

                        downloadProgress.update { current ->
                            current.toMutableMap().apply {
                                removeProgressForDownload(this, download)
                            }
                        }
                        checkStopProgressPolling()

                        runCatching {
                            database.updateDownloadedInfo(downloadId, false, null)
                        }.onSuccess {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    remove(downloadId)
                                }
                            }
                            Timber.tag(TAG).d("Successfully removed download $downloadId from in-memory map")
                        }.onFailure { error ->
                            Timber.tag(TAG).e(error, "Failed to update database for removed download $downloadId, keeping in-memory entry")
                        }
                    }
                }
            )
        }

    private var progressPollJob: Job? = null

    private fun updateProgressForDownload(
        map: MutableMap<String, DownloadMetrics>,
        download: Download,
        metrics: DownloadMetrics,
    ) {
        val id = download.request.id
        map[id] = metrics
        download.request.customCacheKey?.takeIf { it.isNotBlank() }?.let {
            map[it] = metrics
            val bridgedKey = mapDownloadIdToEpisodeId(it)
            if (bridgedKey.isNotBlank()) map[bridgedKey] = metrics
        }
        val uriStr = download.request.uri.toString()
        if (uriStr.isNotBlank()) {
            map[uriStr] = metrics
            val bridgedUri = mapDownloadIdToEpisodeId(uriStr)
            if (bridgedUri.isNotBlank()) map[bridgedUri] = metrics
        }
        val bridged = mapDownloadIdToEpisodeId(id)
        if (bridged.isNotBlank()) map[bridged] = metrics
    }

    private fun removeProgressForDownload(
        map: MutableMap<String, DownloadMetrics>,
        download: Download,
    ) {
        val id = download.request.id
        map.remove(id)
        progressSnapshots.remove(id)
        download.request.customCacheKey?.let {
            map.remove(it)
            val bridgedKey = mapDownloadIdToEpisodeId(it)
            if (bridgedKey.isNotBlank()) map.remove(bridgedKey)
        }
        val uriStr = download.request.uri.toString()
        map.remove(uriStr)
        val bridgedUri = mapDownloadIdToEpisodeId(uriStr)
        if (bridgedUri.isNotBlank()) map.remove(bridgedUri)
        val bridged = mapDownloadIdToEpisodeId(id)
        if (bridged.isNotBlank()) map.remove(bridged)
    }

    fun mapDownloadIdToEpisodeId(
        downloadId: String,
        candidateEpisodeIds: Collection<String> = emptyList(),
    ): String = resolveDownloadIdToEpisodeId(downloadId, candidateEpisodeIds)

    fun calculateProgressPercentage(download: Download): Float =
        com.metrolist.music.playback.calculateProgressPercentage(download)

    fun calculateDownloadMetrics(download: Download): DownloadMetrics {
        val progress = calculateProgressPercentage(download)
        val fraction = (progress / 100f).coerceIn(0f, 1f)
        val id = download.request.id
        val currentBytes = download.bytesDownloaded
        val contentLength = download.contentLength
        val now = System.currentTimeMillis()

        val prevSnapshot = progressSnapshots[id]
        val speedBytesPerSec = if (prevSnapshot != null && now > prevSnapshot.timestampMs) {
            val deltaBytes = (currentBytes - prevSnapshot.bytesDownloaded).coerceAtLeast(0L)
            val deltaTimeSeconds = (now - prevSnapshot.timestampMs) / 1000.0
            if (deltaTimeSeconds > 0.0) deltaBytes / deltaTimeSeconds else 0.0
        } else {
            0.0
        }

        progressSnapshots[id] = ProgressSnapshot(currentBytes, now, speedBytesPerSec)

        val speedText = formatDownloadSpeed(speedBytesPerSec)
        val remainingBytes = if (contentLength > 0L) (contentLength - currentBytes).coerceAtLeast(0L) else -1L
        val etaText = formatDownloadEta(remainingBytes, speedBytesPerSec)

        return DownloadMetrics(
            progress = fraction,
            speedText = speedText,
            etaText = etaText,
        )
    }

    private fun startProgressPolling() {
        if (progressPollJob?.isActive == true) return
        progressPollJob = scope.launch {
            while (isActive) {
                val currentDownloads = downloadManager.currentDownloads
                val activeDownloads = currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                if (activeDownloads.isEmpty()) {
                    break
                }
                downloadProgress.update { currentMap ->
                    val updated = currentMap.toMutableMap()
                    for (download in activeDownloads) {
                        val metrics = calculateDownloadMetrics(download)
                        updateProgressForDownload(updated, download, metrics)
                    }
                    val activeMetrics = updated
                    Log.d("MetricsDebug", "Engine emitting: $activeMetrics")
                    updated
                }
                downloads.update { currentMap ->
                    val updated = currentMap.toMutableMap()
                    for (download in activeDownloads) {
                        updated[download.request.id] = download
                    }
                    updated
                }
                delay(500)
            }
            progressPollJob = null
        }
    }

    private fun checkStopProgressPolling() {
        if (downloadManager.currentDownloads.none { it.state == Download.STATE_DOWNLOADING }) {
            progressPollJob?.cancel()
            progressPollJob = null
        }
    }

    fun updateDownloadsProgress(downloadsList: List<Download>) {
        val active = downloadsList.filter { it.state == Download.STATE_DOWNLOADING }
        if (active.isNotEmpty()) {
            downloadProgress.update { current ->
                val updated = current.toMutableMap()
                for (d in active) {
                    val metrics = calculateDownloadMetrics(d)
                    updateProgressForDownload(updated, d, metrics)
                }
                val activeMetrics = updated
                Log.d("MetricsDebug", "Engine emitting: $activeMetrics")
                updated
            }
        }
    }

    init {
        val result = mutableMapOf<String, Download>()
        val initialProgress = mutableMapOf<String, DownloadMetrics>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                result[download.request.id] = download
                if (download.state == Download.STATE_DOWNLOADING) {
                    val metrics = calculateDownloadMetrics(download)
                    updateProgressForDownload(initialProgress, download, metrics)
                }
            }
        }
        downloads.value = result
        downloadProgress.value = initialProgress
        if (initialProgress.isNotEmpty()) {
            startProgressPolling()
        }
        scope.launch {
            result.values
                .filter { it.state == Download.STATE_COMPLETED }
                .forEach { removeFromPlayerCache(it.request.id) }
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun getDownloadProgress(songId: String): Flow<Float?> = downloadProgress.map { it[songId]?.progress }

    fun getDownloadMetrics(songId: String): Flow<DownloadMetrics?> = downloadProgress.map { it[songId] }

    fun download(song: Song) = download(song.toMediaMetadata())

    fun download(song: SongItem) = download(song.toMediaMetadata())

    fun download(mediaMetadata: MediaMetadata) {
        scope.launch {
            downloadPreparations.withPermit {
                if (!shouldPrepareDownload(downloads.value[mediaMetadata.id]?.state)) return@withPermit

                mediaMetadata.album?.let { album ->
                    if (database.albumEntity(album.id) == null) {
                        database.insert(
                            AlbumEntity(
                                id = album.id,
                                title = album.title,
                                thumbnailUrl = mediaMetadata.thumbnailUrl,
                                songCount = 0,
                                duration = 0,
                            ),
                        )
                    }
                }

                val existing = database.getSongByIdBlocking(mediaMetadata.id)
                if (existing == null) {
                    database.insert(mediaMetadata)
                } else {
                    database.update(
                        existing,
                        mediaMetadata,
                        overwriteTitle = false,
                        overwriteArtists = false,
                    )
                }

                if (!shouldPrepareDownload(downloadManager.downloadIndex.getDownload(mediaMetadata.id)?.state)) {
                    return@withPermit
                }

                val request =
                    DownloadRequest
                        .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                        .setCustomCacheKey(mediaMetadata.id)
                        .setData(mediaMetadata.title.toByteArray())
                        .build()
                DownloadService.sendAddDownload(
                    context,
                    ExoDownloadService::class.java,
                    request,
                    false,
                )

                val albumArtwork = database.getSongByIdBlocking(mediaMetadata.id)?.album?.thumbnailUrl
                downloadArtworkUrls(mediaMetadata.thumbnailUrl, albumArtwork).forEach { artworkUrl ->
                    runCatching {
                        context.imageLoader.execute(
                            ImageRequest
                                .Builder(context)
                                .data(artworkUrl)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .networkCachePolicy(CachePolicy.ENABLED)
                                .build(),
                        )
                    }
                }
            }
        }
    }

    fun download(songId: String) {
        scope.launch {
            database.getSongByIdBlocking(songId)?.let { song ->
                download(song)
            }
        }
    }

    fun release() {
        progressPollJob?.cancel()
        scope.cancel()
    }

    private fun removeFromPlayerCache(songId: String) {
        runCatching { playerCache.removeResource(songId) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to remove downloaded song $songId from player cache") }
    }

    private fun Throwable?.isExpiredStreamError(): Boolean {
        var current = this
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException &&
                (current.responseCode == 403 || current.responseCode == 410 || current.responseCode == 416)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal fun shouldPrepareDownload(downloadState: Int?): Boolean = downloadState != Download.STATE_COMPLETED

internal fun calculateDownloadProgressPercentage(
    bytesDownloaded: Long,
    contentLength: Long,
    percentDownloaded: Float = -1f,
): Float {
    return if (contentLength > 0L) {
        ((bytesDownloaded.toFloat() / contentLength) * 100f).coerceIn(0f, 100f)
    } else if (percentDownloaded >= 0f) {
        percentDownloaded.coerceIn(0f, 100f)
    } else {
        0f
    }
}

internal fun calculateProgressPercentage(download: Download): Float {
    return calculateDownloadProgressPercentage(
        bytesDownloaded = download.bytesDownloaded,
        contentLength = download.contentLength,
        percentDownloaded = download.percentDownloaded,
    )
}

internal fun downloadArtworkUrls(
    songArtwork: String?,
    albumArtwork: String?,
): List<String> = listOfNotNull(songArtwork, albumArtwork).filter(String::isNotBlank).distinct()

internal fun downloadContentLength(
    statusCode: Int,
    contentRange: String?,
    contentLength: String?,
): Long? {
    val rangePattern =
        when (statusCode) {
            206 -> PARTIAL_CONTENT_RANGE
            416 -> UNSATISFIED_CONTENT_RANGE
            else -> null
        }
    if (rangePattern != null) {
        return contentRange
            ?.trim()
            ?.let(rangePattern::matchEntire)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }
    return if (statusCode == 200) contentLength?.toLongOrNull()?.takeIf { it > 0L } else null
}

private val PARTIAL_CONTENT_RANGE = Regex("""bytes\s+0-0/(\d+)""", RegexOption.IGNORE_CASE)
private val UNSATISFIED_CONTENT_RANGE = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)

internal fun resolveDownloadIdToEpisodeId(
    downloadId: String,
    candidateEpisodeIds: Collection<String> = emptyList(),
): String {
    if (downloadId.isBlank()) return ""

    if (candidateEpisodeIds.contains(downloadId)) {
        return downloadId
    }

    for (candidate in candidateEpisodeIds) {
        if (candidate.isNotBlank() && downloadId.contains(candidate)) {
            return candidate
        }
    }

    val vParamMatch = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(downloadId)
    if (vParamMatch != null) {
        val extracted = vParamMatch.groupValues[1]
        if (extracted.isNotBlank()) return extracted
    }

    val shortUrlMatch = Regex("""(?:youtu\.be/|/(?:v|embed|shorts)/)([a-zA-Z0-9_-]+)""").find(downloadId)
    if (shortUrlMatch != null) {
        val extracted = shortUrlMatch.groupValues[1]
        if (extracted.isNotBlank()) return extracted
    }

    val cleaned = downloadId.substringBefore("?").substringBefore("#")
    val lastSegment = cleaned.substringAfterLast("/").substringAfterLast(":")
    if (lastSegment.isNotBlank()) {
        return lastSegment
    }

    return downloadId
}

internal fun mapDownloadIdToEpisodeId(
    downloadId: String,
    candidateEpisodeIds: Collection<String> = emptyList(),
): String = resolveDownloadIdToEpisodeId(downloadId, candidateEpisodeIds)

internal fun formatDownloadSpeed(bytesPerSecond: Double): String {
    val mbps = bytesPerSecond / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB/s", mbps)
}

internal fun formatDownloadEta(remainingBytes: Long, bytesPerSecond: Double): String {
    if (remainingBytes == 0L) return "ETA: 0s"
    if (remainingBytes < 0L || bytesPerSecond <= 0.0) return "ETA: --"
    val etaSeconds = (remainingBytes / bytesPerSecond).toLong()
    val formatted = when {
        etaSeconds < 60 -> "${etaSeconds}s"
        etaSeconds < 3600 -> "${etaSeconds / 60}m ${etaSeconds % 60}s"
        else -> "${etaSeconds / 3600}h ${(etaSeconds % 3600) / 60}m"
    }
    return "ETA: $formatted"
}
