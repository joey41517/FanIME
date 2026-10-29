package tw.fanime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 設定頁：鍵盤參數、使用者資料（記錄的詞、顏文字、符號表）、備份與還原 */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(UserData.PREFS, MODE_PRIVATE) }
    private lateinit var sliders: LinearLayout
    private val palette by lazy { Palette.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Palette.activityTheme(palette))
        super.onCreate(savedInstanceState)
        val root = settingsRoot(this)
        root.addView(heading(this, "FanIME 設定"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        list.addView(section("鍵盤"))
        list.addView(themeChoice())
        list.addView(note(this, "改完之後，下次叫出鍵盤時生效。底部留白只在手勢導覽時使用。"))
        sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(sliders)
        list.addView(Button(this).apply {
            text = "鍵盤參數還原預設"
            setOnClickListener {
                KeyboardSettings().save(prefs)
                renderSliders()
            }
        })

        list.addView(section("資料"))
        list.addView(item("記錄的詞", "注音逐字選字時自動記下的詞，可以搜尋、刪除") {
            startActivity(Intent(this, PhraseListActivity::class.java))
        })
        list.addView(item("顏文字清單", "扇形 (^^) 面板的分類和顏文字") { openFile(FileEditorActivity.FILE_KAOMOJI) })
        list.addView(item("符號表", "扇形「半」「全」的符號和頁數") { openFile(FileEditorActivity.FILE_SYMBOLS) })

        list.addView(section("備份"))
        list.addView(note(this, "解除安裝 FanIME 時，上面的資料會一起刪掉。備份成一個 zip 檔，可以存到「下載」或雲端硬碟。"))
        list.addView(item("備份", "記錄的詞、英文和日文的選字習慣、顏文字、符號表、鍵盤設定") { startBackup() })
        list.addView(item("從備份還原", "會覆蓋目前的資料") { confirmRestore() })

        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Palette.applySystemBars(window, Palette.of(this))
        renderSliders()
    }

    /** 主題：跟隨系統／深色／淺色；換了之後設定頁馬上重畫，鍵盤下次出現時生效 */
    private fun themeChoice(): View {
        val options = listOf(
            Palette.THEME_SYSTEM to "跟隨系統",
            Palette.THEME_DARK to "深色",
            Palette.THEME_LIGHT to "淺色",
        )
        val current = prefs.getString(Palette.PREF_THEME, Palette.THEME_SYSTEM)
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                id = i + 1
                text = label
                setTextColor(palette.text)
                // 系統主題勾選的圓圈顏色太深，在深色底上看不到，自己指定
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.accent, palette.subText),
                )
            })
        }
        // 用 RadioGroup.check() 勾選，直接設 isChecked 的話勾選的圓圈有時不會畫出來
        group.check(options.indexOfFirst { it.first == current }.coerceAtLeast(0) + 1)
        group.setOnCheckedChangeListener { _, id ->
            val value = options[id - 1].first
            if (value == prefs.getString(Palette.PREF_THEME, Palette.THEME_SYSTEM)) return@setOnCheckedChangeListener
            prefs.edit().putString(Palette.PREF_THEME, value).apply()
            recreate()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "主題"; textSize = 15f; setTextColor(palette.text) })
            addView(group)
        }
    }

    private fun renderSliders() {
        sliders.removeAllViews()
        for (s in KeyboardSettings.sliders) {
            val label = TextView(this).apply { textSize = 15f; setTextColor(palette.text) }
            fun show(v: Int) { label.text = "${s.title}：$v ${s.unit}" }
            show(s.get(KeyboardSettings.load(prefs)))
            sliders.addView(label.apply { setPadding(0, dp(10), 0, 0) })
            sliders.addView(SeekBar(this).apply {
                max = s.max - s.min
                progress = s.get(KeyboardSettings.load(prefs)) - s.min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                        show(p + s.min)
                        if (fromUser) s.set(KeyboardSettings.load(prefs), p + s.min).save(prefs)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }
    }

    private fun openFile(which: String) {
        startActivity(Intent(this, FileEditorActivity::class.java).putExtra(FileEditorActivity.EXTRA_FILE, which))
    }

    // 備份、還原用系統的檔案選擇器（不需要儲存權限，使用者自己選位置）

    private fun startBackup() {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "FanIME-backup-$date.zip"),
            REQUEST_BACKUP,
        )
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle("從備份還原")
            .setMessage("備份裡的資料會覆蓋目前的記錄的詞、選字習慣、顏文字、符號表和鍵盤設定。要繼續嗎？")
            .setPositiveButton("選擇備份檔") { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip"),
                    REQUEST_RESTORE,
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQUEST_BACKUP -> {
                    val n = contentResolver.openOutputStream(uri)?.use { UserData.export(this, it) } ?: 0
                    toast("已備份 $n 個檔案")
                }
                REQUEST_RESTORE -> {
                    val n = contentResolver.openInputStream(uri)?.use { UserData.import(this, it) } ?: 0
                    toast(if (n == 0) "這個檔案裡沒有 FanIME 的備份資料" else "已還原 $n 個檔案")
                    renderSliders()
                }
            }
        } catch (e: Exception) {
            toast("失敗：${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(palette.accent)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun item(title: String, summary: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(context).apply { text = title; textSize = 18f; setTextColor(palette.text) })
            addView(TextView(context).apply { text = summary; textSize = 13f; setTextColor(palette.subText) })
            setOnClickListener { onClick() }
        }

    private companion object {
        const val REQUEST_BACKUP = 1
        const val REQUEST_RESTORE = 2
    }
}

// 設定頁共用的版面元件（不使用 AppCompat，維持 App 小巧）

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** 深色底、四周留白的直向版面；Android 15 以後強制全螢幕繪製，自己把系統列和鍵盤的高度留白 */
fun settingsRoot(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Palette.of(context).background)
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
    setTextColor(Palette.of(context).text)
}

fun note(context: Context, text: String) = TextView(context).apply {
    this.text = text
    textSize = 13f
    setTextColor(Palette.of(context).subText)
    setPadding(0, context.dp(8), 0, context.dp(8))
}
