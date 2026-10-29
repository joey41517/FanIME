package tw.fanime.zhuyin

import kotlin.math.log10

/**
 * 注音輸入引擎：組字（聲母、介音、韻母、聲調）、整句自動選詞、候選字、聯想詞、自動記錄字詞。
 *
 * - 可以省略聲調：注音放不進目前這個字時，自動斷成下一個字（沒打聲調的字不限聲調）
 * - 簡拼：只打聲母也行，例如「ㄏㄏ」→ 好好
 * - 打聲調或空白（一聲）也會完成一個字，此時只比對該聲調
 * - 選字不會馬上上字，只會「固定」那一段，候選字列移到下一個還沒固定的字；
 *   選到最後一段、或按空白／Enter 時才整串上字（仿照新酷音）
 */
class ZhuyinEngine(private val dict: ZhuyinDictionary) {

    /**
     * 候選字：從 [selectionStart] 開始涵蓋 [span] 個完整的字；
     * [withPending] 為 true 表示它也包含最後那個還在組的注音，[pendingBase] 是詞庫裡那個字的完整注音
     * （選了之後用它把最後一個字補完整，才能自動記錄）
     */
    data class Candidate(
        val text: String,
        val span: Int,
        val withPending: Boolean = false,
        val pendingBase: String? = null,
    )

    /** 使用者選字固定下來的一段：音節 [start] 到 [end]（不含）固定顯示成 [text] */
    private class Fixed(val start: Int, val end: Int, val text: String)

    private val syllables = mutableListOf<Syllable>()
    private val fixed = mutableListOf<Fixed>()
    private var initial = ""
    private var medial = ""
    private var final = ""
    private var learned: Pair<String, List<Syllable>>? = null

    /** 正在組的注音（還沒完成的字） */
    val pending: String get() = initial + medial + final
    val isEmpty: Boolean get() = syllables.isEmpty() && pending.isEmpty()
    val syllableCount: Int get() = syllables.size

    /** 候選字列對應的位置：最後一個固定段落之後 */
    private val selectionStart: Int get() = fixed.maxOfOrNull { it.end } ?: 0

    /**
     * 輸入一個注音符號。目前這個字放不進去時（該位置已經有注音，或順序不對），
     * 前一個字就算打完了，新的注音放到下一個字，不會取代前一個字。
     * 回傳 false 表示引擎不處理（例如沒有注音時按聲調）。
     */
    fun input(symbol: String): Boolean {
        when (symbol) {
            in INITIALS -> {
                // 聲母只能在字首：目前這個字已經有任何注音，就往後放
                if (pending.isNotEmpty()) completeSyllable(null)
                initial = symbol
            }
            in MEDIALS -> {
                if (medial.isNotEmpty() || final.isNotEmpty()) completeSyllable(null)
                medial = symbol
            }
            in FINALS -> {
                if (final.isNotEmpty()) completeSyllable(null)
                final = symbol
            }
            in TONES -> {
                if (pending.isEmpty()) return false
                completeSyllable(symbol)
            }
            else -> return false
        }
        return true
    }

    /** 空白鍵：有正在組的注音時當作一聲。回傳 false 表示沒有正在組的注音 */
    fun space(): Boolean {
        if (pending.isEmpty()) return false
        completeSyllable("")
        return true
    }

    /**
     * 完成目前這個字。沒打聲調、只有聲母的字（例如「ㄏ」）視為只打一半，
     * 查詢時只比對開頭，所以「ㄏㄏ」可以找到「好好」（簡拼）。
     * 有介音的字（例如「ㄑㄧ」）本身就可能是完整的字，不算只打一半。
     */
    private fun completeSyllable(tone: String?) {
        syllables += Syllable(pending, tone, partial = tone == null && medial.isEmpty() && final.isEmpty())
        initial = ""; medial = ""; final = ""
    }

    /**
     * 倒退：一次刪一個注音符號。正在組的注音刪光後，再按就把最後一個字拆回注音繼續刪
     * （有聲調的字先刪聲調），同時取消它所在的固定段落。回傳 false 表示引擎已經是空的。
     */
    fun backspace(): Boolean {
        if (pending.isEmpty()) {
            if (syllables.isEmpty()) return false
            val last = syllables.removeAt(syllables.lastIndex)
            fixed.removeAll { it.end > syllables.size }
            reopen(last.base)
            // 有聲調（含空白鍵的一聲）的字：這次 ⌫ 只刪掉聲調，注音留著
            if (last.tone != null) return true
        }
        when {
            final.isNotEmpty() -> final = ""
            medial.isNotEmpty() -> medial = ""
            initial.isNotEmpty() -> initial = ""
        }
        return true
    }

    /** 把一個字的注音拆回聲母、介音、韻母，放回「正在組」的狀態 */
    private fun reopen(base: String) {
        initial = ""; medial = ""; final = ""
        for (ch in base) {
            val s = ch.toString()
            when (s) {
                in INITIALS -> initial = s
                in MEDIALS -> medial = s
                in FINALS -> final = s
            }
        }
    }

    fun clear() {
        syllables.clear()
        fixed.clear()
        initial = ""; medial = ""; final = ""
    }

    /**
     * 顯示在輸入框裡的組字內容（仿照超注音）：使用者選字固定的段落顯示中文，
     * 其他部分一律維持顯示注音，不自動猜整句；要哪個字由使用者在候選字列確認。
     */
    fun preedit(): String = fixed.sortedBy { it.start }.joinToString("") { it.text } +
        syllables.subList(selectionStart, syllables.size).joinToString("") { it.toString() } + pending

    /**
     * 候選字列內容（都是從 [selectionStart] 開始），依序是：
     * 1. 最後一個字還在組時：涵蓋剩下全部注音、開頭符合的詞（例如「ㄨㄛˇ ㄇ」→ 我們）
     * 2. 從選字位置算起的詞，長的在前（最長的就是涵蓋剩下全部注音的詞）
     * 3. 選字位置的單字
     * 不列整句猜測的結果，要逐字或逐詞確認（仿照超注音）
     */
    fun candidates(): List<Candidate> {
        val from = selectionStart
        val rest = syllables.subList(from, syllables.size)
        val result = LinkedHashMap<String, Candidate>()
        if (pending.isNotEmpty() && rest.size < dict.maxLength) {
            for (item in dict.lookupPrefix(rest, pending, PREFIX_LIMIT)) {
                result.putIfAbsent(item.text, Candidate(item.text, rest.size, withPending = true, pendingBase = item.bases.last()))
            }
        }
        if (rest.isEmpty()) return result.values.toList()
        for (len in minOf(rest.size, dict.maxLength) downTo 1) {
            for (item in dict.lookup(rest.subList(0, len))) {
                result.putIfAbsent(item.text, Candidate(item.text, len))
            }
        }
        // 詞庫沒有這個讀音：直接給注音
        if (result.isEmpty()) rest[0].toString().let { result[it] = Candidate(it, 1) }
        return result.values.toList()
    }

    /**
     * 選了候選字。選到最後（涵蓋剩下所有的字）時整串上字，回傳要上字的文字；
     * 否則只固定這一段，回傳 null（繼續組字，候選字列移到下一段）。
     */
    fun pick(c: Candidate): String? {
        val start = selectionStart
        if (c.withPending) {
            // 包含還在組的注音：用詞庫裡的完整注音把最後一個字補完整（不限聲調），
            // 這樣整串的讀音才完整，可以照一般流程上字和自動記錄
            syllables += Syllable(c.pendingBase ?: pending, null)
            initial = ""; medial = ""; final = ""
            fixed += Fixed(start, syllables.size, c.text)
            return commitAll()
        }
        val end = (start + c.span).coerceAtMost(syllables.size)
        fixed += Fixed(start, end, c.text)
        if (end < syllables.size || pending.isNotEmpty()) return null
        return commitAll()
    }

    /**
     * 全部上字（空白或 Enter）：已固定的段落照用，其他部分用整句最佳結果；還沒完成的注音直接丟掉，避免上出半個注音。
     * 如果有用選字固定過段落，而且整串是詞庫沒有的詞，就自動記錄（仿照超注音）。
     */
    fun commitAll(): String {
        val segments = bestSegments()
        val text = segments.joinToString("") { it.text }
        maybeLearn(text, segments)
        clear()
        return text
    }

    /**
     * 自動記錄：逐字（逐段）確認完成的詞，也就是至少分兩段、每一段都是使用者選的、長度合理、詞庫還沒有。
     * 直接點整個詞的候選字只有一段，不會記錄；選一半就按 Enter 上字的，猜的部分也不記錄。
     * 簡拼（只打聲母）的字也照記，使用者詞庫是寬鬆比對，之後打完整注音也找得到。
     */
    private fun maybeLearn(text: String, segments: List<Segment>) {
        val reading = syllables.map { it.copy(partial = false) }
        val ok = segments.size >= 2 && segments.all { it.fixed } &&
            reading.size in 2..MAX_LEARN_LENGTH &&
            text.codePointCount(0, text.length) == reading.size &&
            dict.lookup(syllables).none { it.text == text }
        if (ok) {
            dict.addUserPhrase(text, reading)
            learned = text to reading
        }
    }

    /** 取出上一次自動記錄的詞（讓 Service 存檔、提示使用者），取一次就清掉 */
    fun takeLearned(): Pair<String, List<Syllable>>? = learned.also { learned = null }

    fun isUserPhrase(text: String) = dict.isUserPhrase(text)

    fun removeUserPhrase(text: String) = dict.removeUserPhrase(text)

    fun userPhrases() = dict.userPhrases()

    /** 從存檔載入使用者記錄的詞 */
    fun restoreUserPhrase(text: String, reading: List<Syllable>) = dict.addUserPhrase(text, reading)

    /** 聯想詞：上字之後，以剛上的字結尾去找常接在後面的詞 */
    fun associations(committed: String): List<String> {
        // 先用最後兩個字找，找不到再用最後一個字
        for (n in minOf(2, committed.length) downTo 1) {
            val found = dict.associations(committed.takeLast(n), ASSOCIATION_LIMIT)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** 整句選詞的一段：[text] 對應音節 [start] 到 [end]（不含）；[fixed] 表示是使用者選字固定的 */
    private class Segment(val text: String, val start: Int, val end: Int, val fixed: Boolean)

    /**
     * 整句選詞：動態規劃找出分數最高的斷詞方式。
     * 使用者固定的段落一定照用；其他部分長詞優先（長度平方加權），同樣長度看常用度，
     * 每一段扣固定成本；詞庫查不到的音節直接顯示注音並給很低的分數。
     */
    private fun bestSegments(): List<Segment> {
        val n = syllables.size
        if (n == 0) return emptyList()
        val score = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val pick = arrayOfNulls<Segment>(n + 1)
        score[0] = 0.0
        for (i in 1..n) {
            for (len in 1..minOf(i, maxOf(dict.maxLength, fixed.maxOfOrNull { it.end - it.start } ?: 0))) {
                val start = i - len
                if (score[start] == Double.NEGATIVE_INFINITY) continue
                val seg: Segment
                val s: Double
                val exact = fixed.firstOrNull { it.start == start && it.end == i }
                if (exact != null) {
                    seg = Segment(exact.text, start, i, fixed = true)
                    s = 0.0
                } else {
                    // 不能切開或跨過固定段落
                    if (fixed.any { it.start < i && start < it.end }) continue
                    val top = dict.lookup(syllables.subList(start, i)).firstOrNull()
                    when {
                        top != null -> {
                            seg = Segment(top.text, start, i, fixed = false)
                            s = len * len * LENGTH_WEIGHT + log10(top.freq + 1.0) - SEGMENT_COST
                        }
                        len == 1 -> {
                            seg = Segment(syllables[start].toString(), start, i, fixed = false)
                            s = -100.0
                        }
                        else -> continue
                    }
                }
                if (score[start] + s > score[i]) {
                    score[i] = score[start] + s
                    pick[i] = seg
                }
            }
        }
        val parts = ArrayList<Segment>()
        var i = n
        while (i > 0) {
            val seg = pick[i]!!
            parts += seg
            i = seg.start
        }
        return parts.asReversed()
    }

    companion object {
        private const val LENGTH_WEIGHT = 3.0
        /** 每多切一段的成本：避免切成很多常用單字（例如把「天氣」切成「天」「其」） */
        private const val SEGMENT_COST = 6.0
        /** 最後一個字只打一半時，最多列出幾個開頭符合的詞（搭配 ▾ 展開瀏覽） */
        private const val PREFIX_LIMIT = 80
        private const val ASSOCIATION_LIMIT = 30
        /** 自動記錄的詞最多幾個字，避免把整句話記成一個詞 */
        private const val MAX_LEARN_LENGTH = 6

        val INITIALS = setOf(
            "ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ", "ㄍ", "ㄎ", "ㄏ",
            "ㄐ", "ㄑ", "ㄒ", "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ",
        )
        val MEDIALS = setOf("ㄧ", "ㄨ", "ㄩ")
        val FINALS = setOf("ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ")
        val TONES = setOf("ˊ", "ˇ", "ˋ", "˙")
    }
}
