package tw.fanime.korean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulComposerTest {

    /** 依序輸入字母，回傳「已上字 + 正在組」的完整結果 */
    private fun type(c: HangulComposer, jamo: String): String {
        val out = StringBuilder()
        for (j in jamo) out.append(c.input(j))
        return out.toString() + c.composing
    }

    @Test fun basicSyllables() {
        assertEquals("한국어", type(HangulComposer(), "ㅎㅏㄴㄱㅜㄱㅇㅓ"))
        assertEquals("안녕하세요", type(HangulComposer(), "ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"))
    }

    @Test fun finalConsonantMovesToNextSyllable() {
        assertEquals("하나", type(HangulComposer(), "ㅎㅏㄴㅏ"))
    }

    @Test fun compoundVowelAndFinal() {
        assertEquals("과", type(HangulComposer(), "ㄱㅗㅏ"))
        assertEquals("닭", type(HangulComposer(), "ㄷㅏㄹㄱ"))
        // 複合終聲遇到母音：拆開，後半移到下一個字（닭 + ㅣ → 달기）
        assertEquals("달기", type(HangulComposer(), "ㄷㅏㄹㄱㅣ"))
    }

    @Test fun doubleConsonantCannotBeFinal() {
        assertEquals("가ㄸ", type(HangulComposer(), "ㄱㅏㄸ"))
    }

    @Test fun backspaceRemovesOneJamo() {
        val c = HangulComposer()
        type(c, "ㄷㅏㄹㄱ") // 닭
        assertTrue(c.backspace()); assertEquals("달", c.composing)
        assertTrue(c.backspace()); assertEquals("다", c.composing)
        assertTrue(c.backspace()); assertEquals("ㄷ", c.composing)
        assertTrue(c.backspace()); assertTrue(c.isEmpty)
        assertFalse(c.backspace())
    }

    @Test fun backspaceSplitsCompoundVowel() {
        val c = HangulComposer()
        type(c, "ㄱㅗㅏ") // 과
        assertTrue(c.backspace())
        assertEquals("고", c.composing)
    }

    @Test fun standaloneVowelsAndConsonants() {
        assertEquals("ㅋㅋㅋ", type(HangulComposer(), "ㅋㅋㅋ"))
        assertEquals("ㅠㅠ", type(HangulComposer(), "ㅠㅠ"))
    }
}
