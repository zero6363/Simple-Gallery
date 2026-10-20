package com.simplemobiletools.gallery.pro.helpers

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Helpers for reading Android "live photos" (motion photos), i.e. JPEG files with a short video
 * appended right after the still image data. Supported formats:
 *
 *  - Google (old, MVIMG): XMP attribute "GCamera:MicroVideo" + "MicroVideoOffset", the video is
 *    the last <MicroVideoOffset> bytes of the file
 *  - Google (new): XMP "GCamera:MotionPhoto" / Container:Directory item with
 *    GItem:Semantic="MotionPhoto", the video is appended after the JPEG data (found by its "ftyp" box)
 *  - Samsung: video appended right before the "SEFT" trailer at the end of the file
 *  - Some vendors (e.g. LG) store the video as a separate file with the same base name
 */
object MotionPhotoHelper {

    /**
     * Location of the video part inside the motion photo file, in bytes.
     * [videoStart] == -1 means the video is not embedded, it is a separate file (see [findPairedVideoFile]).
     */
    data class MotionInfo(val videoStart: Long, val videoEnd: Long)

    // XMP metadata is stored near the beginning of the file
    private val XMP_SCAN_BYTES = 256 * 1024

    // the Samsung "SEFT" trailer sits at the very end of the file
    private val TRAILER_SCAN_BYTES = 64 * 1024

    // never scan more than this when looking for the appended video stream
    private val MAX_FTYP_SCAN_BYTES = 128L * 1024 * 1024

    private val MIN_VIDEO_SIZE_BYTES = 10_000L

    // the XMP namespace prefix varies between vendors, match on the local attribute names only
    private val MOTION_ATTR_REGEX = Regex("[A-Za-z0-9_.-]+:(?:MicroVideo|MotionPhoto)\\s*=\\s*\"(?:True|true|1)\"")
    private val MOTION_ELEMENT_REGEX = Regex("<[A-Za-z0-9_.-]+:(?:MicroVideo|MotionPhoto)>\\s*(?:True|true|1)\\s*</")
    private val MOTION_SEMANTIC_REGEX = Regex("[A-Za-z0-9_.-]+:Semantic\\s*=\\s*\"(?:MotionPhoto|MovingPhoto)\"")
    private val MICRO_VIDEO_OFFSET_REGEX = Regex("[A-Za-z0-9_.-]+:MicroVideoOffset\\s*=\\s*\"(\\d+)\"")
    private val ITEM_LENGTH_REGEX = Regex("[A-Za-z0-9_.-]+:Length\\s*=\\s*\"(\\d+)\"")

    private val SEFT_MARKER = "SEFT".toByteArray(Charsets.US_ASCII)
    private val FTYP_MARKER = "ftyp".toByteArray(Charsets.US_ASCII)

    private val isMotionPhotoCache = ConcurrentHashMap<String, Boolean>()
    private val pendingCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<(Boolean) -> Unit>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun getCachedIsMotionPhoto(path: String) = isMotionPhotoCache[path] == true

    /**
     * Checks whether the file is a motion photo, in the background. Every [callback] registered for
     * a path is invoked exactly once with the result on the main thread, also when it is false.
     */
    fun detectMotionPhotoAsync(context: Context, path: String, callback: (Boolean) -> Unit) {
        val cached = isMotionPhotoCache[path]
        if (cached != null) {
            callback(cached)
            return
        }

        val callbacks = pendingCallbacks.getOrPut(path) { CopyOnWriteArrayList() }
        callbacks.add(callback)
        if (callbacks.size > 1) {
            // a scan for this path is already queued or running, it will invoke every callback
            return
        }

        val appContext = context.applicationContext
        detectionExecutor.execute {
            val isMotionPhoto = try {
                isMotionPhoto(appContext, path)
            } catch (ignored: Exception) {
                false
            }
            isMotionPhotoCache[path] = isMotionPhoto
            val queued = pendingCallbacks.remove(path)
            queued?.forEach { queuedCallback ->
                mainHandler.post { queuedCallback(isMotionPhoto) }
            }
        }
    }

    fun isMotionPhoto(context: Context, path: String) = getMotionInfo(context, path) != null

    /** Locates the video part inside a motion photo file, null if the file is not a motion photo. */
    fun getMotionInfo(context: Context, path: String): MotionInfo? {
        // motion photos are always JPEG files with the video appended after the still image
        val lowercasedPath = path.toLowerCase()
        if (!lowercasedPath.endsWith(".jpg") && !lowercasedPath.endsWith(".jpeg")) {
            return null
        }

        if (!lowercasedPath.startsWith("content://") && !File(path).isFile) {
            return null
        }

        return try {
            if (path.startsWith("content://")) {
                openContentChannel(context, path)?.use { channel ->
                    getMotionInfo(channel)
                }
            } else {
                RandomAccessFile(path, "r").use { raf ->
                    val info = getMotionInfo(raf.channel)
                    info ?: if (findPairedVideoFile(path) != null) MotionInfo(-1L, -1L) else null
                }
            }
        } catch (ignored: Exception) {
            null
        }
    }

    /** Extracts the video part to the cache directory and returns the playable file, null on failure. */
    fun extractMotionVideo(context: Context, path: String): File? {
        val info = getMotionInfo(context, path) ?: return null

        if (info.videoStart < 0L) {
            return findPairedVideoFile(path)
        }

        val videoLength = info.videoEnd - info.videoStart
        if (videoLength <= 0L) {
            return null
        }

        val outputFile = getVideoCacheFile(context, path, videoLength)
        if (outputFile.isFile && outputFile.length() == videoLength) {
            return outputFile
        }

        return try {
            if (path.startsWith("content://")) {
                openContentChannel(context, path)?.use { channel ->
                    copyVideo(channel, info, outputFile)
                }
            } else {
                RandomAccessFile(path, "r").use { raf ->
                    copyVideo(raf.channel, info, outputFile)
                }
            }

            if (outputFile.length() == videoLength) {
                outputFile
            } else {
                outputFile.delete()
                null
            }
        } catch (ignored: Exception) {
            outputFile.delete()
            null
        }
    }

    private fun getMotionInfo(channel: FileChannel): MotionInfo? {
        val size = channel.size()
        if (size < MIN_VIDEO_SIZE_BYTES) {
            return null
        }

        val headLength = minOf(XMP_SCAN_BYTES.toLong(), size)
        val xmp = String(readRegion(channel, 0L, headLength), Charsets.ISO_8859_1)
        val hasMotionFlag = MOTION_ATTR_REGEX.containsMatchIn(xmp) || MOTION_ELEMENT_REGEX.containsMatchIn(xmp) ||
            MOTION_SEMANTIC_REGEX.containsMatchIn(xmp)

        val tailStart = maxOf(0L, size - TRAILER_SCAN_BYTES)
        val tail = readRegion(channel, tailStart, size - tailStart)
        val seftIndex = tail.lastIndexOf(SEFT_MARKER)
        if (!hasMotionFlag && seftIndex < 0) {
            return null
        }

        // old Google format: the video is the last <MicroVideoOffset> bytes of the file
        val microVideoOffset = MICRO_VIDEO_OFFSET_REGEX.find(xmp)?.groupValues?.get(1)?.toLongOrNull()
        if (microVideoOffset != null && microVideoOffset > MIN_VIDEO_SIZE_BYTES && microVideoOffset < size) {
            return MotionInfo(size - microVideoOffset, size)
        }

        // newer formats: the MP4 stream is appended after the JPEG data, its "ftyp" box marks the beginning
        val searchStart = maxOf(0L, size - minOf(size, MAX_FTYP_SCAN_BYTES))
        val searchEnd = if (seftIndex >= 0) tailStart + seftIndex else size
        if (searchEnd - searchStart < MIN_VIDEO_SIZE_BYTES) {
            return null
        }

        val ftypPosition = findLastMarkerPosition(channel, searchStart, searchEnd, FTYP_MARKER)
        if (ftypPosition < 4) {
            return null
        }

        val videoStart = ftypPosition - 4   // include the 4 byte MP4 box size preceding "ftyp"
        val boxSize = readUnsignedInt32(readRegion(channel, videoStart, 8), 0)
        if (boxSize < 8L || videoStart + boxSize > size) {
            return null
        }

        var videoEnd = if (seftIndex >= 0) tailStart + seftIndex else size
        if (seftIndex < 0) {
            // when available, prefer the video length written into the XMP Container item
            val semanticIndex = MOTION_SEMANTIC_REGEX.find(xmp)?.range?.first ?: -1
            if (semanticIndex >= 0) {
                val length = ITEM_LENGTH_REGEX.find(xmp, semanticIndex)?.groupValues?.get(1)?.toLongOrNull()
                if (length != null && length > MIN_VIDEO_SIZE_BYTES && videoStart + length <= size) {
                    videoEnd = videoStart + length
                }
            }
        }

        if (videoStart < 0 || videoEnd > size || videoEnd - videoStart < MIN_VIDEO_SIZE_BYTES) {
            return null
        }

        return MotionInfo(videoStart, videoEnd)
    }

    // scans the region backwards in chunks, so that huge files do not need to be loaded into memory at once
    private fun findLastMarkerPosition(channel: FileChannel, searchStart: Long, searchEnd: Long, marker: ByteArray): Long {
        val CHUNK_SIZE = 8L * 1024 * 1024
        val CHUNK_OVERLAP = marker.size.toLong()
        var chunkEnd = searchEnd
        while (chunkEnd > searchStart) {
            val chunkStart = maxOf(searchStart, chunkEnd - CHUNK_SIZE)
            val chunk = readRegion(channel, chunkStart, chunkEnd - chunkStart)
            val index = chunk.lastIndexOf(marker)
            if (index >= 0) {
                return chunkStart + index
            }

            if (chunkStart == searchStart) {
                break
            }
            chunkEnd = chunkStart + CHUNK_OVERLAP   // markers can straddle the chunk boundaries
        }
        return -1L
    }

    private fun copyVideo(channel: FileChannel, info: MotionInfo, outputFile: File) {
        FileOutputStream(outputFile).use { output ->
            val outChannel = output.channel
            var position = info.videoStart
            while (position < info.videoEnd) {
                val transferred = channel.transferTo(position, info.videoEnd - position, outChannel)
                if (transferred <= 0L) {
                    break
                }
                position += transferred
            }
        }
    }

    private fun getVideoCacheFile(context: Context, path: String, videoLength: Long): File {
        val folder = File(context.cacheDir, "motion_photos")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        pruneVideoCache(folder)
        return File(folder, "${path.toLowerCase().hashCode()}_$videoLength.mp4")
    }

    private fun pruneVideoCache(folder: File) {
        val files = folder.listFiles() ?: return
        val aWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        files.filter { it.lastModified() < aWeekAgo }.forEach { it.delete() }
    }

    private fun findPairedVideoFile(photoPath: String): File? {
        if (photoPath.startsWith("content://")) {
            return null
        }

        val photoFile = File(photoPath)
        val parent = photoFile.parentFile ?: return null
        val baseName = photoFile.nameWithoutExtension
        val candidates = arrayListOf("$baseName.mp4", "${baseName}_motion.mp4", "${baseName}-motion.mp4", "${baseName}_video.mp4")
        candidates.forEach { candidateName ->
            val candidate = File(parent, candidateName)
            if (candidate.isFile && candidate.length() > MIN_VIDEO_SIZE_BYTES) {
                return candidate
            }
        }
        return null
    }

    private fun openContentChannel(context: Context, path: String): FileChannel? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r") ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
        } catch (ignored: Exception) {
            null
        }
    }

    private fun readRegion(channel: FileChannel, start: Long, length: Long): ByteArray {
        val buffer = ByteBuffer.allocate(length.toInt())
        channel.position(start)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) {
                break
            }
        }
        return if (buffer.position() == buffer.capacity()) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    private fun readUnsignedInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)
    }

    private fun ByteArray.lastIndexOf(pattern: ByteArray): Int {
        if (size < pattern.size) {
            return -1
        }

        for (i in size - pattern.size downTo 0) {
            if (matchesAt(this, i, pattern)) {
                return i
            }
        }
        return -1
    }

    private fun matchesAt(data: ByteArray, position: Int, pattern: ByteArray): Boolean {
        for (i in pattern.indices) {
            if (data[position + i] != pattern[i]) {
                return false
            }
        }
        return true
    }
}
