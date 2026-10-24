package io.github.mjohnson7cd.chronicleauto.injection.modules

import android.content.ComponentName
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import io.github.mjohnson7cd.chronicleauto.data.local.BookRepository
import io.github.mjohnson7cd.chronicleauto.data.local.LibraryRepository
import io.github.mjohnson7cd.chronicleauto.features.account.AccountListViewModel
import io.github.mjohnson7cd.chronicleauto.features.account.AccountManager
import io.github.mjohnson7cd.chronicleauto.features.account.ActiveLibraryProvider
import io.github.mjohnson7cd.chronicleauto.features.player.MediaPlayerService
import io.github.mjohnson7cd.chronicleauto.features.player.MediaServiceConnection
import io.github.mjohnson7cd.chronicleauto.features.player.ProgressUpdater
import io.github.mjohnson7cd.chronicleauto.features.player.SimpleProgressUpdater
import io.github.mjohnson7cd.chronicleauto.injection.scopes.ActivityScope
import io.github.mjohnson7cd.chronicleauto.navigation.Navigator
import io.github.mjohnson7cd.chronicleauto.util.ServiceUtils
import timber.log.Timber

@Module
class ActivityModule(private val activity: AppCompatActivity) {
    @Provides
    @ActivityScope
    fun activity(): AppCompatActivity = activity

    @Provides
    @ActivityScope
    fun coroutineScope(): CoroutineScope = activity.lifecycleScope

    @Provides
    @ActivityScope
    fun fragmentManager(): FragmentManager = activity.supportFragmentManager

    @Provides
    @ActivityScope
    fun provideProgressUpdater(progressUpdater: SimpleProgressUpdater): ProgressUpdater = progressUpdater

    @Provides
    @ActivityScope
    fun provideBroadcastManager(): LocalBroadcastManager =
        LocalBroadcastManager.getInstance(
            activity,
        )

    @Provides
    @ActivityScope
    fun mediaServiceConnection(): MediaServiceConnection {
        val conn =
            MediaServiceConnection(
                activity.applicationContext,
                ComponentName(activity.applicationContext, MediaPlayerService::class.java),
            )
        val doesServiceExist =
            ServiceUtils.isServiceRunning(
                activity.applicationContext,
                MediaPlayerService::class.java,
            )
        Timber.i("Connecting to existing service? $doesServiceExist")
        if (doesServiceExist) {
            conn.connect()
        }
        return conn
    }

    @Provides
    @ActivityScope
    fun provideAccountListViewModelFactory(
        accountManager: AccountManager,
        activeLibraryProvider: ActiveLibraryProvider,
        navigator: Navigator,
        bookRepository: BookRepository,
        libraryRepository: LibraryRepository,
    ): AccountListViewModel.Factory {
        return AccountListViewModel.Factory(accountManager, activeLibraryProvider, navigator, bookRepository, libraryRepository)
    }
}
