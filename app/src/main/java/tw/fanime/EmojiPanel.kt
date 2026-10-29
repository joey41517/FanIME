package tw.fanime

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.emoji2.emojipicker.EmojiPickerView

/** Emoji 面板：上面是 androidx 的 EmojiPickerView，下面一列放「返回」和「⌫」 */
class EmojiPanel(context: Context, private val listener: Listener) :
    LinearLayout(
        ContextThemeWrapper(
            context,
            if (Palette.of(context).dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light,
        ),
    ) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onBackspace()
        fun onBack()
    }

    private val density = resources.displayMetrics.density
    private val palette = Palette.of(context)
    private lateinit var picker: EmojiPickerView

    /**
     * 每次打開都回到「最近使用過」：它是選擇器的第一個分類，
     * 所以把選擇器裡的清單（分類列和 Emoji 清單）都捲回最前面。
     */
    fun showRecent() {
        fun scrollAll(v: android.view.View) {
            if (v is androidx.recyclerview.widget.RecyclerView) v.scrollToPosition(0)
            if (v is ViewGroup) for (i in 0 until v.childCount) scrollAll(v.getChildAt(i))
        }
        scrollAll(picker)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        picker = EmojiPickerView(getContext()).apply {
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
        setTextColor(palette.text)
        setBackgroundColor(palette.keyFn)
        setOnClickListener { onClick() }
    }
}
