package io.github.mjohnson7cd.chronicleauto.features.car

import android.content.Context
import android.content.Intent

/**
 * Form-factor hooks used by shared (main) code. The mobile build has no car-only screens:
 * sign-in and settings live in MainActivity, so every hook is a no-op here.
 *
 * The automotive flavor provides its own implementation in src/automotive.
 */
object CarIntegration {
    /** Intent for the in-car sign-in screen, or null when sign-in happens on the phone UI. */
    fun signInIntent(context: Context): Intent? = null

    /** Intent used as the MediaSession activity when the app has no launcher activity. */
    fun sessionActivityIntent(context: Context): Intent? = null
}
