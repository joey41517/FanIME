package tw.fanime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 設定頁：列出可以調整、編輯的項目 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = settingsRoot(this)
        root.addView(heading(this, "FanIME 設定"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(item("顏文字清單", "扇形 (^^) 面板的分類和顏文字") { openFile(FileEditorActivity.FILE_KAOMOJI) })
        list.addView(item("符號表", "扇形「半」「全」的符號和頁數") { openFile(FileEditorActivity.FILE_SYMBOLS) })
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun openFile(which: String) {
        startActivity(Intent(this, FileEditorActivity::class.java).putExtra(FileEditorActivity.EXTRA_FILE, which))
    }

    private fun item(title: String, summary: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(TextView(context).apply { text = title; textSize = 18f; setTextColor(Color.WHITE) })
            addView(TextView(context).apply { text = summary; textSize = 13f; setTextColor(0xFFB0B0B8.toInt()) })
            setOnClickListener { onClick() }
        }
}

// 設定頁共用的版面元件（不使用 AppCompat，維持 App 小巧）

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** 深色底、四周留白的直向版面；Android 15 以後強制全螢幕繪製，自己把系統列和鍵盤的高度留白 */
fun settingsRoot(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(0xFF1B1B1F.toInt())
    val pad = context.dp(16)
    setPadding(pad, pad, pad, pad)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            insets
        }
    } else {
        fitsSystemWindows = true
    }
}

fun heading(context: Context, text: String) = TextView(context).apply {
    this.text = text
    textSize = 22f
    setTextColor(Color.WHITE)
}

fun note(context: Context, text: String) = TextView(context).apply {
    this.text = text
    textSize = 13f
    setTextColor(0xFFB0B0B8.toInt())
    setPadding(0, context.dp(8), 0, context.dp(8))
}
