package tw.fanime.korean

/**
 * 韓文輸入：用 [HangulComposer] 組字，並把同一個詞（到空白或標點為止）整個維持在組字中，
 * 這樣字詞提示選了之後可以整個詞換掉。
 */
class KoreanEngine {

    private val composer = HangulComposer()
    /** 這個詞裡已經組好的字 */
    private val word = StringBuilder()

    /** 詞表在背景載入，載入完成前沒有提示 */
    @Volatile var dictionary: KoreanDictionary? = null

    val isEmpty: Boolean get() = word.isEmpty() && composer.isEmpty

    /** 輸入框裡顯示的組字內容：這個詞已組好的字 + 正在組的字 */
    fun preedit(): String = word.toString() + composer.composing

    fun input(jamo: Char) {
        word.append(composer.input(jamo))
    }

    /** 倒退一個字母：正在組的字刪光後，把前一個字拆回字母繼續刪。回傳 false 表示已經是空的 */
    fun backspace(): Boolean {
        if (composer.backspace()) return true
        if (word.isEmpty()) return false
        val last = word.last()
        word.setLength(word.length - 1)
        // 把前一個字的按鍵重新組回來（同一個字的按鍵不會產生組好的字），再刪最後一個字母
        for (k in Hangul.keystrokes(last.toString())) composer.input(k)
        composer.backspace()
        return true
    }

    fun clear() {
        word.setLength(0)
        composer.clear()
    }

    /** 上字：回傳整個詞並清空 */
    fun commit(chosen: String = preedit()): String {
        clear()
        return chosen
    }

    /** 字詞提示：第一個是打的詞本身，後面是按鍵序列開頭符合的常用詞 */
    fun suggestions(): List<String> {
        if (isEmpty) return emptyList()
        val typed = preedit()
        val more = dictionary?.complete(Hangul.keystrokes(typed), LIMIT).orEmpty()
        return (listOf(typed) + more).distinct()
    }

    companion object {
        private const val LIMIT = 40
    }
}
