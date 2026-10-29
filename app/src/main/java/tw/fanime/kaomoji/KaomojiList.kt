package tw.fanime.kaomoji

/**
 * 顏文字清單的文字檔格式（使用者可以在手機或電腦上直接編輯）：
 *
 * ```
 * # 註解
 * ## 常用
 * (;ﾟ∀ﾟ)
 * (*´꒳`*)b
 * ## 高興
 * ヾ(*´∀｀*)ﾉ
 * ```
 *
 * - 「##」開頭的行開始一個分類，下面一行一個顏文字
 * - 只有一個「#」開頭的行是註解；空行忽略
 * - 分類和顏文字的順序就是面板上的順序；第一個分類是打開面板時的首頁
 * - 第一個分類之前的顏文字歸到「常用」
 */
data class KaomojiCategory(val name: String, val items: List<String>)

object KaomojiList {
    const val DEFAULT_CATEGORY = "常用"

    fun parse(text: String): List<KaomojiCategory> {
        val result = ArrayList<Pair<String, MutableList<String>>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("##") -> {
                    val name = line.removePrefix("##").trim().ifEmpty { DEFAULT_CATEGORY }
                    // 同名分類寫了兩次就合併
                    if (result.none { it.first == name }) result += name to ArrayList()
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (result.isEmpty()) result += DEFAULT_CATEGORY to ArrayList()
                    result.last().second += line
                }
            }
        }
        return result.filter { it.second.isNotEmpty() }.map { KaomojiCategory(it.first, it.second.distinct()) }
    }
}
