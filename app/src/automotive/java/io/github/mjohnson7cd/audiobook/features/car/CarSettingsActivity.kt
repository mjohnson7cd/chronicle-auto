package io.github.mjohnson7cd.audiobook.features.car

import android.os.Bundle
import android.text.format.Formatter
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import io.github.mjohnson7cd.audiobook.BuildConfig
import io.github.mjohnson7cd.audiobook.R
import io.github.mjohnson7cd.audiobook.application.Injector
import io.github.mjohnson7cd.audiobook.data.sources.plex.IPlexLoginRepo.LoginState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Settings for the Android Automotive OS build, opened from the car media center's settings
 * button (APPLICATION_PREFERENCES). Parked-only: not distraction-optimized.
 *
 * Covers what the phone settings screen would otherwise be needed for: account (sign in,
 * switch server/library, sign out), skip intervals, playback speed, a manual library sync, and
 * deleting all downloaded books.
 */
class CarSettingsActivity : AppCompatActivity() {
    private val component by lazy { Injector.get() }
    private val prefsRepo by lazy { component.prefsRepo() }
    private val plexPrefs by lazy { component.plexPrefs() }
    private val plexConfig by lazy { component.plexConfig() }
    private val plexLoginRepo by lazy { component.plexLoginRepo() }

    private lateinit var accountStatus: TextView
    private lateinit var libraryStatus: TextView
    private lateinit var signIn: Button
    private lateinit var switchLibrary: Button
    private lateinit var sync: Button
    private lateinit var syncProgress: ProgressBar
    private lateinit var signOut: Button
    private lateinit var jumpForward: Button
    private lateinit var jumpBackward: Button
    private lateinit var speed: Button
    private lateinit var pauseOnInterruption: Button
    private lateinit var storageStatus: TextView
    private lateinit var deleteDownloads: Button
    private lateinit var deleteProgress: ProgressBar
    private var downloadedBytes = 0L
    private var deleting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_settings)
        
        // Add back button support
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        accountStatus = findViewById(R.id.car_settings_account_status)
        libraryStatus = findViewById(R.id.car_settings_library_status)
        signIn = findViewById(R.id.car_settings_sign_in)
        switchLibrary = findViewById(R.id.car_settings_switch_library)
        sync = findViewById(R.id.car_settings_sync)
        syncProgress = findViewById(R.id.car_settings_sync_progress)
        signOut = findViewById(R.id.car_settings_sign_out)
        jumpForward = findViewById(R.id.car_settings_jump_forward)
        jumpBackward = findViewById(R.id.car_settings_jump_backward)
        speed = findViewById(R.id.car_settings_speed)
        pauseOnInterruption = findViewById(R.id.car_settings_pause_on_focus_lost)
        storageStatus = findViewById(R.id.car_settings_storage_status)
        deleteDownloads = findViewById(R.id.car_settings_delete_downloads)
        deleteProgress = findViewById(R.id.car_settings_delete_progress)
        deleteDownloads.setOnClickListener { confirmDeleteDownloads() }
        findViewById<TextView>(R.id.car_settings_version).text =
            getString(R.string.car_settings_version, BuildConfig.VERSION_NAME)

        signIn.setOnClickListener { openSignIn() }
        switchLibrary.setOnClickListener {
            // Keep the account/user AND the current server/library until a new library is
            // chosen; the sign-in flow restores them if the switch is cancelled or fails.
            CarIntegration.signInIntent(this)
                ?.putExtra(CarSignInActivity.EXTRA_SWITCH_SERVER, true)
                ?.let { startActivity(it) }
        }
        sync.setOnClickListener {
            component.librarySyncRepo().refreshLibrary()
            Toast.makeText(this, R.string.car_settings_sync_started, Toast.LENGTH_SHORT).show()
        }
        component.librarySyncRepo().isRefreshing.observe(this) { refreshing ->
            sync.isEnabled = !refreshing
            sync.setText(if (refreshing) R.string.car_settings_syncing else R.string.car_settings_sync_now)
            syncProgress.visibility = if (refreshing) View.VISIBLE else View.GONE
        }
        signOut.setOnClickListener { confirmSignOut() }
        
        // Back button
        findViewById<TextView>(R.id.car_settings_back).setOnClickListener {
            finish()
        }
        
        jumpForward.setOnClickListener {
            prefsRepo.jumpForwardSeconds = nextOf(JUMP_OPTIONS_SECONDS, prefsRepo.jumpForwardSeconds)
            renderPlayback()
        }
        jumpBackward.setOnClickListener {
            prefsRepo.jumpBackwardSeconds = nextOf(JUMP_OPTIONS_SECONDS, prefsRepo.jumpBackwardSeconds)
            renderPlayback()
        }
        // MediaPlayerService's prefs listener reapplies the audio attributes (speech = pause on focus loss).
        pauseOnInterruption.setOnClickListener {
            prefsRepo.pauseOnFocusLost = !prefsRepo.pauseOnFocusLost
            renderPlayback()
        }
        speed.setOnClickListener { startActivity(android.content.Intent(this, CarPlaybackSpeedActivity::class.java)) }
    }
    
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // The sign-in activity may have changed account state while we were paused.
        renderAccount()
        renderPlayback()
        refreshStorage()
    }

    /** Re-measures downloaded files (a download may have finished while we were paused). */
    private fun refreshStorage() {
        lifecycleScope.launch {
            downloadedBytes = component.cachedFileManager().getDownloadedBytes()
            renderStorage()
        }
    }

    private fun renderStorage() {
        val size = Formatter.formatShortFileSize(this, downloadedBytes)
        storageStatus.text =
            if (downloadedBytes > 0) {
                getString(R.string.car_settings_storage_used, size)
            } else {
                getString(R.string.car_settings_storage_empty)
            }
        deleteDownloads.isEnabled = downloadedBytes > 0 && !deleting
        deleteDownloads.text =
            when {
                deleting -> getString(R.string.car_settings_deleting_downloads)
                downloadedBytes > 0 -> getString(R.string.car_settings_delete_downloads_size, size)
                else -> getString(R.string.car_settings_delete_downloads)
            }
        deleteProgress.visibility = if (deleting) View.VISIBLE else View.GONE
    }

    private fun confirmDeleteDownloads() {
        lifecycleScope.launch {
            // Measure again right before asking so the number shown is what will be freed.
            downloadedBytes = component.cachedFileManager().getDownloadedBytes()
            renderStorage()
            if (downloadedBytes <= 0) return@launch
            AlertDialog.Builder(this@CarSettingsActivity)
                .setTitle(R.string.car_settings_delete_downloads_confirm_title)
                .setMessage(
                    getString(
                        R.string.car_settings_delete_downloads_confirm,
                        Formatter.formatShortFileSize(this@CarSettingsActivity, downloadedBytes),
                    ),
                )
                .setPositiveButton(R.string.car_settings_delete) { _, _ -> deleteAllDownloads() }
                .setNegativeButton(R.string.car_cancel, null)
                .show()
        }
    }

    private fun deleteAllDownloads() {
        deleting = true
        renderStorage()
        lifecycleScope.launch {
            val result =
                try {
                    component.cachedFileManager().deleteAllDownloads()
                } catch (e: Exception) {
                    Timber.e(e, "[CarSettings] deleting downloads failed")
                    null
                }
            deleting = false
            downloadedBytes = component.cachedFileManager().getDownloadedBytes()
            renderStorage()
            if (result != null) {
                Toast.makeText(
                    this@CarSettingsActivity,
                    getString(
                        R.string.car_settings_delete_downloads_done,
                        result.fileCount,
                        Formatter.formatShortFileSize(this@CarSettingsActivity, result.bytesFreed),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun renderAccount() {
        val signedIn = plexLoginRepo.loginEvent.value?.peekContent() == LoginState.LOGGED_IN_FULLY
        val user = plexPrefs.user
        val server = plexPrefs.server
        val library = plexPrefs.library
        accountStatus.text =
            if (plexPrefs.accountAuthToken.isNotEmpty()) {
                getString(
                    R.string.car_settings_signed_in_as,
                    user?.title?.ifEmpty { null } ?: user?.username.orEmpty(),
                )
            } else {
                getString(R.string.car_settings_not_signed_in)
            }
        libraryStatus.text =
            if (server != null && library != null) {
                getString(R.string.car_settings_library, library.name, server.name)
            } else {
                ""
            }
        // Sign in: only when truly signed out (no saved server). If there's a server but sign-in
        // is incomplete (no library), "Switch server / library" is the escape hatch.
        signIn.visibility = if (server == null && !signedIn) View.VISIBLE else View.GONE
        // Switch: offered when fully signed in OR when stuck half-signed-in (server saved but
        // unreachable / no library chosen), so the user can pick a different server.
        switchLibrary.visibility =
            if ((signedIn || (plexPrefs.accountAuthToken.isNotEmpty() && user != null && server != null))) View.VISIBLE else View.GONE
        sync.visibility = if (signedIn) View.VISIBLE else View.GONE
        signOut.visibility = if (plexPrefs.accountAuthToken.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderPlayback() {
        jumpForward.text =
            getString(R.string.car_settings_jump_forward) + ": " +
            getString(R.string.car_seconds, prefsRepo.jumpForwardSeconds.toInt())
        jumpBackward.text =
            getString(R.string.car_settings_jump_backward) + ": " +
            getString(R.string.car_seconds, prefsRepo.jumpBackwardSeconds.toInt())
        speed.text = getString(R.string.car_settings_speed) + ": " + formatSpeed(prefsRepo.playbackSpeed)
        pauseOnInterruption.text =
            getString(R.string.settings_pause_on_focus_lost_title) + ": " +
            getString(if (prefsRepo.pauseOnFocusLost) R.string.car_on else R.string.car_off)
    }

    private fun openSignIn() {
        CarIntegration.signInIntent(this)?.let { startActivity(it) }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setMessage(R.string.car_settings_sign_out_confirm)
            .setPositiveButton(R.string.car_settings_sign_out) { _, _ -> signOutNow() }
            .setNegativeButton(R.string.car_cancel, null)
            .show()
    }

    /** Mirrors the phone "Log out" (SettingsViewModel.clearConfig(RETURN_TO_LOGIN)). */
    private fun signOutNow() {
        lifecycleScope.launch {
            try {
                // Stop playback through the session (MediaButtonReceiver forwards to the service).
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this@CarSettingsActivity,
                    PlaybackStateCompat.ACTION_STOP,
                )?.send()
            } catch (e: Exception) {
                Timber.w(e, "[CarSettings] could not stop playback before sign-out")
            }
            withContext(Dispatchers.IO) {
                component.cachedFileManager().uncacheAllInLibrary()
                component.bookRepo().clear()
                component.trackRepo().clear()
                component.collectionsRepo().clear()
            }
            plexConfig.clear()
            plexLoginRepo.determineLoginState()
            renderAccount()
        }
    }

    private fun formatSpeed(value: Float): String = "${value}x"

    companion object {
        val JUMP_OPTIONS_SECONDS = listOf(10L, 15L, 20L, 30L, 60L, 90L)

        /** Next option after [current], wrapping; unknown values restart at the first option. */
        fun <T> nextOf(
            options: List<T>,
            current: T,
        ): T {
            val index = options.indexOf(current)
            return options[(index + 1) % options.size]
        }
    }
}
