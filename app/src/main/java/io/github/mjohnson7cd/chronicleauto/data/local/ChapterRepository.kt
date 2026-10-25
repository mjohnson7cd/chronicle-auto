package io.github.mjohnson7cd.chronicleauto.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mjohnson7cd.chronicleauto.BuildConfig
import io.github.mjohnson7cd.chronicleauto.data.model.Chapter
import io.github.mjohnson7cd.chronicleauto.data.model.MediaItemTrack
import io.github.mjohnson7cd.chronicleauto.data.model.asChapter
import io.github.mjohnson7cd.chronicleauto.data.model.filterTransitionMarkers
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexPrefsRepo
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.ScopedPlexServiceFactory
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.ServerConnectionResolver
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.model.toChapter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A repository abstracting all [Chapter]s
 *
 * TODO: this is a work in progress- usage is not recommended. Eventually we will silently move
 *       all chapters from being an attribute of [Audiobook] into this repo
 *
 * */
interface IChapterRepository {
    /**
     * Loads m4b chapter data and any other audiobook details which are not loaded in by default
     * by [BookRepository] or [TrackRepository] and saves results to the DB
     */
    suspend fun loadChapterData(
        bookId: String,
        isAudiobookCached: Boolean,
        tracks: List<MediaItemTrack>,
    )
}

@Singleton
class ChapterRepository
    @Inject
    constructor(
        private val chapterDao: ChapterDao,
        private val prefsRepo: PrefsRepo,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val scopedPlexServiceFactory: ScopedPlexServiceFactory,
        private val serverConnectionResolver: ServerConnectionResolver,
    ) : IChapterRepository {
        override suspend fun loadChapterData(
            bookId: String,
            isAudiobookCached: Boolean,
            tracks: List<MediaItemTrack>,
        ) = withContext(Dispatchers.IO) {
            Timber.i("Loading chapter data for book $bookId with tracks: $tracks")
            val chapters: List<Chapter> =
                try {
                    tracks.flatMap { track ->
                        // Extract numeric ID for Plex API call
                        val numericTrackId =
                            track.id.removePrefix("plex:").toIntOrNull()
                                ?: return@flatMap emptyList()

                        // Get libraryId from the track
                        val libraryId = track.libraryId
                        if (libraryId.isEmpty()) {
                            Timber.w("Track ${track.id} has no libraryId, skipping chapter load")
                            return@flatMap emptyList()
                        }

                        // Resolve the server connection for this library
                        val connection = serverConnectionResolver.resolve(libraryId)

                        // Validate connection has required fields
                        if (connection.serverUrl == null || connection.authToken == null) {
                            Timber.e("No valid server connection for library $libraryId (track ${track.id})")
                            return@flatMap emptyList()
                        }

                        // Get/create a scoped PlexMediaService for this server
                        val scopedService = scopedPlexServiceFactory.getOrCreateService(connection)

                        val networkChapters =
                            scopedService.retrieveChapterInfo(numericTrackId)
                                .plexMediaContainer.metadata.firstOrNull()?.plexChapters
                        if (BuildConfig.DEBUG) {
                            // prevent networkChapters from toString()ing and being slow even if timber
                            // tree isn't attached in the release build
                            Timber.i("Network chapters: $networkChapters")
                        }
                        // If no chapters for this track, make a chapter from the current track
                        networkChapters?.map { plexChapter ->
                            plexChapter.toChapter(
                                trackId = track.id,
                                trackDiscNumber = track.discNumber,
                                downloaded = isAudiobookCached,
                                bookId = bookId,
                            )
                        }?.filterTransitionMarkers().takeIf { !it.isNullOrEmpty() }
                            ?: listOf(track.asChapter(0L, bookId))
                    }.sorted()
                } catch (t: Throwable) {
                    Timber.e("Failed to load chapters: $t")
                    emptyList()
                }
            // Delete existing chapters for this book to prevent duplicates
            // (uid auto-generation means OnConflictStrategy.REPLACE won't detect duplicates)
            chapterDao.deleteByBookId(bookId)
            chapterDao.insertAll(chapters)
        }
    }
