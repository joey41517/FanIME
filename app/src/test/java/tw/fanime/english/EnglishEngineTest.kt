package tw.fanime.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class EnglishEngineTest {

    private lateinit var engine: EnglishEngine

    @Before
    fun setUp() {
        // 每個測試重新載入，避免「打過的字」互相影響；單元測試的工作目錄是 app/
        val dict = EnglishDictionary.load { File("src/main/assets/english/words.tsv").inputStream() }
        engine = EnglishEngine(dict)
    }

    private fun type(s: String) = s.forEach { engine.append(it.toString()) }

    @Test fun prefixCompletionByFrequency() {
        type("ca")
        val s = engine.suggestions()
        assertEquals("ca", s.first()) // 第一個是打的字本身
        val top = s.drop(1).take(5)
        assertTrue("expected can/can't/came near top, got $top", top.containsAll(listOf("can", "can't", "came")))
    }

    @Test fun followsTypedCapitalization() {
        type("Ca")
        assertTrue(engine.suggestions().drop(1).all { it.first().isUpperCase() })
        engine.clear()
        type("CA")
        assertTrue("CAN" in engine.suggestions())
    }

    @Test fun backspaceRemovesLastLetter() {
        type("cat")
        assertTrue(engine.backspace())
        assertEquals("ca", engine.current)
        engine.clear()
        assertFalse(engine.backspace())
    }

    @Test fun usedWordsRankHigher() {
        type("ca")
        val before = engine.suggestions().indexOf("caterpillar")
        repeat(5) { engine.commit("caterpillar") }
        type("ca")
        val after = engine.suggestions().indexOf("caterpillar")
        assertTrue("before=$before after=$after", after in 1 until 5 && (before == -1 || after < before))
    }

    @Test fun unknownWordsAreRememberedAfterUse() {
        engine.commit("fanime")
        type("fan")
        assertTrue("fanime" in engine.suggestions())
    }

    @Test fun apostropheOnlyInsideWord() {
        assertFalse(engine.accepts("'"))
        type("can")
        assertTrue(engine.accepts("'"))
        assertFalse(engine.accepts("1"))
    }
}
