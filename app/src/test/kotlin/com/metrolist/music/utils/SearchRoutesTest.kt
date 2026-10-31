package com.metrolist.music.utils

import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchRoutesTest {
    @Test
    fun `SearchSource toggle cycles through ONLINE, YOUTUBE, LOCAL`() {
        assertEquals(SearchSource.YOUTUBE, SearchSource.ONLINE.toggle())
        assertEquals(SearchSource.LOCAL, SearchSource.YOUTUBE.toggle())
        assertEquals(SearchSource.ONLINE, SearchSource.LOCAL.toggle())
    }

    @Test
    fun `resultRoute defaults to ONLINE source and encodes prefix`() {
        val route = SearchRoutes.resultRoute("coldplay")
        assertEquals("search/__metrolist_search_query__coldplay?source=ONLINE", route)
    }

    @Test
    fun `resultRoute encodes YOUTUBE source`() {
        val route = SearchRoutes.resultRoute("podcast interview", SearchSource.YOUTUBE)
        assertEquals("search/__metrolist_search_query__podcast%20interview?source=YOUTUBE", route)
    }

    @Test
    fun `decodeQuery removes prefix correctly`() {
        val decoded = SearchRoutes.decodeQuery("__metrolist_search_query__test query")
        assertEquals("test query", decoded)
    }

    @Test
    fun `YouTubeUrlParser parses YouTube shorts share link with tracking params`() {
        val parsed = YouTubeUrlParser.parse("https://youtube.com/shorts/ktJ_CfuT5mo?si=cpsm3RMSxxt4YH6g")
        assertTrue(parsed is YouTubeUrlParser.ParsedUrl.Video)
        assertEquals("ktJ_CfuT5mo", (parsed as YouTubeUrlParser.ParsedUrl.Video).id)
    }
}

