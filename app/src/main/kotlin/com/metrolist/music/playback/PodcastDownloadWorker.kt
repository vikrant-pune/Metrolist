/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.metrolist.innertube.YouTube
import com.metrolist.music.R
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.InnerTubeXPlayer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PodcastDownloadWorkerEntryPoint {
    fun database(): MusicDatabase
    fun downloadUtil(): DownloadUtil
}

class PodcastDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: inputData.getString("id") ?: "podcast"
        val episodeTitle = inputData.getString(KEY_EPISODE_TITLE) ?: "Podcast Episode"
        val notificationId = (kotlin.math.abs(episodeId.hashCode()) % 100000) + 1000
        return createForegroundInfo(
            notificationId = notificationId,
            title = episodeTitle,
            content = "Preparing download...",
            subText = "ETA: Calculating...",
            percent = 0,
            indeterminate = true,
        )
    }

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID)
            ?: inputData.getString("id")
            ?: run {
                Timber.e("PodcastDownloadWorker: Missing episodeId")
                return Result.failure()
            }

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            PodcastDownloadWorkerEntryPoint::class.java,
        )
        val database = entryPoint.database()
        val downloadUtil = entryPoint.downloadUtil()

        val episodeTitle = inputData.getString(KEY_EPISODE_TITLE)
            ?: database.getSongByIdBlocking(episodeId)?.song?.title
            ?: "Podcast Episode"
        val notificationId = (kotlin.math.abs(episodeId.hashCode()) % 100000) + 1000

        // Set initial foreground info
        try {
            setForeground(
                createForegroundInfo(
                    notificationId = notificationId,
                    title = episodeTitle,
                    content = "Connecting...",
                    subText = "ETA: Calculating...",
                    percent = 0,
                    indeterminate = true,
                ),
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to set initial foreground info")
        }

        val song = database.getSongByIdBlocking(episodeId)?.song
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        var playbackResult = InnerTubeXPlayer.playerResponseForPlayback(
            episodeId,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivityManager,
            contentHints = com.metrolist.innertubex.extraction.ContentHints(
                isExplicit = song?.explicit,
                isUploaded = song?.isUploaded,
            ),
            allowBoundedRange = false,
        )
        if (playbackResult.isFailure) {
            playbackResult = InnerTubeXPlayer.playerResponseForPlayback(
                episodeId,
                audioQuality = AudioQuality.AUTO,
                connectivityManager = connectivityManager,
                contentHints = com.metrolist.innertubex.extraction.ContentHints(
                    isExplicit = song?.explicit,
                    isUploaded = song?.isUploaded,
                ),
                allowBoundedRange = true,
            )
        }
        val playbackData = playbackResult.getOrNull()
        val rawAudioUrl = inputData.getString(KEY_AUDIO_URL)
            ?: playbackData?.streamUrl
            ?: run {
                Timber.e("PodcastDownloadWorker: Failed to resolve audio URL for $episodeId")
                notificationManager.cancel(notificationId)
                return Result.failure()
            }

        val streamHeaders = playbackData?.streamHeaders ?: emptyMap()

        val httpClient = OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Logic: Inside doWork(), use OkHttp to fetch the Content-Length of the audio URL
        var contentLength: Long = playbackData?.format?.contentLength?.takeIf { it > 0L } ?: -1L

        if (contentLength <= 0L) {
            try {
                val headReqBuilder = Request.Builder().url(rawAudioUrl).head()
                streamHeaders.forEach { (k, v) -> headReqBuilder.header(k, v) }
                httpClient.newCall(headReqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "HEAD request failed for Content-Length")
            }
        }

        if (contentLength <= 0L) {
            try {
                val rangeProbeBuilder = Request.Builder()
                    .url(rawAudioUrl)
                    .header("Range", "bytes=0-0")
                streamHeaders.forEach { (k, v) -> rangeProbeBuilder.header(k, v) }
                httpClient.newCall(rangeProbeBuilder.build()).execute().use { response ->
                    contentLength = downloadContentLength(
                        response.code,
                        response.header("Content-Range"),
                        response.header("Content-Length"),
                    ) ?: response.header("Content-Length")?.toLongOrNull() ?: -1L
                }
            } catch (e: Exception) {
                Timber.w(e, "Range probe failed for Content-Length")
            }
        }

        if (contentLength <= 0L) {
            Timber.e("PodcastDownloadWorker: Could not determine Content-Length for $rawAudioUrl")
            notificationManager.cancel(notificationId)
            return Result.failure()
        }

        downloadUtil.updatePodcastDownloadState(
            songId = episodeId,
            state = Download.STATE_DOWNLOADING,
            contentLength = contentLength,
            bytesDownloaded = 0L,
            speedFormatted = "0 KB/s",
            etaFormatted = "Calculating...",
        )

        // Divide the file into 4 equal chunks
        val chunkSize = contentLength / 4
        val chunks = (0 until 4).map { i ->
            val start = i * chunkSize
            val end = if (i == 3) contentLength - 1 else (i + 1) * chunkSize - 1
            start to end
        }

        val audioDir = File(applicationContext.filesDir, "audio").apply { mkdirs() }
        val tempFile = File(audioDir, "$episodeId.tmp")
        val targetFile = File(audioDir, episodeId)

        // Storage: Write the incoming bytes concurrently to a RandomAccessFile allocated to the exact Content-Length
        val randomAccessFile = RandomAccessFile(tempFile, "rw")
        randomAccessFile.setLength(contentLength)
        val fileChannel = randomAccessFile.channel

        val totalBytesDownloaded = AtomicLong(0L)
        val startTimeMs = SystemClock.elapsedRealtime()
        var lastNotificationUpdateMs = 0L
        val notificationLock = Any()
        val segmentSize = 4 * 1024 * 1024L // 4MB segments bypass YouTube's 10MB throttle cliff

        try {
            // Multi-threading: Launch 4 parallel async coroutines. Each must make an OkHttp request using the Range: bytes=start-end header.
            coroutineScope {
                val deferreds = chunks.map { (chunkStart, chunkEnd) ->
                    async(Dispatchers.IO) {
                        var currentPos = chunkStart
                        while (currentPos <= chunkEnd) {
                            val subEnd = minOf(currentPos + segmentSize - 1, chunkEnd)
                            val chunkUrl = buildRangeUrl(rawAudioUrl, currentPos, subEnd)

                            val reqBuilder = Request.Builder()
                                .url(chunkUrl)
                                .header("Range", "bytes=$currentPos-$subEnd")
                            streamHeaders.forEach { (name, value) ->
                                reqBuilder.header(name, value)
                            }

                            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                                if (!response.isSuccessful && response.code != 200 && response.code != 206) {
                                    throw IOException("HTTP error ${response.code} downloading range bytes=$currentPos-$subEnd")
                                }
                                val body = response.body
                                body.byteStream().use { inputStream ->
                                    val buffer = ByteArray(64 * 1024)
                                    var bytesRead: Int
                                    var writePos = currentPos

                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                                        while (byteBuffer.hasRemaining()) {
                                            val written = fileChannel.write(byteBuffer, writePos)
                                            writePos += written
                                        }

                                        val currentTotal = totalBytesDownloaded.addAndGet(bytesRead.toLong())
                                        val now = SystemClock.elapsedRealtime()

                                        if (now - lastNotificationUpdateMs >= 350L) {
                                            synchronized(notificationLock) {
                                                if (now - lastNotificationUpdateMs >= 350L) {
                                                    lastNotificationUpdateMs = now

                                                    val elapsedSec = (now - startTimeMs) / 1000.0
                                                    val speedBytesPerSec =
                                                        if (elapsedSec > 0.25) (currentTotal / elapsedSec).toLong() else 0L
                                                    val remainingBytes = (contentLength - currentTotal).coerceAtLeast(0L)
                                                    val etaSec =
                                                        if (speedBytesPerSec > 0) remainingBytes / speedBytesPerSec else null

                                                    val etaStr = formatEta(etaSec)
                                                    val speedStr = formatSpeed(speedBytesPerSec)
                                                    val progressMbStr = formatMb(currentTotal, contentLength)
                                                    val percent = if (contentLength > 0) {
                                                        ((currentTotal * 100) / contentLength).toInt().coerceIn(0, 100)
                                                    } else 0

                                                    val notificationText = "$progressMbStr ($percent%) • $speedStr"
                                                    val notification = createNotification(
                                                        title = episodeTitle,
                                                        content = notificationText,
                                                        subText = "ETA: $etaStr",
                                                        percent = percent,
                                                        indeterminate = false,
                                                    )
                                                    notificationManager.notify(notificationId, notification)

                                                    downloadUtil.updatePodcastDownloadState(
                                                        songId = episodeId,
                                                        state = Download.STATE_DOWNLOADING,
                                                        contentLength = contentLength,
                                                        bytesDownloaded = currentTotal,
                                                        speedFormatted = speedStr,
                                                        etaFormatted = etaStr,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            currentPos = subEnd + 1
                        }
                    }
                }
                deferreds.awaitAll()
            }
        } catch (e: Exception) {
            Timber.e(e, "PodcastDownloadWorker: Download failed for episode $episodeId")
            downloadUtil.updatePodcastDownloadState(
                songId = episodeId,
                state = Download.STATE_FAILED,
                contentLength = contentLength,
                bytesDownloaded = 0L,
            )
            tempFile.delete()
            notificationManager.cancel(notificationId)
            return Result.failure()
        } finally {
            try {
                randomAccessFile.close()
            } catch (e: Exception) {
                Timber.w(e, "Error closing RandomAccessFile")
            }
        }

        // Handoff: Once all 4 coroutines await and complete successfully, save the file to the app's internal
        // audio directory using the episode.id as the filename, and update the Episode database entity to point to this local file:// path.
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        val localPath = "file://${targetFile.absolutePath}"

        database.query {
            updateEpisodeDownloadedInfo(
                songId = episodeId,
                downloaded = true,
                date = LocalDateTime.now(),
                localPath = localPath,
            )
        }

        downloadUtil.updatePodcastDownloadState(
            songId = episodeId,
            state = Download.STATE_COMPLETED,
            contentLength = contentLength,
            bytesDownloaded = contentLength,
        )

        notificationManager.cancel(notificationId)

        Timber.i("PodcastDownloadWorker: Successfully downloaded episode $episodeId to $localPath at high speed")
        return Result.success(workDataOf("local_path" to localPath))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.downloading),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = applicationContext.getString(R.string.download_desc)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun createNotification(
        title: String,
        content: String,
        subText: String?,
        percent: Int,
        indeterminate: Boolean,
    ): Notification {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                R.drawable.close,
                applicationContext.getString(android.R.string.cancel),
                cancelIntent,
            )

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
        }

        return builder.build()
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        content: String,
        subText: String?,
        percent: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        ensureNotificationChannel()
        val notification = createNotification(title, content, subText, percent, indeterminate)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
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
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatMb(downloaded: Long, total: Long): String {
        val dlMb = downloaded.toDouble() / (1024 * 1024)
        val totMb = total.toDouble() / (1024 * 1024)
        return String.format(Locale.US, "%.1f / %.1f MB", dlMb, totMb)
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val KEY_EPISODE_ID = "episode_id"
        const val KEY_EPISODE_TITLE = "episode_title"
        const val KEY_AUDIO_URL = "audio_url"

        fun enqueue(
            context: Context,
            episodeId: String,
            episodeTitle: String? = null,
            audioUrl: String? = null,
        ) {
            val data = Data.Builder()
                .putString(KEY_EPISODE_ID, episodeId)
                .apply {
                    if (episodeTitle != null) {
                        putString(KEY_EPISODE_TITLE, episodeTitle)
                    }
                    if (audioUrl != null) {
                        putString(KEY_AUDIO_URL, audioUrl)
                    }
                }
                .build()

            val request = OneTimeWorkRequestBuilder<PodcastDownloadWorker>()
                .setInputData(data)
                .addTag("podcast_download_$episodeId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "podcast_download_$episodeId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
