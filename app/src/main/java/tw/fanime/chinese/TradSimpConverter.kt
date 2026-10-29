package tw.fanime.chinese

import java.io.InputStream

/**
 * 繁體轉簡體：用 OpenCC 的詞庫（Apache 2.0；見 assets/opencc/LICENSE）。
 * 先找最長符合的詞（例如「頭髮」→「头发」，避免「髮」「發」都轉成「发」時出錯），
 * 找不到詞就逐字轉換；一對多的字取第一個（最常用的）寫法。
 */
class TradSimpConverter private constructor(
    private val phrases: Map<String, String>,
    private val chars: Map<Int, String>,
    private val maxPhrase: Int,
) {
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in minOf(maxPhrase, text.length - i) downTo 2) {
                val hit = phrases[text.substring(i, i + len)] ?: continue
                out.append(hit)
                i += len
                matched = true
                break
            }
            if (matched) continue
            val cp = text.codePointAt(i)
            out.append(chars[cp] ?: String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    companion object {
        fun load(open: (String) -> InputStream): TradSimpConverter {
            val phrases = HashMap<String, String>()
            val chars = HashMap<Int, String>()
            read(open("opencc/TSPhrases.txt")) { k, v -> phrases[k] = v }
            read(open("opencc/TSCharacters.txt")) { k, v ->
                if (k.codePointCount(0, k.length) == 1) chars[k.codePointAt(0)] = v else phrases[k] = v
            }
            return TradSimpConverter(phrases, chars, phrases.keys.maxOfOrNull { it.length } ?: 0)
        }

        /** 一行一筆「繁體<TAB>簡體 [其他寫法…]」，# 開頭是註解 */
        private fun read(input: InputStream, put: (String, String) -> Unit) {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    put(line.substring(0, tab), line.substring(tab + 1).substringBefore(' ').trim())
                }
            }
        }
    }
}
