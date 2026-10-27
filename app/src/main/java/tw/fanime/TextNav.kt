package tw.fanime

import android.view.KeyEvent

/**
 * 在純文字上計算游標移動後的位置（以換行字元判斷行，不考慮畫面上的自動折行）。
 * 用於「開選」狀態：輸入法自己維護選取範圍，不依賴 App 處理 Shift+方向鍵。
 */
object TextNav {
    fun move(s: String, i: Int, code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (i > 0) i - Character.charCount(s.codePointBefore(i)) else 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (i < s.length) i + Character.charCount(s.codePointAt(i)) else s.length
        KeyEvent.KEYCODE_MOVE_HOME -> lineStart(s, i)
        KeyEvent.KEYCODE_MOVE_END -> lineEnd(s, i)
        KeyEvent.KEYCODE_DPAD_UP -> {
            val start = lineStart(s, i)
            if (start == 0) {
                0
            } else {
                val prevStart = lineStart(s, start - 1)
                prevStart + minOf(i - start, start - 1 - prevStart)
            }
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            val end = lineEnd(s, i)
            if (end == s.length) {
                s.length
            } else {
                val nextStart = end + 1
                nextStart + minOf(i - lineStart(s, i), lineEnd(s, nextStart) - nextStart)
            }
        }
        else -> i
    }

    private fun lineStart(s: String, i: Int) = if (i <= 0) 0 else s.lastIndexOf('\n', i - 1) + 1

    private fun lineEnd(s: String, i: Int) = s.indexOf('\n', i).let { if (it < 0) s.length else it }
}
