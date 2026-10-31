/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.Uri
import com.metrolist.music.constants.SearchSource

object SearchRoutes {
    const val ROUTE = "search/{query}?source={source}"

    // Prefix query payloads so a literal "null" search is not confused with
    // an absent or undefined Navigation argument.
    private const val QUERY_ROUTE_PREFIX = "__metrolist_search_query__"

    // Encode the prefixed query as a route path segment before navigating.
    fun resultRoute(query: String, source: SearchSource = SearchSource.ONLINE): String =
        "search/${Uri.encode(QUERY_ROUTE_PREFIX + query)}?source=${source.name}"

    // Navigation already decodes path segments once, so only strip the prefix.
    // Decoding again would turn literal input like "%2F" into "/".
    fun decodeQuery(rawQuery: String): String =
        rawQuery.removePrefix(QUERY_ROUTE_PREFIX)
}
