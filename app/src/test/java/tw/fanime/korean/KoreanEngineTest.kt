package tw.fanime.korean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class KoreanEngineTest {

    private lateinit var engine: KoreanEngine

    @Before
    fun setUp() {
        engine = KoreanEngine()
        engine.dictionary = KoreanDictionary.load { File("src/main/assets/korean/words.tsv").inputStream() }
    }

    private fun type(jamo: String) = jamo.forEach { engine.input(it) }

    @Test fun keystrokesSplitCompounds() {
        assertEquals("ㅎㅏㄴㅏ", Hangul.keystrokes("하나"))
        assertEquals("ㄱㅗㅏ", Hangul.keystrokes("과"))
        assertEquals("ㄷㅏㄹㄱ", Hangul.keystrokes("닭"))
    }

    @Test fun wholeWordStaysComposing() {
        type("ㅇㅏㄴㄴㅕㅇ")
        assertEquals("안녕", engine.preedit())
    }

    @Test fun suggestsCommonWords() {
        type("ㄱㅡㄹㅐ") // 그래
        val s = engine.suggestions()
        assertEquals("그래", s.first())
        assertTrue("expected longer words starting with 그래, got $s", s.drop(1).any { it.startsWith("그래") })
    }

    @Test fun partialSyllableStillMatches() {
        type("ㅇㅏㄴㄴㅕ") // 「안녀」：下一個字母可能讓它變成 안녕
        assertTrue("안녕" in engine.suggestions())
    }

    @Test fun backspaceReopensPreviousSyllable() {
        type("ㅎㅏㄴㄱㅜㄱ") // 한국
        repeat(3) { assertTrue(engine.backspace()) } // ㄱ、ㅜ、ㄱ
        assertEquals("한", engine.preedit())
        assertTrue(engine.backspace()) // 拆開「한」刪 ㄴ
        assertEquals("하", engine.preedit())
    }

    @Test fun commitReturnsWholeWord() {
        type("ㅎㅏㄴㄱㅜㄱㅇㅓ")
        assertEquals("한국어", engine.commit())
        assertTrue(engine.isEmpty)
    }
}
