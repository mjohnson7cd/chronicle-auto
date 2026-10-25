package io.github.mjohnson7cd.chronicleauto.features.player

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.DrawableRes
import io.github.mjohnson7cd.chronicleauto.BuildConfig
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.CONTENT_STYLE_BROWSABLE_HINT
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.CONTENT_STYLE_LIST_ITEM_HINT_VALUE
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.CONTENT_STYLE_PLAYABLE_HINT
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.CONTENT_STYLE_SUPPORTED

/** Create a basic browsable item for Auto */
fun makeBrowsable(
    title: String,
    @DrawableRes iconRes: Int,
    desc: String = "",
): MediaItem {
    val mediaDescription = MediaDescriptionCompat.Builder()
    mediaDescription.setTitle(title)
    mediaDescription.setSubtitle(desc)
    // NOTE: Do NOT set iconUri for browsable categories. The android.resource:// URI scheme
    // fails on AAOS because the car media center can't resolve resources from our package.
    // The car UI provides default folder icons automatically.
    mediaDescription.setMediaId(title)
    val extras = Bundle()
    extras.putBoolean(CONTENT_STYLE_SUPPORTED, true)
    extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
    mediaDescription.setExtras(extras)
    return MediaItem(mediaDescription.build(), FLAG_BROWSABLE)
}

/**
 * Create a message item for Android Auto error/info display.
 * Uses FLAG_PLAYABLE so Android Auto displays the item (0 flags would be filtered out).
 * When tapped, AudiobookMediaSessionCallback sets an error PlaybackState to show the message.
 *
 * @param title The main message to display
 * @param subtitle Optional subtitle/additional context (shown below title)
 * @param iconRes Icon resource to display (unused on AAOS; kept for API compatibility)
 * @param mediaId Unique identifier for this message item (must start with "__error" or "__message")
 */
fun makeMessageItem(
    title: String,
    subtitle: String = "",
    @DrawableRes iconRes: Int,
    mediaId: String = "__message__",
): MediaItem {
    val extras = Bundle()
    extras.putBoolean(CONTENT_STYLE_SUPPORTED, true)
    extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)

    val mediaDescription =
        MediaDescriptionCompat.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // NOTE: iconUri removed for same reason as makeBrowsable (android.resource:// fails on AAOS)
            .setMediaId(mediaId)
            .setExtras(extras)
            .build()
    // FLAG_PLAYABLE ensures Android Auto displays the item (0 flags would be filtered out)
    // When tapped, AudiobookMediaSessionCallback sets an error PlaybackState
    return MediaItem(mediaDescription, FLAG_PLAYABLE)
}
