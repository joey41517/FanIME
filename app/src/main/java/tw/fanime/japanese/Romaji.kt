package tw.fanime.japanese

/**
 * 羅馬拼音 → 平假名（ワープロ式，相容訓令式和平文式）。
 * - 子音重複 → っ（kitte → きって），n + 子音 → ん（shinbun → しんぶん），nn / n' → ん
 * - x / l 開頭是小寫假名（xtu → っ、lya → ゃ），- → ー
 */
object Romaji {
    private val table: Map<String, String> = buildMap {
        fun row(c: String, kana: String) {
            // kana 依序是 a i u e o 五個
            "aiueo".forEachIndexed { i, v -> if (kana[i] != '・') put(c + v, kana[i].toString()) }
        }
        fun yRow(c: String, base: String) {
            put(c + "ya", base + "ゃ"); put(c + "yu", base + "ゅ"); put(c + "yo", base + "ょ")
            put(c + "yi", base + "ぃ"); put(c + "ye", base + "ぇ")
        }
        row("", "あいうえお")
        row("k", "かきくけこ"); yRow("k", "き")
        row("g", "がぎぐげご"); yRow("g", "ぎ")
        row("s", "さしすせそ"); yRow("s", "し")
        put("shi", "し"); put("sha", "しゃ"); put("shu", "しゅ"); put("she", "しぇ"); put("sho", "しょ")
        row("z", "ざじずぜぞ"); yRow("z", "じ")
        put("ji", "じ"); put("ja", "じゃ"); put("ju", "じゅ"); put("je", "じぇ"); put("jo", "じょ"); yRow("j", "じ")
        row("t", "たちつてと"); yRow("t", "ち")
        put("chi", "ち"); put("cha", "ちゃ"); put("chu", "ちゅ"); put("che", "ちぇ"); put("cho", "ちょ"); yRow("c", "ち")
        put("tsu", "つ"); put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ")
        put("thi", "てぃ"); put("thu", "てゅ"); put("twu", "とぅ")
        row("d", "だぢづでど"); yRow("d", "ぢ")
        put("dhi", "でぃ"); put("dhu", "でゅ"); put("dwu", "どぅ")
        row("n", "なにぬねの"); yRow("n", "に")
        put("nn", "ん"); put("n'", "ん")
        row("h", "はひふへほ"); yRow("h", "ひ")
        put("fu", "ふ"); put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ"); put("fyu", "ふゅ")
        row("b", "ばびぶべぼ"); yRow("b", "び")
        row("p", "ぱぴぷぺぽ"); yRow("p", "ぴ")
        row("m", "まみむめも"); yRow("m", "み")
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("ye", "いぇ")
        row("r", "らりるれろ"); yRow("r", "り")
        put("wa", "わ"); put("wi", "うぃ"); put("we", "うぇ"); put("wo", "を"); put("wyi", "ゐ"); put("wye", "ゑ")
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
        put("qa", "くぁ"); put("qi", "くぃ"); put("qe", "くぇ"); put("qo", "くぉ")
        for (p in listOf("x", "l")) {
            row(p, "ぁぃぅぇぉ")
            put(p + "ya", "ゃ"); put(p + "yu", "ゅ"); put(p + "yo", "ょ")
            put(p + "tu", "っ"); put(p + "tsu", "っ"); put(p + "wa", "ゎ")
        }
        put("xn", "ん")
        put("-", "ー")
    }

    /** 某個字串是不是表裡某個拼音的開頭（還要等更多字母才知道是哪個假名） */
    private val prefixes: Set<String> = buildSet {
        for (k in table.keys) for (i in 1 until k.length) add(k.substring(0, i))
    }

    /**
     * 把還沒轉換的羅馬拼音轉成假名。回傳 (轉好的假名, 還要等下一個字母的剩餘拼音)。
     * 表裡沒有的字母（例如數字）直接原樣輸出。
     */
    fun feed(input: String): Pair<String, String> {
        val out = StringBuilder()
        var p = input.lowercase()
        while (p.isNotEmpty()) {
            // 子音重複 → っ（n 除外）
            if (p.length >= 2 && p[0] == p[1] && p[0].isLetter() && p[0] !in "aiueon") {
                out.append('っ'); p = p.substring(1); continue
            }
            // n 後面接子音（不是母音、y、n、'）→ ん
            if (p.length >= 2 && p[0] == 'n' && p[1] !in "aiueoyn'") {
                out.append('ん'); p = p.substring(1); continue
            }
            // 最長比對
            var matched = false
            for (len in minOf(4, p.length) downTo 1) {
                val key = p.substring(0, len)
                val kana = table[key] ?: continue
                // 整段剛好是某個拼音，但也可能是更長拼音的開頭（例如 n）→ 先等
                if (len == p.length && key in prefixes) return out.toString() to p
                out.append(kana); p = p.substring(len); matched = true
                break
            }
            if (matched) continue
            if (p in prefixes) return out.toString() to p // 還沒打完（例如 sh、ky）
            out.append(p[0]); p = p.substring(1)           // 表裡沒有：原樣輸出
        }
        return out.toString() to ""
    }

    /** 組字結束時，把剩下的拼音收尾：n → ん，其他原樣輸出 */
    fun flush(rest: String): String = when {
        rest.isEmpty() -> ""
        rest == "n" -> "ん"
        else -> feed(rest).let { (kana, r) -> kana + r }
    }

    /** 平假名 → 片假名（ー 和其他字元不變） */
    fun toKatakana(s: String): String = buildString {
        for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }
}
