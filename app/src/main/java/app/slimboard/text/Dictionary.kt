package app.slimboard.text

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.TreeMap
import kotlin.math.min

/** A dictionary hit. [rank] is 1..255 (log-scaled frequency), [distance] the edit distance from the query. */
class Candidate(val word: String, val rank: Int, val distance: Int)

/**
 * Read-only word trie over a flat byte array, so the whole English dictionary costs one allocation
 * (~1.5 MB) and zero objects per node.
 *
 * Node layout (big-endian):
 *   u8 childCount, u8 rank (0 = not a word), then childCount × { u16 char, u32 childOffset }
 */
class Dictionary private constructor(private val data: ByteArray) {

    val sizeBytes: Int get() = data.size

    /** The serialised trie, suitable for caching on disk and reloading with [fromBytes]. */
    fun toBytes(): ByteArray = data

    fun rankOf(word: String): Int {
        var node = ROOT
        for (c in word) {
            node = findChild(node, c)
            if (node < 0) return 0
        }
        return rank(node)
    }

    fun contains(word: String): Boolean = rankOf(word) > 0

    /** Words starting with [prefix], best first. */
    fun completions(prefix: String, limit: Int): List<Candidate> {
        var node = ROOT
        for (c in prefix) {
            node = findChild(node, c)
            if (node < 0) return emptyList()
        }
        val out = ArrayList<Candidate>()
        val sb = StringBuilder(prefix)
        collect(node, sb, out, 0)
        out.sortByDescending { it.rank }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    private fun collect(node: Int, sb: StringBuilder, out: MutableList<Candidate>, depth: Int) {
        if (depth > MAX_COMPLETION_DEPTH) return
        val n = childCount(node)
        for (i in 0 until n) {
            val child = childOffset(node, i)
            sb.append(childChar(node, i))
            val r = rank(child)
            if (r > 0) out.add(Candidate(sb.toString(), r, 0))
            collect(child, sb, out, depth + 1)
            sb.setLength(sb.length - 1)
        }
    }

    /**
     * Words within [maxDist] edits (insert / delete / substitute) of [word]. Bounded trie walk with
     * one Levenshtein row per depth; rows are reused, nothing is allocated per node.
     */
    fun corrections(word: String, maxDist: Int, out: MutableList<Candidate>) {
        val m = word.length
        if (m == 0) return
        val maxDepth = m + maxDist
        val rows = Array(maxDepth + 1) { IntArray(m + 1) }
        for (j in 0..m) rows[0][j] = j
        val buf = CharArray(maxDepth)
        walk(ROOT, 0, word, m, maxDist, maxDepth, rows, buf, out)
    }

    private fun walk(
        node: Int, depth: Int, word: String, m: Int, maxDist: Int, maxDepth: Int,
        rows: Array<IntArray>, buf: CharArray, out: MutableList<Candidate>,
    ) {
        val prev = rows[depth]
        val cur = rows[depth + 1]
        val n = childCount(node)
        for (i in 0 until n) {
            val c = childChar(node, i)
            cur[0] = depth + 1
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (word[j - 1] == c) 0 else 1
                var v = prev[j - 1] + cost
                val del = prev[j] + 1
                if (del < v) v = del
                val ins = cur[j - 1] + 1
                if (ins < v) v = ins
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) continue
            buf[depth] = c
            val child = childOffset(node, i)
            val r = rank(child)
            if (r > 0 && cur[m] <= maxDist) out.add(Candidate(String(buf, 0, depth + 1), r, cur[m]))
            if (depth + 1 < maxDepth) walk(child, depth + 1, word, m, maxDist, maxDepth, rows, buf, out)
        }
    }

    // ---- byte access ----

    private fun childCount(node: Int) = data[node].toInt() and 0xFF
    private fun rank(node: Int) = data[node + 1].toInt() and 0xFF
    private fun childChar(node: Int, i: Int): Char {
        val p = node + 2 + i * 6
        return (((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)).toChar()
    }
    private fun childOffset(node: Int, i: Int): Int {
        val p = node + 4 + i * 6
        return ((data[p].toInt() and 0xFF) shl 24) or ((data[p + 1].toInt() and 0xFF) shl 16) or
            ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
    }
    private fun findChild(node: Int, c: Char): Int {
        val n = childCount(node)
        for (i in 0 until n) if (childChar(node, i) == c) return childOffset(node, i)
        return -1
    }

    companion object {
        private const val ROOT = 0
        private const val MAX_COMPLETION_DEPTH = 16

        fun fromBytes(bytes: ByteArray) = Dictionary(bytes)

        /** Builds the byte trie from (word, rank 1..255) pairs. Words must be BMP-only. */
        fun build(words: Iterable<Pair<String, Int>>): Dictionary {
            class N { val children = TreeMap<Char, N>(); var rank = 0; var offset = 0 }
            val root = N()
            for ((w, r) in words) {
                if (w.isEmpty() || r <= 0) continue
                var n = root
                for (c in w) n = n.children.getOrPut(c) { N() }
                n.rank = min(255, r)
            }
            var next = 0
            fun assign(n: N) {
                n.offset = next
                next += 2 + 6 * n.children.size
                for (child in n.children.values) assign(child)
            }
            assign(root)
            val bos = ByteArrayOutputStream(next)
            val out = DataOutputStream(bos)
            fun write(n: N) {
                out.writeByte(n.children.size)
                out.writeByte(n.rank)
                for ((c, child) in n.children) {
                    out.writeChar(c.code)
                    out.writeInt(child.offset)
                }
                for (child in n.children.values) write(child)
            }
            write(root)
            out.flush()
            return Dictionary(bos.toByteArray())
        }
    }
}
