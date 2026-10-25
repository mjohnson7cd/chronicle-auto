package io.github.mjohnson7cd.chronicleauto.features.player

import android.support.v4.media.MediaMetadataCompat
import io.github.mjohnson7cd.chronicleauto.data.model.MediaItemTrack
import io.github.mjohnson7cd.chronicleauto.data.model.toMediaMetadata
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig

fun buildPlaylist(
    tracks: List<MediaItemTrack>,
    plexConfig: PlexConfig,
): List<MediaMetadataCompat> {
    return tracks.map { track -> track.toMediaMetadata(plexConfig) }
}
