package tw.fanime.zhuyin

import android.content.res.AssetManager
import java.io.InputStream

/**
 * 新酷音詞庫（libchewing-data，LGPL-2.1-or-later）。
 *
 * 兩種索引：
 * - [byBase]：去掉聲調的完整注音，例如「台灣」→「ㄊㄞ ㄨㄢ」。打完整注音時用，最快
 * - [byAbbr]：每個字只取第一個注音符號，例如「好好」→「ㄏ ㄏ」。
 *   有字只打一半（簡拼，例如只打聲母）時用，查到後再逐字比對開頭
 */
class ZhuyinDictionary private constructor(
    private val byBase: HashMap<String, Array<Item>>,
    private val byAbbr: HashMap<String, Array<Item>>,
    /** 依文字排序的所有詞（不重複），用來找聯想詞 */
    private val sortedTexts: Array<String>,
    private val textFreq: HashMap<String, Int>,
    maxLength: Int,
) {
    /**
     * [bases] 是每個字不含聲調的注音；[tones] 是每個字的聲調：""（一聲）、"ˊ"、"ˇ"、"ˋ"、"˙"。
     * 使用者自己記錄的詞，沒打聲調的字存成 null（不限聲調）
     */
    class Item(val text: String, val freq: Int, val bases: Array<String>, val tones: Array<String?>)

    /** 詞庫中最長的詞有幾個字 */
    var maxLength: Int = maxLength
        private set

    // 使用者記錄的詞：數量少，直接存在記憶體，查詢時排在內建詞庫前面
    private val userItems = ArrayList<Item>()
    private val userTexts = LinkedHashSet<String>()

    /** 記錄使用者的詞（例如長按候選字）。已經記過的詞不重複加入 */
    fun addUserPhrase(text: String, syllables: List<Syllable>) {
        val bases = Array(syllables.size) { syllables[it].base }
        if (userItems.any { it.text == text && it.bases.contentEquals(bases) }) return
        userItems.add(0, Item(text, USER_FREQ, bases, Array(syllables.size) { syllables[it].tone }))
        userTexts += text
        maxLength = maxOf(maxLength, syllables.size)
    }

    fun isUserPhrase(text: String) = text in userTexts

    /** 從使用者詞庫刪除（所有讀音都刪）。回傳 false 表示不是使用者記錄的詞 */
    fun removeUserPhrase(text: String): Boolean {
        if (!userItems.removeAll { it.text == text }) return false
        userTexts.remove(text)
        return true
    }

    /** 使用者記錄的詞（存檔用），依記錄時間由舊到新 */
    fun userPhrases(): List<Pair<String, List<Syllable>>> = userItems.reversed().map { item ->
        item.text to item.bases.indices.map { Syllable(item.bases[it], item.tones[it]) }
    }

    /**
     * 查詢一串字的注音。聲調為 null 的字不限聲調；[Syllable.partial] 的字只比對開頭（簡拼）。
     * 結果依常用度由高到低，使用者記錄的詞在最前面。
     */
    fun lookup(syllables: List<Syllable>): List<Item> {
        val user = userItems.filter { matchesUser(it, syllables) }
        val builtIn = if (syllables.none { it.partial }) {
            byBase[syllables.joinToString(" ") { it.base }]?.filter { matchesTones(it, syllables) }.orEmpty()
        } else {
            byAbbr[abbrKey(syllables.map { it.base })]?.filter { matches(it, syllables) }.orEmpty()
        }
        return if (user.isEmpty()) builtIn else (user + builtIn).distinctBy { it.text }
    }

    /** 最後一個字只打一半（[partial]）時的查詢，回傳常用度最高的 [limit] 個詞 */
    fun lookupPrefix(complete: List<Syllable>, partial: String, limit: Int): List<Item> =
        lookup(complete + Syllable(partial, null, partial = true))
            .sortedByDescending { it.freq }
            .distinctBy { it.text }
            .take(limit)

    /** 聯想詞：以 [prefix] 開頭的較長詞，回傳去掉 prefix 後剩下的部分，依常用度排序 */
    fun associations(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        // 使用者記錄的詞排在最前面（最近記錄的優先）
        val user = userTexts.reversed().filter { it.length > prefix.length && it.startsWith(prefix) }
        val found = ArrayList<String>()
        var i = lowerBound(sortedTexts, prefix)
        while (i < sortedTexts.size && sortedTexts[i].startsWith(prefix)) {
            if (sortedTexts[i].length > prefix.length) found += sortedTexts[i]
            i++
        }
        return (user + found.sortedByDescending { textFreq[it] ?: 0 })
            .map { it.substring(prefix.length) }
            .distinct()
            .take(limit)
    }

    private fun matches(item: Item, syllables: List<Syllable>): Boolean {
        if (item.bases.size != syllables.size) return false
        for (i in syllables.indices) {
            val s = syllables[i]
            val ok = if (s.partial) item.bases[i].startsWith(s.base) else item.bases[i] == s.base
            if (!ok) return false
        }
        return matchesTones(item, syllables)
    }

    /**
     * 使用者記錄的詞用寬鬆比對：每個字只要一邊是另一邊的開頭就算符合
     * （例如記錄時打「ㄌㄧㄝ」，之後打「ㄌㄧ」或只打「ㄌ」也找得到；反過來也行）。
     * 使用者的詞數量少，寬鬆比對不會讓整句選詞變亂。
     */
    private fun matchesUser(item: Item, syllables: List<Syllable>): Boolean {
        if (item.bases.size != syllables.size) return false
        for (i in syllables.indices) {
            val a = item.bases[i]
            val b = syllables[i].base
            if (!a.startsWith(b) && !b.startsWith(a)) return false
        }
        return matchesTones(item, syllables)
    }

    /** 兩邊任一個聲調是 null（不限聲調）就算符合 */
    private fun matchesTones(item: Item, syllables: List<Syllable>): Boolean {
        for (i in syllables.indices) {
            val t = syllables[i].tone ?: continue
            val it = item.tones[i] ?: continue
            if (it != t) return false
        }
        return true
    }

    companion object {
        private const val DIR = "chewing"
        /** 使用者記錄的詞的常用度：比內建詞庫最高的還高，選詞時優先 */
        private const val USER_FREQ = 10_000_000
        private val TONE_MARKS = charArrayOf('ˊ', 'ˇ', 'ˋ', '˙')

        /** 簡拼索引的 key：每個字的第一個注音符號 */
        private fun abbrKey(bases: List<String>) = bases.joinToString(" ") { it.take(1) }

        fun load(assets: AssetManager): ZhuyinDictionary = load { path -> assets.open(path) }

        /** [open] 依相對路徑（例如 "chewing/tsi.csv"）開啟檔案；單元測試會直接讀專案裡的檔案 */
        fun load(open: (String) -> InputStream): ZhuyinDictionary {
            val byBase = HashMap<String, ArrayList<Item>>(200_000)
            val byAbbr = HashMap<String, ArrayList<Item>>(100_000)
            val seen = HashSet<String>(200_000) // 「文字|完整讀音」，避免重複
            val textFreq = HashMap<String, Int>(200_000)
            var maxLen = 1

            fun add(text: String, freq: Int, reading: String) {
                if (!seen.add("$text|$reading")) return
                val syls = reading.split(' ')
                val bases = Array(syls.size) { syls[it].trimEnd(*TONE_MARKS) }
                val tones = Array<String?>(syls.size) { i -> syls[i].lastOrNull()?.takeIf { it in TONE_MARKS }?.toString() ?: "" }
                val item = Item(text, freq, bases, tones)
                byBase.getOrPut(bases.joinToString(" ")) { ArrayList(2) }.add(item)
                byAbbr.getOrPut(abbrKey(bases.asList())) { ArrayList(4) }.add(item)
                if (freq >= (textFreq[text] ?: -1)) textFreq[text] = freq
                maxLen = maxOf(maxLen, syls.size)
            }

            // tsi.csv：詞,常用度,注音（字與字之間以空白分隔）
            readCsv(open, "$DIR/tsi.csv") { text, freq, reading -> add(text, freq, reading) }
            // word.csv：字,0,注音。補上 tsi.csv 沒收錄的單字讀音
            readCsv(open, "$DIR/word.csv") { text, _, reading -> add(text, 0, reading) }

            fun freeze(m: HashMap<String, ArrayList<Item>>) =
                HashMap<String, Array<Item>>(m.size).also { out ->
                    for ((k, v) in m) out[k] = v.sortedByDescending { it.freq }.toTypedArray()
                }
            val sortedTexts = textFreq.keys.toTypedArray().also { it.sort() }
            return ZhuyinDictionary(freeze(byBase), freeze(byAbbr), sortedTexts, textFreq, maxLen)
        }

        private fun lowerBound(sorted: Array<String>, key: String): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private inline fun readCsv(open: (String) -> InputStream, path: String, onRow: (String, Int, String) -> Unit) {
            open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val a = line.indexOf(',')
                    val b = line.indexOf(',', a + 1)
                    if (a <= 0 || b < 0) continue
                    val freq = line.substring(a + 1, b).toIntOrNull() ?: continue
                    onRow(line.substring(0, a), freq, line.substring(b + 1).trim())
                }
            }
        }
    }
}

/**
 * 一個字的注音：[base] 是不含聲調的注音；[tone] 為 null 表示沒打聲調（不限聲調），"" 是一聲；
 * [partial] 表示這個字只打了一半（例如只有聲母），查詢時只比對開頭（簡拼）。
 */
data class Syllable(val base: String, val tone: String?, val partial: Boolean = false) {
    /** 顯示用（詞庫查不到時直接顯示注音） */
    override fun toString() = base + (tone ?: "")

    /** 存檔用：「注音|聲調」，聲調 null 存成 "-"、一聲存成 "1" */
    fun encode(): String = base + "|" + when (tone) { null -> "-"; "" -> "1"; else -> tone }

    companion object {
        fun decode(s: String): Syllable? {
            val i = s.indexOf('|')
            if (i <= 0) return null
            val t = s.substring(i + 1)
            return Syllable(s.substring(0, i), when (t) { "-" -> null; "1" -> ""; else -> t })
        }
    }
}
