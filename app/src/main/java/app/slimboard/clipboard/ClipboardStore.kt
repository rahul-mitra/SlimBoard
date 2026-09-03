package app.slimboard.clipboard

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class ClipItem(
    val id: Long,
    val type: Type,
    val text: String,
    val mime: String,
    val imageFile: File?,
    val thumbFile: File?,
    val createdAt: Long,
    var pinned: Boolean,
) {
    enum class Type { TEXT, IMAGE }

    /** Short single-line preview for chips and logs. */
    fun preview(maxChars: Int = 60): String = when (type) {
        Type.TEXT -> text.trim().replace(Regex("\\s+"), " ").let { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }
        Type.IMAGE -> "Image"
    }
}

/**
 * Clipboard history. Metadata lives in files/clips.json, image bytes and thumbnails in files/clips/.
 * All mutation happens on the main thread; disk writes and image copies run on one background thread.
 * Process-wide singleton so the settings screen and the IME service see the same list.
 */
class ClipboardStore private constructor(context: Context) {

    private val jsonFile = File(context.filesDir, "clips.json")
    private val imageDir = File(context.filesDir, "clips").apply { mkdirs() }
    private val items = ArrayList<ClipItem>()   // newest first
    private var loaded = false
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "slimboard-clips") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private val nextId = AtomicLong(System.currentTimeMillis())

    var maxItems = 100

    fun items(): List<ClipItem> {
        ensureLoaded()
        return items
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Adds text, or moves an identical existing entry to the top. Returns null for blank text. */
    fun addText(text: String, createdAt: Long): ClipItem? {
        ensureLoaded()
        if (text.isBlank() || text.length > MAX_TEXT_CHARS) return null
        val existing = items.indexOfFirst { it.type == ClipItem.Type.TEXT && it.text == text }
        val item = if (existing >= 0) {
            val old = items.removeAt(existing)
            ClipItem(old.id, ClipItem.Type.TEXT, text, "text/plain", null, null, createdAt, old.pinned)
        } else {
            ClipItem(nextId.incrementAndGet(), ClipItem.Type.TEXT, text, "text/plain", null, null, createdAt, false)
        }
        items.add(0, item)
        trim()
        changed()
        return item
    }

    /**
     * Copies the image behind [uri] into private storage on the background thread, builds a
     * thumbnail, then adds the item on the main thread. [onDone] receives null on failure or if the
     * image exceeds [maxBytes].
     */
    fun addImageAsync(
        resolver: ContentResolver, uri: Uri, mime: String, createdAt: Long, maxBytes: Long,
        onDone: (ClipItem?) -> Unit,
    ) {
        val id = nextId.incrementAndGet()
        executor.execute {
            val result = try {
                copyImage(resolver, uri, mime, createdAt, maxBytes, id)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (result != null) {
                    ensureLoaded()
                    items.add(0, result)
                    trim()
                    changed()
                }
                onDone(result)
            }
        }
    }

    private fun copyImage(resolver: ContentResolver, uri: Uri, mime: String, createdAt: Long, maxBytes: Long, id: Long): ClipItem? {
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val dst = File(imageDir, "$id.$ext")
        val input = resolver.openInputStream(uri) ?: return null
        var total = 0L
        input.use { src ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        dst.delete()
                        return null
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        if (total == 0L) { dst.delete(); return null }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(dst.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { dst.delete(); return null }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2
        val bmp = BitmapFactory.decodeFile(dst.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: run { dst.delete(); return null }
        val hasAlpha = ext != "jpg"
        val thumb = File(imageDir, if (hasAlpha) "${id}_t.png" else "${id}_t.jpg")
        thumb.outputStream().use {
            bmp.compress(if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
        }
        bmp.recycle()
        return ClipItem(id, ClipItem.Type.IMAGE, "", mime, dst, thumb, createdAt, false)
    }

    fun setPinned(id: Long, pinned: Boolean) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.pinned == pinned) return
        item.pinned = pinned
        changed()
    }

    fun delete(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        deleteFiles(items.removeAt(idx))
        changed()
    }

    fun clearUnpinned() {
        ensureLoaded()
        val removed = items.filter { !it.pinned }
        if (removed.isEmpty()) return
        items.removeAll(removed)
        removed.forEach { deleteFiles(it) }
        changed()
    }

    fun clearAll() {
        ensureLoaded()
        if (items.isEmpty()) return
        items.forEach { deleteFiles(it) }
        items.clear()
        changed()
    }

    /** Removes unpinned items older than [maxAgeMs]. No-op when [maxAgeMs] <= 0. */
    fun expire(maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        if (maxAgeMs <= 0) return
        ensureLoaded()
        val cutoff = now - maxAgeMs
        val removed = items.filter { !it.pinned && it.createdAt < cutoff }
        if (removed.isEmpty()) return
        items.removeAll(removed)
        removed.forEach { deleteFiles(it) }
        changed()
    }

    // ---- internals ----

    private fun trim() {
        while (items.size > maxItems) {
            val idx = items.indexOfLast { !it.pinned }
            if (idx < 0) break
            deleteFiles(items.removeAt(idx))
        }
    }

    private fun deleteFiles(item: ClipItem) {
        val a = item.imageFile
        val b = item.thumbFile
        executor.execute {
            a?.delete()
            b?.delete()
        }
    }

    private fun changed() {
        persist()
        for (l in listeners.toList()) l()
    }

    private fun persist() {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("type", it.type.name)
                put("text", it.text)
                put("mime", it.mime)
                put("image", it.imageFile?.name ?: "")
                put("thumb", it.thumbFile?.name ?: "")
                put("created", it.createdAt)
                put("pinned", it.pinned)
            })
        }
        val json = arr.toString()
        executor.execute {
            val tmp = File(jsonFile.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(jsonFile)) {
                jsonFile.writeText(json)
                tmp.delete()
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!jsonFile.exists()) return
            val arr = JSONArray(jsonFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = ClipItem.Type.valueOf(o.getString("type"))
                val image = o.optString("image", "").takeIf { it.isNotEmpty() }?.let { File(imageDir, it) }
                val thumb = o.optString("thumb", "").takeIf { it.isNotEmpty() }?.let { File(imageDir, it) }
                if (type == ClipItem.Type.IMAGE && (image == null || !image.exists())) continue
                items.add(ClipItem(o.getLong("id"), type, o.optString("text", ""), o.optString("mime", "text/plain"), image, thumb, o.getLong("created"), o.optBoolean("pinned", false)))
            }
            val maxId = items.maxOfOrNull { it.id } ?: 0L
            if (maxId >= nextId.get()) nextId.set(maxId + 1)
        } catch (e: Exception) {
            items.clear()
        }
    }

    companion object {
        private const val MAX_TEXT_CHARS = 100_000
        private const val THUMB_PX = 320

        @Volatile private var instance: ClipboardStore? = null

        fun get(context: Context): ClipboardStore =
            instance ?: synchronized(this) {
                instance ?: ClipboardStore(context.applicationContext).also { instance = it }
            }
    }
}
