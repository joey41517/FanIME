package tw.fanime

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.emoji2.emojipicker.EmojiPickerView

/** Emoji 面板：上面是 androidx 的 EmojiPickerView，下面一列放「返回」和「⌫」 */
class EmojiPanel(context: Context, private val listener: Listener) :
    LinearLayout(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onBackspace()
        fun onBack()
    }

    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1B1F.toInt())
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val picker = EmojiPickerView(getContext()).apply {
            emojiGridColumns = 9
            setOnEmojiPickedListener { listener.onEmoji(it.emoji) }
        }
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(getContext()).apply { orientation = HORIZONTAL }
        bar.addView(button("返回") { listener.onBack() }, LayoutParams(0, (46 * density).toInt(), 1f))
        bar.addView(button("⌫") { listener.onBackspace() }, LayoutParams(0, (46 * density).toInt(), 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF34343A.toInt())
        setOnClickListener { onClick() }
    }
}
