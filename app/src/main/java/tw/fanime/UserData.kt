package tw.fanime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 使用者資料的位置、備份和還原。
 *
 * 設定頁改了資料（刪記錄的詞、還原備份）之後呼叫 [markChanged]，
 * 輸入法下次出現時發現版本號變了，就重新讀取這些檔案。
 */
object UserData {
    const val PREFS = "settings"
    private const val STAMP = "dataStamp"

    /** 注音自動記錄的詞：一行一個「詞<TAB>讀音」 */
    const val USER_PHRASES = "user_phrases.tsv"
    const val ENGLISH_WORDS = "english_words.tsv"
    const val JAPANESE_PREFS = "japanese_prefs.tsv"
    private const val SETTINGS_ENTRY = "settings.properties"

    fun internal(context: Context, name: String) = File(context.filesDir, name)

    /** 備份裡的檔名 → 手機上的檔案；還原時只接受這些檔名 */
    private fun files(context: Context): Map<String, File> = mapOf(
        USER_PHRASES to internal(context, USER_PHRASES),
        ENGLISH_WORDS to internal(context, ENGLISH_WORDS),
        JAPANESE_PREFS to internal(context, JAPANESE_PREFS),
        "kaomoji.txt" to UserTextFile.kaomoji(context).file,
        "symbols.txt" to UserTextFile.symbols(context).file,
    )

    fun stamp(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(STAMP, 0L)

    fun markChanged(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(STAMP, System.currentTimeMillis()).apply()
    }

    /** 備份成 zip：記錄的詞、英文用字、日文選字、顏文字清單、符號表、鍵盤設定。回傳放了幾個檔案 */
    fun export(context: Context, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            for ((name, file) in files(context)) {
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
                .filterKeys { it != STAMP }
                .entries.joinToString("") { (k, v) -> "$k=${typeTag(v)}:$v\n" }
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
            zip.write(prefs.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return count + 1
    }

    /** 從備份還原（覆蓋現有資料）。不認得的檔名一律略過。回傳還原了幾個檔案 */
    fun import(context: Context, input: InputStream): Int {
        val targets = files(context)
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                when (entry.name) {
                    SETTINGS_ENTRY -> { restorePrefs(context, bytes.toString(Charsets.UTF_8)); count++ }
                    in targets -> {
                        val file = targets.getValue(entry.name)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                        count++
                    }
                }
            }
        }
        markChanged(context)
        return count
    }

    private fun typeTag(v: Any?) = when (v) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        else -> "s"
    }

    private fun restorePrefs(context: Context, text: String) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0 || line.length < eq + 3 || line[eq + 2] != ':') continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 3)
            when (line[eq + 1]) {
                'b' -> edit.putBoolean(key, value.toBoolean())
                'i' -> value.toIntOrNull()?.let { edit.putInt(key, it) }
                'l' -> value.toLongOrNull()?.let { edit.putLong(key, it) }
                'f' -> value.toFloatOrNull()?.let { edit.putFloat(key, it) }
                else -> edit.putString(key, value)
            }
        }
        edit.apply()
    }
}
