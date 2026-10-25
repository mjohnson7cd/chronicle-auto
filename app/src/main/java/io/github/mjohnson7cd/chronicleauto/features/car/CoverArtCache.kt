package io.github.mjohnson7cd.chronicleauto.features.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mjohnson7cd.chronicleauto.data.sources.plex.PlexConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches Plex album art locally for offline/AAOS use.
 * AAOS media center can't load authenticated Plex URLs, so we download covers
 * during sync and serve local file:// URIs.
 */
object CoverArtCache {
    private const val TAG = "CoverArtCache"
    private const val MAX_SIZE_PX = 1024  // Downscale to 1024x1024 max for AAOS displays
    
    fun getCacheDir(context: Context): File {
        return File(context.cacheDir, "cover_art").apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * Returns a content:// URI (served by [CoverArtProvider]) for the cached cover, or null.
     * Not file://: the car media center runs in another process and can't open our private files.
     */
    fun getCachedCoverUri(context: Context, bookId: String): android.net.Uri? {
        val safeId = bookId.replace(":", "_")
        val file = File(getCacheDir(context), "$safeId.jpg")
        return if (file.exists()) {
            android.net.Uri.parse("content://${context.packageName}.coverart/${file.name}")
        } else {
            null
        }
    }
    
    // Single-entry in-memory cache so repeated metadata updates don't re-decode from disk
    @Volatile private var lastBitmapKey: String? = null
    @Volatile private var lastBitmap: Bitmap? = null

    /**
     * Loads the cached cover as a small Bitmap suitable for MediaMetadata (METADATA_KEY_ALBUM_ART).
     * Other processes (media center, instrument cluster) can't read our file:// URIs, so the
     * bitmap is what actually reaches them. Kept small to stay well under the Binder limit.
     */
    fun loadCachedBitmap(context: Context, bookId: String, maxPx: Int = 320): Bitmap? {
        if (bookId.isBlank()) return null
        if (lastBitmapKey == bookId) lastBitmap?.let { if (!it.isRecycled) return it }
        val file = File(getCacheDir(context), "${bookId.replace(":", "_")}.jpg")
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, maxPx, maxPx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.also {
                lastBitmapKey = bookId
                lastBitmap = it
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached cover for $bookId", e)
            null
        }
    }

    /**
     * Downloads cover from Plex thumb URL, downscales to 512x512, caches locally.
     * Safe to call multiple times - skips if already cached.
     */
    suspend fun downloadAndCache(
        context: Context,
        bookId: String,
        thumbPath: String,
        plexConfig: PlexConfig
    ): Boolean = withContext(Dispatchers.IO) {
        val safeId = bookId.replace(":", "_")
        val cacheFile = File(getCacheDir(context), "$safeId.jpg")
        
        // Skip if already cached
        if (cacheFile.exists()) {
            Log.d(TAG, "Cover already cached for $bookId")
            return@withContext true
        }
        
        try {
            val thumbUrl = plexConfig.makeThumbUri(thumbPath).toString()
            Log.d(TAG, "Downloading cover for $bookId from $thumbUrl")
            
            val connection = URL(thumbUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            
            connection.connect()
            
            if (connection.responseCode != 200) {
                Log.w(TAG, "Failed to download cover: HTTP ${connection.responseCode}")
                return@withContext false
            }
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inPreferredConfig = Bitmap.Config.ARGB_8888  // Higher quality color
            }
            connection.inputStream.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            // Calculate sample size to downscale
            options.inSampleSize = calculateInSampleSize(options, MAX_SIZE_PX, MAX_SIZE_PX)
            options.inJustDecodeBounds = false
            
            // Re-open connection and decode scaled bitmap
            val connection2 = URL(thumbUrl).openConnection() as HttpURLConnection
            connection2.requestMethod = "GET"
            connection2.connectTimeout = 10_000
            connection2.readTimeout = 15_000
            
            val bitmap = connection2.inputStream.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode bitmap for $bookId")
                return@withContext false
            }
            
            // Save to cache with higher quality
            FileOutputStream(cacheFile).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            bitmap.recycle()
            
            Log.i(TAG, "Cached cover for $bookId (${cacheFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading cover for $bookId", e)
            false
        }
    }
    
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
}
