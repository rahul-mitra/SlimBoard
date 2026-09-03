package app.slimboard.settings

import android.content.Context
import app.slimboard.clipboard.ClipItem
import app.slimboard.clipboard.ClipboardStore
import app.slimboard.text.PersonalDictionary
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain-JSON backup of everything worth keeping: settings (including shortcuts and app lists),
 * learned words, and text clips. Images are not included. Stays local; the user picks the file.
 */
object Backup {

    private const val VERSION = 1

    fun export(context: Context): String {
        val prefs = Prefs(context)
        val personal = PersonalDictionary.get(context)
        val clips = ClipboardStore.get(context)
        val o = JSONObject()
        o.put("app", "SlimBoard")
        o.put("version", VERSION)
        o.put("exportedAt", System.currentTimeMillis())
        o.put("settings", prefs.exportAll().apply { remove(Prefs.APPS_SEEN) })
        o.put("words", JSONObject(personal.rawAll() as Map<*, *>))
        val arr = JSONArray()
        for (c in clips.items()) if (c.type == ClipItem.Type.TEXT) {
            arr.put(JSONObject().put("text", c.text).put("created", c.createdAt).put("pinned", c.pinned))
        }
        o.put("clips", arr)
        return o.toString(2)
    }

    /** Returns a short human-readable summary, or throws on malformed input. */
    fun import(context: Context, json: String): String {
        val o = JSONObject(json)
        require(o.optString("app") == "SlimBoard") { "Not a SlimBoard backup" }
        val prefs = Prefs(context)
        var settings = 0
        o.optJSONObject("settings")?.let { prefs.importAll(it); settings = it.length() }
        var words = 0
        o.optJSONObject("words")?.let { w ->
            val map = HashMap<String, Int>()
            for (k in w.keys()) map[k] = w.optInt(k, 1)
            PersonalDictionary.get(context).replaceAll(map)
            words = map.size
        }
        var clips = 0
        o.optJSONArray("clips")?.let { arr ->
            val store = ClipboardStore.get(context)
            for (i in arr.length() - 1 downTo 0) {
                val c = arr.getJSONObject(i)
                val item = store.addText(c.optString("text"), c.optLong("created", System.currentTimeMillis()))
                if (item != null) {
                    if (c.optBoolean("pinned")) store.setPinned(item.id, true)
                    clips++
                }
            }
        }
        return "Restored $settings settings, $words words, $clips clips"
    }
}
