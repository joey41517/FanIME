package tw.fanime

import android.content.SharedPreferences

/**
 * 設定頁可以調整的鍵盤參數（單位：dp、毫秒）。
 * 存在 SharedPreferences「settings」裡，鍵盤每次出現時重新讀取。
 */
data class KeyboardSettings(
    val rowHeight: Int = 42,
    val toolbarHeight: Int = 34,
    val fanHintMs: Int = 200,
    /** 手勢導覽、直向時底部留給「切換輸入法」「縮小鍵盤」圖示的空白 */
    val bottomPortrait: Int = 38,
    /** 手勢導覽、橫向時底部的空白 */
    val bottomLandscape: Int = 12,
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putInt(ROW_HEIGHT, rowHeight)
            .putInt(TOOLBAR_HEIGHT, toolbarHeight)
            .putInt(FAN_HINT_MS, fanHintMs)
            .putInt(BOTTOM_PORTRAIT, bottomPortrait)
            .putInt(BOTTOM_LANDSCAPE, bottomLandscape)
            .apply()
    }

    /** 設定頁的滑桿：名稱、範圍、單位、讀值和改值 */
    class Slider(
        val key: String,
        val title: String,
        val min: Int,
        val max: Int,
        val unit: String,
        val get: (KeyboardSettings) -> Int,
        val set: (KeyboardSettings, Int) -> KeyboardSettings,
    )

    companion object {
        const val ROW_HEIGHT = "rowHeight"
        const val TOOLBAR_HEIGHT = "toolbarHeight"
        const val FAN_HINT_MS = "fanHintMs"
        const val BOTTOM_PORTRAIT = "bottomPortrait"
        const val BOTTOM_LANDSCAPE = "bottomLandscape"

        fun load(prefs: SharedPreferences): KeyboardSettings {
            val d = KeyboardSettings()
            return KeyboardSettings(
                rowHeight = prefs.getInt(ROW_HEIGHT, d.rowHeight),
                toolbarHeight = prefs.getInt(TOOLBAR_HEIGHT, d.toolbarHeight),
                fanHintMs = prefs.getInt(FAN_HINT_MS, d.fanHintMs),
                bottomPortrait = prefs.getInt(BOTTOM_PORTRAIT, d.bottomPortrait),
                bottomLandscape = prefs.getInt(BOTTOM_LANDSCAPE, d.bottomLandscape),
            )
        }

        val sliders = listOf(
            Slider(ROW_HEIGHT, "按鍵高度", 30, 60, "dp", { it.rowHeight }, { s, v -> s.copy(rowHeight = v) }),
            Slider(TOOLBAR_HEIGHT, "工具列、候選字列高度", 26, 48, "dp", { it.toolbarHeight }, { s, v -> s.copy(toolbarHeight = v) }),
            Slider(FAN_HINT_MS, "扇形提示出現時間", 0, 800, "毫秒", { it.fanHintMs }, { s, v -> s.copy(fanHintMs = v) }),
            Slider(BOTTOM_PORTRAIT, "直向底部留白", 0, 64, "dp", { it.bottomPortrait }, { s, v -> s.copy(bottomPortrait = v) }),
            Slider(BOTTOM_LANDSCAPE, "橫向底部留白", 0, 48, "dp", { it.bottomLandscape }, { s, v -> s.copy(bottomLandscape = v) }),
        )
    }
}
