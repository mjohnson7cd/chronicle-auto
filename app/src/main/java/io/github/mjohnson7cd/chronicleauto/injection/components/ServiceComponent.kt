package io.github.mjohnson7cd.chronicleauto.injection.components

import android.app.PendingIntent
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dagger.Component
import kotlinx.coroutines.CoroutineScope
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexMediaRepository
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexMediaSource
import io.github.mjohnson7cd.chronicleauto.features.player.*
import io.github.mjohnson7cd.chronicleauto.injection.modules.ServiceModule
import io.github.mjohnson7cd.chronicleauto.injection.scopes.ServiceScope
import io.github.mjohnson7cd.chronicleauto.util.PackageValidator

@OptIn(kotlin.time.ExperimentalTime::class)
@ServiceScope
@Component(dependencies = [AppComponent::class], modules = [ServiceModule::class])
interface ServiceComponent {
    fun progressUpdater(): ProgressUpdater

    fun exoPlayer(): ExoPlayer

    fun mediaSession(): MediaSessionCompat

    fun pendingIntent(): PendingIntent

    fun sleepTimer(): SleepTimer

    fun localBroadcastManager(): LocalBroadcastManager

    fun notificationManager(): NotificationManagerCompat

    fun notificationBuilder(): NotificationBuilder

    fun becomingNoisyReceiver(): BecomingNoisyReceiver

    fun mediaSessionCallback(): AudiobookMediaSessionCallback

    fun mediaSource(): PlexMediaRepository

    fun serviceScope(): CoroutineScope

    fun serviceController(): ServiceController

    fun plexDataSourceFactory(): HttpDataSource.Factory

    fun packageValidator(): PackageValidator

    fun foregroundServiceController(): ForegroundServiceController

    fun trackListManager(): TrackListStateManager

    fun mediaController(): MediaControllerCompat

    fun plexMediaSource(): PlexMediaSource

    fun inject(mediaPlayerService: MediaPlayerService)
}
