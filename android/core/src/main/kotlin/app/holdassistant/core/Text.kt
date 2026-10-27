package app.holdassistant.core

private val PUNCT = Regex("[^\\p{L}\\p{N}_\\s']")
private val SPACE = Regex("\\s+")

/** Lowercase, drop punctuation (keep apostrophes), collapse whitespace. */
fun normalize(text: String): String =
    text.lowercase().replace('’', '\'').replace(PUNCT, " ").replace(SPACE, " ").trim()

fun wordCount(text: String): Int = if (text.isBlank()) 0 else text.trim().split(' ').size

/** Indel similarity, 0-100 (same scale as rapidfuzz's fuzz.ratio). */
fun ratio(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 100.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return 200.0 * lcs(a, b) / (a.length + b.length)
}

/**
 * Best [ratio] of the shorter string against equal-length windows of the
 * longer one. Windows start at word boundaries, which is all STT cuts need.
 */
fun partialRatio(a: String, b: String): Double {
    val (short, long) = if (a.length <= b.length) a to b else b to a
    if (short.isEmpty()) return 0.0
    var best = 0.0
    for (start in long.indices) {
        if (start != 0 && long[start - 1] != ' ') continue
        val window = long.substring(start, minOf(start + short.length, long.length))
        best = maxOf(best, ratio(short, window))
        if (best >= 100.0 || start + short.length >= long.length) break
    }
    return best
}

/** How closely a new segment repeats an earlier one, 0-100. */
fun repeatScore(segment: String, earlier: String): Double {
    if (segment.isEmpty() || earlier.isEmpty()) return 0.0
    return maxOf(ratio(segment, earlier), partialRatio(segment, earlier))
}

/** Fuzzy whole-word search, also trying adjacent word pairs ("pine apple"). */
fun containsWord(text: String, word: String, threshold: Double = 80.0): Boolean {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    val candidates = tokens + tokens.zipWithNext { x, y -> x + y }
    return candidates.any { ratio(it, word) >= threshold }
}

private fun lcs(a: String, b: String): Int {
    var prev = IntArray(b.length + 1)
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}
