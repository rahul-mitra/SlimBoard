package app.slimboard.text

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Words learned from the user's own typing. Stored lowercase with a use count in files/personal.txt.
 * A word counts as "known" once it has been typed twice, or immediately when the user reverted an
 * autocorrect on it. Process-wide singleton shared by the IME and the settings screen.
 */
class PersonalDictionary private constructor(context: Context) {

    private val file = File(context.filesDir, "personal.txt")
    private val counts = HashMap<String, Int>()
    private var loaded = false
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "slimboard-personal") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Record one use. Returns true when the word has just become "known". */
    fun learn(word: String, force: Boolean = false): Boolean {
        ensureLoaded()
        val key = word.lowercase()
        if (key.length < 2 || key.length > 40) return false
        val before = counts[key] ?: 0
        val after = if (force) maxOf(before + 1, KNOWN_AT) else before + 1
        counts[key] = after
        persist()
        if (before < KNOWN_AT && after >= KNOWN_AT) { notifyChanged(); return true }
        return false
    }

    fun forget(word: String) {
        ensureLoaded()
        if (counts.remove(word.lowercase()) != null) { persist(); notifyChanged() }
    }

    fun clear() {
        ensureLoaded()
        if (counts.isEmpty()) return
        counts.clear()
        persist()
        notifyChanged()
    }

    fun contains(word: String): Boolean {
        ensureLoaded()
        return (counts[word.lowercase()] ?: 0) >= KNOWN_AT
    }

    /** Known words, most used first. */
    fun all(): List<Pair<String, Int>> {
        ensureLoaded()
        return counts.filter { it.value >= KNOWN_AT }.toList().sortedByDescending { it.second }
    }

    fun completions(prefix: String, out: MutableList<Candidate>) {
        ensureLoaded()
        for ((w, c) in counts) {
            if (c >= KNOWN_AT && w.length > prefix.length && w.startsWith(prefix)) out.add(Candidate(w, rankFor(c), 0))
        }
    }

    fun corrections(word: String, maxDist: Int, out: MutableList<Candidate>) {
        ensureLoaded()
        for ((w, c) in counts) {
            if (c < KNOWN_AT || w == word || abs(w.length - word.length) > maxDist) continue
            val d = levenshtein(word, w, maxDist)
            if (d in 1..maxDist) out.add(Candidate(w, rankFor(c), d))
        }
    }

    private fun rankFor(count: Int) = min(255, 180 + count * 5)

    private fun levenshtein(a: String, b: String, max: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            file.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i > 0) counts[line.substring(0, i)] = line.substring(i + 1).toIntOrNull() ?: 1
            }
        } catch (e: Exception) {
            counts.clear()
        }
    }

    private fun persist() {
        val snapshot = buildString { for ((w, c) in counts) append(w).append('\t').append(c).append('\n') }
        executor.execute {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(snapshot)
            if (!tmp.renameTo(file)) { file.writeText(snapshot); tmp.delete() }
        }
    }

    private fun notifyChanged() {
        for (l in listeners.toList()) l()
    }

    companion object {
        const val KNOWN_AT = 2

        @Volatile private var instance: PersonalDictionary? = null

        fun get(context: Context): PersonalDictionary =
            instance ?: synchronized(this) {
                instance ?: PersonalDictionary(context.applicationContext).also { instance = it }
            }
    }
}
