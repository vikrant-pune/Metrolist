/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioAttributesTest {
    @Test
    fun `audio attributes are built with media usage, music content type, and never spatialization`() {
        val audioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                .build()

        assertEquals(C.USAGE_MEDIA, audioAttributes.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, audioAttributes.contentType)
        assertEquals(C.SPATIALIZATION_BEHAVIOR_NEVER, audioAttributes.spatializationBehavior)
    }
}

