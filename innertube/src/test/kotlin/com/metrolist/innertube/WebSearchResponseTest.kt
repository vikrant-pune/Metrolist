package com.metrolist.innertube

import com.metrolist.innertube.models.response.WebSearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WebSearchResponseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun parseInitialSearchResponseWithVideosAndShorts() {
        val jsonString = """
            {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "itemSectionRenderer": {
                            "contents": [
                              {
                                "videoRenderer": {
                                  "videoId": "qxghNHKgz4U",
                                  "thumbnail": {
                                    "thumbnails": [
                                      { "url": "https://i.ytimg.com/vi/qxghNHKgz4U/hqdefault.jpg" }
                                    ]
                                  },
                                  "title": {
                                    "runs": [
                                      { "text": "THAPPAD TRAILER Review" }
                                    ]
                                  },
                                  "longBylineText": {
                                    "runs": [
                                      {
                                        "text": "SHAAN",
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "UCTp-wVGxhPsuRXewzHuPacA"
                                          }
                                        }
                                      }
                                    ]
                                  },
                                  "lengthText": {
                                    "simpleText": "11:30"
                                  }
                                }
                              },
                              {
                                "gridShelfViewModel": {
                                  "contents": [
                                    {
                                      "shortsLockupViewModel": {
                                        "entityId": "shorts-shelf-item-ktJ_CfuT5mo",
                                        "overlayMetadata": {
                                          "primaryText": {
                                            "content": "How Did Taapsee's Family React to 'Thappad'?"
                                          }
                                        },
                                        "onTap": {
                                          "innertubeCommand": {
                                            "reelWatchEndpoint": {
                                              "videoId": "ktJ_CfuT5mo"
                                            }
                                          }
                                        },
                                        "thumbnailViewModel": {
                                          "thumbnailViewModel": {
                                            "image": {
                                              "sources": [
                                                { "url": "https://i.ytimg.com/vi/ktJ_CfuT5mo/frame0.jpg" }
                                              ]
                                            }
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        },
                        {
                          "continuationItemRenderer": {
                            "continuationEndpoint": {
                              "continuationCommand": {
                                "token": "test_continuation_token_123"
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<WebSearchResponse>(jsonString)
        val result = parsed.toSearchResult()

        assertEquals(2, result.items.size)
        assertEquals("test_continuation_token_123", result.continuation)

        val video = result.items[0]
        assertEquals("qxghNHKgz4U", video.id)
        assertEquals("THAPPAD TRAILER Review", video.title)
        assertEquals(690, (video as com.metrolist.innertube.models.SongItem).duration)
        assertEquals("SHAAN", video.artists.firstOrNull()?.name)
        assertEquals("UCTp-wVGxhPsuRXewzHuPacA", video.artists.firstOrNull()?.id)

        val short = result.items[1]
        assertEquals("ktJ_CfuT5mo", short.id)
        assertEquals("How Did Taapsee's Family React to 'Thappad'?", short.title)
    }

    @Test
    fun parseContinuationResponse() {
        val jsonString = """
            {
              "onResponseReceivedCommands": [
                {
                  "appendContinuationItemsAction": {
                    "continuationItems": [
                      {
                        "itemSectionRenderer": {
                          "contents": [
                            {
                              "videoRenderer": {
                                "videoId": "next_video_id",
                                "title": { "simpleText": "Next Video Title" }
                              }
                            }
                          ]
                        }
                      },
                      {
                        "continuationItemRenderer": {
                          "continuationEndpoint": {
                            "continuationCommand": {
                              "token": "token_page_3"
                            }
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<WebSearchResponse>(jsonString)
        val result = parsed.toSearchResult()

        assertEquals(1, result.items.size)
        assertEquals("next_video_id", result.items[0].id)
        assertEquals("Next Video Title", result.items[0].title)
        assertEquals("token_page_3", result.continuation)
    }
}

