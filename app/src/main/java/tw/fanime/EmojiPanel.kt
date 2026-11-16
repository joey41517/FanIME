package tw.fanime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.emoji2.emojipicker.RecentEmojiProvider
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

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
    private val recent = RecentEmojis(context)

    /**
     * 每次打開都回到「最近使用過」：它是選擇器的第一個分類，
     * 所以把選擇器裡的清單（分類列和 Emoji 清單）都捲回最前面。
     */
    fun showRecent() {
        fun scrollAll(v: View) {
            if (v is RecyclerView) v.scrollToPosition(0)
            if (v is ViewGroup) for (i in 0 until v.childCount) scrollAll(v.getChildAt(i))
        }
        reloadRecent()
        scrollAll(picker)
    }

    /** 選擇器的清單是非同步建立的，所以在第一次觸控時才掛長按監聽 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) hookLongPress(picker)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 選擇器點了 Emoji 之後不會馬上重畫「最近使用過」，畫面上的順序和存檔可能不同，
     * 所以記下選擇器目前顯示的清單（重新讀取時更新），長按時用它對位置。
     */
    private var shownRecent: List<String> = emptyList()

    private fun reloadRecent() {
        shownRecent = recent.list()
        picker.setRecentEmojiProvider(recent)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        picker = EmojiPickerView(getContext()).apply {
            emojiGridColumns = 9
            setRecentEmojiProvider(recent)
            setOnEmojiPickedListener { listener.onEmoji(it.emoji) }
        }
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(getContext()).apply { orientation = HORIZONTAL }
        bar.addView(button("返回") { listener.onBack() }, LayoutParams(0, (46 * density).toInt(), 1f))
        bar.addView(button("⌫") { listener.onBackspace() }, LayoutParams(0, (46 * density).toInt(), 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ---- 長按「最近使用過」裡的 Emoji：從最近使用移除 ----

    private var hookedBody: RecyclerView? = null
    private val longPressHandler = Handler(Looper.getMainLooper())

    /**
     * 選擇器的 Emoji 清單是內部的 RecyclerView（載入完才建立），找到後掛一個觸控監聽。
     * 選擇器本身只對有膚色變化的 Emoji 處理長按，所以這裡自己計時。
     */
    private fun hookLongPress(root: View) {
        val body = findBody(root) ?: return
        if (body === hookedBody) return
        hookedBody = body
        body.addOnItemTouchListener(RecentLongPress(body))
    }

    /** Emoji 清單是最大的那個 RecyclerView（另一個是上面的分類列） */
    private fun findBody(root: View): RecyclerView? {
        val all = mutableListOf<RecyclerView>()
        fun walk(v: View) {
            if (v is RecyclerView) all += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return all.maxByOrNull { it.height }
    }

    private inner class RecentLongPress(private val body: RecyclerView) : RecyclerView.OnItemTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var target: View? = null
        private var fired = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val check = Runnable {
            val v = target ?: return@Runnable
            val emoji = recentEmojiAt(v) ?: return@Runnable
            fired = true
            v.cancelLongPress() // 不要再跳出膚色選單
            v.isPressed = false
            recent.remove(emoji)
            reloadRecent()
            Toast.makeText(context, "已從最近使用移除 $emoji", Toast.LENGTH_SHORT).show()
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    downX = e.x
                    downY = e.y
                    target = rv.findChildViewUnder(e.x, e.y)
                    // 比系統長按稍早觸發，搶在膚色選單之前
                    longPressHandler.postDelayed(check, (ViewConfiguration.getLongPressTimeout() * 0.9).toLong())
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(e.x - downX, e.y - downY) > slop) longPressHandler.removeCallbacks(check)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> longPressHandler.removeCallbacks(check)
            }
            // 長按已觸發：吃掉剩下的觸控，放開時不要輸入這個 Emoji
            return fired
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        /** 只有「最近使用過」分類裡的格子才算：它是清單最前面一段（標題之後的 N 格） */
        private fun recentEmojiAt(v: View): String? {
            val pos = body.getChildAdapterPosition(v)
            val list = shownRecent
            if (pos < 1 || pos > list.size) return null
            val emoji = emojiOf(v) ?: return null
            return emoji.takeIf { it == list[pos - 1] }
        }
    }

    /** 選擇器的 EmojiView 是函式庫內部類別，用反射讀它的 emoji */
    private fun emojiOf(v: View): String? = try {
        v.javaClass.getMethod("getEmoji").invoke(v)?.toString()
    } catch (_: Exception) {
        null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(palette.text)
        setBackgroundColor(palette.keyFn)
        setOnClickListener { onClick() }
    }
}

/**
 * 最近使用的 Emoji。存放方式和 androidx 內建的一樣（同一個檔案、逗號分隔），
 * 所以換成這個之後原本的紀錄還在；多了 [remove] 讓使用者刪掉誤按的。
 */
class RecentEmojis(context: Context) : RecentEmojiProvider {
    private val prefs = context.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
    private val items: MutableList<String> =
        prefs.getString(KEY, null)?.split(',')?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()

    fun list(): List<String> = items.toList()

    override suspend fun getRecentEmojiList(): List<String> = list()

    override fun recordSelection(emoji: String) {
        items.remove(emoji)
        items.add(0, emoji)
        save()
    }

    fun remove(emoji: String) {
        items.remove(emoji)
        save()
    }

    private fun save() = prefs.edit().putString(KEY, items.joinToString(",")).apply()

    private companion object {
        const val KEY = "pref_key_recent_emoji"
    }
}
