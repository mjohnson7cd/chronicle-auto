package io.github.mjohnson7cd.chronicleauto.features.car

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import io.github.mjohnson7cd.chronicleauto.R

/**
 * PlaybackState error-resolution extras understood by car media hosts (Android Automotive OS
 * media center and Android Auto). When present on a STATE_ERROR playback state, the host shows
 * an action button (e.g. "Sign in") that fires the PendingIntent.
 *
 * Values match androidx.media.utils.MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_*.
 */
object CarErrorResolution {
    const val EXTRA_ERROR_RESOLUTION_ACTION_LABEL = "android.media.extras.ERROR_RESOLUTION_ACTION_LABEL"
    const val EXTRA_ERROR_RESOLUTION_ACTION_INTENT = "android.media.extras.ERROR_RESOLUTION_ACTION_INTENT"

    private const val REQUEST_CODE_SIGN_IN = 0xCA5

    /**
     * Extras that make the car host offer a "Sign in" button, or null when this build has no
     * in-car sign-in screen (mobile flavor: sign-in happens in the phone UI).
     */
    fun signInExtras(context: Context): Bundle? {
        val intent = CarIntegration.signInIntent(context) ?: return null
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_SIGN_IN,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Bundle().apply {
            putString(EXTRA_ERROR_RESOLUTION_ACTION_LABEL, context.getString(R.string.action_sign_in))
            putParcelable(EXTRA_ERROR_RESOLUTION_ACTION_INTENT, pendingIntent)
        }
    }
}
