package tw.fanime.kaomoji

import android.content.Context
import java.io.File

/**
 * 顏文字清單存放在 App 專屬的外部儲存空間（Android/data/tw.fanime/files/kaomoji.txt），
 * 不需要任何權限，手機接電腦時也可以用檔案總管直接編輯。
 * 第一次使用時從 assets 複製預設清單。
 */
class KaomojiStore(private val context: Context) {

    val file: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    private var cached: List<KaomojiCategory> = emptyList()
    private var cachedStamp = -1L

    fun defaultText(): String = context.assets.open("kaomoji/default.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun readText(): String {
        val f = file
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(defaultText(), Charsets.UTF_8)
        }
        return f.readText(Charsets.UTF_8)
    }

    fun writeText(text: String) {
        val f = file
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    }

    /** 檔案有改過（例如在電腦上編輯）才重新解析 */
    fun categories(): List<KaomojiCategory> {
        val text = readText()
        val stamp = file.lastModified() xor text.hashCode().toLong()
        if (stamp != cachedStamp) {
            cached = KaomojiList.parse(text)
            cachedStamp = stamp
        }
        return cached
    }

    companion object {
        const val FILE_NAME = "kaomoji.txt"
    }
}
