package io.github.mjohnson7cd.chronicleauto.features.car

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only, exported provider serving cached cover JPEGs to the car media center, which runs in
 * another process and cannot open our private file:// paths. Covers are not sensitive; only bare
 * "<name>.jpg" files inside cache/cover_art are served.
 */
class CoverArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment
        // Trust boundary: reject traversal / anything that isn't a plain cached jpg.
        if (mode != "r" || name == null || !SAFE_NAME.matches(name)) throw FileNotFoundException(uri.toString())
        val file = File(CoverArtCache.getCacheDir(context!!), name)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]+\\.jpg")
    }
}
