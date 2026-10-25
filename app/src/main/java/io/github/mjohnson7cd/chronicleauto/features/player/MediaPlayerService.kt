package io.github.mjohnson7cd.chronicleauto.features.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import io.github.mjohnson7cd.chronicleauto.features.car.CoverArtCache
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import io.github.mjohnson7cd.chronicleauto.BuildConfig
import io.github.mjohnson7cd.chronicleauto.R
import io.github.mjohnson7cd.chronicleauto.application.ChronicleApplication
import io.github.mjohnson7cd.chronicleauto.application.Injector
import io.github.mjohnson7cd.chronicleauto.data.local.IBookRepository
import io.github.mjohnson7cd.chronicleauto.data.local.ITrackRepository
import io.github.mjohnson7cd.chronicleauto.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mjohnson7cd.chronicleauto.data.local.LibrarySyncRepository
import io.github.mjohnson7cd.chronicleauto.data.local.PrefsRepo
import io.github.mjohnson7cd.chronicleauto.data.model.EMPTY_AUDIOBOOK
import io.github.mjohnson7cd.chronicleauto.data.model.EMPTY_CHAPTER
import io.github.mjohnson7cd.chronicleauto.data.model.MediaItemTrack
import io.github.mjohnson7cd.chronicleauto.data.model.getActiveTrack
import io.github.mjohnson7cd.chronicleauto.data.model.getProgress
import io.github.mjohnson7cd.chronicleauto.data.model.toMediaItem
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.*
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.ICachedFileManager.CacheStatus
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.IPlexLoginRepo.LoginState.*
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.getDuration
import io.github.mjohnson7cd.chronicleauto.features.car.CarErrorResolution
import io.github.mjohnson7cd.chronicleauto.features.currentlyplaying.CurrentlyPlaying
import io.github.mjohnson7cd.chronicleauto.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_ACTION
import io.github.mjohnson7cd.chronicleauto.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_DURATION_MILLIS
import io.github.mjohnson7cd.chronicleauto.features.player.SleepTimer.SleepTimerAction
import io.github.mjohnson7cd.chronicleauto.injection.components.DaggerServiceComponent
import io.github.mjohnson7cd.chronicleauto.injection.modules.ServiceModule
import io.github.mjohnson7cd.chronicleauto.util.Event
import io.github.mjohnson7cd.chronicleauto.util.PackageValidator
import io.github.mjohnson7cd.chronicleauto.util.SecurityUtils
import io.github.mjohnson7cd.chronicleauto.util.ServiceUtils
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** The service responsible for media playback, notification */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalTime::class)
class MediaPlayerService :
    MediaBrowserServiceCompat(),
    ForegroundServiceController,
    ServiceController,
    IPlaybackErrorReporter,
    SleepTimer.SleepTimerBroadcaster,
    io.github.mjohnson7cd.chronicleauto.features.currentlyplaying.OnChapterChangeListener {
    val serviceJob: CompletableJob = SupervisorJob()
    val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    @Inject
    lateinit var onMediaChangedCallback: OnMediaChangedCallback

    @Inject
    lateinit var packageValidator: PackageValidator

    @Inject
    lateinit var notificationBuilder: NotificationBuilder

    @Inject
    lateinit var plexConfig: PlexConfig

    @Inject
    lateinit var becomingNoisyReceiver: BecomingNoisyReceiver

    @Inject
    lateinit var mediaSession: MediaSessionCompat

    @Inject
    lateinit var mediaController: MediaControllerCompat

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var currentlyPlaying: CurrentlyPlaying

    @Inject
    lateinit var bookRepository: IBookRepository

    @Inject
    lateinit var trackRepository: ITrackRepository

    @Inject
    lateinit var trackListManager: TrackListStateManager

    @Inject
    lateinit var mediaSessionCallback: AudiobookMediaSessionCallback

    @Inject
    lateinit var prefsRepo: PrefsRepo

    @Inject
    lateinit var cachedFileManager: ICachedFileManager

    // AAOS only: drives the download button's icon in the car overflow menu.
    private var carDownloadStatus = CacheStatus.NOT_CACHED
    private var carDownloadPercentStep = 0
    private var carDownloadStatusBookId: String? = null
    private var carDownloadGraceUntil = 0L
    private var carActiveDownloads: Set<String> = emptySet()
    private var carDownloadProgress: Map<String, Int> = emptyMap()
    private val carDownloadProgressObserver =
        Observer<Map<String, Int>> {
            carDownloadProgress = it
            refreshCarDownloadStatus()
        }
    private var carCachedBookIds: Set<String>? = null
    private val carActiveDownloadsObserver =
        Observer<Set<String>> {
            carActiveDownloads = it.toSet()
            refreshCarDownloadStatus()
        }
    private val carCachedBooksObserver =
        Observer<List<io.github.mjohnson7cd.chronicleauto.data.model.Audiobook>> { books ->
            carCachedBookIds = books.map { it.id }.toSet()
            refreshCarDownloadStatus()
        }
    private val carCachedBooksLiveData by lazy { bookRepository.getCachedAudiobooks() }
    private var carFavoriteBookIds: Set<String> = emptySet()
    private val carFavoritesObserver =
        Observer<List<io.github.mjohnson7cd.chronicleauto.data.model.Audiobook>> { books ->
            val ids = books.map { it.id }.toSet()
            if (ids == carFavoriteBookIds) return@Observer
            carFavoriteBookIds = ids
            updateCustomActions()
            notifyChildrenChanged(getString(R.string.auto_category_favorites))
        }
    private val carFavoritesLiveData by lazy { bookRepository.getFavoriteAudiobooks() }

    @Inject
    lateinit var plexPrefs: PlexPrefsRepo

    @Inject
    lateinit var plexLoginRepo: IPlexLoginRepo

    @Inject
    lateinit var playbackStateController: PlaybackStateController

    @Inject
    lateinit var voiceCommandBridgeAudio: VoiceCommandBridgeAudio

    @Inject
    lateinit var librarySyncRepository: LibrarySyncRepository

    companion object {
        /** How long the car download button holds 100% waiting for the DB cached flag. */
        private const val CAR_DOWNLOAD_FINISH_GRACE_MS = 2_000L

        /** Republish the playback state only when the position drifts this far from the controllers' extrapolation. */
        const val POSITION_DRIFT_TOLERANCE_MS = 1500L

        /** Strings used by plex to indicate playback state */
        const val PLEX_STATE_PLAYING = "playing"
        const val PLEX_STATE_STOPPED = "stopped"
        const val PLEX_STATE_PAUSED = "paused"

        /** Strings used to indicate playback errors */
        const val ACTION_PLAYBACK_ERROR = "playback error action intent"
        const val PLAYBACK_ERROR_MESSAGE = "playback error message"

        /**
         * Boolean extra on [ACTION_PLAYBACK_ERROR] indicating the error is being recovered from
         * (fix #3). When `true`, UI should show a transient "Reconnecting…" state; on success a
         * [ACTION_PLAYBACK_RECOVERED] broadcast follows, on failure the same
         * [ACTION_PLAYBACK_ERROR] action is re-sent with this extra set to `false`. Defaults to
         * `false` for receivers that don't read it (backwards-compatible).
         */
        const val EXTRA_IS_RECOVERING = "playback error is recovering"

        /**
         * Broadcast sent by [PlaybackErrorRecoveryHandler] after a recoverable network failure has
         * been successfully recovered (cache flushed, player re-prepared). UI can dismiss the
         * "Reconnecting…" affordance on receipt.
         */
        const val ACTION_PLAYBACK_RECOVERED = "playback recovered action intent"

        /**
         * Key for storing absolute track position in PlaybackStateCompat extras.
         * This is used to avoid confusion with chapter-relative position stored in PlaybackStateCompat.position
         */
        const val EXTRA_ABSOLUTE_TRACK_POSITION = "ABSOLUTE_TRACK_POSITION"

        /**
         * Key indicating playback start time offset relative to the start of the track being
         * played (only use for, m4b chapters, as mp3 durations are generally too imprecise)
         */
        const val KEY_START_TIME_TRACK_OFFSET = "track index bundle 2939829 tubers"

        // Key indicating the ID of the track to begin playback at
        const val KEY_SEEK_TO_TRACK_WITH_ID = "MediaPlayerService.key_seek_to_track_with_id"

        // Value indicating to begin playback at the most recently listened position
        const val ACTIVE_TRACK = "__ACTIVE_TRACK__"
        const val USE_SAVED_TRACK_PROGRESS = Long.MIN_VALUE + 22250L

        private const val CHRONICLE_MEDIA_ROOT_ID = "chronicle_media_root_id"
        private const val CHRONICLE_MEDIA_EMPTY_ROOT = "empty root"
        private const val CHRONICLE_MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        /**
         * Exoplayer back-buffer (millis to keep of playback prior to current location)
         *
         * @see DefaultLoadControl.Builder.setBufferDurationsMs
         */
        val EXOPLAYER_BACK_BUFFER_DURATION_MILLIS: Int = 120.seconds.inWholeMilliseconds.toInt()

        /**
         * Exoplayer min-buffer (the minimum millis of buffer which exo will attempt to keep in
         * memory)
         *
         * @see DefaultLoadControl.Builder.setBufferDurationsMs
         */
        val EXOPLAYER_MIN_BUFFER_DURATION_MILLIS: Int = 10.seconds.inWholeMilliseconds.toInt()

        /**
         * Exoplayer max-buffer (the maximum duration of buffer which Exoplayer will store in memory)
         *
         * @see DefaultLoadControl.Builder.setBufferDurationsMs
         */
        val EXOPLAYER_MAX_BUFFER_DURATION_MILLIS: Int = 360.seconds.inWholeMilliseconds.toInt()
    }

    @Inject
    lateinit var sleepTimer: SleepTimer

    @Inject
    lateinit var progressUpdater: ProgressUpdater

    private fun mediaBrowserCompatStringField(name: String): String? {
        return runCatching { MediaBrowserCompat::class.java.getField(name).get(null) as? String }
            .getOrElse {
                runCatching {
                    MediaBrowserCompat.MediaItem::class.java.getField(name).get(null) as? String
                }.getOrNull()
            }
    }

    private fun mediaBrowserCompatIntField(name: String): Int? {
        return runCatching { MediaBrowserCompat::class.java.getField(name).getInt(null) }
            .getOrElse {
                runCatching { MediaBrowserCompat.MediaItem::class.java.getField(name).getInt(null) }.getOrNull()
            }
    }

    @Inject
    lateinit var mediaSource: PlexMediaRepository

    @Inject
    lateinit var localBroadcastManager: LocalBroadcastManager

    @Inject
    lateinit var connectionRefreshCoordinator: ConnectionRefreshCoordinator

    @Inject
    lateinit var playbackUrlResolver: PlaybackUrlResolver

    @Inject
    lateinit var plexDataSourceFactory: PlexHttpDataSourceFactory

    /**
     * Recovery handler for network-y playback errors (fix #3). Built lazily after Dagger injection
     * because it needs access to the injected fields + [serviceScope] + [currentPlayer].
     */
    private val recoveryHandler: PlaybackErrorRecoveryHandler by lazy { buildRecoveryHandler() }

    var currentPlayer: Player? = null

    private var sessionErrorMessage: String? = null
    private var isErrorState: Boolean = false

    /**
     * "Sign in" error-resolution extras for car hosts; null on the mobile flavor, where sign-in
     * happens in the phone UI. Attached to error states while the user isn't fully logged in.
     */
    private val signInResolutionExtras: Bundle? by lazy { CarErrorResolution.signInExtras(this) }

    private fun signInResolutionIfNeeded(): Bundle? =
        signInResolutionExtras?.takeIf { plexLoginRepo.loginEvent.value?.peekContent() != LOGGED_IN_FULLY }
    private var sessionCustomActions: List<PlaybackStateCompat.CustomAction> = emptyList()
    private val timelineWindow = Timeline.Window()

    /** Track last pause update time for debouncing */
    private var lastPauseUpdateTime = 0L
    private val PAUSE_UPDATE_DEBOUNCE_MS = 500L

    override fun onCreate() {
        super.onCreate()

        Timber.d("[TokenInjection] MediaPlayerService.onCreate() starting - about to inject dependencies")

        DaggerServiceComponent.builder()
            .appComponent((application as ChronicleApplication).appComponent)
            .serviceModule(ServiceModule(this))
            .build()
            .inject(this)

        Timber.d("[TokenInjection] MediaPlayerService.onCreate() - dependencies injected")

        // Verify tokens after injection
        val serverTokenHash = SecurityUtils.hashToken(plexPrefs.server?.accessToken)
        val userTokenHash = SecurityUtils.hashToken(plexPrefs.user?.authToken)
        val accountTokenHash = SecurityUtils.hashToken(plexPrefs.accountAuthToken)
        Timber.d(
            "[TokenInjection] Post-injection token check: " +
                "serverToken=$serverTokenHash, userToken=$userTokenHash, accountToken=$accountTokenHash",
        )

        ServiceUtils.notifyServiceStarted(this)

        Timber.i("Service created! $this")

        // Initialize TTS early so it's ready for voice commands
        voiceCommandBridgeAudio.initialize()

        updateAudioAttrs(exoPlayer)

        prefsRepo.registerPrefsListener(prefsListener)

        serviceScope.launch(Injector.get().unhandledExceptionHandler()) { mediaSource.load() }

        lastPublishedPlaybackState = null
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().build())
        mediaSession.setCallback(mediaSessionCallback)

        updateCustomActions()
        switchToPlayer(exoPlayer)

        mediaController.registerCallback(onMediaChangedCallback)

        // Register chapter change listener to update metadata when chapter changes
        currentlyPlaying.setOnChapterChangeListener(this)

        // Observe PlaybackStateController to keep MediaSession in sync
        // This ensures Android Auto and other media clients always have current state
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            playbackStateController.state.collect { state ->
                Timber.d(
                    "[AndroidAuto] PlaybackStateController state changed: hasMedia=${state.hasMedia}, " +
                        "isPlaying=${state.isPlaying}, trackIndex=${state.currentTrackIndex}",
                )
                // Trigger MediaSession update when controller state changes
                updateSessionPlaybackState()
                // Book/chapter info often arrives after the player's media item transition, so
                // refresh metadata when it changes (otherwise the cluster never sees the title).
                if (metadataKey(state) != lastPublishedMetadataKey) {
                    currentPlayer?.let { updateSessionMetadataFromPlayer(it) }
                }
            }
        }

        // startForeground has to be called within 5 seconds of starting the service or the app
        // will ANR (on Android 9.0 and above, maybe earlier). Call it SYNCHRONOUSLY with an
        // art-free notification so we never miss the window behind a blocking network image
        // fetch, then refresh with artwork asynchronously.
        startForegroundSafely()
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            val notification = notificationBuilder.buildNotification(mediaSession.sessionToken)
            if (notification != null) {
                // Update the existing foreground notification with artwork.
                startForeground(NOW_PLAYING_NOTIFICATION, notification)
            }
        }

        localBroadcastManager.registerReceiver(
            sleepTimerBroadcastReceiver,
            IntentFilter(SleepTimer.ACTION_SLEEP_TIMER_CHANGE),
        )

        invalidatePlaybackParams()
        progressUpdater.startRegularProgressUpdates()

        plexConfig.connectionState.observeForever(serverChangedListener)
        plexLoginRepo.loginEvent.observeForever(loginStateObserver)
        librarySyncRepository.isRefreshing.observeForever(librarySyncObserver)
        if (BuildConfig.IS_AUTOMOTIVE) {
            plexConfig.isConnected.observeForever(carLibrarySyncObserver)
            cachedFileManager.activeBookDownloads.observeForever(carActiveDownloadsObserver)
            cachedFileManager.bookDownloadProgress.observeForever(carDownloadProgressObserver)
            carCachedBooksLiveData.observeForever(carCachedBooksObserver)
            carFavoritesLiveData.observeForever(carFavoritesObserver)
            serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                currentlyPlaying.book.collect { refreshCarDownloadStatus() }
            }
        }
    }

    private fun updateAudioAttrs(exoPlayer: ExoPlayer) {
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(
                    if (prefsRepo.pauseOnFocusLost) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC,
                )
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true,
        )
    }

    private fun updateCustomActions() {
        // Book changes reach here via refreshCarDownloadStatus (it pushes whenever the book id changes).
        val favorited = currentlyPlaying.book.value.id in carFavoriteBookIds
        sessionCustomActions = buildCustomActions(prefsRepo, carDownloadStatus, carDownloadPercentStep, favorited)
        updateSessionPlaybackState()
    }

    /**
     * Recomputes the car download button from the service-owned download state (Fetch progress,
     * active downloads, DB cached flags). The state lives here, not in the car UI, and every
     * PlaybackState push carries the current icon, so closing and reopening the car's overflow
     * row always redraws the right icon: progress ring while downloading, done when cached,
     * download otherwise.
     */
    private fun refreshCarDownloadStatus() {
        val book = currentlyPlaying.book.value
        val bookId = book.id
        val percent = carDownloadProgress[bookId]
        // The DB LiveData is the source of truth once it has emitted; the in-memory playing
        // book's isCached goes stale when downloads are deleted mid-playback.
        val cached = carCachedBookIds?.contains(bookId) ?: book.isCached
        val now = android.os.SystemClock.elapsedRealtime()
        // CachedFileManager drops the book from the progress map right after writing the cached
        // flags, but Room's LiveData re-query lands a moment later. Hold the full ring through
        // that gap instead of flashing the plain download icon between 100% and done.
        val justFinished =
            percent == null && !cached && bookId == carDownloadStatusBookId &&
                carDownloadStatus == CacheStatus.CACHING && carDownloadPercentStep == 100
        if (justFinished && carDownloadGraceUntil == 0L) {
            carDownloadGraceUntil = now + CAR_DOWNLOAD_FINISH_GRACE_MS
            serviceScope.launch {
                delay(CAR_DOWNLOAD_FINISH_GRACE_MS + 100)
                refreshCarDownloadStatus()
            }
        }
        val inGrace = justFinished && now < carDownloadGraceUntil
        if (!justFinished) carDownloadGraceUntil = 0L
        val status =
            when {
                percent != null || bookId in carActiveDownloads || inGrace -> CacheStatus.CACHING
                cached -> CacheStatus.CACHED
                else -> CacheStatus.NOT_CACHED
            }
        val step =
            when {
                status != CacheStatus.CACHING -> 0
                inGrace -> 100
                else -> downloadProgressStep(percent ?: 0)
            }
        // Only push when the visible icon changes (10% steps): each push re-lays out the bar.
        if (status != carDownloadStatus || step != carDownloadPercentStep || bookId != carDownloadStatusBookId) {
            Timber.i("Car download status for $bookId: $carDownloadStatus/$carDownloadPercentStep% -> $status/$step%")
            carDownloadStatus = status
            carDownloadPercentStep = step
            carDownloadStatusBookId = bookId
            updateCustomActions()
        }
    }

    private fun setSessionCustomErrorMessage(message: String?) {
        if (message != null) {
            // When setting error message, also set STATE_ERROR
            setPlaybackStateError(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message)
        } else {
            // Clear error state
            sessionErrorMessage = null
            isErrorState = false
            updateSessionPlaybackState()
        }
    }

    /**
     * Sets the MediaSession playback state to STATE_ERROR with appropriate error code and message.
     * This ensures Android Auto displays the error to the user instead of failing silently.
     *
     * @param errorCode PlaybackStateCompat error code (e.g., ERROR_CODE_AUTHENTICATION_EXPIRED)
     * @param errorMessage User-facing error message to display in Android Auto
     */
    override fun setPlaybackStateError(
        errorCode: Int,
        errorMessage: String,
    ) {
        Timber.e("[AndroidAuto] Setting error state: code=$errorCode, message=$errorMessage")

        // Stop current playback when entering error state
        currentPlayer?.playWhenReady = false

        isErrorState = true
        sessionErrorMessage = errorMessage

        val errorState =
            PlaybackStateCompat.Builder()
                .setActions(basePlaybackActions())
                .setState(
                    PlaybackStateCompat.STATE_ERROR,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f,
                )
                .setErrorMessage(errorCode, errorMessage)
                .apply { signInResolutionIfNeeded()?.let { setExtras(it) } }
                .build()

        lastPublishedPlaybackState = null
        mediaSession.setPlaybackState(errorState)
    }

    /**
     * Clears the error state when playback starts successfully.
     */
    override fun clearPlaybackError() {
        if (isErrorState) {
            Timber.d("[AndroidAuto] Clearing error state")
            isErrorState = false
            sessionErrorMessage = null
            updateSessionPlaybackState()
        }
    }

    override fun broadcastUpdate(
        sleepTimerAction: SleepTimerAction,
        durationMillis: Long,
    ) {
        val broadcastIntent =
            Intent(SleepTimer.ACTION_SLEEP_TIMER_CHANGE).apply {
                putExtra(ARG_SLEEP_TIMER_ACTION, sleepTimerAction)
                putExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, durationMillis)
            }
        localBroadcastManager.sendBroadcast(broadcastIntent)
    }

    private val sleepTimerBroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent != null) {
                    val durationMillis = intent.getLongExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, 0L)
                    val action =
                        IntentCompat.getSerializableExtra(
                            intent,
                            ARG_SLEEP_TIMER_ACTION,
                            SleepTimerAction::class.java,
                        )
                    if (action != null) {
                        sleepTimer.handleAction(action, durationMillis)
                    }
                }
            }
        }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PrefsRepo.KEY_SKIP_SILENCE -> {
                    invalidatePlaybackParams()
                }
                PrefsRepo.KEY_PLAYBACK_SPEED -> {
                    invalidatePlaybackParams()
                    updateCustomActions()
                }
                PrefsRepo.KEY_PAUSE_ON_FOCUS_LOST -> {
                    updateAudioAttrs(exoPlayer)
                }
                PrefsRepo.KEY_JUMP_FORWARD_SECONDS, PrefsRepo.KEY_JUMP_BACKWARD_SECONDS -> {
                    updateCustomActions()
                    serviceScope.launch {
                        withContext(Dispatchers.IO) {
                            sessionToken?.let {
                                val notification = notificationBuilder.buildNotification(it)
                                startForeground(NOW_PLAYING_NOTIFICATION, notification)
                            }
                        }
                    }
                }
            }
        }

    private val serverChangedListener =
        Observer<PlexConfig.ConnectionState> {
            if (mediaController.playbackState.isPrepared) {
                // Only can change server when playback is prepared because otherwise we would be
                // attempting to load data on a null/empty tracklist
                onChangeConnection()
            }
        }

    private val loginStateObserver =
        Observer<Event<IPlexLoginRepo.LoginState>> { event ->
            event.peekContent().let { loginState ->
                Timber.d("[AndroidAuto] Login state changed: $loginState")

                // Clear Android Auto error messages when login completes successfully
                if (loginState == LOGGED_IN_FULLY && isErrorState) {
                    Timber.d("[AndroidAuto] Login successful - clearing error state")
                    // Set playback state to STOPPED to clear any displayed error messages
                    lastPublishedPlaybackState = null
                    mediaSession.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setState(
                                PlaybackStateCompat.STATE_STOPPED,
                                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                                0f,
                            )
                            .build(),
                    )
                    isErrorState = false
                    sessionErrorMessage = null
                }

                notifyChildrenChanged(CHRONICLE_MEDIA_ROOT_ID)
            }
        }

    private val librarySyncObserver =
        Observer<Boolean> { isRefreshing ->
            if (!isRefreshing) {
                Timber.d("[AndroidAuto] Library sync completed, refreshing browse tree")
                notifyChildrenChanged(CHRONICLE_MEDIA_ROOT_ID)
                notifyChildrenChanged(getString(R.string.auto_category_library))
                notifyChildrenChanged(getString(R.string.auto_category_recently_listened))
                notifyChildrenChanged(getString(R.string.auto_category_offline))
            }
        }

    /**
     * AAOS only: the phone build syncs the library from its Home/Library screens, which don't
     * exist in the car. Sync from here instead whenever the server connects and the local
     * library is empty or older than the configured refresh rate.
     */
    private val carLibrarySyncObserver =
        Observer<Boolean> { isConnected ->
            if (!isConnected || plexLoginRepo.loginEvent.value?.peekContent() != LOGGED_IN_FULLY) {
                return@Observer
            }
            if (librarySyncRepository.isRefreshing.value == true) return@Observer
            serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                val minutesSinceRefresh = (System.currentTimeMillis() - prefsRepo.lastRefreshTimeStamp) / 60_000L
                val isEmpty = withContext(Dispatchers.IO) { bookRepository.getBookCount() } == 0
                if (isEmpty || minutesSinceRefresh > prefsRepo.refreshRateMinutes) {
                    Timber.i("[AAOS] Syncing library (empty=$isEmpty, ${minutesSinceRefresh}min since last sync)")
                    librarySyncRepository.refreshLibrary()
                }
            }
        }

    /**
     * Change the tracks in the player to refer to the new server url. Because [PlexConfig] is a
     * Singleton we don't need to keep track of state here
     */
    private fun onChangeConnection() {
        when (mediaController.playbackState.state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                mediaSessionCallback.onPlayFromMediaId(
                    trackListManager.trackList.map { it.id }.firstOrNull { true }.toString(),
                    Bundle().apply {
                        putString(KEY_SEEK_TO_TRACK_WITH_ID, ACTIVE_TRACK)
                        putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
                    },
                )
            }
            PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.STATE_BUFFERING -> {
                mediaSessionCallback.onPrepareFromMediaId(
                    trackListManager.trackList.map { it.id }.firstOrNull { true }.toString(),
                    Bundle().apply {
                        putString(KEY_SEEK_TO_TRACK_WITH_ID, ACTIVE_TRACK)
                        putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
                    },
                )
            }
            else -> {
                // if there isn't playback, there's nothing to change
            }
        }
    }

    private fun invalidatePlaybackParams() {
        Timber.i(
            "Playback params: speed = ${prefsRepo.playbackSpeed}, skip silence = ${prefsRepo.skipSilence}",
        )
        currentPlayer?.setPlaybackParameters(PlaybackParameters(prefsRepo.playbackSpeed, 1.0f))
        (currentPlayer as? ExoPlayer)?.skipSilenceEnabled = prefsRepo.skipSilence
    }

    private fun updateSessionPlaybackState() {
        val player = currentPlayer
        val playbackState =
            if (player != null) {
                buildPlaybackState(player)
            } else {
                buildEmptyPlaybackState()
            }
        // Car media rebuilds its control bar on every state push, which collapses the ⋮ overflow row;
        // the ~1Hz progress ticks carry nothing controllers can't extrapolate, so skip those.
        if (isEquivalent(lastPublishedPlaybackState, playbackState)) return
        lastPublishedPlaybackState = playbackState
        mediaSession.setPlaybackState(playbackState)
    }

    private var lastPublishedPlaybackState: PlaybackStateCompat? = null

    private fun isEquivalent(
        old: PlaybackStateCompat?,
        new: PlaybackStateCompat,
    ): Boolean {
        if (old == null) return false
        if (old.state != new.state || old.actions != new.actions || old.playbackSpeed != new.playbackSpeed ||
            old.errorMessage != new.errorMessage || old.activeQueueItemId != new.activeQueueItemId
        ) {
            return false
        }
        val actions = { s: PlaybackStateCompat -> s.customActions.map { Triple(it.action, it.name, it.icon) } }
        if (actions(old) != actions(new)) return false
        val elapsed = new.lastPositionUpdateTime - old.lastPositionUpdateTime
        val expected = old.position + if (old.state == PlaybackStateCompat.STATE_PLAYING) (elapsed * old.playbackSpeed).toLong() else 0L
        return kotlin.math.abs(new.position - expected) < POSITION_DRIFT_TOLERANCE_MS
    }

    private fun buildPlaybackState(player: Player): PlaybackStateCompat {
        // If in error state, return error PlaybackState
        if (isErrorState && sessionErrorMessage != null) {
            val builder =
                PlaybackStateCompat.Builder()
                    .setActions(basePlaybackActions())
                    .setState(PlaybackStateCompat.STATE_ERROR, 0L, 0f)
                    .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, sessionErrorMessage)
            signInResolutionIfNeeded()?.let { builder.setExtras(it) }

            sessionCustomActions.forEach(builder::addCustomAction)
            return builder.build()
        }

        val playbackState = mapPlayerState(player)
        val playbackSpeed = player.playbackParameters.speed

        // Calculate chapter-relative position for the progress bar
        val trackPosition = if (player.playbackState == Player.STATE_IDLE) 0L else player.currentPosition
        // Read from synchronous state source to avoid race condition with async updates
        val chapter = playbackStateController.state.value.currentChapter ?: EMPTY_CHAPTER
        val position =
            if (chapter != EMPTY_CHAPTER) {
                // Chapter-scoped position: current position minus chapter start
                kotlin.math.max(0L, trackPosition - chapter.startTimeOffset)
            } else {
                // Fallback to track position when no chapter data
                trackPosition
            }

        // Calculate chapter-relative buffered position
        val trackBufferedPosition = player.bufferedPosition
        val bufferedPosition =
            if (chapter != EMPTY_CHAPTER) {
                kotlin.math.max(0L, trackBufferedPosition - chapter.startTimeOffset)
            } else {
                trackBufferedPosition
            }

        // Store absolute track position in extras to avoid confusion with chapter-relative position
        val extras =
            Bundle().apply {
                putLong(EXTRA_ABSOLUTE_TRACK_POSITION, trackPosition)
            }

        val builder =
            PlaybackStateCompat.Builder()
                .setActions(basePlaybackActions())
                .setBufferedPosition(bufferedPosition)
                .setState(playbackState, position, playbackSpeed)
                .setExtras(extras)
                // Queue ids are chapter indices (see playBook), so this highlights the playing chapter.
                .setActiveQueueItemId(playbackStateController.state.value.currentChapterIndex.toLong())

        sessionCustomActions.forEach(builder::addCustomAction)
        sessionErrorMessage?.let {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it)
        }

        return builder.build()
    }

    private fun basePlaybackActions(): Long =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
            PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED or
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM

    private fun buildEmptyPlaybackState(): PlaybackStateCompat {
        val builder =
            PlaybackStateCompat.Builder()
                .setActions(basePlaybackActions())
                .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
        sessionCustomActions.forEach(builder::addCustomAction)
        sessionErrorMessage?.let {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it)
            signInResolutionIfNeeded()?.let { extras -> builder.setExtras(extras) }
        }
        return builder.build()
    }

    private fun mapPlayerState(player: Player): Int =
        when (player.playbackState) {
            Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY ->
                if (player.playWhenReady) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }

    private fun updateSessionMetadataFromPlayer(player: Player) {
        val description =
            player.currentMediaItem?.localConfiguration?.tag as? MediaDescriptionCompat
                ?: extractDescriptionFromTimeline(player)

        // Read from synchronous state source to avoid race condition with async updates
        // This ensures metadata is always up-to-date, even during multi-library playback
        val state = playbackStateController.state.value
        val chapter = state.currentChapter ?: EMPTY_CHAPTER
        val book = state.audiobook ?: EMPTY_AUDIOBOOK
        val hasBook = book != EMPTY_AUDIOBOOK && book.title.isNotBlank()

        if (description == null && !hasBook) {
            return
        }

        val metadata =
            if (hasBook) {
                // Always publish full book metadata (TITLE/ARTIST/ALBUM/art) once the book is known.
                // AAOS media center and the instrument cluster read these standard keys; without
                // them the cluster falls back to its placeholder text.
                val hasChapter = chapter != EMPTY_CHAPTER
                val title = if (hasChapter && chapter.title.isNotBlank()) chapter.title else book.title
                val duration =
                    if (hasChapter) {
                        chapter.endTimeOffset - chapter.startTimeOffset
                    } else {
                        description?.extras?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0L)
                            ?.takeIf { it > 0 }
                            ?: player.duration.takeIf { it > 0 }
                            ?: 0L
                    }

                val metadataBuilder =
                    MediaMetadataCompat.Builder()
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                            description?.mediaId ?: book.id.toString(),
                        )
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.author)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, book.author)
                        .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, book.author)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book.title)
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                            if (hasChapter) book.title else book.author,
                        )
                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, book.author)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

                // Art URI (works for in-process consumers / content-resolvable URIs)
                // Car media prefers the URI over the bitmap, so on AAOS it must be our content:// cover.
                val artUri =
                    if (BuildConfig.IS_AUTOMOTIVE) {
                        CoverArtCache.getCachedCoverUri(applicationContext, book.id.toString())
                    } else {
                        description?.iconUri
                    }
                artUri?.toString()?.let {
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it)
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it)
                }
                // Bitmap art: the cluster/media center can't load authenticated Plex URLs or our
                // private file:// URIs, so ship the locally cached cover as a bitmap.
                CoverArtCache.loadCachedBitmap(applicationContext, book.id.toString())?.let {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                }

                metadataBuilder.build()
            } else {
                // Book not loaded yet: fallback to standard track-based metadata
                val base = description!!.toMediaMetadataCompat()
                MediaMetadataCompat.Builder(base).apply {
                    description.subtitle?.toString()?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
                    }
                }.build()
            }

        lastPublishedMetadataKey = metadataKey(state)
        mediaSession.setMetadata(metadata)
    }

    /** Identity of what the published metadata describes; used to avoid redundant updates. */
    private var lastPublishedMetadataKey: String? = null

    private fun metadataKey(state: PlaybackState): String {
        val book = state.audiobook
        val chapter = state.currentChapter
        return "${book?.id}|${book?.title}|${book?.author}|${state.currentTrackIndex}|" +
            "${chapter?.title}|${chapter?.startTimeOffset}"
    }

    private fun extractDescriptionFromTimeline(player: Player): MediaDescriptionCompat? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            return null
        }
        timeline.getWindow(player.currentMediaItemIndex, timelineWindow)
        return timelineWindow.mediaItem?.localConfiguration?.tag as? MediaDescriptionCompat
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Failsafe: pause before teardown so audio never keeps going while we clean up.
        currentPlayer?.pause()

        // Ensures that players will not block being removed as a foreground service
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    override fun onDestroy() {
        Timber.i("Service destroyed")
        // Failsafe: pause first so playback stops (and the final position is stable) before cleanup.
        currentPlayer?.pause()

        // Release TTS resources
        voiceCommandBridgeAudio.release()

        plexLoginRepo.loginEvent.removeObserver(loginStateObserver)
        librarySyncRepository.isRefreshing.removeObserver(librarySyncObserver)
        plexConfig.isConnected.removeObserver(carLibrarySyncObserver)
        if (BuildConfig.IS_AUTOMOTIVE) {
            cachedFileManager.activeBookDownloads.removeObserver(carActiveDownloadsObserver)
            cachedFileManager.bookDownloadProgress.removeObserver(carDownloadProgressObserver)
            carCachedBooksLiveData.removeObserver(carCachedBooksObserver)
            carFavoritesLiveData.removeObserver(carFavoritesObserver)
        }

        // Send one last update to local/remote servers that playback has stopped
        val metadata = mediaController.metadata
        val trackId = metadata?.id
        Timber.d("onDestroy: metadata=${if (metadata != null) "present" else "null"}, trackId=$trackId")
        if (trackId != null && trackId != TRACK_NOT_FOUND) {
            val finalPosition = currentPlayer?.currentPosition ?: 0L
            Timber.d("onDestroy: Sending final progress update for trackId=$trackId, position=$finalPosition")
            progressUpdater.updateProgress(
                trackId,
                PLEX_STATE_STOPPED,
                finalPosition,
                true,
            )
        } else {
            Timber.d("onDestroy: Skipping progress update - no valid track metadata available")
        }

        // Clear playback state in controller
        serviceScope.launch {
            playbackStateController.clear()
        }

        progressUpdater.cancel()
        serviceJob.cancel()

        plexConfig.connectionState.removeObserver(serverChangedListener)
        prefsRepo.unregisterPrefsListener(prefsListener)
        localBroadcastManager.unregisterReceiver(sleepTimerBroadcastReceiver)
        sleepTimer.cancel()

        mediaSession.run {
            isActive = false
            release()
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            intent.setPackage(packageName)
            intent.component =
                ComponentName(
                    packageName,
                    MediaPlayerService::class.qualifiedName
                        ?: "io.github.mjohnson7cd.chronicleauto.features.player.MediaPlayerService",
                )
            intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, 312202))
            // Allow the system to restart app past death on media button click. See onStartCommand
            setMediaButtonReceiver(
                PendingIntent.getService(
                    this@MediaPlayerService,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        mediaSession.setCallback(null)
        mediaController.unregisterCallback(onMediaChangedCallback)
        becomingNoisyReceiver.unregister()
        serviceJob.cancel()

        exoPlayer.removeListener(playerEventListener)

        ServiceUtils.notifyServiceStopped(this)
        super.onDestroy()
    }

    /** Handle hardware commands from notifications and custom actions from UI as intents */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // No need to parse actions if none were provided
        Timber.i("Start command!")

        // Handle intents sent from notification clicks as media button events
        val ke: KeyEvent? =
            intent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
        Timber.i("Key event: $ke")
        if (ke != null) {
            mediaSessionCallback.onMediaButtonEvent(intent)
        }

        // startForeground has to be called within 5 seconds of starting the service or the app
        // will ANR (on Android 9.0+). Even if we don't have full metadata here for unknown reasons,
        // we should launch with whatever it is we have, assuming the event isn't the notification
        // itself being removed (KEYCODE_MEDIA_STOP)
        if (ke?.keyCode != KEYCODE_MEDIA_STOP) {
            // Promote to foreground SYNCHRONOUSLY with an art-free notification to guarantee we
            // meet the OS deadline, then refresh with artwork asynchronously.
            startForegroundSafely()
            serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                val notification = notificationBuilder.buildNotification(mediaSession.sessionToken)
                if (notification != null) {
                    // Update the existing foreground notification with artwork.
                    startForeground(NOW_PLAYING_NOTIFICATION, notification)
                }
            }
        }

        /**
         * Return [START_NOT_STICKY] to instruct the system not to restart the
         * service upon death by the OS. This will prevent an empty notification
         * from appearing on service restart
         */
        return START_NOT_STICKY
    }

    /**
     * Promote the service to the foreground immediately using a notification that requires no
     * network I/O. This must complete quickly to satisfy the OS foreground-service start deadline
     * (otherwise [android.app.RemoteServiceException.ForegroundServiceDidNotStartInTimeException]
     * is thrown). Any failure to start (e.g. background-start restrictions on API 31+) is caught
     * and logged rather than crashing the process.
     */
    private fun startForegroundSafely() {
        try {
            val notification = notificationBuilder.buildNotificationWithoutArt(mediaSession.sessionToken)
            startForeground(NOW_PLAYING_NOTIFICATION, notification)
        } catch (e: Exception) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+) and any other
            // start-time failures. Nothing actionable for the user; avoid crashing.
            Timber.e(e, "Failed to start foreground service")
        }
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        Timber.d("[AndroidAuto] onLoadChildren: parentId=$parentId")

        // Handle error/message items - these are not browsable, return empty
        if (parentId.startsWith("__error_") || parentId.startsWith("__message_")) {
            Timber.d("[AndroidAuto] Message item requested, returning empty (not browsable)")
            result.sendResult(mutableListOf())
            return
        }

        if (parentId == CHRONICLE_MEDIA_EMPTY_ROOT || !prefsRepo.allowAuto) {
            Timber.d("[AndroidAuto] Returning empty result (empty root or auto disabled)")
            result.sendResult(mutableListOf())
            return
        }

        result.detach()
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            try {
                withContext(Dispatchers.IO) {
                    when (parentId) {
                        CHRONICLE_MEDIA_ROOT_ID -> {
                            // Check login state and display appropriate message if not fully logged in
                            val loginState = plexLoginRepo.loginEvent.value?.peekContent()
                            if (loginState != LOGGED_IN_FULLY) {
                                Timber.w("[AndroidAuto] Not logged in fully, showing message. State: $loginState")
                                val errorMessage =
                                    when (loginState) {
                                        NOT_LOGGED_IN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_not_logged_in),
                                                subtitle = getString(R.string.auto_not_logged_in_subtitle),
                                                iconRes = R.drawable.ic_lock_white_24dp,
                                                mediaId = "__error_not_logged_in__",
                                            )
                                        }
                                        LOGGED_IN_NO_USER_CHOSEN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_no_user_chosen),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_no_user__",
                                            )
                                        }
                                        LOGGED_IN_NO_SERVER_CHOSEN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_no_server_chosen),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_no_server__",
                                            )
                                        }
                                        LOGGED_IN_NO_LIBRARY_CHOSEN -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_access_error_no_library_chosen),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_no_library__",
                                            )
                                        }
                                        else -> {
                                            makeMessageItem(
                                                title = getString(R.string.auto_error_complete_required_steps_on_phone),
                                                iconRes = R.drawable.ic_error_outline_white,
                                                mediaId = "__error_unknown__",
                                            )
                                        }
                                    }
                                result.sendResult(mutableListOf(errorMessage))
                                return@withContext
                            }

                            // Query content to determine tab visibility
                            val recentlyListened = bookRepository.getRecentlyListenedAsync()
                            val cachedBooks = bookRepository.getCachedAudiobooksAsync()

                            // Build tab list conditionally based on content
                            val tabs = mutableListOf<MediaBrowserCompat.MediaItem>()

                            if (recentlyListened.isNotEmpty()) {
                                tabs.add(
                                    makeBrowsable(
                                        getString(R.string.auto_category_recently_listened),
                                        R.drawable.ic_recent,
                                    ),
                                )
                            }

                            if (cachedBooks.isNotEmpty()) {
                                tabs.add(
                                    makeBrowsable(
                                        getString(R.string.auto_category_offline),
                                        R.drawable.ic_cloud_download_white,
                                    ),
                                )
                            }

                            tabs.add(
                                makeBrowsable(
                                    getString(R.string.auto_category_favorites),
                                    R.drawable.ic_favorite_white,
                                ),
                            )

                            tabs.add(
                                makeBrowsable(
                                    getString(R.string.auto_category_library),
                                    R.drawable.nav_library,
                                ),
                            )

                            Timber.d(
                                "[AndroidAuto] Loading root categories: " +
                                    "recentlyListened=${recentlyListened.size}, " +
                                    "cachedBooks=${cachedBooks.size}, " +
                                    "tabs=${tabs.size}",
                            )
                            result.sendResult(tabs)
                        }
                        getString(R.string.auto_category_recently_listened) -> {
                            Timber.d("[AndroidAuto] Loading recently listened")
                            val recentlyListened = bookRepository.getRecentlyListenedAsync()
                            val items =
                                recentlyListened
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig, applicationContext) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} recently listened items")
                            result.sendResult(items)
                        }
                        getString(R.string.auto_category_library) -> {
                            Timber.d("[AndroidAuto] Loading full library")
                            val books = bookRepository.getAllBooksAsync()
                            val items =
                                books
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig, applicationContext) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} library items")
                            result.sendResult(items)
                        }
                        getString(R.string.auto_category_favorites) -> {
                            val items =
                                bookRepository.getFavoriteAudiobooksAsync()
                                    .map { it.toMediaItem(plexConfig, applicationContext) }
                                    .toMutableList()
                            if (items.isEmpty()) {
                                items.add(
                                    makeMessageItem(
                                        title = getString(R.string.auto_favorites_empty),
                                        iconRes = R.drawable.ic_favorite_white,
                                        mediaId = "__favorites_empty__",
                                    ),
                                )
                            }
                            result.sendResult(items)
                        }
                        getString(R.string.auto_category_offline) -> {
                            Timber.d("[AndroidAuto] Loading offline content")
                            val offline = bookRepository.getCachedAudiobooksAsync()
                            val items =
                                offline
                                    .filterNotNull()
                                    .map { it.toMediaItem(plexConfig, applicationContext) }
                                    .toMutableList()
                            Timber.d("[AndroidAuto] Loaded ${items.size} offline items")
                            result.sendResult(items)
                        }
                        else -> {
                            Timber.w("[AndroidAuto] Unknown parentId in onLoadChildren: $parentId")
                            result.sendResult(mutableListOf())
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[AndroidAuto] Error loading children for parentId: $parentId")
                result.sendResult(mutableListOf())
            }
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        Timber.i("[AndroidAuto] onSearch: query='$query'")

        if (!prefsRepo.allowAuto) {
            Timber.w("[AndroidAuto] Search rejected - Android Auto is disabled")
            result.sendResult(mutableListOf())
            return
        }

        if (query.isBlank()) {
            Timber.w("[AndroidAuto] Empty search query")
            result.sendResult(mutableListOf())
            return
        }

        result.detach()
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            try {
                withContext(Dispatchers.IO) {
                    val books = bookRepository.searchAsync(query)
                    val items =
                        books
                            .filterNotNull()
                            .map { it.toMediaItem(plexConfig, applicationContext) }
                            .toMutableList()
                    Timber.d("[AndroidAuto] Search found ${items.size} results for: $query")
                    result.sendResult(items)
                }
            } catch (e: Exception) {
                Timber.e(e, "[AndroidAuto] Error searching for: $query")
                result.sendResult(mutableListOf())
            }
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        Timber.i("[AndroidAuto] onGetRoot: package=$clientPackageName, uid=$clientUid")

        val isClientLegal = packageValidator.isKnownCaller(clientPackageName, clientUid) || BuildConfig.DEBUG || !prefsRepo.strictAutoValidation

        val extras =
            Bundle().apply {
                putBoolean(
                    CHRONICLE_MEDIA_SEARCH_SUPPORTED,
                    isClientLegal && prefsRepo.allowAuto && plexLoginRepo.loginEvent.value?.peekContent() == LOGGED_IN_FULLY,
                )
                mediaBrowserCompatStringField("EXTRA_MEDIA_SEARCH_SUPPORTED")?.let { putBoolean(it, true) }
                mediaBrowserCompatStringField("EXTRA_SUGGESTED_PRESENTATION_DISPLAY_HINT")?.let { putBoolean(it, true) }
                val focusKey = mediaBrowserCompatStringField("EXTRA_MEDIA_FOCUS")
                val focusValue = mediaBrowserCompatIntField("FOCUS_FULL")
                if (focusKey != null && focusValue != null) {
                    putInt(focusKey, focusValue)
                }
            }

        return when {
            !prefsRepo.allowAuto -> {
                Timber.w("[AndroidAuto] Access denied - Android Auto is disabled")
                setSessionCustomErrorMessage(
                    getString(R.string.auto_access_error_auto_is_disabled),
                )
                BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
            }
            !isClientLegal -> {
                Timber.w("[AndroidAuto] Access denied - invalid client: $clientPackageName")
                setSessionCustomErrorMessage(
                    getString(R.string.auto_access_error_invalid_client),
                )
                BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
            }
            else -> {
                // Return normal root even if not logged in - onLoadChildren() will display appropriate message
                val loginState = plexLoginRepo.loginEvent.value?.peekContent()
                if (loginState != LOGGED_IN_FULLY) {
                    Timber.w("[AndroidAuto] Not fully logged in - will show message in browse tree")
                    if (signInResolutionExtras != null) {
                        // AAOS: there is no phone app to finish setup in, so surface an error with a
                        // "Sign in" resolution button right away (Google's car-app sign-in pattern).
                        setPlaybackStateError(
                            PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                            getString(loginErrorMessageRes(loginState)),
                        )
                    }
                } else {
                    Timber.d("[AndroidAuto] Access granted")
                    setSessionCustomErrorMessage(null)
                }
                BrowserRoot(CHRONICLE_MEDIA_ROOT_ID, extras)
            }
        }
    }

    private fun loginErrorMessageRes(loginState: IPlexLoginRepo.LoginState?): Int =
        when (loginState) {
            LOGGED_IN_NO_USER_CHOSEN -> R.string.auto_access_error_no_user_chosen
            LOGGED_IN_NO_SERVER_CHOSEN -> R.string.auto_access_error_no_server_chosen
            LOGGED_IN_NO_LIBRARY_CHOSEN -> R.string.auto_access_error_no_library_chosen
            else -> R.string.auto_access_error_not_logged_in
        }

    private val playerEventListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.e("Exoplayer playback error: $error")
                // Fix #3: delegate to the recovery handler. It broadcasts ACTION_PLAYBACK_ERROR
                // itself (with EXTRA_IS_RECOVERING reflecting whether it intends to attempt
                // recovery), so we no longer broadcast here. The handler will also send
                // ACTION_PLAYBACK_RECOVERED on success or a follow-up ACTION_PLAYBACK_ERROR with
                // EXTRA_IS_RECOVERING=false if recovery fails.
                recoveryHandler.handleError(error)
                setSessionCustomErrorMessage(error.message)
                updateSessionPlaybackState()
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                // Update playing state in controller
                serviceScope.launch {
                    playbackStateController.updatePlayingState(playWhenReady)
                }
                updateSessionPlaybackState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Update playing state in controller
                serviceScope.launch {
                    playbackStateController.updatePlayingState(isPlaying)
                }
                updateSessionPlaybackState()

                // When playback actually starts, stop TTS bridge audio and log latency
                if (isPlaying) {
                    voiceCommandBridgeAudio.stop()
                    mediaSessionCallback.logVoiceCommandLatencyIfPending()
                } else {
                    // Playback paused - send immediate update to Plex
                    // Only send if user explicitly paused (playWhenReady = false), not during buffering
                    if (!exoPlayer.playWhenReady) {
                        sendImmediatePauseUpdate()
                    }
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                currentPlayer?.let {
                    updateSessionMetadataFromPlayer(it)
                    updateSessionPlaybackState()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        Timber.i("Playing next track")
                        // Update track progress
                        val trackId = mediaController.metadata.id
                        if (trackId != null && trackId != TRACK_NOT_FOUND) {
                            val plexState = PLEX_STATE_PLAYING
                            withContext(Dispatchers.IO) {
                                val bookId = trackRepository.getBookIdForTrack(trackId)
                                val track = trackRepository.getTrackAsync(trackId)
                                val tracks = trackRepository.getTracksForAudiobookAsync(bookId)

                                if (tracks.getDuration() == tracks.getProgress()) {
                                    mediaController.transportControls.stop()
                                }
                                progressUpdater.updateProgress(
                                    trackId,
                                    plexState,
                                    track?.duration ?: 0L,
                                    true,
                                )
                            }
                        }
                    }
                }
                currentPlayer?.let { updateSessionMetadataFromPlayer(it) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_IDLE) {
                    setSessionCustomErrorMessage(null)
                }
                if (playbackState == Player.STATE_READY) {
                    // Fix #3: a successful track start clears the retry budget so the next
                    // off-network handover can attempt recovery again from scratch.
                    recoveryHandler.onPlayerReady()
                }
                updateSessionPlaybackState()
                if (playbackState != Player.STATE_ENDED) {
                    return
                }
                Timber.i("Player STATE ENDED")
                serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
                    withContext(Dispatchers.IO) {
                        // get track through tracklistmanager b/c metadata will be empty
                        val activeTrack = trackListManager.trackList.getActiveTrack()
                        if (activeTrack.id != MediaItemTrack.EMPTY_TRACK.id) {
                            progressUpdater.updateProgress(
                                activeTrack.id,
                                PLEX_STATE_STOPPED,
                                activeTrack.duration,
                                true,
                            )
                        }
                    }
                }
            }
        }

    private fun switchToPlayer(player: Player) {
        if (player == currentPlayer) {
            Timber.i("NOT SWITCHING PLAYER")
            return
        }
        Timber.i("SWITCHING PLAYER to $player")

        val prevPlayer: Player? = currentPlayer

        prevPlayer?.removeListener(playerEventListener)
        if (prevPlayer?.playbackState == Player.STATE_ENDED) {
            prevPlayer.stop()
        }

        currentPlayer = player
        mediaSessionCallback.currentPlayer = player

        prevPlayer?.let {
            val previousIndex = it.currentMediaItemIndex
            if (previousIndex != C.INDEX_UNSET) {
                player.seekTo(previousIndex, it.currentPosition)
            } else {
                player.seekTo(it.currentPosition)
            }
            player.playWhenReady = it.playWhenReady
        }

        player.addListener(playerEventListener)

        prevPlayer?.takeIf { it != player }?.let {
            if (it.playbackState != Player.STATE_ENDED) {
                it.stop()
            }
            it.clearMediaItems()
        }

        updateSessionMetadataFromPlayer(player)
        updateSessionPlaybackState()
        invalidatePlaybackParams()
    }

    override fun stopService() {
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    override fun stopForegroundService(removeNotification: Boolean) {
        stopForegroundCompat(removeNotification)
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val stopMode =
                if (removeNotification) {
                    Service.STOP_FOREGROUND_REMOVE
                } else {
                    Service.STOP_FOREGROUND_DETACH
                }
            stopForeground(stopMode)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    /**
     * Constructs the [PlaybackErrorRecoveryHandler] used by `onPlayerError`. Built lazily so that
     * Dagger injection has run and [currentPlayer] is wired before any field is dereferenced.
     *
     * The [PlaybackErrorRecoveryHandler.PlayerHandle] adapts the parts of the current ExoPlayer
     * the handler needs; the [PlaybackErrorRecoveryHandler.MediaSourceRebuilder] reads the active
     * track from [trackRepository], regenerates its streaming URL through the now-refreshed
     * [PlaybackUrlResolver] / [ServerConnectionResolver] chain, and wraps it in a
     * [androidx.media3.exoplayer.source.ProgressiveMediaSource] built with the same
     * [PlexHttpDataSourceFactory] the original playback used.
     */
    private fun buildRecoveryHandler(): PlaybackErrorRecoveryHandler {
        val playerHandle =
            object : PlaybackErrorRecoveryHandler.PlayerHandle {
                override fun getCurrentMediaItem(): MediaItem? = currentPlayer?.currentMediaItem

                override fun getCurrentPosition(): Long = currentPlayer?.currentPosition ?: 0L

                override fun getPlayWhenReady(): Boolean = currentPlayer?.playWhenReady ?: false

                override fun setMediaSource(
                    mediaSource: androidx.media3.exoplayer.source.MediaSource,
                    resetPosition: Boolean,
                ) {
                    (currentPlayer as? ExoPlayer)?.setMediaSource(mediaSource, resetPosition)
                }

                override fun seekTo(positionMs: Long) {
                    currentPlayer?.seekTo(positionMs)
                }

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    currentPlayer?.playWhenReady = playWhenReady
                }

                override fun prepare() {
                    currentPlayer?.prepare()
                }
            }

        val rebuilder =
            PlaybackErrorRecoveryHandler.MediaSourceRebuilder { _ ->
                val trackId = mediaController.metadata?.id
                if (trackId.isNullOrEmpty() || trackId == TRACK_NOT_FOUND) {
                    Timber.w("[PlaybackRecovery] rebuild: no current trackId; aborting")
                    return@MediaSourceRebuilder null
                }
                val track =
                    withContext(Dispatchers.IO) {
                        trackRepository.getTrackAsync(trackId)
                    }
                if (track == null) {
                    Timber.w("[PlaybackRecovery] rebuild: trackId=$trackId not found in repository")
                    return@MediaSourceRebuilder null
                }
                // getTrackSource() consults MediaItemTrack.streamingUrlCache first (cleared by the
                // refresh routine just above) and otherwise calls serverConnectionResolver.resolve
                // which now points at the freshly-probed connection.
                val freshUrl = track.getTrackSource()
                Timber.i("[PlaybackRecovery] Rebuilding MediaSource for track=$trackId url=$freshUrl")

                // Re-pin the library context on the data source factory so the per-library token
                // cache is refreshed (resolve() may have updated it).
                plexDataSourceFactory.currentLibraryId = track.libraryId

                val dataSourceFactory =
                    androidx.media3.datasource.DefaultDataSource.Factory(applicationContext, plexDataSourceFactory)
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setMediaId(track.id)
                            .setUri(freshUrl)
                            .build(),
                    )
            }

        val handler =
            PlaybackErrorRecoveryHandler(
                connectionRefreshCoordinator = connectionRefreshCoordinator,
                playbackUrlResolver = playbackUrlResolver,
                localBroadcastManager = localBroadcastManager,
                scope = serviceScope,
                playerHandle = playerHandle,
                mediaSourceRebuilder = rebuilder,
            )
        // Wire the network-change re-arm so a Wi-Fi -> cellular handover can't permanently
        // exhaust the recovery budget (leaving the player dead until app restart).
        connectionRefreshCoordinator.onNetworkChangedListener = handler::onNetworkChanged
        return handler
    }

    /**
     * Sends an immediate progress update to Plex when playback pauses.
     * This ensures the Plex "Now Playing" dashboard shows the correct paused state
     * without waiting for the next regular polling interval.
     *
     * Includes debouncing to prevent rapid play/pause toggles from flooding the server.
     */
    private fun sendImmediatePauseUpdate() {
        val currentTime = System.currentTimeMillis()

        // Debounce: skip if we just sent a pause update recently
        if (currentTime - lastPauseUpdateTime < PAUSE_UPDATE_DEBOUNCE_MS) {
            Timber.d("Skipping pause update - debounced (last update ${currentTime - lastPauseUpdateTime}ms ago)")
            return
        }

        lastPauseUpdateTime = currentTime

        val currentTrack = mediaController.metadata?.id
        if (currentTrack == null || currentTrack == TRACK_NOT_FOUND) {
            Timber.d("Skipping pause update - no valid track")
            return
        }

        // Get current position (same logic as ProgressUpdater)
        val absolutePositionFromExtras =
            mediaController.playbackState?.extras
                ?.getLong(EXTRA_ABSOLUTE_TRACK_POSITION) ?: 0L
        val chapterRelativePosition = mediaController.playbackState?.currentPlayBackPosition ?: 0L
        // Read from synchronous state source to avoid race condition with async updates
        val chapter = playbackStateController.state.value.currentChapter ?: EMPTY_CHAPTER

        val playerPosition =
            if (chapter != EMPTY_CHAPTER && chapterRelativePosition >= 0) {
                chapter.startTimeOffset + chapterRelativePosition
            } else {
                absolutePositionFromExtras
            }

        // Force immediate network update (forceNetworkUpdate = true)
        progressUpdater.updateProgress(
            trackId = currentTrack,
            playbackState = PLEX_STATE_PAUSED,
            progress = playerPosition,
            forceNetworkUpdate = true,
        )

        Timber.i("Sent immediate pause update to Plex: position=${playerPosition}ms")
    }

    override fun onChapterChange(chapter: io.github.mjohnson7cd.chronicleauto.data.model.Chapter) {
        Timber.i("Chapter changed to: ${chapter.title}")
        // Dispatch to main thread since we're accessing the player (ExoPlayer requires main thread access)
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
            currentPlayer?.let { player ->
                // [ChapterDebug] Log the chapter change with player context (must be on main thread)
                val playerPosition = player.currentPosition
                val isPlaying = player.isPlaying
                val playbackState = player.playbackState

                Timber.d(
                    "[ChapterDebug] MediaPlayerService.onChapterChange: " +
                        "newChapter='${chapter.title}' (idx=${chapter.index}), " +
                        "chapterRange=[${chapter.startTimeOffset} - ${chapter.endTimeOffset}], " +
                        "playerPosition=$playerPosition, " +
                        "isPlaying=$isPlaying, " +
                        "playbackState=$playbackState",
                )

                // [ChapterDebug] Check if player position is actually in the new chapter range
                val isPositionInChapter = playerPosition in chapter.startTimeOffset..chapter.endTimeOffset
                if (!isPositionInChapter) {
                    Timber.w(
                        "[ChapterDebug] WARNING: playerPosition=$playerPosition is NOT in chapter range [${chapter.startTimeOffset} - ${chapter.endTimeOffset}]! " +
                            "This may indicate the chapter was set before seek completed.",
                    )
                }

                updateSessionMetadataFromPlayer(player)
                updateSessionPlaybackState()
            }
        }
    }
}

interface ServiceController {
    fun stopService()
}

interface ForegroundServiceController {
    fun startForeground(
        nowPlayingNotification: Int,
        notification: Notification,
    )

    fun stopForegroundService(removeNotification: Boolean)
}
