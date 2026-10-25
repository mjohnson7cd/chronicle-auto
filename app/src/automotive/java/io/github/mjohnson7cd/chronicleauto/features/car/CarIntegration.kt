package io.github.mjohnson7cd.chronicleauto.features.car

import android.content.Context
import android.content.Intent

/**
 * Android Automotive OS (Google built-in) hooks used by shared (main) code.
 *
 * There is no phone UI in the car, so sign-in and settings are dedicated, parked-only
 * activities declared in src/automotive/AndroidManifest.xml.
 */
object CarIntegration {
    /** Launches the plex.tv/link code sign-in; used as the MediaSession error resolution. */
    fun signInIntent(context: Context): Intent? =
        Intent(context, CarSignInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The AAOS build has no launcher activity; the car settings screen stands in for it. */
    fun sessionActivityIntent(context: Context): Intent? = Intent(context, CarSettingsActivity::class.java)
}
