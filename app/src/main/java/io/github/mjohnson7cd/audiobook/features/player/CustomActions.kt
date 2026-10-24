package io.github.mjohnson7cd.audiobook.features.player

import android.os.Build
import android.os.Build.VERSION_CODES.M
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
import android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
import io.github.mjohnson7cd.audiobook.BuildConfig
import io.github.mjohnson7cd.audiobook.R
import io.github.mjohnson7cd.audiobook.data.local.PrefsRepo
import io.github.mjohnson7cd.audiobook.data.sources.plex.ICachedFileManager.CacheStatus

// Order matters: with no ACTION_SKIP_TO_NEXT/PREVIOUS, car UIs put the first two custom actions
// next to play (-10s | play | +30s). AAOS's ControlBar then fills the outer-left slot with the next
// action and turns outer-right into ⋮, so a blank spacer holds outer-left and everything after it
// lands in the overflow menu: download, prev chapter, next chapter, speed, favorite.
// The trailing spacer keeps ⋮ in its slot while the car briefly re-lays out the bar on each state
// push (it drops -10/+30 then re-adds them); without it the open overflow row goes blank.
fun buildCustomActions(
    prefsRepo: PrefsRepo,
    downloadStatus: CacheStatus = CacheStatus.NOT_CACHED,
    downloadPercent: Int = 0,
    favorited: Boolean = false,
): List<PlaybackStateCompat.CustomAction> =
    listOfNotNull(
        makeSkipBackward(prefsRepo),
        makeSkipForward(prefsRepo),
        if (BuildConfig.IS_AUTOMOTIVE) CAR_SLOT_SPACER else null,
        if (BuildConfig.IS_AUTOMOTIVE) makeDownloadBook(downloadStatus, downloadPercent) else null,
        SKIP_TO_PREVIOUS,
        SKIP_TO_NEXT,
        makePlaybackSpeed(prefsRepo),
        if (BuildConfig.IS_AUTOMOTIVE) makeFavorite(favorited) else null,
        if (BuildConfig.IS_AUTOMOTIVE) CAR_SLOT_SPACER_END else null,
    )

const val FAVORITE_STRING = "Favorite"

/** Car overflow favorite toggle; filled heart when the playing book is a favorite. */
fun makeFavorite(favorited: Boolean): PlaybackStateCompat.CustomAction =
    if (favorited) {
        PlaybackStateCompat.CustomAction.Builder(FAVORITE_STRING, "Remove from favorites", R.drawable.ic_favorite_white)
    } else {
        PlaybackStateCompat.CustomAction.Builder(FAVORITE_STRING, "Add to favorites", R.drawable.ic_favorite_border_white)
    }.build()

const val DOWNLOAD_BOOK_STRING = "Download book"

/**
 * Car overflow download button. The icon/label track the playing book's download state, which is
 * the only feedback that reliably shows on AAOS (toasts from the media service are queued but not
 * drawn while the car media app is in front). The action id stays [DOWNLOAD_BOOK_STRING] in every
 * state; the callback decides what a tap means.
 *
 * While downloading, the icon is a ring that fills clockwise in 10% steps. Custom action icons are
 * plain resource ids the car inflates itself, so a real animated drawable/progress view isn't
 * possible; stepping through static frames is. Keep the step coarse: every change is a new
 * PlaybackState push, which re-lays out the car's control bar.
 */
fun makeDownloadBook(
    status: CacheStatus,
    percent: Int = 0,
): PlaybackStateCompat.CustomAction {
    val (label, icon) =
        when (status) {
            CacheStatus.NOT_CACHED -> "Download for offline" to R.drawable.ic_cloud_download_white
            CacheStatus.CACHING -> {
                val step = downloadProgressStep(percent)
                "Downloading $step%" to DOWNLOAD_PROGRESS_ICONS[step / 10]
            }
            CacheStatus.CACHED -> "Downloaded" to R.drawable.ic_cloud_done_white
        }
    return PlaybackStateCompat.CustomAction.Builder(DOWNLOAD_BOOK_STRING, label, icon).build()
}

/** [percent] rounded down to the 10% step shown by the car download icon (0..100). */
fun downloadProgressStep(percent: Int): Int = (percent.coerceIn(0, 100) / 10) * 10

private val DOWNLOAD_PROGRESS_ICONS =
    intArrayOf(
        R.drawable.ic_car_download_progress_0,
        R.drawable.ic_car_download_progress_10,
        R.drawable.ic_car_download_progress_20,
        R.drawable.ic_car_download_progress_30,
        R.drawable.ic_car_download_progress_40,
        R.drawable.ic_car_download_progress_50,
        R.drawable.ic_car_download_progress_60,
        R.drawable.ic_car_download_progress_70,
        R.drawable.ic_car_download_progress_80,
        R.drawable.ic_car_download_progress_90,
        R.drawable.ic_car_download_progress_100,
    )

/** Second invisible no-op action; see the ordering note on [buildCustomActions]. */
val CAR_SLOT_SPACER_END: PlaybackStateCompat.CustomAction =
    PlaybackStateCompat.CustomAction.Builder("spacer_end", " ", R.drawable.ic_blank).build()

/** Invisible no-op action that keeps the AAOS outer-left control slot empty (unhandled in onCustomAction). */
val CAR_SLOT_SPACER: PlaybackStateCompat.CustomAction =
    PlaybackStateCompat.CustomAction.Builder("spacer", " ", R.drawable.ic_blank).build()

const val PLAYBACK_SPEED_STRING = "Playback speed"

/** Cycles through [PLAYBACK_SPEED_PRESETS] on tap; the icon shows the current speed. */
fun makePlaybackSpeed(prefsRepo: PrefsRepo): PlaybackStateCompat.CustomAction {
    val speed = prefsRepo.playbackSpeed
    // shortcut: only these icon sizes exist, so in-between speeds show the nearest lower icon (2.5x shows 2.0x).
    val drawable: Int =
        when {
            speed < 0.75f -> R.drawable.ic_speed_up_0_5x
            speed < 1.0f -> R.drawable.ic_speed_up_0_7x
            speed < 1.25f -> R.drawable.ic_speed_up_1_0x
            speed < 1.5f -> R.drawable.ic_speed_up_1_2x
            speed < 1.75f -> R.drawable.ic_speed_up_1_5x
            speed < 2.0f -> R.drawable.ic_speed_up_1_7x
            speed < 3.0f -> R.drawable.ic_speed_up_2_0x
            else -> R.drawable.ic_speed_up_3_0x
        }
    val label = "%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')
    return PlaybackStateCompat.CustomAction.Builder(
        PLAYBACK_SPEED_STRING,
        "$PLAYBACK_SPEED_STRING ${label}x",
        drawable,
    ).build()
}

/** Next preset above [current], wrapping to the slowest. */
fun nextPlaybackSpeed(current: Float): Float =
    PLAYBACK_SPEED_PRESETS.firstOrNull { it > current + 0.001f } ?: PLAYBACK_SPEED_PRESETS.first()

/** Threshold to decide whether to jump to the beginning of the current chapter or to the previous chapter. */
const val SKIP_TO_PREVIOUS_CHAPTER_THRESHOLD_SECONDS = 5L

const val SKIP_TO_NEXT_STRING = "Skip to next"
val SKIP_TO_NEXT: PlaybackStateCompat.CustomAction =
    PlaybackStateCompat.CustomAction.Builder(
        SKIP_TO_NEXT_STRING,
        "Skip to next chapter",
        R.drawable.ic_skip_next_white,
    ).build()

const val SKIP_TO_PREVIOUS_STRING = "Skip to previous"
val SKIP_TO_PREVIOUS: PlaybackStateCompat.CustomAction =
    PlaybackStateCompat.CustomAction.Builder(
        SKIP_TO_PREVIOUS_STRING,
        "Skip to previous chapter",
        R.drawable.ic_skip_previous_white,
    ).build()

const val SKIP_FORWARDS_STRING = "Skip forwards"

fun makeSkipForward(prefsRepo: PrefsRepo): PlaybackStateCompat.CustomAction {
    val drawable: Int =
        when (prefsRepo.jumpForwardSeconds) {
            10L -> R.drawable.ic_forward_10_white
            15L -> R.drawable.ic_forward_15_white
            20L -> R.drawable.ic_forward_20_white
            30L -> R.drawable.ic_forward_30_white
            60L -> R.drawable.ic_forward_60_white
            90L -> R.drawable.ic_forward_90_white
            else -> R.drawable.ic_forward_30_white
        }
    return PlaybackStateCompat.CustomAction.Builder(
        SKIP_FORWARDS_STRING,
        SKIP_FORWARDS_STRING,
        drawable,
    ).build()
}

const val SKIP_BACKWARDS_STRING = "Skip backwards"

fun makeSkipBackward(prefsRepo: PrefsRepo): PlaybackStateCompat.CustomAction {
    val drawable: Int =
        when (prefsRepo.jumpBackwardSeconds) {
            10L -> R.drawable.ic_replay_10_white
            15L -> R.drawable.ic_replay_15_white
            20L -> R.drawable.ic_replay_20_white
            30L -> R.drawable.ic_replay_30_white
            60L -> R.drawable.ic_replay_60_white
            90L -> R.drawable.ic_replay_90_white
            else -> R.drawable.ic_replay_10_white
        }
    return PlaybackStateCompat.CustomAction.Builder(
        SKIP_BACKWARDS_STRING,
        SKIP_BACKWARDS_STRING,
        drawable,
    ).build()
}

val mediaSkipForwardCode = if (Build.VERSION.SDK_INT >= M) KEYCODE_MEDIA_SKIP_FORWARD else 272
val mediaSkipBackwardCode = if (Build.VERSION.SDK_INT >= M) KEYCODE_MEDIA_SKIP_BACKWARD else 273
val mediaSkipToNextCode = if (Build.VERSION.SDK_INT >= M) KEYCODE_MEDIA_NEXT else 87
val mediaSkipToPreviousCode = if (Build.VERSION.SDK_INT >= M) KEYCODE_MEDIA_PREVIOUS else 88
