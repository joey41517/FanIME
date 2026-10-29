package tw.fanime

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import tw.fanime.kaomoji.KaomojiList
import tw.fanime.symbols.SymbolList

/**
 * 使用者文字檔（顏文字清單、符號表）的編輯器：直接編輯檔案內容，離開畫面時自動存檔。
 * 電腦上也可以編輯同一個檔案。
 */
class FileEditorActivity : Activity() {

    private lateinit var file: UserTextFile
    private lateinit var editor: EditText
    private var saved = ""
    private var summarize: (String) -> String = { "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Palette.activityTheme(Palette.of(this)))
        super.onCreate(savedInstanceState)
        val palette = Palette.of(this)
        val title: String
        val help: String
        when (intent.getStringExtra(EXTRA_FILE)) {
            FILE_SYMBOLS -> {
                file = UserTextFile.symbols(this)
                title = "符號表"
                help = "「## 半形」「## 全形」下面放符號，符號之間用空白隔開，一個符號可以是好幾個字（例如 ->）。" +
                    "一行 8 個、每 4 行是一頁，順序就是表上的順序。"
                summarize = { text ->
                    SymbolList.parse(text).entries.joinToString("、") { (name, pages) -> "$name ${pages.size} 頁" }
                }
            }
            else -> {
                file = UserTextFile.kaomoji(this)
                title = "顏文字清單"
                help = "「## 分類名」開始一個分類，下面一行一個顏文字；順序就是面板上的順序，第一個分類是首頁。"
                summarize = { text ->
                    val list = KaomojiList.parse(text)
                    "${list.size} 個分類、${list.sumOf { it.items.size }} 個顏文字"
                }
            }
        }

        val root = settingsRoot(this)
        root.addView(heading(this, title))
        root.addView(note(this, "$help\n離開這個畫面時自動存檔。電腦上的檔案位置：Android/data/tw.fanime/files/${file.fileName}"))
        val pad = dp(8)
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(palette.text)
            setBackgroundColor(palette.surface)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "還原預設"
            // 只放進編輯器，不馬上存檔；按錯可以直接改回來
            setOnClickListener {
                editor.setText(file.defaultText())
                toast("已放入預設內容，離開或按儲存才會存檔")
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "儲存"
            setOnClickListener { save(showToast = true) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)
        Palette.applySystemBars(window, Palette.of(this))
    }

    override fun onResume() {
        super.onResume()
        // 回到畫面時重新讀檔（可能剛在電腦上改過）
        saved = file.readText()
        if (editor.text.toString() != saved) editor.setText(saved)
    }

    override fun onPause() {
        super.onPause()
        save(showToast = false)
    }

    private fun save(showToast: Boolean) {
        val text = editor.text.toString()
        if (text != saved) {
            file.writeText(text)
            saved = text
        }
        if (showToast) toast("已儲存：${summarize(text)}")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_FILE = "file"
        const val FILE_KAOMOJI = "kaomoji"
        const val FILE_SYMBOLS = "symbols"
    }
}
