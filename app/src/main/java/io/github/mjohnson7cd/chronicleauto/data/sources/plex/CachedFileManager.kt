package io.github.mjohnson7cd.chronicleauto.data.sources.plex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2core.DownloadBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mjohnson7cd.chronicleauto.BuildConfig
import io.github.mjohnson7cd.chronicleauto.application.Injector
import io.github.mjohnson7cd.chronicleauto.data.local.IBookRepository
import io.github.mjohnson7cd.chronicleauto.data.local.ITrackRepository
import io.github.mjohnson7cd.chronicleauto.data.local.PrefsRepo
import io.github.mjohnson7cd.chronicleauto.data.model.Audiobook
import io.github.mjohnson7cd.chronicleauto.data.model.MediaItemTrack
import io.github.mjohnson7cd.chronicleauto.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mjohnson7cd.chronicleauto.features.download.DownloadNotificationWorker
import io.github.mjohnson7cd.chronicleauto.features.download.FetchGroupStartFinishListener
import io.github.mjohnson7cd.chronicleauto.util.ScopedCoroutineManager
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import javax.inject.Inject

interface ICachedFileManager {
    enum class CacheStatus { CACHED, CACHING, NOT_CACHED }

    val activeBookDownloads: LiveData<Set<String>>

    /**
     * Download progress (0-100) per [Audiobook.id] for every book Fetch is working on. A book
     * stays in the map at 100 until its cached flags have been written to the DB, so observers
     * can go straight from "downloading" to "downloaded" without a not-cached gap in between.
     */
    val bookDownloadProgress: LiveData<Map<String, Int>>

    fun cancelCaching()

    fun cancelGroup(id: String)

    /** Outcome of [downloadTracks] once the requests have been handed to Fetch (or not). */
    sealed class EnqueueResult {
        /** [trackCount] track downloads were queued. */
        data class Started(val trackCount: Int) : EnqueueResult()

        /** Every track file is already on disk; nothing was queued. */
        object AlreadyDownloaded : EnqueueResult()

        /** The book has no tracks in the local DB, so there is nothing to download. */
        object NoTracks : EnqueueResult()

        data class Failed(val reason: String) : EnqueueResult()
    }

    /**
     * Queues downloads for every not-yet-cached track of [bookId]. [onResult] is invoked on the
     * main thread once the outcome is known, so callers without a UI (e.g. the car media
     * session) can give the user feedback.
     */
    fun downloadTracks(
        bookId: String,
        bookTitle: String,
        onResult: (EnqueueResult) -> Unit = {},
    )

    suspend fun uncacheAllInLibrary(): Int

    /** What [deleteAllDownloads] removed. */
    data class DeleteAllResult(val fileCount: Int, val bytesFreed: Long)

    /**
     * Total size in bytes of every downloaded track file in all download locations, including
     * files from other libraries and partial downloads (i.e. what [deleteAllDownloads] frees).
     */
    suspend fun getDownloadedBytes(): Long

    /**
     * Cancels every running download, deletes every downloaded track file in all download
     * locations (not just the current library's) and clears the cached flags on all tracks,
     * books and chapters.
     */
    suspend fun deleteAllDownloads(): DeleteAllResult

    suspend fun deleteCachedBook(bookId: String)

    suspend fun hasUserCachedTracks(): Boolean

    suspend fun refreshTrackDownloadedStatus()

    /**
     * Reconciles DB cached flags against the filesystem for a single book.
     * Cheaper than [refreshTrackDownloadedStatus] — call it when the user opens a book's
     * details page so a stale flag (file deleted after the startup reconcile) is cleared
     * immediately instead of leaving a phantom "downloaded" book that can't be re-downloaded.
     */
    suspend fun refreshDownloadedStatusForBook(bookId: String)
}

interface SimpleSet<T> {
    fun add(elem: T): Boolean

    fun remove(elem: T): Boolean

    operator fun contains(elem: T): Boolean

    val size: Int
}

class CachedFileManager
    @Inject
    constructor(
        private val fetch: Fetch,
        private val prefsRepo: PrefsRepo,
        private val trackRepository: ITrackRepository,
        private val bookRepository: IBookRepository,
        private val plexConfig: PlexConfig,
        private val applicationContext: Context,
    ) : ICachedFileManager {
        // Lazy so construction doesn't touch the Injector singleton (which requires the
        // Application instance to exist). Only uncacheAllInLibrary() reads this; deferring
        // keeps every other path (e.g. refreshTrackDownloadedStatus) constructible in unit tests.
        private val externalFileDirs by lazy { Injector.get().externalDeviceDirs() }

        private val scopeManager = ScopedCoroutineManager()

        private val downloadListener =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        DownloadNotificationWorker.ACTION_CANCEL_ALL_DOWNLOADS ->
                            Injector.get().fetch()
                                .cancelAll()
                        DownloadNotificationWorker.ACTION_CANCEL_BOOK_DOWNLOAD -> {
                            val bookId =
                                intent.getStringExtra(DownloadNotificationWorker.KEY_BOOK_ID)
                            if (bookId != null) {
                                Timber.i("Cancelling book: $bookId")
                                // Extract numeric ID for Fetch group
                                val numericId = bookId.removePrefix("plex:").toIntOrNull() ?: return
                                Injector.get().fetch().cancelGroup(numericId)
                            }
                        }
                    }
                }
            }

        override fun cancelGroup(id: String) {
            // Extract numeric ID for Fetch group
            val numericId = id.removePrefix("plex:").toIntOrNull() ?: return
            fetch.cancelGroup(numericId)
        }

        override fun cancelCaching() {
            fetch.cancelAll()
        }

        override suspend fun hasUserCachedTracks(): Boolean {
            return withContext(Dispatchers.IO) {
                trackRepository.getCachedTracks().isNotEmpty()
            }
        }

        override fun downloadTracks(
            bookId: String,
            bookTitle: String,
            onResult: (ICachedFileManager.EnqueueResult) -> Unit,
        ) {
            // Add downloads to Fetch
            scopeManager.launchSafe(
                tag = "download-book-$bookId",
                onError = { error ->
                    Timber.e("Failed to download book $bookId: ${error.message}")
                    onResult(ICachedFileManager.EnqueueResult.Failed(error.message ?: error.toString()))
                },
            ) {
                if (trackRepository.getTracksForAudiobookAsync(bookId).isEmpty()) {
                    Timber.w("No tracks in DB for book $bookId, nothing to download")
                    onResult(ICachedFileManager.EnqueueResult.NoTracks)
                    return@launchSafe
                }
                val requests = makeRequests(bookId, bookTitle)
                if (requests.isEmpty()) {
                    // All files are on disk but the book wasn't flagged cached (e.g. a finish
                    // listener that never ran). Fix the flags instead of enqueueing nothing,
                    // which would never fire onFinished.
                    Timber.i("All tracks for $bookId already on disk, reconciling cache flags")
                    refreshDownloadedStatusForBook(bookId)
                    onResult(ICachedFileManager.EnqueueResult.AlreadyDownloaded)
                    return@launchSafe
                }
                fetch.enqueue(requests) { results ->
                    val errors =
                        results.mapNotNull { (_, error) ->
                            if (error == Error.NONE) null else error
                        }
                    if (BuildConfig.DEBUG && errors.isNotEmpty()) {
                        Toast.makeText(
                            applicationContext,
                            "Error enqueuing download: $errors",
                            LENGTH_SHORT,
                        ).show()
                    }
                    if (errors.isEmpty()) {
                        DownloadNotificationWorker.start()
                        onResult(ICachedFileManager.EnqueueResult.Started(requests.size))
                    } else {
                        Timber.e("Error enqueuing download for $bookId: $errors")
                        onResult(ICachedFileManager.EnqueueResult.Failed(errors.joinToString()))
                    }
                }
            }
        }

        /**
         * Creates [Request]s for all missing files associated with [bookId]
         *
         * @return the number of files to be downloaded
         */
        private suspend fun makeRequests(
            bookId: String,
            bookTitle: String,
        ): List<Request> {
            // Gets all tracks for album id
            val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
            // Extract numeric ID for Fetch group
            val numericBookId = bookId.removePrefix("plex:").toIntOrNull() ?: return emptyList()

            val cachedFilesDir = prefsRepo.cachedMediaDir
            Timber.i("Caching tracks to: ${cachedFilesDir.path}")
            Timber.i("Tracks to cache: $tracks")

            val requests =
                tracks.mapNotNull { track ->
                    // File exists but is not marked as cached in the database- more likely than not
                    // this means that it has failed to fully download
                    val destFile = File(cachedFilesDir, track.getCachedFileName())
                    val trackCached = track.cached
                    val destFileExists = destFile.exists()

                    // No need to make a new request, the file is already downloaded
                    if (trackCached && destFileExists) {
                        return@mapNotNull null
                    }

                    // File exists but is not marked as cached in the database- probably means a download
                    // has failed. Delete it and try again
                    if (!trackCached && destFileExists) {
                        val deleted = destFile.delete()
                        if (!deleted) {
                            Timber.e("Failed to delete previously cached file. Download will fail!")
                        } else {
                            Timber.e("Succeeding in deleting cached file")
                        }
                    }

                    return@mapNotNull makeTrackDownloadRequest(
                        track,
                        numericBookId,
                        bookTitle,
                        "file://${destFile.absolutePath}",
                    )
                }
            Timber.i("Made download requests: ${requests.map { it.file }}")
            return requests
        }

        /** Create a [Request] for a track download with the proper metadata */
        private fun makeTrackDownloadRequest(
            track: MediaItemTrack,
            bookId: Int,
            bookTitle: String,
            dest: String,
        ) = plexConfig.makeDownloadRequest(track.media, bookId, bookTitle, dest)

        override suspend fun uncacheAllInLibrary(): Int {
            Timber.i("Removing books from library")
            val cachedTrackNamesForLibrary =
                trackRepository.getCachedTracks()
                    .map { it.getCachedFileName() }
            val allCachedTrackFiles =
                externalFileDirs.flatMap { dir ->
                    dir.listFiles(
                        FileFilter {
                            MediaItemTrack.cachedFilePattern.matches(it.name)
                        },
                    )?.toList() ?: emptyList()
                }
            allCachedTrackFiles.forEach {
                Timber.i("Cached for library: $cachedTrackNamesForLibrary")
                if (cachedTrackNamesForLibrary.contains(it.name)) {
                    Timber.i("Deleting file: ${it.name}")
                    it.delete()
                } else {
                    Timber.i("Not deleting file: ${it.name}")
                }
            }
            trackRepository.uncacheAll()
            bookRepository.uncacheAll()
            return allCachedTrackFiles.size
        }

        /** Every downloaded/partial track file in the active and all other download dirs. */
        private fun allDownloadedTrackFiles(): List<File> =
            (listOf(prefsRepo.cachedMediaDir) + externalFileDirs)
                .distinctBy { it.absolutePath }
                .flatMap { dir ->
                    dir.listFiles(
                        FileFilter { it.isFile && MediaItemTrack.cachedFilePattern.matches(it.name) },
                    )?.toList() ?: emptyList()
                }

        override suspend fun getDownloadedBytes(): Long =
            withContext(Dispatchers.IO) {
                allDownloadedTrackFiles().sumOf { it.length() }
            }

        override suspend fun deleteAllDownloads(): ICachedFileManager.DeleteAllResult {
            Timber.i("Deleting all downloaded files")
            // Stop Fetch first so nothing is re-written while we delete. deleteAll also removes
            // Fetch's records, so a later download of the same book starts from scratch.
            withContext(Dispatchers.Main) {
                fetch.cancelAll()
                fetch.deleteAll()
            }
            return withContext(Dispatchers.IO) {
                var count = 0
                var bytes = 0L
                allDownloadedTrackFiles().forEach { file ->
                    val size = file.length()
                    if (file.delete()) {
                        count++
                        bytes += size
                    } else {
                        Timber.e("Failed to delete downloaded file: ${file.path}")
                    }
                }
                // Chapter "downloaded" flags live inside each book row, so reset them per book
                // before the bulk flag reset.
                bookRepository.getCachedAudiobooksAsync().forEach { book ->
                    bookRepository.update(
                        book.copy(
                            isCached = false,
                            chapters = book.chapters.map { it.copy(downloaded = false) },
                        ),
                    )
                }
                trackRepository.uncacheAll()
                bookRepository.uncacheAll()
                Timber.i("Deleted $count downloaded files, freed $bytes bytes")
                ICachedFileManager.DeleteAllResult(count, bytes)
            }
        }

        /**
         * Deletes cached tracks from the filesystem corresponding to [tracks]. Assume all tracks have
         * the correct [MediaItemTrack.parentKey] set
         *
         * Return [Result.success] on successful deletion of all files or [Result.failure] if the
         * deletion of any files fail
         */
        override suspend fun deleteCachedBook(bookId: String) {
            Timber.i("Deleting downloaded book: $bookId")
            // Extract numeric ID for Fetch group
            val numericBookId = bookId.removePrefix("plex:").toIntOrNull()
            if (numericBookId != null) {
                fetch.deleteGroup(numericBookId)
            }
            scopeManager.launchSafe(
                tag = "delete-cache-$bookId",
                onError = { error ->
                    Timber.e("Failed to delete cached book $bookId: ${error.message}")
                },
            ) {
                withContext(Dispatchers.IO) {
                    val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
                    tracks.forEach {
                        val trackFile = File(prefsRepo.cachedMediaDir, it.getCachedFileName())
                        trackFile.delete()
                        // now count it as deleted
                        trackRepository.updateCachedStatus(it.id, false)
                    }
                    bookRepository.updateCachedStatus(bookId, false)
                }
            }
        }

        /** Set of [Audiobook.id] representing all books being actively downloaded */
        private var activeDownloads =
            object : SimpleSet<String> {
                private val internalSet = mutableSetOf<String>()
                override val size: Int
                    get() = internalSet.size

                override fun add(elem: String): Boolean {
                    _activeBookDownloads.postValue(internalSet)
                    return internalSet.add(elem)
                }

                override fun remove(elem: String): Boolean {
                    _activeBookDownloads.postValue(internalSet)
                    return internalSet.remove(elem)
                }

                override fun toString() = internalSet.toString()

                override operator fun contains(elem: String) = internalSet.contains(elem)
            }

        private val _activeBookDownloads = MutableLiveData<Set<String>>()
        override val activeBookDownloads: LiveData<Set<String>>
            get() = _activeBookDownloads

        // Written from Fetch's callback thread and the DB-update coroutine.
        private val downloadProgress = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val _bookDownloadProgress = MutableLiveData<Map<String, Int>>(emptyMap())
        override val bookDownloadProgress: LiveData<Map<String, Int>>
            get() = _bookDownloadProgress

        private fun setProgress(
            bookId: String,
            percent: Int?,
        ) {
            val changed =
                if (percent == null) {
                    downloadProgress.remove(bookId) != null
                } else {
                    downloadProgress.put(bookId, percent) != percent
                }
            if (changed) _bookDownloadProgress.postValue(HashMap(downloadProgress))
        }

        init {
            applicationContext.registerReceiver(
                downloadListener,
                IntentFilter().apply {
                    addAction(DownloadNotificationWorker.ACTION_CANCEL_BOOK_DOWNLOAD)
                    addAction(DownloadNotificationWorker.ACTION_CANCEL_ALL_DOWNLOADS)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )

            // singleton so we can observe downloads always
            fetch.addListener(
                object : FetchGroupStartFinishListener() {
                    override fun onStarted(
                        groupId: Int,
                        fetchGroup: FetchGroup,
                    ) {
                        // Convert numeric group ID to String format
                        val bookId = "plex:$groupId"
                        if (bookId !in activeDownloads) {
                            Timber.i("Starting downloading book with id: $bookId")
                        }
                        activeDownloads.add(bookId)
                        if (!downloadProgress.containsKey(bookId)) {
                            setProgress(bookId, groupProgressPercent(fetchGroup))
                        }
                    }

                    override fun onProgress(
                        groupId: Int,
                        download: Download,
                        etaInMilliSeconds: Long,
                        downloadedBytesPerSecond: Long,
                        fetchGroup: FetchGroup,
                    ) {
                        setProgress("plex:$groupId", groupProgressPercent(fetchGroup))
                        super.onProgress(groupId, download, etaInMilliSeconds, downloadedBytesPerSecond, fetchGroup)
                    }

                    override fun onStarted(
                        download: Download,
                        downloadBlocks: List<DownloadBlock>,
                        totalBlocks: Int,
                    ) {
                        Timber.i("Starting download!")
                        DownloadNotificationWorker.start()
                        super.onResumed(download)
                    }

                    override fun onFinished(
                        groupId: Int,
                        fetchGroup: FetchGroup,
                    ) {
                        // Convert numeric group ID to String format
                        val bookId = "plex:$groupId"
                        // Handle the various downloaded statuses
                        Timber.i(
                            "Group change for book with id $bookId: ${fetchGroup.downloads.size} tracks downloaded",
                        )
                        val downloads = fetchGroup.downloads
                        Timber.i(downloads.joinToString { it.status.toString() })
                        activeDownloads.remove(bookId)
                        val downloadSuccess =
                            downloads.all { it.error == Error.NONE } && downloads.isNotEmpty()
                        if (!downloadSuccess) setProgress(bookId, null)
                        if (downloadSuccess) {
                            // Hold at 100% until the DB says the book is cached.
                            setProgress(bookId, 100)
                            scopeManager.launchSafe(
                                tag = "update-cache-status-$bookId",
                                onError = { error ->
                                    Timber.e("Failed to update cache status for book $bookId: ${error.message}")
                                    setProgress(bookId, null)
                                },
                            ) {
                                withContext(Dispatchers.IO) {
                                    Timber.i("Book download success for ($bookId)")
                                    val tracksForBook =
                                        trackRepository.getAllTracksAsync().filter { it.parentKey == bookId }
                                    tracksForBook.forEach { track ->
                                        trackRepository.updateCachedStatus(track.id, true)
                                    }
                                    Timber.i("Marked ${tracksForBook.size} tracks as cached for ($bookId)")
                                    bookRepository.updateCachedStatus(bookId, true)
                                }
                                setProgress(bookId, null)
                            }
                        }
                    }
                },
            )
        }

        /**
         * Book-level progress for [fetchGroup]: the mean of its tracks' percentages. Track sizes
         * are often unknown (-1) until each track starts, so a byte-weighted figure would jump
         * backwards as later tracks report their size; the mean only ever goes up.
         */
        private fun groupProgressPercent(fetchGroup: FetchGroup): Int {
            val downloads = fetchGroup.downloads
            if (downloads.isEmpty()) return 0
            val sum =
                downloads.sumOf {
                    if (it.status == Status.COMPLETED) 100 else it.progress.coerceIn(0, 100)
                }
            return (sum / downloads.size).coerceIn(0, 100)
        }

        /**
         * Update [trackRepository] and [bookRepository] to reflect downloaded files
         *
         * Deletes files for [Audiobook]s no longer in the database and updates [Audiobook.isCached]
         * for downloaded files which no longer exist on the file system
         */
        override suspend fun refreshTrackDownloadedStatus() {
            val idToFileMap = HashMap<String, File>()
            val trackIdsFoundOnDisk =
                prefsRepo.cachedMediaDir.listFiles(
                    FileFilter {
                        MediaItemTrack.cachedFilePattern.matches(it.name)
                    },
                )?.map {
                    val id = MediaItemTrack.getTrackIdFromFileName(it.name)
                    idToFileMap[id] = it
                    id
                } ?: emptyList()

            val reportedCachedKeys = trackRepository.getCachedTracks().map { it.id }

            val alteredTracks = mutableListOf<String>()

            // Exists in DB but not in cache- remove from DB!
            reportedCachedKeys.filter {
                !trackIdsFoundOnDisk.contains(it)
            }.forEach {
                Timber.i("Removed track: $it")
                alteredTracks.add(it)
                trackRepository.updateCachedStatus(it, false)
            }

            // Exists in cache but not in DB- add to DB!
            trackIdsFoundOnDisk.filter {
                !reportedCachedKeys.contains(it)
            }.forEach {
                val rowsUpdated = trackRepository.updateCachedStatus(it, true)
                if (rowsUpdated == 0) {
                    // TODO: this will be relevant when multiple sources is implemented, but for now
                    //       we just have to trust as we allow users to retain downloads across libraries
//                // File has been orphaned- no longer exists in DB, remove it from file system!
//                idToFileMap[it]?.delete()
                } else {
                    alteredTracks.add(it)
                }
            }

            // Update cached status for the books containing any added/removed tracks
            alteredTracks.map {
                trackRepository.getBookIdForTrack(it)
            }.distinct().forEach { bookId: String ->
                Timber.i("Book: $bookId")
                if (bookId == NO_AUDIOBOOK_FOUND_ID) {
                    return@forEach
                }
                val bookTrackCacheCount =
                    trackRepository.getCachedTrackCountForBookAsync(bookId)
                val bookTrackCount = trackRepository.getTrackCountForBookAsync(bookId)
                val isBookCached = bookTrackCacheCount == bookTrackCount && bookTrackCount > 0
                val book = bookRepository.getAudiobookAsync(bookId)
                if (book != null) {
                    bookRepository.update(
                        book.copy(
                            isCached = isBookCached,
                            chapters = book.chapters.map { it.copy(downloaded = isBookCached) },
                        ),
                    )
                }
            }
        }

        /**
         * Reconciles the cached flags of one book's tracks (and the book itself) against the
         * files actually present in the cache directory.
         *
         * Both stale directions are fixed: a track flagged cached whose file is gone is marked
         * not-cached (the phantom-download dead-end), and a track whose file is present but
         * whose flag was lost (e.g. a download-finish listener that never updated the DB) is
         * marked cached so the book reappears in the offline list.
         */
        override suspend fun refreshDownloadedStatusForBook(bookId: String) {
            withContext(Dispatchers.IO) {
                val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
                if (tracks.isEmpty()) return@withContext

                val cacheDir = prefsRepo.cachedMediaDir
                tracks.forEach { track ->
                    val fileExists = File(cacheDir, track.getCachedFileName()).exists()
                    if (track.cached != fileExists) {
                        Timber.i(
                            "Reconcile book $bookId: track ${track.id} cached=${track.cached} " +
                                "but fileExists=$fileExists — correcting",
                        )
                        trackRepository.updateCachedStatus(track.id, fileExists)
                    }
                }

                // Recompute the book-level flag: cached only when every track's file exists.
                val allCached =
                    tracks.all { File(cacheDir, it.getCachedFileName()).exists() }
                val book = bookRepository.getAudiobookAsync(bookId)
                if (book != null && book.isCached != allCached) {
                    Timber.i("Reconcile book $bookId: isCached ${book.isCached} -> $allCached")
                    bookRepository.update(
                        book.copy(
                            isCached = allCached,
                            chapters = book.chapters.map { it.copy(downloaded = allCached) },
                        ),
                    )
                }
            }
        }

        /**
         * Cancels all pending download operations.
         * Call this when the manager is no longer needed to prevent leaks.
         */
        fun cancelAllDownloads() {
            scopeManager.cancelAll()
        }

        /**
         * Cancels a specific download by book ID.
         */
        fun cancelDownload(bookId: String) {
            scopeManager.cancel("download-book-$bookId")
        }

        /**
         * Returns true if a download is in progress for the given book.
         */
        fun isDownloading(bookId: String): Boolean {
            return scopeManager.isActive("download-book-$bookId")
        }

        /**
         * Migrates cached files from being named after the [MediaItemTrack.id] to being named after
         * the persistent part in [MediaItemTrack.media]
         */
        private fun migrateCachedFiles() {
        }
    }
