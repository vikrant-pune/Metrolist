/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.SearchResult
import com.metrolist.innertube.utils.parseTime
import kotlinx.serialization.Serializable

@Serializable
data class WebSearchResponse(
    val contents: Contents? = null,
    val onResponseReceivedCommands: List<OnResponseReceivedCommand>? = null,
) {
    @Serializable
    data class Contents(
        val twoColumnSearchResultsRenderer: TwoColumnSearchResultsRenderer? = null,
    )

    @Serializable
    data class TwoColumnSearchResultsRenderer(
        val primaryContents: PrimaryContents? = null,
    )

    @Serializable
    data class PrimaryContents(
        val sectionListRenderer: SectionListRenderer? = null,
    )

    @Serializable
    data class SectionListRenderer(
        val contents: List<SectionContent>? = null,
    )

    @Serializable
    data class SectionContent(
        val itemSectionRenderer: ItemSectionRenderer? = null,
        val continuationItemRenderer: ContinuationItemRenderer? = null,
    )

    @Serializable
    data class ItemSectionRenderer(
        val contents: List<ItemContent>? = null,
    )

    @Serializable
    data class ItemContent(
        val videoRenderer: VideoRenderer? = null,
        val compactVideoRenderer: VideoRenderer? = null,
        val gridShelfViewModel: GridShelfViewModel? = null,
        val reelShelfRenderer: ReelShelfRenderer? = null,
    )

    @Serializable
    data class GridShelfViewModel(
        val contents: List<GridShelfContent>? = null,
    )

    @Serializable
    data class GridShelfContent(
        val shortsLockupViewModel: ShortsLockupViewModel? = null,
    )

    @Serializable
    data class ShortsLockupViewModel(
        val entityId: String? = null,
        val onTap: OnTap? = null,
        val overlayMetadata: OverlayMetadata? = null,
        val thumbnailViewModel: ThumbnailViewModelWrapper? = null,
    ) {
        @Serializable
        data class OnTap(
            val innertubeCommand: InnertubeCommand? = null,
        )

        @Serializable
        data class InnertubeCommand(
            val reelWatchEndpoint: ReelWatchEndpoint? = null,
        )

        @Serializable
        data class ReelWatchEndpoint(
            val videoId: String? = null,
        )

        @Serializable
        data class OverlayMetadata(
            val primaryText: TextContent? = null,
        )

        @Serializable
        data class TextContent(
            val content: String? = null,
        )

        @Serializable
        data class ThumbnailViewModelWrapper(
            val thumbnailViewModel: InnerThumbnailViewModel? = null,
        )

        @Serializable
        data class InnerThumbnailViewModel(
            val image: ImageWrapper? = null,
        )

        @Serializable
        data class ImageWrapper(
            val sources: List<Source>? = null,
        )

        @Serializable
        data class Source(
            val url: String? = null,
        )
    }

    @Serializable
    data class ReelShelfRenderer(
        val items: List<ReelItem>? = null,
    )

    @Serializable
    data class ReelItem(
        val reelItemRenderer: ReelItemRenderer? = null,
    )

    @Serializable
    data class ReelItemRenderer(
        val videoId: String? = null,
        val headline: TextResult? = null,
        val thumbnail: Thumbnails? = null,
    )

    @Serializable
    data class VideoRenderer(
        val videoId: String? = null,
        val title: TextResult? = null,
        val thumbnail: Thumbnails? = null,
        val ownerText: TextResult? = null,
        val longBylineText: TextResult? = null,
        val shortBylineText: TextResult? = null,
        val lengthText: LengthText? = null,
    )

    @Serializable
    data class TextResult(
        val runs: List<Run>? = null,
        val simpleText: String? = null,
    ) {
        fun text(): String? = runs?.joinToString("") { it.text.orEmpty() }?.takeIf { it.isNotEmpty() } ?: simpleText
    }

    @Serializable
    data class Run(
        val text: String? = null,
        val navigationEndpoint: NavigationEndpoint? = null,
    )

    @Serializable
    data class LengthText(
        val simpleText: String? = null,
    )

    @Serializable
    data class ContinuationItemRenderer(
        val continuationEndpoint: ContinuationEndpoint? = null,
    )

    @Serializable
    data class ContinuationEndpoint(
        val continuationCommand: ContinuationCommand? = null,
    )

    @Serializable
    data class ContinuationCommand(
        val token: String? = null,
    )

    @Serializable
    data class OnResponseReceivedCommand(
        val appendContinuationItemsAction: AppendContinuationItemsAction? = null,
    )

    @Serializable
    data class AppendContinuationItemsAction(
        val continuationItems: List<SectionContent>? = null,
    )

    fun toSearchResult(): SearchResult {
        val items = mutableListOf<YTItem>()
        var continuationToken: String? = null

        fun processSectionContent(section: SectionContent) {
            if (section.continuationItemRenderer != null) {
                val token = section.continuationItemRenderer.continuationEndpoint?.continuationCommand?.token
                if (token != null) {
                    continuationToken = token
                }
            }
            section.itemSectionRenderer?.contents?.forEach { itemContent ->
                val video = itemContent.videoRenderer ?: itemContent.compactVideoRenderer
                if (video != null && video.videoId != null) {
                    val title = video.title?.text() ?: "Unknown"
                    val authorRun = video.longBylineText?.runs?.firstOrNull()
                        ?: video.ownerText?.runs?.firstOrNull()
                        ?: video.shortBylineText?.runs?.firstOrNull()
                    val authorName = authorRun?.text
                        ?: video.longBylineText?.simpleText
                        ?: video.ownerText?.simpleText
                        ?: "Unknown"
                    val authorId = authorRun?.navigationEndpoint?.browseEndpoint?.browseId
                    val duration = video.lengthText?.simpleText?.parseTime()
                    val thumbnail = video.thumbnail?.thumbnails?.lastOrNull()?.url
                        ?: "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg"

                    items.add(
                        SongItem(
                            id = video.videoId,
                            title = title,
                            artists = listOf(Artist(name = authorName, id = authorId)),
                            duration = duration,
                            thumbnail = thumbnail,
                            endpoint = WatchEndpoint(videoId = video.videoId),
                        )
                    )
                }

                itemContent.gridShelfViewModel?.contents?.forEach { shelfContent ->
                    val short = shelfContent.shortsLockupViewModel
                    val videoId = short?.onTap?.innertubeCommand?.reelWatchEndpoint?.videoId
                        ?: short?.entityId?.removePrefix("shorts-shelf-item-")
                    if (short != null && videoId != null) {
                        val title = short.overlayMetadata?.primaryText?.content ?: "YouTube Short"
                        val thumbnail = short.thumbnailViewModel?.thumbnailViewModel?.image?.sources?.lastOrNull()?.url
                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        items.add(
                            SongItem(
                                id = videoId,
                                title = title,
                                artists = emptyList(),
                                duration = null,
                                thumbnail = thumbnail,
                                endpoint = WatchEndpoint(videoId = videoId),
                            )
                        )
                    }
                }

                itemContent.reelShelfRenderer?.items?.forEach { reelItem ->
                    val reel = reelItem.reelItemRenderer
                    if (reel?.videoId != null) {
                        val title = reel.headline?.text() ?: "YouTube Short"
                        val thumbnail = reel.thumbnail?.thumbnails?.lastOrNull()?.url
                            ?: "https://i.ytimg.com/vi/${reel.videoId}/hqdefault.jpg"
                        items.add(
                            SongItem(
                                id = reel.videoId,
                                title = title,
                                artists = emptyList(),
                                duration = null,
                                thumbnail = thumbnail,
                                endpoint = WatchEndpoint(videoId = reel.videoId),
                            )
                        )
                    }
                }
            }
        }

        contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents?.forEach {
            processSectionContent(it)
        }

        onResponseReceivedCommands?.forEach { cmd ->
            cmd.appendContinuationItemsAction?.continuationItems?.forEach {
                processSectionContent(it)
            }
        }

        return SearchResult(
            items = items.distinctBy { it.id },
            continuation = continuationToken,
        )
    }
}
