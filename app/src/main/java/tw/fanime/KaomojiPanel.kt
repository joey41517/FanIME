package tw.fanime

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tw.fanime.kaomoji.KaomojiCategory

/**
 * 顏文字面板：上面一列是分類，中間是顏文字（依寬度佔 1~4 格），下面一列放「返回」「編輯」「⌫」。
 * 清單內容來自使用者可編輯的文字檔，每次打開都回到第一個分類（首頁）。
 */
class KaomojiPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onKaomoji(text: String)
        fun onBackspace()
        fun onBack()
        fun onEdit()
    }

    private val density = resources.displayMetrics.density
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val tabScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabs)
    }
    private val adapter = Adapter()
    private val grid = RecyclerView(context)
    private var categories: List<KaomojiCategory> = emptyList()
    private var current = 0

    private val textPaint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ITEM_TEXT_SP, resources.displayMetrics)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1B1F.toInt())
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        addView(tabScroll, LayoutParams(LayoutParams.MATCH_PARENT, (40 * density).toInt()))

        val layout = GridLayoutManager(context, COLUMNS)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = spanFor(adapter.items[position])
        }
        grid.layoutManager = layout
        grid.adapter = adapter
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        val h = (46 * density).toInt()
        bar.addView(button("返回") { listener.onBack() }, LayoutParams(0, h, 1f))
        bar.addView(button("✎ 編輯") { listener.onEdit() }, LayoutParams(0, h, 1f))
        bar.addView(button("⌫") { listener.onBackspace() }, LayoutParams(0, h, 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 顯示清單並回到首頁（第一個分類） */
    fun show(list: List<KaomojiCategory>) {
        categories = list
        tabs.removeAllViews()
        list.forEachIndexed { i, cat ->
            tabs.addView(
                TextView(context).apply {
                    text = cat.name
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                    setOnClickListener { select(i) }
                },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
        }
        select(0)
        tabScroll.scrollTo(0, 0)
    }

    private fun select(index: Int) {
        current = index
        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as TextView).apply {
                setTextColor(if (i == index) Color.WHITE else 0xFF9A9AA2.toInt())
                setBackgroundColor(if (i == index) 0xFF34343A.toInt() else Color.TRANSPARENT)
            }
        }
        adapter.items = categories.getOrNull(index)?.items.orEmpty()
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
    }

    /** 依文字寬度決定佔幾格，太長的顏文字一行一個 */
    private fun spanFor(text: String): Int {
        val width = (if (grid.width > 0) grid.width else resources.displayMetrics.widthPixels) / COLUMNS.toFloat()
        val need = textPaint.measureText(text) + 16 * density
        return (need / width).toInt().plus(1).coerceIn(1, COLUMNS)
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF34343A.toInt())
        setOnClickListener { onClick() }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<String> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = TextView(parent.context).apply {
                textSize = ITEM_TEXT_SP
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF26262B.toInt())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                    val m = (2 * density).toInt()
                    setMargins(m, m, m, m)
                }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val text = items[position]
            (holder.itemView as TextView).apply {
                this.text = text
                setOnClickListener { listener.onKaomoji(text) }
            }
        }
    }

    private companion object {
        const val COLUMNS = 4
        const val ITEM_TEXT_SP = 16f
    }
}
