package tw.fanime.english

import android.content.res.AssetManager
import java.io.InputStream

/**
 * 英文詞表（AOSP LatinIME，Apache License 2.0），依小寫排序，用來做前綴補完。
 * 另外記錄使用者打過的字：用過的字排序往前，詞表沒有的字也會出現在提示裡。
 */
class EnglishDictionary private constructor(
    private val lower: Array<String>,
    private val words: Array<String>,
    private val freq: IntArray,
) {
    /** 使用者打過的字（小寫 → 原本的寫法、次數） */
    private val userWords = HashMap<String, Pair<String, Int>>()

    fun contains(word: String): Boolean {
        val i = lowerBound(word.lowercase())
        return i < lower.size && lower[i] == word.lowercase()
    }

    /** 記錄使用者打了這個字一次 */
    fun recordUse(word: String) {
        val key = word.lowercase()
        val (_, count) = userWords[key] ?: (word to 0)
        userWords[key] = word to count + 1
    }

    fun restoreUse(word: String, count: Int) {
        userWords[word.lowercase()] = word to count
    }

    /** 重新載入前清空（設定頁還原備份後） */
    fun clearUserWords() = userWords.clear()

    /** 存檔用：(字, 次數) */
    fun userWordCounts(): List<Pair<String, Int>> = userWords.values.toList()

    /**
     * 以 [prefix] 開頭的字（不分大小寫），依「常用度 + 使用者打過的次數加權」排序。
     * 不包含和 prefix 完全相同的字（那個由呼叫端放在第一個）。
     */
    fun complete(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val scored = HashMap<String, Pair<String, Int>>() // 小寫 → (寫法, 分數)
        var i = lowerBound(p)
        while (i < lower.size && lower[i].startsWith(p)) {
            val key = lower[i]
            if (key != p) {
                val s = freq[i] + userBonus(key)
                val old = scored[key]
                // 同一個字有多種大小寫（例如 may / May）時取分數高的
                if (old == null || s > old.second) scored[key] = words[i] to s
            }
            i++
        }
        // 詞表沒有、使用者打過的字（例如專有名詞）：給中等的基礎分數，用過一次就會出現在提示裡
        for ((key, v) in userWords) {
            if (key != p && key.startsWith(p) && key !in scored) scored[key] = v.first to USER_ONLY_BASE + userBonus(key)
        }
        return scored.values.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** 打過越多次排越前面，最多加到 5 次 */
    private fun userBonus(key: String): Int = (userWords[key]?.second ?: 0).coerceAtMost(5) * USER_WEIGHT

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = lower.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** 使用者每打一次加的分數（常用度範圍是 0–255） */
        private const val USER_WEIGHT = 40
        /** 詞表沒有的字的基礎分數（大約是一般常用字的程度） */
        private const val USER_ONLY_BASE = 150

        fun load(assets: AssetManager): EnglishDictionary = load { assets.open("english/words.tsv") }

        fun load(open: () -> InputStream): EnglishDictionary {
            val l = ArrayList<String>(170_000)
            val w = ArrayList<String>(170_000)
            val f = ArrayList<Int>(170_000)
            open().bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    w += word
                    l += word.lowercase()
                    f += line.substring(tab + 1).toIntOrNull() ?: 0
                }
            }
            // 檔案已依小寫排序；保險起見再排一次，確保二分搜尋正確
            val order = l.indices.sortedBy { l[it] }
            return EnglishDictionary(
                Array(order.size) { l[order[it]] },
                Array(order.size) { w[order[it]] },
                IntArray(order.size) { f[order[it]] },
            )
        }
    }
}
