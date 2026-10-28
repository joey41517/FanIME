package tw.fanime.korean

import android.content.res.AssetManager
import java.io.InputStream

/**
 * 韓文常用詞表（hermitdave/FrequencyWords，OpenSubtitles 2018，CC BY-SA 4.0；見 assets/korean/NOTICE）。
 * 每個詞拆成兩套式的按鍵序列（例如 하나 → ㅎㅏㄴㅏ）後排序，用按鍵序列做前綴比對，
 * 所以打到一半的字（例如「한」之後可能變成「하나」）也找得到。
 */
class KoreanDictionary private constructor(
    private val keys: Array<String>,
    private val words: Array<String>,
    private val counts: IntArray,
) {
    /** 按鍵序列以 [prefixKeys] 開頭的詞，依出現次數取前 [limit] 個 */
    fun complete(prefixKeys: String, limit: Int): List<String> {
        if (prefixKeys.isEmpty()) return emptyList()
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefixKeys) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Int>()
        var i = lo
        while (i < keys.size && keys[i].startsWith(prefixKeys)) found += i++
        return found.sortedByDescending { counts[it] }.take(limit).map { words[it] }
    }

    companion object {
        fun load(assets: AssetManager): KoreanDictionary = load { assets.open("korean/words.tsv") }

        fun load(open: () -> InputStream): KoreanDictionary {
            val entries = ArrayList<Triple<String, String, Int>>(50_000)
            open().bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    entries += Triple(Hangul.keystrokes(word), word, line.substring(tab + 1).toIntOrNull() ?: 0)
                }
            }
            entries.sortBy { it.first }
            return KoreanDictionary(
                Array(entries.size) { entries[it].first },
                Array(entries.size) { entries[it].second },
                IntArray(entries.size) { entries[it].third },
            )
        }
    }
}

/** 韓文音節和兩套式按鍵序列的轉換 */
object Hangul {
    private val SPLIT = mapOf(
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ", 'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ",
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ",
        'ㄽ' to "ㄹㅅ", 'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ",
    )

    private fun key(jamo: Char): String = SPLIT[jamo] ?: jamo.toString()

    /** 把一段韓文拆成按鍵序列：음절 → 초성 + 중성 + 종성，複合母音和複合終聲拆成兩個按鍵 */
    fun keystrokes(text: String): String = buildString {
        for (ch in text) {
            val code = ch.code - 0xAC00
            if (code in 0 until 11172) {
                val cho = code / (21 * 28)
                val jung = code % (21 * 28) / 28
                val jong = code % 28
                append(HangulComposer.CHO[cho])
                append(key(HangulComposer.JUNG[jung]))
                if (jong > 0) append(key(HangulComposer.JONG[jong]))
            } else {
                append(key(ch))
            }
        }
    }
}
