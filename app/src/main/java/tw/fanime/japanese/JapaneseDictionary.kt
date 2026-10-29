package tw.fanime.japanese

import android.content.res.AssetManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 日文詞庫（Mozc OSS 詞庫，主體為 IPAdic；授權見 assets/japanese/LICENSE-mozc-dictionary.txt）。
 * 由 tools/build-ja-dict.ps1 轉成精簡的二進位格式，依讀音排序，用二分搜尋查詢。
 * 成本越低越常用（Mozc 的 word cost）。
 */
class JapaneseDictionary private constructor(
    private val chars: CharArray,
    private val readingOff: IntArray,
    private val readingLen: ByteArray,
    private val surfaceOff: IntArray,
    private val surfaceLen: ByteArray,
    private val cost: ShortArray,
) {
    class Entry(val reading: String, val surface: String, val cost: Int)

    /** 使用者選過的字：讀音 → 寫法，查詢時大幅降低成本，下次優先 */
    private val preferred = HashMap<String, String>()

    val size: Int get() = cost.size

    fun prefer(reading: String, surface: String) {
        preferred[reading] = surface
    }

    fun preferences(): Map<String, String> = preferred

    fun clearPreferences() = preferred.clear()

    /** 讀音完全相同的詞，依成本由低到高 */
    fun lookup(reading: String): List<Entry> {
        val result = ArrayList<Entry>()
        var i = lowerBound(reading)
        while (i < size && compareReading(i, reading) == 0) {
            result += entry(i)
            i++
        }
        return applyPreference(reading, result)
    }

    /** 預測：讀音以 [prefix] 開頭且比它長的詞，依成本取前 [limit] 個 */
    fun predict(prefix: String, limit: Int): List<Entry> {
        if (prefix.isEmpty()) return emptyList()
        val result = ArrayList<Entry>()
        var i = lowerBound(prefix)
        var scanned = 0
        // 前綴很短時符合的詞很多，最多看 PREDICT_SCAN 筆，避免每打一個字都掃很久
        while (i < size && scanned < PREDICT_SCAN && readingStartsWith(i, prefix)) {
            if (readingLen[i].toInt() and 0xFF > prefix.length) result += entry(i)
            i++
            scanned++
        }
        return result.sortedBy { it.cost }.distinctBy { it.surface }.take(limit)
    }

    private fun applyPreference(reading: String, list: List<Entry>): List<Entry> {
        val pref = preferred[reading] ?: return list
        val (hit, rest) = list.partition { it.surface == pref }
        val top = hit.firstOrNull() ?: Entry(reading, pref, 0)
        return listOf(Entry(top.reading, top.surface, maxOf(0, top.cost - PREFERENCE_BONUS))) + rest
    }

    private fun entry(i: Int) = Entry(
        String(chars, readingOff[i], readingLen[i].toInt() and 0xFF),
        String(chars, surfaceOff[i], surfaceLen[i].toInt() and 0xFF),
        cost[i].toInt(),
    )

    /** 第 i 筆的讀音和 key 比大小（ordinal，和建檔時的排序一致） */
    private fun compareReading(i: Int, key: String): Int {
        val off = readingOff[i]
        val len = readingLen[i].toInt() and 0xFF
        val n = minOf(len, key.length)
        for (k in 0 until n) {
            val d = chars[off + k] - key[k]
            if (d != 0) return d
        }
        return len - key.length
    }

    private fun readingStartsWith(i: Int, prefix: String): Boolean {
        val len = readingLen[i].toInt() and 0xFF
        if (len < prefix.length) return false
        val off = readingOff[i]
        for (k in prefix.indices) if (chars[off + k] != prefix[k]) return false
        return true
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareReading(mid, key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val PREDICT_SCAN = 4000
        /** 使用者選過的寫法降低的成本（一般詞的成本約 3000–7000） */
        private const val PREFERENCE_BONUS = 4000

        fun load(assets: AssetManager): JapaneseDictionary = load { assets.open("japanese/dict.bin") }

        fun load(open: () -> InputStream): JapaneseDictionary {
            val buf = ByteBuffer.wrap(open().use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "JPD1") { "unknown dictionary format" }
            val n = buf.int
            val charCount = buf.int
            val chars = CharArray(charCount).also { buf.asCharBuffer().get(it) }
            buf.position(buf.position() + charCount * 2)
            val rOff = IntArray(n).also { buf.asIntBuffer().get(it) }
            buf.position(buf.position() + n * 4)
            val rLen = ByteArray(n).also { buf.get(it) }
            val sOff = IntArray(n).also { buf.asIntBuffer().get(it) }
            buf.position(buf.position() + n * 4)
            val sLen = ByteArray(n).also { buf.get(it) }
            val cost = ShortArray(n).also { buf.asShortBuffer().get(it) }
            return JapaneseDictionary(chars, rOff, rLen, sOff, sLen, cost)
        }
    }
}
