package io.github.mjohnson7cd.audiobook.data.model

import io.github.mjohnson7cd.audiobook.data.model.MediaItemTrack.Companion.REMOTE_PROGRESS_SLACK_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaItemTrackMergeTest {
    private val local = MediaItemTrack(id = "plex:1", progress = 1_000L, lastViewedAt = 1_000_000L, cached = true)

    @Test
    fun `newer server listen from another device wins`() {
        val network = MediaItemTrack(id = "plex:1", progress = 215_082L, lastViewedAt = local.lastViewedAt + REMOTE_PROGRESS_SLACK_MS + 1)
        val merged = MediaItemTrack.merge(network, local)
        assertEquals(215_082L, merged.progress)
        assertEquals(true, merged.cached)
    }

    @Test
    fun `our own slightly newer timeline report does not rewind local progress`() {
        val network = MediaItemTrack(id = "plex:1", progress = 500L, lastViewedAt = local.lastViewedAt + 1_000L)
        val merged = MediaItemTrack.merge(network, local)
        assertEquals(1_000L, merged.progress)
        assertEquals(local.lastViewedAt, merged.lastViewedAt)
    }
}
