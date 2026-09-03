package app.slimboard.text

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ln

/**
 * Turns the word being typed into up to three suggestions plus an optional autocorrect candidate.
 * Main dictionary: assets/dict/en.txt ("word count" per line, best first) built into a byte trie
 * once and cached in files/dict/. Personal dictionary: words the user typed at least twice.
 * All lookups run on one background thread; results come back on the main thread.
 */
class SuggestionEngine(context: Context) {

    class Result(val typed: String, val suggestions: List<String>, val autoCorrect: String?)

    private val appContext = context.applicationContext
    private val personal = PersonalDictionary.get(context)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "slimboard-suggest") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var dict: Dictionary? = null
    @Volatile private var loadStarted = false

    /** Kicks off dictionary loading; safe to call repeatedly. */
    fun ensureLoaded() {
        if (loadStarted) return
        loadStarted = true
        executor.execute { dict = loadDictionary() }
    }

    val isReady: Boolean get() = dict != null

    fun isWord(word: String): Boolean {
        val lower = word.lowercase()
        return (dict?.contains(lower) == true) || personal.contains(lower)
    }

    fun learn(word: String, force: Boolean = false) = personal.learn(word, force)

    fun suggestAsync(typed: String, generation: Int, callback: (Int, Result) -> Unit) {
        executor.execute {
            val r = compute(typed)
            main.post { callback(generation, r) }
        }
    }

    /** Synchronous variant for commit time; a few milliseconds on a mid-range phone. */
    fun suggestSync(typed: String): Result = compute(typed)

    // ---- core ----

    private fun compute(typed: String): Result {
        if (typed.isEmpty()) return Result(typed, emptyList(), null)
        val lower = typed.lowercase()
        val d = dict
        val known = (d?.contains(lower) == true) || personal.contains(lower)
        val correctable = lower.length >= 2 && lower.all { it.isLetter() || it == '\'' } &&
            !hasInnerCapitals(typed)

        val cands = ArrayList<Candidate>()
        personal.completions(lower, cands)
        d?.completions(lower, 12)?.let { cands.addAll(it) }
        if (correctable) {
            val maxDist = if (lower.length <= 3) 1 else 2
            d?.corrections(lower, maxDist, cands)
            personal.corrections(lower, maxDist, cands)
        }

        // Dedupe, drop the typed word itself, score.
        val seen = HashSet<String>()
        seen.add(lower)
        val scored = ArrayList<Pair<Int, Candidate>>()
        for (c in cands) {
            if (!seen.add(c.word)) continue
            // Distance dominates frequency: one extra edit costs more than the whole rank range,
            // so "their" (1 edit) beats "the" (2 edits) despite "the" being the commonest word.
            var s = c.rank * 3
            s -= c.distance * 400
            if (c.distance > 0 && c.word[0] != lower[0]) s -= 90
            if (c.distance > 0 && c.word.length == lower.length) s += 30
            if (c.distance > 0 && c.word.replace("'", "") == lower) s += 300   // dont → don't
            if (c.distance == 0) s -= (c.word.length - lower.length) * 6   // shorter completions first
            scored.add(s to c)
        }
        scored.sortByDescending { it.first }

        val bestCorrection = scored.firstOrNull { it.second.distance > 0 }?.second
        val auto = if (!known && correctable && bestCorrection != null && acceptable(bestCorrection, lower)) bestCorrection.word else null

        val ordered = ArrayList<String>(3)
        if (known) {
            // Typed word is valid: it takes the middle, completions around it.
            val comps = scored.map { it.second.word }
            comps.getOrNull(0)?.let { ordered.add(it) }
            ordered.add(lower)
            comps.getOrNull(1)?.let { ordered.add(it) }
        } else {
            ordered.add(lower)   // verbatim always available on the left
            val best = auto ?: scored.firstOrNull()?.second?.word
            if (best != null) ordered.add(best)
            scored.map { it.second.word }.firstOrNull { it != best }?.let { ordered.add(it) }
        }
        val shown = ordered.mapIndexed { i, w -> if (i == 0 && !known) typed else applyCase(typed, w) }
        return Result(typed, shown, auto?.let { applyCase(typed, it) })
    }

    private fun acceptable(c: Candidate, lower: String): Boolean = when (c.distance) {
        1 -> c.rank >= 60 || (c.word[0] == lower[0] && c.rank >= 30)
        2 -> lower.length >= 5 && c.rank >= 120 && c.word[0] == lower[0]
        else -> false
    }

    private fun hasInnerCapitals(s: String): Boolean {
        for (i in 1 until s.length) if (s[i].isUpperCase()) {
            // ALL CAPS is fine (handled by applyCase); mixed case like iPhone is not.
            return !s.all { !it.isLetter() || it.isUpperCase() }
        }
        return false
    }

    // ---- loading ----

    private fun loadDictionary(): Dictionary? {
        val cacheDir = File(appContext.filesDir, "dict").apply { mkdirs() }
        val lines = try {
            appContext.assets.open("dict/en.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            Log.d(TAG, "no bundled dictionary")
            return null
        }
        // Cache keyed on the asset header + size, so a regenerated word list invalidates it.
        val tag = ((lines.firstOrNull() ?: "").hashCode() * 31 + lines.size).toUInt().toString(16)
        val cache = File(cacheDir, "en-$tag-v$FORMAT_VERSION.bin")
        try {
            if (cache.exists()) return Dictionary.fromBytes(cache.readBytes()).also {
                Log.d(TAG, "dictionary loaded from cache: ${it.sizeBytes / 1024} KB")
            }
        } catch (e: Exception) { cache.delete() }
        cacheDir.listFiles()?.forEach { if (it.name != cache.name) it.delete() }
        val start = System.currentTimeMillis()
        var maxCount = 1.0
        var minCount = Double.MAX_VALUE
        val parsed = ArrayList<Pair<String, Long>>(lines.size)
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val word = line.substring(0, sp)
            val count = line.substring(sp + 1).trim().toLongOrNull() ?: continue
            if (word.any { it.code > 0xFFFF }) continue
            parsed.add(word to count)
            if (count > maxCount) maxCount = count.toDouble()
            if (count < minCount) minCount = count.toDouble()
        }
        val lnMin = ln(minCount)
        val span = (ln(maxCount) - lnMin).coerceAtLeast(1e-9)
        val ranked = parsed.map { (w, c) -> w to (1 + ((ln(c.toDouble()) - lnMin) / span * 254).toInt()) }
        val built = Dictionary.build(ranked)
        try {
            val tmp = File(cache.path + ".tmp")
            tmp.writeBytes(built.toBytes())
            if (!tmp.renameTo(cache)) { cache.writeBytes(built.toBytes()); tmp.delete() }
        } catch (e: Exception) { /* cache is optional */ }
        Log.d(TAG, "dictionary built: ${parsed.size} words, ${built.sizeBytes / 1024} KB in ${System.currentTimeMillis() - start} ms")
        return built
    }

    companion object {
        private const val TAG = "SlimBoard"
        private const val FORMAT_VERSION = 1

        /** Applies the typed word's capitalisation pattern, and "i" → "I". */
        fun applyCase(typed: String, word: String): String {
            val fixed = if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word
            if (typed.isEmpty()) return fixed
            val letters = typed.filter { it.isLetter() }
            return when {
                letters.length > 1 && letters.all { it.isUpperCase() } -> fixed.uppercase()
                typed[0].isUpperCase() -> fixed.replaceFirstChar { it.uppercaseChar() }
                else -> fixed
            }
        }
    }
}
