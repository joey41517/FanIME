package tw.fanime.kaomoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KaomojiListTest {

    @Test fun parsesCategoriesInOrder() {
        val list = KaomojiList.parse("# 註解\n## 常用\n(;ﾟ∀ﾟ)\n\n(｡-_-｡)\n## 高興\nヾ(*´∀｀*)ﾉ\n")
        assertEquals(listOf("常用", "高興"), list.map { it.name })
        assertEquals(listOf("(;ﾟ∀ﾟ)", "(｡-_-｡)"), list[0].items)
    }

    @Test fun itemsBeforeFirstCategoryGoToDefault() {
        val list = KaomojiList.parse("(;ﾟ∀ﾟ)\r\n## 愛\r\n(っ´ω`c)♡\r\n")
        assertEquals("常用", list[0].name)
        assertEquals(listOf("(っ´ω`c)♡"), list[1].items) // Windows 換行也可以
    }

    @Test fun emptyCategoriesAndDuplicatesDropped() {
        val list = KaomojiList.parse("## 空的\n## 常用\nA\nA\n## 常用\nB\n")
        assertEquals(listOf(KaomojiCategory("常用", listOf("A", "B"))), list)
    }

    @Test fun keepsInnerSpaces() {
        assertEquals("(●´ω｀● )", KaomojiList.parse("(●´ω｀● )")[0].items[0])
    }

    @Test fun defaultFileStartsWithFavorites() {
        val list = KaomojiList.parse(File("src/main/assets/kaomoji/default.txt").readText())
        assertEquals("常用", list.first().name)
        assertTrue(list.sumOf { it.items.size } > 50)
    }
}
