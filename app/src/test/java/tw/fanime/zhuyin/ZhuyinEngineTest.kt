package tw.fanime.zhuyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class ZhuyinEngineTest {

    companion object {
        private lateinit var dict: ZhuyinDictionary

        @BeforeClass @JvmStatic
        fun loadDictionary() {
            // 單元測試的工作目錄是 app/
            dict = ZhuyinDictionary.load { path -> File("src/main/assets/$path").inputStream() }
        }
    }

    /** 依序輸入注音；音節之間用空白分隔，一聲的音節自動補空白鍵 */
    private fun type(engine: ZhuyinEngine, syllables: String) {
        for (syl in syllables.split(' ')) {
            for (ch in syl) engine.input(ch.toString())
            if (syl.last().toString() !in ZhuyinEngine.TONES) engine.space()
        }
    }

    private fun convert(syllables: String): String {
        val e = ZhuyinEngine(dict)
        type(e, syllables)
        return e.preedit()
    }

    @Test fun singlePhrase() {
        assertEquals("我們", convert("ㄨㄛˇ ㄇㄣ˙"))
        assertEquals("電腦", convert("ㄉㄧㄢˋ ㄋㄠˇ"))
    }

    @Test fun sentence() {
        assertEquals("今天天氣很好", convert("ㄐㄧㄣ ㄊㄧㄢ ㄊㄧㄢ ㄑㄧˋ ㄏㄣˇ ㄏㄠˇ"))
        assertEquals("我想要喝水", convert("ㄨㄛˇ ㄒㄧㄤˇ ㄧㄠˋ ㄏㄜ ㄕㄨㄟˇ"))
    }

    @Test fun symbolThatDoesNotFitStartsNextSyllable() {
        val e = ZhuyinEngine(dict)
        e.input("ㄅ"); e.input("ㄆ") // 聲母放不進去 → 往後放，不取代
        assertEquals(1, e.syllableCount)
        assertEquals("ㄆ", e.pending)
        e.input("ㄚ")
        assertEquals("ㄆㄚ", e.pending)
    }

    @Test fun abbreviatedInitialsFindPhrase() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄏㄏ") // 簡拼：只打聲母
        val texts = e.candidates().map { it.text }
        assertTrue("expected 好好 in $texts", "好好" in texts.take(8))
    }

    @Test fun abbreviatedSyllableStaysAsBopomofo() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄔㄈ")
        assertEquals("ㄔㄈ", e.preedit()) // 只打聲母的字不提早轉成猜測的字
        assertTrue(e.backspace())
        assertEquals("ㄔ", e.preedit())
    }

    /** 選目前選字位置的單字候選（span = 1） */
    private fun pickChar(e: ZhuyinEngine, ch: String) =
        e.pick(e.candidates().first { it.span == 1 && it.text == ch })

    @Test fun autoLearnPhrasePickedCharByChar() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄌㄧㄝˋ ㄎㄨㄥ ㄗㄨㄛˋ")
        assertEquals(null, pickChar(e, "烈"))
        assertEquals(null, pickChar(e, "空"))
        assertEquals("烈空坐", pickChar(e, "坐")) // 選到最後才整串上字
        assertTrue(e.isEmpty)
        assertEquals("烈空坐", e.takeLearned()?.first)

        // 下次打同樣的注音，整句直接就是記錄的詞；聯想詞也找得到
        val again = ZhuyinEngine(dict)
        type(again, "ㄌㄧㄝˋ ㄎㄨㄥ ㄗㄨㄛˋ")
        assertEquals("烈空坐", again.preedit())
        assertEquals("空坐", again.associations("烈").first())

        // 長按刪除
        assertTrue(again.isUserPhrase("烈空坐"))
        assertTrue(again.removeUserPhrase("烈空坐"))
        assertFalse(again.isUserPhrase("烈空坐"))
    }

    @Test fun noLearnWhenWholeSentencePickedAtOnce() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄐㄧㄣ ㄊㄧㄢ")
        e.pick(e.candidates().first { it.span == 2 })
        assertEquals(null, e.takeLearned())
    }

    @Test fun learnWhenSpaceCommitsAfterFixing() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄌㄧㄝˋ ㄏㄨㄛˇ ㄕㄢ")
        pickChar(e, "烈") // 只固定第一個字，剩下的按空白一起上字
        val text = e.commitAll()
        assertTrue(text.startsWith("烈"))
        assertEquals(text, e.takeLearned()?.first)
        e.removeUserPhrase(text)
    }

    @Test fun noLearnForExistingPhrase() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄐㄧㄣ ㄊㄧㄢ")
        pickChar(e, "今"); pickChar(e, "天") // 「今天」詞庫已經有了
        assertEquals(null, e.takeLearned())
    }

    @Test fun backspaceDeletesOneSymbolAtATime() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄨㄛˇ ㄇㄣ˙")
        e.input("ㄏ")
        assertTrue(e.backspace()) // 刪 ㄏ
        assertEquals("我們", e.preedit())
        assertTrue(e.backspace()) // 拆開「ㄇㄣ˙」，先刪聲調
        assertEquals("ㄇㄣ", e.pending)
        assertTrue(e.backspace()) // 刪 ㄣ
        assertEquals("ㄇ", e.pending)
        assertTrue(e.backspace()) // 刪 ㄇ
        assertEquals(1, e.syllableCount)
        repeat(3) { assertTrue(e.backspace()) } // ˇ、ㄛ、ㄨ
        assertTrue(e.isEmpty)
        assertFalse(e.backspace())
    }

    @Test fun candidatesStartWithSentenceThenPrefixPhrases() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄊㄞˊ ㄨㄢ ㄖㄣˊ")
        val c = e.candidates()
        assertEquals(3, c.first().span) // 第一個是整句
        assertTrue(c.any { it.text == "台灣" && it.span == 2 })
        assertTrue(c.any { it.text == "臺" && it.span == 1 })
    }

    /** 一個符號一個符號輸入，不按聲調也不按空白 */
    private fun typeRaw(engine: ZhuyinEngine, symbols: String) {
        for (ch in symbols) engine.input(ch.toString())
    }

    @Test fun tonelessContinuousTyping() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄐㄧㄣㄊㄧㄢ") // 沒打聲調：打 ㄊ 時自動斷字
        assertEquals(1, e.syllableCount)
        assertEquals("ㄊㄧㄢ", e.pending)
        e.space() // 最後一個字用空白（一聲）結束
        assertEquals("今天", e.preedit())
    }

    @Test fun tonelessSentence() {
        val e = ZhuyinEngine(dict)
        // 註：省略聲調時「ㄏㄜ」會選到更常用的「和」而不是「喝」，這是省略聲調的取捨，所以不用那句測
        typeRaw(e, "ㄐㄧㄣㄊㄧㄢㄊㄧㄢㄑㄧㄏㄣㄏㄠ")
        e.input("ˇ")
        assertEquals("今天天氣很好", e.preedit())
    }

    @Test fun partialLastSyllableSuggestsPhrase() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄨㄛˇㄇ") // 第二個字只打了聲母
        val first = e.candidates().first()
        assertEquals("我們", first.text)
        assertTrue(first.withPending)
        assertEquals("我們", e.pick(first))
        assertTrue(e.isEmpty)
    }

    @Test fun manyCandidatesForSingleInitial() {
        val e = ZhuyinEngine(dict)
        e.input("ㄗ")
        assertTrue(e.candidates().size > 40)
    }

    @Test fun backspaceToEmptyLeavesNothing() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄌㄧㄝㄎ")
        assertTrue(e.backspace()) // ㄎ
        assertTrue(e.backspace()) // 前一個字拆回注音並刪掉 ㄝ，不會整個字一起刪
        assertEquals("ㄌㄧ", e.preedit())
        assertTrue(e.backspace()) // ㄧ
        assertTrue(e.backspace()) // ㄌ
        assertTrue(e.isEmpty)
        assertEquals("", e.preedit())
    }

    @Test fun associationsAfterCommit() {
        val e = ZhuyinEngine(dict)
        val next = e.associations("今天")
        assertTrue("expected associations for 今天, got $next", next.isNotEmpty())
        assertFalse(next.any { it.isEmpty() })
    }

    @Test fun explicitToneIsStrict() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄇㄚˇ")
        assertTrue(e.candidates().none { it.text == "媽" }) // 媽是一聲
        assertTrue(e.candidates().any { it.text == "馬" })
    }

    @Test fun pickFixesSegmentAndMovesToNext() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄊㄞˊ ㄨㄢ ㄖㄣˊ")
        val taiwan = e.candidates().first { it.text == "臺灣" }
        assertEquals(null, e.pick(taiwan)) // 還沒選到最後：只固定，不上字
        assertEquals(3, e.syllableCount)
        assertTrue(e.preedit().startsWith("臺灣"))
        // 候選字列移到第三個字
        val ren = e.candidates().first { it.text == "人" }
        assertEquals(1, ren.span)
        assertEquals("臺灣人", e.pick(ren)) // 選到最後：整串上字
        assertTrue(e.isEmpty)
    }

    @Test fun backspaceUnfixesSegment() {
        val e = ZhuyinEngine(dict)
        type(e, "ㄌㄧㄝˋ ㄎㄨㄥ")
        pickChar(e, "烈")
        assertTrue(e.preedit().startsWith("烈"))
        repeat(4) { e.backspace() } // 一聲、ㄥ、ㄨ、ㄎ
        assertEquals("烈", e.preedit()) // 第一個字還是固定的
        assertTrue(e.backspace()) // 拆開「ㄌㄧㄝˋ」，固定跟著取消
        assertEquals("ㄌㄧㄝ", e.preedit())
    }

    @Test fun autoLearnWithoutTones() {
        val e = ZhuyinEngine(dict)
        typeRaw(e, "ㄌㄧㄝㄎㄨㄥㄗㄨㄛ") // 全部不打聲調，最後一個字還在組
        assertEquals(null, pickChar(e, "烈"))
        assertEquals(null, pickChar(e, "空"))
        // 最後一個字的候選字包含還在組的注音
        val zuo = e.candidates().first { it.text == "坐" }
        assertTrue(zuo.withPending)
        assertEquals("烈空坐", e.pick(zuo))
        assertEquals("烈空坐", e.takeLearned()?.first)
        val again = ZhuyinEngine(dict)
        typeRaw(again, "ㄌㄧㄝㄎㄨㄥㄗㄨㄛ")
        again.space()
        assertEquals("烈空坐", again.preedit())

        // 記錄的詞用寬鬆比對：少打幾個注音也找得到
        val loose = ZhuyinEngine(dict)
        typeRaw(loose, "ㄌㄧㄎㄨㄥㄗ")
        assertEquals("烈空坐", loose.candidates().first().text)
        val abbr = ZhuyinEngine(dict)
        typeRaw(abbr, "ㄌㄎㄗ")
        assertEquals("烈空坐", abbr.candidates().first().text)
        again.removeUserPhrase("烈空坐")
    }
}
