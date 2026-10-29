package tw.fanime.chinese

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class TradSimpConverterTest {

    companion object {
        private lateinit var conv: TradSimpConverter

        @BeforeClass @JvmStatic
        fun load() {
            conv = TradSimpConverter.load { path -> File("src/main/assets/$path").inputStream() }
        }
    }

    @Test fun convertsCharacters() {
        assertEquals("今天天气很好", conv.toSimplified("今天天氣很好"))
        assertEquals("台湾人", conv.toSimplified("臺灣人"))
        assertEquals("这里", conv.toSimplified("這裡"))
    }

    @Test fun phrasesBeforeCharacters() {
        assertEquals("头发", conv.toSimplified("頭髮"))
        assertEquals("发现", conv.toSimplified("發現"))
    }

    @Test fun leavesOtherTextAlone() {
        assertEquals("abc，😀！", conv.toSimplified("abc，😀！"))
    }
}
