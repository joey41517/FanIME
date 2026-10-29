package tw.fanime

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import tw.fanime.kaomoji.KaomojiList
import tw.fanime.kaomoji.KaomojiStore

/**
 * 設定頁：目前只有顏文字清單的編輯器。
 * 直接編輯文字檔內容，離開畫面時自動存檔；電腦上也可以編輯同一個檔案。
 */
class SettingsActivity : Activity() {

    private lateinit var store: KaomojiStore
    private lateinit var editor: EditText
    private var saved = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KaomojiStore(this)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1B1B1F.toInt())
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "顏文字清單"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "「## 分類名」開始一個分類，下面一行一個顏文字；順序就是面板上的順序，第一個分類是首頁。" +
                "離開這個畫面時自動存檔。\n電腦上的檔案位置：Android/data/tw.fanime/files/${KaomojiStore.FILE_NAME}"
            textSize = 13f
            setTextColor(0xFFB0B0B8.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        })
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF26262B.toInt())
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "還原預設"
            // 只放進編輯器，不馬上存檔；按錯可以直接改回來或不離開
            setOnClickListener {
                editor.setText(store.defaultText())
                toast("已放入預設清單，離開或按儲存才會存檔")
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "儲存"
            setOnClickListener { save(showToast = true) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        // Android 15 以後強制全螢幕繪製：自己把系統列和鍵盤的高度留白
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 回到畫面時重新讀檔（可能剛在電腦上改過）
        saved = store.readText()
        if (editor.text.toString() != saved) editor.setText(saved)
    }

    override fun onPause() {
        super.onPause()
        save(showToast = false)
    }

    private fun save(showToast: Boolean) {
        val text = editor.text.toString()
        if (text != saved) {
            store.writeText(text)
            saved = text
        }
        if (showToast) {
            val list = KaomojiList.parse(text)
            toast("已儲存：${list.size} 個分類、${list.sumOf { it.items.size }} 個顏文字")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
