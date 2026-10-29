package tw.fanime.symbols

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SymbolListTest {

    @Test fun splitsIntoPagesOf32() {
        val cells = (1..40).joinToString(" ")
        val pages = SymbolList.parse("## 半形\n$cells\n")[SymbolList.HALF]!!
        assertEquals(2, pages.size)
        assertEquals(32, pages[0].size)
        assertEquals(listOf("33", "34", "35", "36", "37", "38", "39", "40"), pages[1])
    }

    @Test fun hashIsASymbolNotAComment() {
        val pages = SymbolList.parse("## 半形\n# $ %\n-> ...\n")[SymbolList.HALF]!!
        assertEquals(listOf("#", "$", "%", "->", "..."), pages[0])
    }

    @Test fun defaultFilePages() {
        val parsed = SymbolList.parse(File("src/main/assets/symbols/default.txt").readText())
        val half = parsed[SymbolList.HALF]!!
        assertEquals(4, half.size) // ASCII、數學、性別／撲克牌／希臘字母（兩頁）
        assertEquals(listOf("!", "@", "#", "$", "%", "^", "&", "*"), half[0].take(8))
        assertEquals("♀", half[2][0])
        assertEquals(listOf("Ψ", "Ω"), half[3].takeLast(2))
        assertEquals(3, parsed[SymbolList.FULL]!!.size)
    }
}
