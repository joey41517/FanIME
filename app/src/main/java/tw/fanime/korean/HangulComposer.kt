package tw.fanime.korean

/**
 * 韓文兩套式組字：把字母（자모）組成音節（초성 + 중성 + 종성）。
 *
 * 只有「目前正在組的這個字」是組字中（[composing]），組好的字由 [input] 的回傳值交給呼叫端上字。
 * 這個字的按鍵記在 [keys]，倒退時拿掉最後一個按鍵再重新組一次，所以可以一個字母一個字母刪
 * （한 → 하 → ㅎ），複合母音、複合終聲也會正確拆開。
 */
class HangulComposer {

    private var cho = -1   // 初聲索引（CHO），-1 表示沒有
    private var jung = -1  // 中聲索引（JUNG）
    private var jong = 0   // 終聲索引（JONG），0 表示沒有
    private val keys = ArrayList<Char>()

    val isEmpty: Boolean get() = keys.isEmpty()

    /** 目前正在組的字（還沒確定） */
    val composing: String get() = render()

    /**
     * 輸入一個字母。回傳因為這次輸入而「組好」的字（要上字），沒有則回傳空字串；
     * 目前正在組的字看 [composing]。
     */
    fun input(jamo: Char): String {
        val out = StringBuilder()
        feed(jamo, out)
        return out.toString()
    }

    /** 倒退一個字母。回傳 false 表示沒有正在組的字（呼叫端照常刪前一個字） */
    fun backspace(): Boolean {
        if (keys.isEmpty()) return false
        val replay = keys.dropLast(1)
        reset()
        // 同一個字的按鍵重新組一次不會產生組好的字
        for (k in replay) feed(k, StringBuilder())
        return true
    }

    /** 結束組字：回傳目前的字並清空 */
    fun commit(): String = render().also { reset() }

    fun clear() = reset()

    private fun reset() {
        cho = -1; jung = -1; jong = 0
        keys.clear()
    }

    private fun feed(j: Char, out: StringBuilder) {
        val c = CHO.indexOf(j)
        val v = JUNG.indexOf(j)
        when {
            c >= 0 -> consonant(j, c, out)
            v >= 0 -> vowel(j, v, out)
            else -> { out.append(commit()).append(j) }
        }
    }

    private fun consonant(j: Char, c: Int, out: StringBuilder) {
        when {
            // 空的：當初聲
            cho < 0 && jung < 0 -> { cho = c; keys += j }
            // 只有初聲（或只有母音）：不能再接子音 → 前一個字結束，這個子音開新的字
            jung < 0 || cho < 0 -> { out.append(commit()); cho = c; keys += j }
            // 初聲 + 中聲：當終聲（ㄸㅃㅉ 不能當終聲）
            jong == 0 -> {
                val f = JONG.indexOf(j)
                if (f > 0) { jong = f; keys += j } else { out.append(commit()); cho = c; keys += j }
            }
            // 已有終聲：能組成複合終聲就組，否則開新的字
            else -> {
                val compound = COMPOUND_JONG["${JONG[jong]}$j"]
                if (compound != null) { jong = JONG.indexOf(compound); keys += j } else { out.append(commit()); cho = c; keys += j }
            }
        }
    }

    private fun vowel(j: Char, v: Int, out: StringBuilder) {
        when {
            jung < 0 -> { jung = v; keys += j } // 空的或只有初聲
            jong == 0 -> {
                // 已有中聲：能組成複合母音就組，否則前一個字結束，母音單獨開新的字
                val compound = COMPOUND_JUNG["${JUNG[jung]}$j"]
                if (compound != null) { jung = JUNG.indexOf(compound); keys += j } else { out.append(commit()); jung = v; keys += j }
            }
            else -> {
                // 終聲移位：最後一個終聲子音變成下一個字的初聲（한 + ㅏ → 하나）
                val last = keys.last()
                keys.removeAt(keys.lastIndex)
                val split = SPLIT_JONG[JONG[jong]]
                jong = if (split != null) JONG.indexOf(split.first) else 0
                out.append(render())
                reset()
                cho = CHO.indexOf(last); jung = v
                keys += last; keys += j
            }
        }
    }

    private fun render(): String = when {
        cho >= 0 && jung >= 0 -> (0xAC00 + (cho * 21 + jung) * 28 + jong).toChar().toString()
        cho >= 0 -> CHO[cho].toString()
        jung >= 0 -> JUNG[jung].toString()
        else -> ""
    }

    companion object {
        const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        /** 索引 0 是「沒有終聲」 */
        const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        private val COMPOUND_JUNG = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
            "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ',
            "ㅡㅣ" to 'ㅢ',
        )
        private val COMPOUND_JONG = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
            "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ',
            "ㅂㅅ" to 'ㅄ',
        )
        /** 複合終聲拆開：留下的終聲、移到下一個字的初聲 */
        private val SPLIT_JONG = COMPOUND_JONG.entries.associate { (k, v) -> v to (k[0] to k[1]) }
    }
}
