package tw.fanime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 自繪鍵盤。用 Canvas 畫按鍵、自己處理觸控，包含扇形手勢（依拖曳角度選擇選項）。
 */
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onToggleLanguage()
        fun onFanSelect(target: FanTarget)
        /** 工具列和編輯功能表的按鍵 */
        fun onCommand(action: KeyAction)
        fun onTableBack()
        fun onTablePage(delta: Int)
        fun onTogglePin()
    }

    private class Key(val spec: KeySpec, val rect: RectF, val toolbar: Boolean = false)

    private enum class Shift { OFF, ONCE, LOCKED }

    private val density = resources.displayMetrics.density
    // TODO: 之後做成設定頁可調整的參數
    private val rowHeight = 42f * density
    /** 按鍵區固定為 5 列高；列數較少的配置（例如編輯功能表）會把每列拉高填滿 */
    private val fixedRows = 5
    private val toolbarHeight = 34f * density
    private val gap = 3f * density
    private val corner = 6f * density
    /** 滑動距離超過這個值才算「滑」，否則視為輕點 */
    private val flickThreshold = 18f * density

    // 扇形參數
    /** 手指離扇形鍵中心超過這個距離才算選到選項；在這之內放開就是取消 */
    private val fanDeadZone = 24f * density
    /** 選項標籤離扇形鍵中心的距離 */
    private val fanRadius = 150f * density
    /** 扇形範圍：從正右方（0°）到正上方再往左 15°（105°） */
    private val fanMaxDeg = 105.0
    /** 按住不動多久才畫出扇形提示（熟練後直接滑就好，不需要等） */
    private val fanHintMs = 200L

    private val bgPaint = Paint().apply { color = 0xFF1B1B1F.toInt() }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4A50.toInt() }
    private val keyFnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34343A.toInt() }
    private val keyDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A7A82.toInt() }
    private val accent = 0xFF8AB4F8.toInt()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F2F2.toInt(); textAlign = Paint.Align.CENTER; textSize = 22f * density
    }
    private val smallTextPaint = Paint(textPaint).apply { textSize = 13f * density }
    private val accentTextPaint = Paint(textPaint).apply { color = accent }
    private val fanShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99101014.toInt() }
    private val fanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; strokeWidth = 1.5f * density
    }
    private val fanChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E8EC.toInt() }
    private val fanChipOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val fanChipTextPaint = Paint(textPaint).apply { color = 0xFF1B1B1F.toInt(); textSize = 18f * density }

    private var rows: List<KeyRow> = Layouts.english
    private var keys: List<Key> = emptyList()
    private var shift = Shift.OFF
    private val shifted get() = shift != Shift.OFF
    private var lastShiftTap = 0L
    /** 底部留給系統導覽列（縮小鍵盤、切換輸入法、手勢列）的高度 */
    var bottomInset = 0
        private set

    /** 工具列上要用強調色標示的按鍵（例如編輯功能表開著時的 ☰） */
    var activeToolbarAction: KeyAction? = null
        set(value) { field = value; invalidate() }

    private var downKey: Key? = null
    private var downX = 0f
    private var downY = 0f

    private var fanVisible = false
    private var fanSelected: FanTarget? = null

    private val handler = Handler(Looper.getMainLooper())
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            listener.onBackspace()
            handler.postDelayed(this, 50)
        }
    }
    private val showFanHint = Runnable {
        fanVisible = true
        invalidate()
    }

    /** 換一組按鍵配置（英文鍵盤、符號表、編輯功能表…），整體高度固定不變 */
    fun setRows(newRows: List<KeyRow>) {
        rows = newRows
        layoutKeys(width)
        invalidate()
    }

    /**
     * 手勢導覽模式下，系統回報的導覽列 inset 只有手勢列那麼高，
     * 但鍵盤出現時系統會在底部畫「切換輸入法」「縮小鍵盤」按鈕，佔的是導覽列框架的高度。
     * 所以取兩者較大的值，至少留 48dp。
     */
    private val minNavFrame: Int = run {
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        maxOf(if (id != 0) resources.getDimensionPixelSize(id) else 0, (48 * density).toInt())
    }

    /**
     * 系統導覽模式：0 = 三按鈕、1 = 兩按鈕、2 = 手勢。
     * 手勢模式下，系統不論直向橫向都會在鍵盤底部畫「切換輸入法」「縮小鍵盤」按鈕。
     */
    private fun isGestureNav(): Boolean = try {
        android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode") == 2
    } catch (_: Exception) {
        false
    }

    private fun bottomInsetFor(insets: WindowInsets?): Int {
        val reported = when {
            insets == null -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else -> @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return when {
            // 手勢模式橫向時系統只畫手勢列，不畫「切換輸入法」「縮小鍵盤」，留一點空白就好
            isGestureNav() && landscape -> maxOf(reported, (12 * density).toInt())
            // 直向：系統的「切換輸入法」「縮小鍵盤」圖示畫在導覽列框架（48dp）正中間，
            // 圖示頂端約在 36dp，所以留 38dp 就不會壓到圖示（使用者希望盡量矮）
            isGestureNav() -> maxOf(reported, (38 * density).toInt())
            // 三按鈕模式橫向時導覽列在側邊，底部不需要留白
            reported > 0 -> maxOf(reported, minNavFrame)
            else -> 0
        }
    }

    private fun updateBottomInset(insets: WindowInsets?) {
        val bottom = bottomInsetFor(insets)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
            android.util.Log.d(
                "FanIME",
                "inset reported=${insets.getInsets(WindowInsets.Type.navigationBars()).bottom} " +
                    "tappable=${insets.getInsets(WindowInsets.Type.tappableElement()).bottom} " +
                    "minNavFrame=$minNavFrame density=$density -> bottom=$bottom",
            )
        }
        if (bottom != bottomInset) {
            bottomInset = bottom
            requestLayout()
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateBottomInset(insets)
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 旋轉螢幕後鍵盤會重建，新的 View 不一定會收到 insets，主動讀一次再要求重新派送
        updateBottomInset(rootWindowInsets)
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (toolbarHeight + fixedRows * rowHeight + gap * 2).toInt() + bottomInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w)
    }

    private fun layoutKeys(width: Int) {
        if (width <= 0) return
        val result = mutableListOf<Key>()
        val toolUnit = width / Layouts.toolbar.size.toFloat()
        Layouts.toolbar.forEachIndexed { i, spec ->
            result += Key(spec, RectF(i * toolUnit, 0f, (i + 1) * toolUnit, toolbarHeight), toolbar = true)
        }
        val rowH = fixedRows * rowHeight / rows.size
        rows.forEachIndexed { r, row ->
            val unit = width / row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            val top = toolbarHeight + gap + r * rowH
            for (spec in row) {
                val w = spec.weight * unit
                result += Key(spec, RectF(x + gap, top + gap, x + w - gap, top + rowH - gap))
                x += w
            }
        }
        keys = result
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (key in keys) {
            val action = key.spec.action
            if (action == KeyAction.Spacer) continue
            if (key.toolbar) {
                // 工具列只畫圖示，按下或啟用時才畫底色
                if (key === downKey || action == activeToolbarAction) {
                    canvas.drawRoundRect(key.rect, corner, corner, keyFnPaint)
                }
            } else if (action != KeyAction.Label) {
                val paint = when {
                    key === downKey -> keyDownPaint
                    action is KeyAction.Text -> keyPaint
                    else -> keyFnPaint
                }
                canvas.drawRoundRect(key.rect, corner, corner, paint)
            }
            drawLabel(canvas, key)
        }
        if (fanVisible) downKey?.let { drawFan(canvas, it) }
    }

    private fun drawLabel(canvas: Canvas, key: Key) {
        val spec = key.spec
        val cx = key.rect.centerX()
        val cy = key.rect.centerY()
        val action = spec.action
        if (action is KeyAction.FlickPunct) {
            // 標籤照方向排成 2×2：↖ ↗ / ↙ ↘
            val dx = key.rect.width() / 5
            val dy = key.rect.height() / 5
            val base = -(smallTextPaint.ascent() + smallTextPaint.descent()) / 2
            canvas.drawText(action.upLeft, cx - dx, cy - dy + base, smallTextPaint)
            canvas.drawText(action.upRight, cx + dx, cy - dy + base, smallTextPaint)
            canvas.drawText(action.downLeft, cx - dx, cy + dy + base, smallTextPaint)
            canvas.drawText(action.downRight, cx + dx, cy + dy + base, smallTextPaint)
            return
        }
        val label = if (shifted && action is KeyAction.Text) spec.label.uppercase() else spec.label
        val paint = when {
            action == KeyAction.Shift && shifted -> accentTextPaint
            action == KeyAction.TogglePin && label == "釘選" -> accentTextPaint
            // 文字太長（例如「全形空白」「1/2」）時用小字
            label.length > 2 || action == KeyAction.Label -> smallTextPaint
            else -> textPaint
        }
        canvas.drawText(label, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
        if (action == KeyAction.Shift && shift == Shift.LOCKED) {
            // 鎖定大寫時在鍵下緣畫一條線
            val y = key.rect.bottom - key.rect.height() / 6
            canvas.drawLine(cx - key.rect.width() / 5, y, cx + key.rect.width() / 5, y, paint)
        }
    }

    /** 從扇形鍵中心放射出各選項：半透明底、分隔線、選項標籤 */
    private fun drawFan(canvas: Canvas, key: Key) {
        val cx = key.rect.centerX()
        val cy = key.rect.centerY()
        val targets = FanTarget.entries
        val step = fanMaxDeg / targets.size
        val outer = fanRadius + 30f * density
        val oval = RectF(cx - outer, cy - outer, cx + outer, cy + outer)
        // drawArc 的角度是順時針、從 +x 起算，所以往上的角度要取負號
        canvas.drawArc(oval, -fanMaxDeg.toFloat(), fanMaxDeg.toFloat(), true, fanShadePaint)
        for (i in 0..targets.size) {
            val rad = Math.toRadians(step * i)
            canvas.drawLine(cx, cy, cx + (outer * cos(rad)).toFloat(), cy - (outer * sin(rad)).toFloat(), fanLinePaint)
        }
        targets.forEachIndexed { i, t ->
            // targets 依序從左上（大角度）排到右（小角度）
            val rad = Math.toRadians(fanMaxDeg - step * (i + 0.5))
            val x = cx + (fanRadius * cos(rad)).toFloat()
            val y = cy - (fanRadius * sin(rad)).toFloat()
            val half = 20f * density
            val chip = RectF(x - half, y - half, x + half, y + half)
            canvas.drawRoundRect(chip, corner, corner, if (t == fanSelected) fanChipOnPaint else fanChipPaint)
            canvas.drawText(t.label, x, y - (fanChipTextPaint.ascent() + fanChipTextPaint.descent()) / 2, fanChipTextPaint)
        }
    }

    /** 依手指相對扇形鍵中心的位置判斷選項；在 dead zone 內回傳 null（取消） */
    private fun fanTargetAt(dx: Float, dy: Float): FanTarget? {
        if (hypot(dx, dy) < fanDeadZone) return null
        // 螢幕的 y 軸向下，換成數學角度要取負號；範圍 -180°~180°
        var deg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        // 超出扇形範圍時歸到最近的一端：往下偏右算最右邊，往左（含左下）算最左邊
        if (deg < 0) deg = if (deg > -90) 0.0 else fanMaxDeg
        deg = deg.coerceIn(0.0, fanMaxDeg)
        val targets = FanTarget.entries
        val step = fanMaxDeg / targets.size
        val fromLeft = ((fanMaxDeg - deg) / step).toInt().coerceIn(0, targets.size - 1)
        return targets[fromLeft]
    }

    private fun keyAt(x: Float, y: Float): Key? =
        keys.firstOrNull { it.spec.action.isTouchable() && it.rect.contains(x, y) }
            // 落在按鍵間隙時，找距離最近的鍵，避免邊緣點不到
            ?: keys.filter { it.spec.action.isTouchable() }
                .minByOrNull { hypot(it.rect.centerX() - x, it.rect.centerY() - y) }

    private fun KeyAction.isTouchable() = this != KeyAction.Spacer && this != KeyAction.Label

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downKey = keyAt(event.x, event.y)
                downX = event.x
                downY = event.y
                when (downKey?.spec?.action) {
                    KeyAction.Backspace -> {
                        listener.onBackspace()
                        handler.postDelayed(backspaceRepeat, 400)
                    }
                    KeyAction.Fan -> handler.postDelayed(showFanHint, fanHintMs)
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (downKey?.spec?.action == KeyAction.Fan && fanVisible) {
                    val sel = fanTargetAt(event.x - downX, event.y - downY)
                    if (sel != fanSelected) { fanSelected = sel; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                stopTimers()
                downKey?.let { fire(it, event.x - downX, event.y - downY) }
                resetTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                stopTimers()
                resetTouch()
            }
        }
        return true
    }

    private fun stopTimers() {
        handler.removeCallbacks(backspaceRepeat)
        handler.removeCallbacks(showFanHint)
    }

    private fun resetTouch() {
        downKey = null
        fanVisible = false
        fanSelected = null
        invalidate()
    }

    private fun fire(key: Key, dx: Float, dy: Float) {
        when (val action = key.spec.action) {
            is KeyAction.Text -> {
                listener.onText(if (shifted) action.text.uppercase() else action.text)
                if (shift == Shift.ONCE) { shift = Shift.OFF; invalidate() }
            }
            is KeyAction.FlickPunct -> listener.onText(flick(action, dx, dy))
            KeyAction.Space -> listener.onText(" ")
            KeyAction.Enter -> listener.onEnter()
            KeyAction.Shift -> { onShiftTap(); invalidate() }
            KeyAction.ToggleLanguage -> listener.onToggleLanguage()
            // 不需要先按住：一滑就依方向觸發；在中心附近放開（含輕點）就取消
            KeyAction.Fan -> fanTargetAt(dx, dy)?.let(listener::onFanSelect)
            KeyAction.Settings, KeyAction.Voice, KeyAction.TradSimp,
            KeyAction.CursorLeft, KeyAction.CursorRight,
            KeyAction.EditMenu, KeyAction.HideKeyboard, is KeyAction.Edit -> listener.onCommand(action)
            KeyAction.TableBack -> listener.onTableBack()
            KeyAction.TablePrev -> listener.onTablePage(-1)
            KeyAction.TableNext -> listener.onTablePage(1)
            KeyAction.TogglePin -> listener.onTogglePin()
            KeyAction.Backspace, KeyAction.Spacer, KeyAction.Label -> Unit // 倒退鍵在按下時就處理了
        }
    }

    /** 點一下：只對下一個字母大寫；快速點兩下：鎖定大寫；鎖定中再點一下：解除 */
    private fun onShiftTap() {
        val now = System.currentTimeMillis()
        shift = when {
            shift == Shift.LOCKED -> Shift.OFF
            shift == Shift.ONCE && now - lastShiftTap < 350 -> Shift.LOCKED
            shift == Shift.ONCE -> Shift.OFF
            else -> Shift.ONCE
        }
        lastShiftTap = now
    }

    /**
     * 依滑動落在哪個象限決定標點；滑動距離太短視為輕點。
     * 邊界歸屬照實測超注音：正上 → ↖、正下 → ↙、正左 → ↙、正右 → ↘
     */
    private fun flick(p: KeyAction.FlickPunct, dx: Float, dy: Float): String {
        if (hypot(dx, dy) < flickThreshold) return p.tap
        val up = dy < 0
        val left = dx <= 0
        return when {
            up && left -> p.upLeft
            up -> p.upRight
            left -> p.downLeft
            else -> p.downRight
        }
    }
}
