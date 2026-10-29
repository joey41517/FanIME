package tw.fanime

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import tw.fanime.zhuyin.Syllable

/**
 * 管理注音自動記錄的詞：列出全部（新的在前），可以搜尋、點 ✕ 刪除。
 * 刪除後通知輸入法重新讀取，下次打字就不會再出現。
 */
class PhraseListActivity : Activity() {

    private class Phrase(val text: String, val reading: String)

    private val file get() = UserData.internal(this, UserData.USER_PHRASES)
    private var phrases: MutableList<Phrase> = ArrayList()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private var filter = ""
    private val palette by lazy { Palette.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Palette.activityTheme(palette))
        super.onCreate(savedInstanceState)
        val root = settingsRoot(this)
        root.addView(heading(this, "記錄的詞"))
        count = note(this, "")
        root.addView(count)
        root.addView(EditText(this).apply {
            hint = "搜尋"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(palette.text)
            setHintTextColor(palette.subText)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString()?.trim().orEmpty()
                    render()
                }
            })
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Palette.applySystemBars(window, Palette.of(this))
    }

    override fun onResume() {
        super.onResume()
        load()
        render()
    }

    private fun load() {
        phrases = ArrayList()
        if (!file.exists()) return
        file.forEachLine(Charsets.UTF_8) { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val reading = line.substring(tab + 1).split(' ').mapNotNull { Syllable.decode(it) }
            phrases += Phrase(line.substring(0, tab), reading.joinToString(" "))
        }
        // 同一個詞記過好幾次時只留最後一筆；新的在前
        phrases = phrases.asReversed().distinctBy { it.text }.toMutableList()
    }

    private fun render() {
        list.removeAllViews()
        val shown = phrases.filter { filter.isEmpty() || filter in it.text || filter in it.reading.replace(" ", "") }
        count.text = "共 ${phrases.size} 個詞。這些是逐字選字時自動記下來的；點 ✕ 刪除。"
        for (p in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = p.text; textSize = 18f; setTextColor(palette.text) })
            texts.addView(TextView(this).apply { text = p.reading; textSize = 12f; setTextColor(palette.subText) })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(palette.danger)
                setOnClickListener { delete(p) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            list.addView(row)
        }
    }

    private fun delete(p: Phrase) {
        phrases.remove(p)
        // 檔案裡同一個詞的每一筆都刪掉；順序維持原本的（舊的在前）
        val keep = file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() && it.substringBefore('\t') != p.text }
        file.writeText(keep.joinToString("") { "$it\n" }, Charsets.UTF_8)
        UserData.markChanged(this)
        render()
    }
}
