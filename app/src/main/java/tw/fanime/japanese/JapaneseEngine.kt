package tw.fanime.japanese

/**
 * 日文輸入引擎：羅馬拼音 → 平假名 → 漢字轉換。
 *
 * - 打字時輸入框顯示平假名（加上還沒轉換的拼音），候選字列顯示轉換結果和預測
 * - 整句轉換：動態規劃找出成本總和最低的斷詞（Mozc 的 word cost；動詞是語幹 + 語尾分開收錄）
 * - 選字和注音一樣：沒選到最後只固定那一段，選到最後才整串上字；選過的寫法會記住，下次優先
 */
class JapaneseEngine(private val dict: JapaneseDictionary) {

    /** 候選字：從選字位置開始涵蓋 [span] 個假名；[predicted] 是預測的較長詞（讀音比打的長） */
    data class Candidate(val text: String, val span: Int, val predicted: Boolean = false)

    private class Fixed(val start: Int, val end: Int, val text: String, val reading: String)

    private val kana = StringBuilder()
    private var romaji = ""
    private val fixed = mutableListOf<Fixed>()
    private var learned: Pair<String, String>? = null

    val isEmpty: Boolean get() = kana.isEmpty() && romaji.isEmpty()

    private val selectionStart: Int get() = fixed.maxOfOrNull { it.end } ?: 0

    /** 輸入一個字母（羅馬拼音） */
    fun input(letter: String) {
        val (out, rest) = Romaji.feed(romaji + letter)
        kana.append(out)
        romaji = rest
    }

    /** 直接輸入假名或符號（例如 ー），會先把還沒轉換的拼音收尾 */
    fun inputKana(s: String) {
        flushRomaji()
        kana.append(s)
    }

    private fun flushRomaji() {
        kana.append(Romaji.flush(romaji))
        romaji = ""
    }

    /** 倒退：先刪拼音，再刪假名（連帶取消包含它的固定段落）。回傳 false 表示已經是空的 */
    fun backspace(): Boolean {
        when {
            romaji.isNotEmpty() -> romaji = romaji.dropLast(1)
            kana.isNotEmpty() -> {
                kana.setLength(kana.length - 1)
                fixed.removeAll { it.end > kana.length }
            }
            else -> return false
        }
        return true
    }

    fun clear() {
        kana.setLength(0)
        romaji = ""
        fixed.clear()
    }

    /** 輸入框顯示：固定的段落顯示選好的字，其餘顯示平假名，最後接還沒轉換的拼音 */
    fun preedit(): String {
        val from = selectionStart
        return fixedText(from) + kana.substring(from) + romaji
    }

    private fun fixedText(until: Int) = fixed.filter { it.end <= until }.sortedBy { it.start }.joinToString("") { it.text }

    /** 選字位置之後的假名（還沒轉換的拼音先預覽收尾，例如結尾的 n 當成 ん） */
    private fun rest(): String = kana.substring(selectionStart) + Romaji.flush(romaji)

    /**
     * 候選字列內容（從選字位置開始），依序是：
     * 1. 剩下部分的整句轉換
     * 2. 預測：讀音以剩下部分開頭的較長詞（例如 ありが → ありがとう）
     * 3. 從選字位置算起的詞，長的在前
     * 4. 平假名、片假名
     */
    fun candidates(): List<Candidate> {
        val r = rest()
        if (r.isEmpty()) return emptyList()
        val result = LinkedHashMap<String, Candidate>()
        fun add(text: String, span: Int, predicted: Boolean = false) =
            result.putIfAbsent(text, Candidate(text, span, predicted))
        add(convert(r), r.length)
        for (e in dict.predict(r, PREDICT_LIMIT)) add(e.surface, r.length, predicted = true)
        for (len in minOf(r.length, MAX_READING) downTo 1) {
            for (e in ranked(r.substring(0, len))) add(e.surface, len)
        }
        add(r, r.length)
        add(Romaji.toKatakana(r), r.length)
        return result.values.toList()
    }

    /**
     * 選了候選字。選到最後時整串上字並回傳文字；否則只固定這一段，回傳 null。
     * 選過的寫法會記住（同樣的讀音下次優先），可以用 [takeLearned] 取出存檔。
     */
    fun pick(c: Candidate): String? {
        flushRomaji()
        val start = selectionStart
        val end = (start + c.span).coerceAtMost(kana.length)
        val reading = kana.substring(start, end)
        // 預測的詞讀音比打的長，記了會讓整句轉換多出字，不記錄；平假名本身也不需要記錄
        if (!c.predicted && c.text != reading) {
            dict.prefer(reading, c.text)
            learned = reading to c.text
        }
        fixed += Fixed(start, end, c.text, reading)
        if (end < kana.length) return null
        val text = fixedText(kana.length)
        clear()
        return text
    }

    /** 空白鍵：整串轉換後上字（尊重已固定的段落） */
    fun commitConverted(): String {
        flushRomaji()
        val from = selectionStart
        val text = fixedText(from) + convert(kana.substring(from))
        clear()
        return text
    }

    /** Enter：照平假名上字（已固定的段落用選好的字） */
    fun commitKana(): String {
        flushRomaji()
        val from = selectionStart
        val text = fixedText(from) + kana.substring(from)
        clear()
        return text
    }

    /** 取出上一次記住的選字（讀音 → 寫法），讓 Service 存檔；取一次就清掉 */
    fun takeLearned(): Pair<String, String>? = learned.also { learned = null }

    fun restorePreference(reading: String, surface: String) = dict.prefer(reading, surface)

    fun preferences() = dict.preferences()

    /** 重新載入前清空（設定頁還原備份後） */
    fun clearPreferences() = dict.clearPreferences()

    private class Ranked(val surface: String, val cost: Int)

    /**
     * 同讀音的詞依調整後的成本排序。Mozc 原本靠詞性連接成本平衡寫法，這裡沒有連接成本，所以做兩個調整：
     * 1. 兩個假名以上的純平假名寫法加 [KANA_PENALTY]：不加的話「わたし」會勝過「私」
     * 2. 同讀音同時有平假名和片假名寫法時，片假名排在平假名後面：詞庫裡「デス」成本 0、「です」成本 40，
     *    不調的話助動詞會變成片假名；沒有平假名寫法的外來語（例如コーヒー）不受影響
     */
    private fun ranked(reading: String): List<Ranked> {
        val entries = dict.lookup(reading)
        val katakana = Romaji.toKatakana(reading)
        val long = reading.length >= 2
        var hiraganaCost: Int? = null
        val adjusted = entries.map { e ->
            val c = if (long && e.surface == reading) e.cost + KANA_PENALTY else e.cost
            if (e.surface == reading) hiraganaCost = c
            Ranked(e.surface, c)
        }
        val h = hiraganaCost
        return adjusted.map { r ->
            if (long && h != null && r.surface == katakana && r.cost <= h) Ranked(r.surface, h + 1) else r
        }.sortedBy { it.cost }
    }

    /**
     * 整句轉換：動態規劃找出成本總和最低的斷詞。
     * 每個詞都有成本（Mozc word cost），切越多段成本越高，自然傾向長詞；
     * 詞庫查不到的假名單獨成一段、成本很高，原樣顯示。
     */
    fun convert(s: String): String {
        val n = s.length
        if (n == 0) return ""
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val from = IntArray(n + 1)
        val text = arrayOfNulls<String>(n + 1)
        best[0] = 0
        for (i in 1..n) {
            for (len in 1..minOf(i, MAX_READING)) {
                val start = i - len
                if (best[start] == Int.MAX_VALUE) continue
                val top = ranked(s.substring(start, i)).firstOrNull()
                val (t, c) = when {
                    top != null -> top.surface to top.cost + SEGMENT_COST
                    len == 1 -> s.substring(start, i) to UNKNOWN_COST
                    else -> continue
                }
                if (best[start] + c < best[i]) {
                    best[i] = best[start] + c
                    from[i] = start
                    text[i] = t
                }
            }
        }
        val parts = ArrayList<String>()
        var i = n
        while (i > 0) {
            parts += text[i]!!
            i = from[i]
        }
        return parts.asReversed().joinToString("")
    }

    companion object {
        /** 詞的讀音最多看幾個假名 */
        private const val MAX_READING = 16
        private const val PREDICT_LIMIT = 20
        /**
         * 每一段額外的成本。Mozc 原本靠詞性連接成本懲罰亂切，但詞庫裡很多助詞、平假名條目成本是 0，
         * 沒有連接成本時會被切成一堆小段（にほんご → に|ほん|ご）。用固定的段落成本代替連接成本。
         */
        private const val SEGMENT_COST = 4000
        /** 純平假名寫法的額外成本，見 [ranked] */
        private const val KANA_PENALTY = 2000
        /** 詞庫查不到的單一假名 */
        private const val UNKNOWN_COST = 10_000
    }
}
