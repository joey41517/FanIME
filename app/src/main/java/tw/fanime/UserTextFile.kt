package tw.fanime

import android.content.Context
import java.io.File

/**
 * 使用者可以自己編輯的文字檔（顏文字清單、符號表），
 * 放在 App 專屬的外部儲存空間（Android/data/tw.fanime/files/），不需要任何權限，
 * 手機接電腦時也可以用檔案總管直接編輯。第一次使用時從 assets 複製預設內容。
 */
class UserTextFile(private val context: Context, val fileName: String, private val defaultAsset: String) {

    val file: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, fileName)

    fun defaultText(): String = context.assets.open(defaultAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }

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

    /** 檔案內容有變（例如在電腦上改過）才重新解析，否則沿用上次的結果 */
    fun <T> cached(parse: (String) -> T): () -> T {
        var stamp: Int? = null
        var value: T? = null
        return {
            val text = readText()
            if (stamp != text.hashCode() || value == null) {
                value = parse(text)
                stamp = text.hashCode()
            }
            value!!
        }
    }

    companion object {
        fun kaomoji(context: Context) = UserTextFile(context, "kaomoji.txt", "kaomoji/default.txt")
        fun symbols(context: Context) = UserTextFile(context, "symbols.txt", "symbols/default.txt")
    }
}
