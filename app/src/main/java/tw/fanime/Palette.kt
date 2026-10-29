package tw.fanime

import android.content.Context
import android.content.res.Configuration

/**
 * 鍵盤和設定頁的配色。預設跟著手機的深色／淺色模式，也可以在設定頁固定成其中一種。
 * 主題換了之後，輸入法會重建畫面（系統切換深淺色時也會），所以畫面建立時讀一次就好。
 */
data class Palette(
    val dark: Boolean,
    /** 鍵盤底色、設定頁底色 */
    val background: Int,
    /** 字母、注音等一般按鍵 */
    val key: Int,
    /** 功能鍵（⌫、⏎、切換鍵）、面板的按鈕 */
    val keyFn: Int,
    val keyDown: Int,
    /** 浮動選單、顏文字格子、編輯框的底色 */
    val surface: Int,
    val text: Int,
    val subText: Int,
    val accent: Int,
    /** 候選字之間的分隔線 */
    val divider: Int,
    /** 扇形、浮動選單下面那層半透明底（兩種主題都是深色，上面的白色選項才看得清楚） */
    val shade: Int,
    val chip: Int,
    val chipText: Int,
    val danger: Int,
) {
    companion object {
        val DARK = Palette(
            dark = true,
            background = 0xFF1B1B1F.toInt(),
            key = 0xFF4A4A50.toInt(),
            keyFn = 0xFF34343A.toInt(),
            keyDown = 0xFF7A7A82.toInt(),
            surface = 0xFF26262B.toInt(),
            text = 0xFFF2F2F2.toInt(),
            subText = 0xFFB0B0B8.toInt(),
            accent = 0xFF8AB4F8.toInt(),
            divider = 0x66FFFFFF,
            shade = 0x99101014.toInt(),
            chip = 0xFFE8E8EC.toInt(),
            chipText = 0xFF1B1B1F.toInt(),
            danger = 0xFFFF8A80.toInt(),
        )

        val LIGHT = Palette(
            dark = false,
            background = 0xFFE4E6EA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyFn = 0xFFCDD1D7.toInt(),
            keyDown = 0xFFA9AEB6.toInt(),
            surface = 0xFFF4F5F7.toInt(),
            text = 0xFF202124.toInt(),
            subText = 0xFF5F6368.toInt(),
            accent = 0xFF1A73E8.toInt(),
            divider = 0x33000000,
            shade = 0x77202124,
            chip = 0xFFFFFFFF.toInt(),
            chipText = 0xFF202124.toInt(),
            danger = 0xFFD93025.toInt(),
        )

        const val PREF_THEME = "theme"
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        fun of(context: Context): Palette {
            val choice = context.getSharedPreferences(UserData.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_THEME, THEME_SYSTEM)
            val dark = when (choice) {
                THEME_DARK -> true
                THEME_LIGHT -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return if (dark) DARK else LIGHT
        }

        /** 淺色主題時，系統列（狀態列、導覽列）的圖示改成深色，才看得清楚 */
        fun applySystemBars(window: android.view.Window?, p: Palette) {
            if (window == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else mask, mask)
        }

        /** 設定頁的視窗主題（系統元件例如按鈕、滑桿、對話框跟著深淺色） */
        fun activityTheme(p: Palette) =
            if (p.dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar
    }
}
