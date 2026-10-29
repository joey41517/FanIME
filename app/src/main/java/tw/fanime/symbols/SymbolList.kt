package tw.fanime.symbols

/**
 * 符號表的文字檔格式（使用者可以自己編輯）：
 *
 * ```
 * ## 半形
 * ! @ # $ % ^ & *
 * ( ) [ ] { } < >
 * ## 全形
 * ， 、 。 ．
 * ```
 *
 * - 「## 半形」「## 全形」開始一個表；符號之間用空白分隔，一個符號可以是好幾個字（例如 `->`）
 * - 每 32 個符號（8 個一列、4 列）是一頁，不夠一頁的留空；所以照「一行 8 個」排最好對照
 * - 沒有註解行，因為 # 本身就是常用符號；「##」開頭的行一定是表名，所以一列不能以 ## 開頭
 */
object SymbolList {
    const val HALF = "半形"
    const val FULL = "全形"
    const val PAGE_SIZE = 32

    /** 回傳每個表的頁面（每頁最多 32 個符號）；名稱以外的表、空的表都略過 */
    fun parse(text: String): Map<String, List<List<String>>> {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.removePrefix("\uFEFF")
            val trimmed = line.trim()
            if (trimmed.startsWith("##")) {
                val name = trimmed.removePrefix("##").trim()
                current = if (name.isEmpty()) null else sections.getOrPut(name) { ArrayList() }
                continue
            }
            // 用一般空白分隔；全形空白「　」也算空白，所以全形空白放在底列的專用鍵
            current?.addAll(trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() })
        }
        return sections.filterValues { it.isNotEmpty() }.mapValues { (_, cells) -> cells.chunked(PAGE_SIZE) }
    }
}
