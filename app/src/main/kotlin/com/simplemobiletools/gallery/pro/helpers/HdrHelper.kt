package com.simplemobiletools.gallery.pro.helpers

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Detects HDR still images. Covered standards:
 *
 *  - Ultra HDR JPEG (the gain map format introduced with Android 14): XMP marker "hdrgm:GainMap"
 *  - ISO 21496-1 gain maps in HEIF/AVIF containers: top level "tmap" box
 *  - HDR10 (SMPTE ST 2084 / PQ) and HLG in HEIF/AVIF: "colr" box with transfer characteristics 16 / 18
 *  - Dolby Vision in HEIF/AVIF: "dvcC" / "dvvC" item property
 *  - HDR PNG: "cICP" chunk with PQ / HLG transfer characteristics
 *
 * Images with an explicit gain map ([HdrType.GAIN_MAP]) can be rendered in HDR through the
 * platform gain map pipeline on Android 14+, the rest is flagged with a badge only.
 */
object HdrHelper {

    enum class HdrType { NONE, GAIN_MAP, HDR }

    // the XMP packet and the PNG header chunks both sit near the beginning of the file
    private val METADATA_SCAN_BYTES = 1024 * 1024

    // any XMP "GainMap" entry counts, writers use an attribute, an element or an rdf:parseType container.
    // a plain literal search instead of a regex: prefixes like "hdrgm" sit right before the colon,
    // and a "+ "-style character class regex blows up on long ASCII runs like base64 ICC profiles
    private val GAIN_MAP_MARKER = ":GainMap"

    // the XMP namespace declaration, present even when the GainMap attribute itself is absent
    private val HDRGM_NS_MARKER = "hdrgm"

    // ISO 21496-1 gain maps are identified by the number from the standard, appearing either in the
    // URN form ("urn:iso:std:iso:ts:21496:-1") or in vendor binary markers; some new writers emit
    // only that one without the legacy XMP "hdrgm" entry
    private val ISO_21496_MARKER = "21496"

    private val MPF_MARKER_BYTES = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)

    // colour transfer characteristics signalling HDR in nclx colour information
    private val HDR_TRANSFER_PQ = 16      // SMPTE ST 2084 (HDR10)
    private val HDR_TRANSFER_HLG = 18     // BT.2100 HLG

    private val typeCache = ConcurrentHashMap<String, HdrType>()
    private val pendingCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<(Boolean) -> Unit>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // a small pool: while a folder scrolls into view every thumbnail wants a scan, a single thread
    // would make the badges trickle in one by one
    private val detectionExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    fun getCachedIsHdr(path: String) = typeCache[path]?.let { it != HdrType.NONE } == true

    /**
     * Checks whether the file is an HDR image, in the background. Every [callback] registered for a
     * path is invoked exactly once with the result on the main thread, also when the result is false.
     */
    fun detectHdrAsync(context: Context, path: String, callback: (Boolean) -> Unit) {
        val cached = typeCache[path]
        if (cached != null) {
            callback(cached != HdrType.NONE)
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
            val type = try {
                detectType(appContext, path)
            } catch (ignored: Exception) {
                HdrType.NONE
            }
            typeCache[path] = type
            val queued = pendingCallbacks.remove(path)
            queued?.forEach { queuedCallback ->
                mainHandler.post { queuedCallback(type != HdrType.NONE) }
            }
        }
    }

    fun isHdrImage(context: Context, path: String) = getHdrType(context, path) != HdrType.NONE

    /** True for images whose HDR data is a gain map, those can be rendered in HDR on Android 14+. */
    fun isGainMapImage(context: Context, path: String) = getHdrType(context, path) == HdrType.GAIN_MAP

    private fun getHdrType(context: Context, path: String): HdrType {
        // content URIs can point to different files over time, only cache real paths
        if (path.startsWith("content://")) {
            return detectType(context, path)
        }

        return typeCache.getOrPut(path) {
            detectType(context, path)
        }
    }

    private fun detectType(context: Context, path: String): HdrType {
        return try {
            if (path.startsWith("content://")) {
                openContentChannel(context, path)?.use { channel -> detect(channel) } ?: HdrType.NONE
            } else {
                val file = File(path)
                if (!file.isFile) HdrType.NONE else RandomAccessFile(file, "r").use { detect(it.channel) }
            }
        } catch (ignored: Exception) {
            HdrType.NONE
        } catch (ignored: OutOfMemoryError) {
            HdrType.NONE
        }
    }

    private fun detect(channel: FileChannel): HdrType {
        val head = readRegion(channel, 0L, minOf(METADATA_SCAN_BYTES.toLong(), channel.size()))
        return when {
            isJpeg(head) -> {
                val headResult = isHdrJpeg(head)
                when {
                    headResult != HdrType.NONE -> headResult
                    // ultra hdr jpegs always carry an MPF index to their gain map image, gate the
                    // expensive whole-file scan on it so plain photos never pay for it
                    head.indexOf(MPF_MARKER_BYTES) < 0 -> HdrType.NONE                    else -> {
                        val viaSecondary = detectIsoGainmapViaMpf(channel, head)
                        if (viaSecondary != HdrType.NONE) viaSecondary else scanFileForGainMapMarkers(channel)
                    }
                }
            }
            isIsoBmff(head) -> isHdrIsoBmff(channel)
            isPng(head) -> isHdrPng(head)
            else -> HdrType.NONE
        }
    }

    // last resort for exotic ISO 21496-1 writers: stream the whole file looking for the known gain
    // map literals, wherever the writer decided to hide them
    private fun scanFileForGainMapMarkers(channel: FileChannel): HdrType {
        val markers = listOf(GAIN_MAP_MARKER, HDRGM_NS_MARKER, "urn:iso:std:iso:ts:21496").map { it.toByteArray(Charsets.ISO_8859_1) }
        val size = channel.size()
        val chunkSize = 1024 * 1024
        val overlap = 64
        var carry = ByteArray(0)
        var offset = 0L
        while (offset < size) {
            val length = minOf(chunkSize.toLong(), size - offset).toInt()
            val haystack = carry + readRegion(channel, offset, length.toLong())
            if (markers.any { haystack.indexOf(it) >= 0 }) {
                return HdrType.GAIN_MAP
            }

            carry = if (haystack.size > overlap) haystack.copyOfRange(haystack.size - overlap, haystack.size) else haystack
            offset += length
        }
        return HdrType.NONE
    }

    // ISO 21496-1 metadata can sit inside the MPF secondary image (the gain map JPEG itself) near the
    // end of the file, beyond the scanned head. Parse the MPF index, read the secondary image head
    // and check it for the gain map markers.
    private fun detectIsoGainmapViaMpf(channel: FileChannel, head: ByteArray): HdrType {
        val mpfIndex = head.indexOf(MPF_MARKER_BYTES)
        if (mpfIndex < 0) {
            return HdrType.NONE
        }

        val tiffStart = mpfIndex + MPF_MARKER_BYTES.size   // right after "MPF\0"
        if (tiffStart + 8 > head.size) {
            return HdrType.NONE
        }

        val bigEndian = when {
            head[tiffStart] == 0x49.toByte() && head[tiffStart + 1] == 0x49.toByte() -> false   // "II"
            head[tiffStart] == 0x4D.toByte() && head[tiffStart + 1] == 0x4D.toByte() -> true    // "MM"
            else -> return HdrType.NONE
        }

        fun u16(offset: Int) = if (bigEndian) {
            ((head[offset].toInt() and 0xFF) shl 8) or (head[offset + 1].toInt() and 0xFF)
        } else {
            (head[offset].toInt() and 0xFF) or ((head[offset + 1].toInt() and 0xFF) shl 8)
        }

        fun u32(offset: Int) = if (bigEndian) {
            ((head[offset].toLong() and 0xFF) shl 24) or ((head[offset + 1].toLong() and 0xFF) shl 16) or
                ((head[offset + 2].toLong() and 0xFF) shl 8) or (head[offset + 3].toLong() and 0xFF)
        } else {
            (head[offset].toLong() and 0xFF) or ((head[offset + 1].toLong() and 0xFF) shl 8) or
                ((head[offset + 2].toLong() and 0xFF) shl 16) or ((head[offset + 3].toLong() and 0xFF) shl 24)
        }

        val ifdOffset = tiffStart + u32(tiffStart + 4).toInt()
        if (ifdOffset + 2 > head.size) {
            return HdrType.NONE
        }

        val entryCount = u16(ifdOffset)
        var secondaryOffset = -1L
        for (i in 0 until entryCount) {
            val entryOffset = ifdOffset + 2 + i * 12
            if (entryOffset + 12 > head.size) {
                return HdrType.NONE
            }

            when (u16(entryOffset)) {
                0xB002 -> secondaryOffset = tiffStart + u32(entryOffset + 8)   // Individual Image Data Offset
            }
        }

        if (secondaryOffset <= 0L) {
            return HdrType.NONE
        }

        val size = channel.size()
        if (secondaryOffset >= size) {
            return HdrType.NONE
        }

        // the gain map image is a small JPEG, its metadata sits in the first bytes
        val secondaryHead = readRegion(channel, secondaryOffset, minOf(4096L, size - secondaryOffset))
        val secondary = String(secondaryHead, Charsets.ISO_8859_1)
        return if (secondary.contains(GAIN_MAP_MARKER, ignoreCase = true) || secondary.contains(HDRGM_NS_MARKER, ignoreCase = true) ||
            secondary.contains(ISO_21496_MARKER)
        ) {
            HdrType.GAIN_MAP
        } else {
            HdrType.NONE
        }
    }

    // Ultra HDR JPEG: the XMP packet near the file start carries the gain map marker
    private fun isHdrJpeg(head: ByteArray): HdrType {
        val xmp = String(head, Charsets.ISO_8859_1)
        val isGainMap = xmp.contains(GAIN_MAP_MARKER, ignoreCase = true) || xmp.contains(HDRGM_NS_MARKER, ignoreCase = true) ||
            xmp.contains(ISO_21496_MARKER)
        return if (isGainMap) HdrType.GAIN_MAP else HdrType.NONE
    }

    // HDR PNG: the cICP chunk declares the colour transfer characteristics, it precedes the image data
    private fun isHdrPng(head: ByteArray): HdrType {
        val marker = "cICP".toByteArray(Charsets.US_ASCII)
        val index = head.indexOf(marker)
        if (index < 0 || index + 8 > head.size) {
            return HdrType.NONE
        }

        val transfer = head[index + 5].toInt() and 0xFF
        return if (transfer == HDR_TRANSFER_PQ || transfer == HDR_TRANSFER_HLG) HdrType.HDR else HdrType.NONE
    }

    private fun isJpeg(head: ByteArray) =
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()

    private fun isPng(head: ByteArray) =
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()

    private fun isIsoBmff(head: ByteArray) =
        head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    // walks the box structure without ever reading the heavy mdat payload
    private fun isHdrIsoBmff(channel: FileChannel): HdrType {
        channel.position(0L)
        return findHdrBox(channel, 0L, channel.size(), depth = 0)
    }

    private fun findHdrBox(channel: FileChannel, start: Long, end: Long, depth: Int): HdrType {
        if (depth > 3) {
            return HdrType.NONE
        }

        var found = HdrType.NONE
        var offset = start
        while (offset + 8 <= end) {
            val header = readRegion(channel, offset, 8L)
            if (header.size < 8) {
                return found
            }

            var boxSize = readUnsignedInt32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (boxSize == 1L) {
                val largeSize = readRegion(channel, offset + 8, 8L)
                if (largeSize.size < 8) {
                    return found
                }
                boxSize = (readUnsignedInt32(largeSize, 0) shl 32) or readUnsignedInt32(largeSize, 4)
                headerSize = 16L
            } else if (boxSize == 0L) {
                boxSize = end - offset   // the box extends to the end of the enclosing one
            }

            if (boxSize < headerSize || offset + boxSize > end) {
                return found
            }

            val contentStart = offset + headerSize
            val contentLength = minOf(boxSize - headerSize, 512L)
            val boxResult = when (type) {
                "tmap" -> HdrType.GAIN_MAP
                "dvcC", "dvvC" -> HdrType.HDR
                // Apple writes its gain map as an auxiliary image, referenced by an "auxC" URN, not as a "tmap" box
                "auxC" -> if (isGainMapAuxiliary(readRegion(channel, contentStart, contentLength))) HdrType.GAIN_MAP else HdrType.NONE
                "meta" -> findHdrBox(channel, contentStart + 4, offset + boxSize, depth + 1)   // meta is a full box, skip version and flags
                "iprp", "ipco" -> findHdrBox(channel, contentStart, offset + boxSize, depth + 1)
                "colr" -> if (isHdrColourBox(readRegion(channel, contentStart, contentLength))) HdrType.HDR else HdrType.NONE
                else -> HdrType.NONE
            }

            if (boxResult == HdrType.GAIN_MAP) {
                return HdrType.GAIN_MAP
            } else if (boxResult == HdrType.HDR) {
                found = HdrType.HDR
            }

            offset += boxSize
        }
        return found
    }

    // the auxC payload holds the auxiliary image URN, e.g. Apple's
    // "urn:com:apple:photo:2023:aux:hdrgainmap" or the ISO 21496-1 "urn:iso:std:iso:ts:21496:-1"
    private fun isGainMapAuxiliary(payload: ByteArray): Boolean {
        if (payload.size < 5) {
            return false
        }

        val urn = String(payload, 4, payload.size - 4, Charsets.ISO_8859_1)
        return urn.contains("gainmap", ignoreCase = true) || urn.contains("21496")
    }

    // nclx/nclc colour information: colour type (4), primaries (2), transfer characteristics (2), matrix (2), range (1)
    private fun isHdrColourBox(payload: ByteArray): Boolean {
        if (payload.size < 8) {
            return false
        }

        val colourType = String(payload, 0, 4, Charsets.ISO_8859_1)
        if (!colourType.equals("nclx", true) && !colourType.equals("nclc", true)) {
            return false
        }

        val transfer = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        return transfer == HDR_TRANSFER_PQ || transfer == HDR_TRANSFER_HLG
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
        val buffer = java.nio.ByteBuffer.allocate(length.toInt())
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

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        if (size < pattern.size) {
            return -1
        }

        for (i in 0..size - pattern.size) {
            var matches = true
            for (j in pattern.indices) {
                if (this[i + j] != pattern[j]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return i
            }
        }
        return -1
    }
}
