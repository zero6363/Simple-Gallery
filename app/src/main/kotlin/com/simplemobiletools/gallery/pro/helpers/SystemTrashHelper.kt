package com.simplemobiletools.gallery.pro.helpers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import com.simplemobiletools.commons.extensions.getFilenameFromPath
import com.simplemobiletools.commons.helpers.isRPlus
import java.io.File

/**
 * Android 11+ offers a system level recycle bin through MediaStore: recycled items stay on the
 * storage, get flagged as trashed in the media store, disappear from all apps and get purged by
 * the system after ~30 days. On disk the files are renamed like ".trashed-<id>-original.jpg".
 */
object SystemTrashHelper {
    private const val TAG = "SystemTrashHelper"

    data class TrashedItem(val diskPath: String, val displayName: String, val uri: Uri)

    // hidden until API 35, the key and values are stable since Android 11; MATCH_ONLY (3) returns
    // just the trashed rows, MATCH_INCLUDE (1) would return everything and poison the cache
    private const val QUERY_ARG_MATCH_TRASHED = "android:query-arg-match-trashed"
    private const val MATCH_ONLY = 3

    private val TRASHED_ID_PREFIX = Regex("^\\d+-")

    @Volatile
    private var cachedItems: Map<String, TrashedItem>? = null

    fun getTrashedItems(context: Context): Map<String, TrashedItem> {
        cachedItems?.let { return it }

        val items = HashMap<String, TrashedItem>()
        if (isRPlus()) {
            val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val imagesUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videosUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(MediaStore.Files.FileColumns.DATA, BaseColumns._ID)
            val selection = "${MediaStore.MediaColumns.IS_TRASHED} = 1"
            val queryArgs = Bundle().apply { putInt(QUERY_ARG_MATCH_TRASHED, MATCH_ONLY) }

            // ROMs differ in which query form they honour, run them all and merge the results
            tryQueryInto(items, context, "files+matchArg", filesUri, projection, queryArgs, null)
            tryQueryInto(items, context, "images+matchArg", imagesUri, projection, queryArgs, null)
            tryQueryInto(items, context, "videos+matchArg", videosUri, projection, queryArgs, null)
            tryQueryInto(items, context, "files+selection", filesUri, projection, null, selection)
            tryQueryInto(items, context, "images+selection", imagesUri, projection, null, selection)
            tryQueryInto(items, context, "videos+selection", videosUri, projection, null, selection)
            Log.d(TAG, "trashed item queries finished, total=${getTrashedItemList(items).size}")
        }

        cachedItems = items
        return items
    }

    // values of the indexed map contain every item twice (disk path and original path), dedupe by uri
    fun getTrashedItemList(context: Context): List<TrashedItem> = getTrashedItems(context).values.distinctBy { it.uri }

    private fun getTrashedItemList(items: Map<String, TrashedItem>): List<TrashedItem> = items.values.distinctBy { it.uri }

    private fun tryQueryInto(
        items: MutableMap<String, TrashedItem>, context: Context, strategy: String, collection: Uri,
        projection: Array<String>, queryArgs: Bundle?, selection: String?
    ) {
        val rowsBefore = items.size
        val cursor = try {
            if (queryArgs != null) {
                context.contentResolver.query(collection, projection, queryArgs, null)
            } else {
                context.contentResolver.query(collection, projection, selection, null, null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "query \"$strategy\" threw: $e")
            null
        } ?: return

        cursor.use {
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val idIndex = it.getColumnIndexOrThrow(BaseColumns._ID)
            while (it.moveToNext()) {
                val diskPath = it.getString(pathIndex) ?: continue
                val id = it.getLong(idIndex)
                val item = TrashedItem(
                    diskPath,
                    deriveDisplayName(diskPath.getFilenameFromPath()),
                    ContentUris.withAppendedId(collection, id)
                )

                // index both the on-disk ".trashed-" path and the path the file had before recycling,
                // lookups arrive in either form
                items[diskPath.lowercase()] = item
                items[getOriginalPath(item).lowercase()] = item
            }
            Log.d(TAG, "query \"$strategy\" returned ${it.count} rows, +${items.size - rowsBefore} new")
        }
    }

    fun isTrashed(context: Context, path: String) = getTrashedItems(context).containsKey(path.lowercase())

    // the path the file had before being recycled, used at matching database rows
    fun getOriginalPath(item: TrashedItem): String {
        val parent = File(item.diskPath).parentFile
        return if (parent != null) File(parent, item.displayName).path else item.diskPath
    }

    fun lookupUri(context: Context, path: String): Uri? = getTrashedItems(context)[path.lowercase()]?.uri

    // use at places where a fresh query is not worth it, the cache is refreshed at every media fetch
    fun getCachedIsTrashed(path: String) = cachedItems?.containsKey(path.lowercase()) == true

    fun clearCache() {
        cachedItems = null
    }

    // trashed files are renamed on disk like ".trashed-<id>-original.jpg", strip that prefix
    private fun deriveDisplayName(name: String): String {
        if (!name.startsWith(".trashed-")) {
            return name
        }

        val rest = name.removePrefix(".trashed-")
        return if (TRASHED_ID_PREFIX.containsMatchIn(rest)) rest.replaceFirst(TRASHED_ID_PREFIX, "") else rest
    }
}
