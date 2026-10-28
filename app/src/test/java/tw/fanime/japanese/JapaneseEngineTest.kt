package tw.fanime.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class JapaneseEngineTest {

    companion object {
        private lateinit var dict: JapaneseDictionary

        @BeforeClass @JvmStatic
        fun loadDictionary() {
            dict = JapaneseDictionary.load { File("src/main/assets/japanese/dict.bin").inputStream() }
        }
    }

    private fun kana(romaji: String): String {
        val (out, rest) = Romaji.feed(romaji)
        return out + Romaji.flush(rest)
    }

    @Test fun romajiBasics() {
        assertEquals("こんにちは", kana("konnnichiha"))
        assertEquals("きって", kana("kitte"))
        assertEquals("しんぶん", kana("shinbun"))
        assertEquals("ちゃ", kana("cha"))
        assertEquals("つ", kana("tsu"))
        assertEquals("ほん", kana("hon")) // 結尾的 n 收尾成 ん
        assertEquals("らーめん", kana("ra-men"))
        assertEquals("っ", kana("xtu"))
    }

    @Test fun romajiWaitsForIncompleteSyllable() {
        assertEquals("" to "sh", Romaji.feed("sh"))
        assertEquals("" to "n", Romaji.feed("n"))
        assertEquals("な" to "", Romaji.feed("na"))
    }

    @Test fun katakana() {
        assertEquals("コーヒー", Romaji.toKatakana("こーひー"))
    }

    private fun type(e: JapaneseEngine, romaji: String) = romaji.forEach { e.input(it.toString()) }

    @Test fun convertsCommonWords() {
        for ((romaji, expected) in listOf(
            "toukyou" to "東京",
            "nihongo" to "日本語",
            "watashiha" to "私は",
            "gakusei" to "学生",
            "tabemasu" to "食べます",        // 動詞：語幹 + 語尾
            "kyouhaiitenkidesune" to "今日はいい天気ですね",
            "ko-hi-" to "コーヒー",            // 外來語仍是片假名
        )) {
            val e = JapaneseEngine(dict)
            type(e, romaji)
            assertEquals(romaji, expected, e.candidates().first().text)
        }
    }

    @Test fun preeditShowsHiraganaWhileTyping() {
        val e = JapaneseEngine(dict)
        type(e, "nihong")
        assertEquals("にほんg", e.preedit())
    }

    @Test fun predictionCompletesWord() {
        val e = JapaneseEngine(dict)
        type(e, "ariga")
        val texts = e.candidates().map { it.text }
        assertTrue("expected ありがとう in $texts", texts.take(10).any { it.startsWith("ありがとう") || it == "有難う" })
    }

    @Test fun hiraganaAndKatakanaAlwaysOffered() {
        val e = JapaneseEngine(dict)
        type(e, "ko-hi-")
        val texts = e.candidates().map { it.text }
        assertTrue("こーひー" in texts)
        assertTrue("コーヒー" in texts)
    }

    @Test fun pickFixesSegmentThenCommits() {
        val e = JapaneseEngine(dict)
        type(e, "watashiha")
        val watashi = e.candidates().first { it.text == "私" && it.span == 3 }
        assertEquals(null, e.pick(watashi))
        assertEquals("私は", e.preedit())
        val ha = e.candidates().first { it.text == "は" }
        assertEquals("私は", e.pick(ha))
        assertTrue(e.isEmpty)
    }

    @Test fun enterCommitsKanaSpaceCommitsConversion() {
        val e = JapaneseEngine(dict)
        type(e, "nihongo")
        assertEquals("にほんご", e.commitKana())
        type(e, "nihongo")
        assertEquals("日本語", e.commitConverted())
    }

    @Test fun backspaceRomajiThenKana() {
        val e = JapaneseEngine(dict)
        type(e, "kak")
        assertTrue(e.backspace())
        assertEquals("か", e.preedit())
        assertTrue(e.backspace())
        assertTrue(e.isEmpty)
    }
}
