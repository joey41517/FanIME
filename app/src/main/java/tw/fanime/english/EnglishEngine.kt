package tw.fanime.english

/**
 * 英文字詞提示：記住目前正在打的字，提供前綴補完的候選字。
 * 候選字第一個是使用者打的字本身（點了照原樣上字），後面是補完的字，大小寫跟著使用者打的走。
 */
class EnglishEngine(private val dict: EnglishDictionary) {

    private val word = StringBuilder()

    val current: String get() = word.toString()
    val isEmpty: Boolean get() = word.isEmpty()

    /** 游標在字裡的位置（0..長度）；使用者把游標點到字中間時，新的字母插在這裡 */
    var cursor: Int = 0
        set(value) { field = value.coerceIn(0, word.length) }

    /** 會被當成「字的一部分」的字元：字母和縮寫用的撇號（can't） */
    fun accepts(ch: String): Boolean =
        ch.length == 1 && (ch[0].isLetter() || (ch == "'" && word.isNotEmpty()))

    fun append(ch: String) {
        word.insert(cursor, ch)
        cursor += ch.length
    }

    /** 刪游標前的字母；游標在字首時回傳 false（交給輸入框刪字前面的字） */
    fun backspace(): Boolean {
        if (word.isEmpty() || cursor == 0) return false
        word.deleteCharAt(cursor - 1)
        cursor -= 1
        return true
    }

    fun clear() {
        word.setLength(0)
        cursor = 0
    }

    fun suggestions(): List<String> {
        if (word.isEmpty()) return emptyList()
        val typed = current
        return listOf(typed) + dict.complete(typed, LIMIT).map { matchCase(it, typed) }
            .filter { !it.equals(typed, ignoreCase = true) }
            .distinct()
    }

    /** 上字：記錄使用次數，清掉目前的字，回傳要上字的文字 */
    fun commit(chosen: String = current): String {
        if (chosen.length >= 2 && chosen.all { it.isLetter() || it == '\'' }) dict.recordUse(chosen)
        clear()
        return chosen
    }

    /** 使用者打大寫開頭 → 提示也大寫開頭；打全大寫（兩個字母以上）→ 提示也全大寫 */
    private fun matchCase(suggestion: String, typed: String): String = when {
        typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
        typed.first().isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
        else -> suggestion
    }

    companion object {
        private const val LIMIT = 40
    }
}
