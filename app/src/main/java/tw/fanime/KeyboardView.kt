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
        fun onBopomofo(symbol: String)
        fun onJamo(jamo: Char)
        fun onCandidate(index: Int)
        fun onCandidateLongPress(index: Int)
        fun onSpace()
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
    private val overlayPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26262B.toInt() }

    private var rows: List<KeyRow> = Layouts.english
    private var keys: List<Key> = emptyList()

    /**
     * 浮在鍵盤上的選單（編輯功能表）：靠右上、佔按鍵區約 4/5，
     * 其他地方蓋一層半透明的底（像對話框的 backdrop），點底就關閉選單。
     */
    private var overlayRows: List<KeyRow>? = null
    private var overlayKeys: List<Key> = emptyList()
    private var overlayRect = RectF()
    /** 按下的是選單外的半透明底 */
    private var backdropDown = false
    private var shift = Shift.OFF
    private val shifted get() = shift != Shift.OFF
    private var lastShiftTap = 0L
    /** 底部留給系統導覽列（縮小鍵盤、切換輸入法、手勢列）的高度 */
    var bottomInset = 0
        private set

    // 候選字列：有候選字時取代工具列，可以左右滑動；最右邊的 ▾ 展開成整片候選字格
    private enum class CandTouch { NONE, STRIP, EXPAND, GRID }

    private var candidates: List<String> = emptyList()
    private var candidateRects: List<RectF> = emptyList()
    private var candidateScroll = 0f
    private var candTouch = CandTouch.NONE
    private var candidateDragging = false
    /** 長按已觸發（記錄字詞），放開時不要再選字 */
    private var candidateLongPressed = false
    private var candidateScrollStart = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val candidatePaint = Paint(textPaint).apply { textSize = 19f * density }
    private val candidateFirstPaint = Paint(candidatePaint).apply { color = accent }
    private val candidatePad = 12f * density
    private val expandButtonWidth = 40f * density

    /** 展開狀態：候選字格蓋住按鍵區，可以上下捲動 */
    private var expanded = false
    private var gridRects: List<RectF> = emptyList()
    private var gridScroll = 0f
    private val gridCellHeight = 46f * density
    /** 長按候選字多久算「記錄字詞」 */
    private val candidateLongPressMs = 500L

    /** 長按候選字 → 記錄字詞 */
    private var pressedCandidate = -1
    private val candidateLongPress = Runnable {
        if (!candidateDragging && pressedCandidate >= 0) {
            candidateLongPressed = true
            listener.onCandidateLongPress(pressedCandidate)
        }
    }

    fun setCandidates(list: List<String>) {
        candidates = list
        var x = 0f
        candidateRects = list.map { text ->
            val w = candidatePaint.measureText(text) + candidatePad * 2
            RectF(x, 0f, x + w, toolbarHeight).also { x += w }
        }
        candidateScroll = 0f
        // 候選字換了（例如選了字）就收起展開的候選字格
        expanded = false
        gridScroll = 0f
        invalidate()
    }

    private fun stripWidth() = width - expandButtonWidth

    private fun maxCandidateScroll() = maxOf(0f, (candidateRects.lastOrNull()?.right ?: 0f) - stripWidth())

    private fun gridTop() = toolbarHeight
    private fun gridBottom() = height - bottomInset.toFloat()

    /** 候選字格：由左到右排，每格至少 1/6 寬，放不下就換行 */
    private fun layoutGrid() {
        val minW = width / 6f
        var x = 0f
        var y = 0f
        gridRects = candidates.map { text ->
            val w = maxOf(minW, candidatePaint.measureText(text) + candidatePad * 2).coerceAtMost(width.toFloat())
            if (x + w > width) { x = 0f; y += gridCellHeight }
            RectF(x, y, x + w, y + gridCellHeight).also { x += w }
        }
    }

    private fun maxGridScroll() = maxOf(0f, (gridRects.lastOrNull()?.bottom ?: 0f) - (gridBottom() - gridTop()))

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

    /** 顯示（或傳 null 收起）浮在鍵盤上的選單 */
    fun setOverlay(newRows: List<KeyRow>?) {
        overlayRows = newRows
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
        layoutOverlay(width)
    }

    private fun layoutOverlay(width: Int) {
        val rows = overlayRows ?: run { overlayKeys = emptyList(); return }
        val contentTop = toolbarHeight + gap
        val contentHeight = fixedRows * rowHeight
        overlayRect = RectF(width * (1 - OVERLAY_FRACTION), contentTop, width.toFloat(), contentTop + contentHeight * OVERLAY_FRACTION)
        val rowH = overlayRect.height() / rows.size
        val result = mutableListOf<Key>()
        rows.forEachIndexed { r, row ->
            val unit = overlayRect.width() / row.sumOf { it.weight.toDouble() }.toFloat()
            var x = overlayRect.left
            val top = overlayRect.top + r * rowH
            for (spec in row) {
                val w = spec.weight * unit
                result += Key(spec, RectF(x + gap, top + gap, x + w - gap, top + rowH - gap))
                x += w
            }
        }
        overlayKeys = result
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (candidates.isNotEmpty()) drawCandidates(canvas)
        for (key in keys) {
            val action = key.spec.action
            if (action == KeyAction.Spacer) continue
            if (key.toolbar) {
                if (candidates.isNotEmpty()) continue
                // 工具列只畫圖示，按下或啟用時才畫底色
                if (key === downKey || action == activeToolbarAction) {
                    canvas.drawRoundRect(key.rect, corner, corner, keyFnPaint)
                }
            } else if (action != KeyAction.Label) {
                val paint = when {
                    key === downKey -> keyDownPaint
                    action is KeyAction.Text || action is KeyAction.Bopomofo || action is KeyAction.Jamo -> keyPaint
                    else -> keyFnPaint
                }
                canvas.drawRoundRect(key.rect, corner, corner, paint)
            }
            drawLabel(canvas, key)
        }
        if (overlayRows != null) drawOverlay(canvas)
        if (fanVisible) downKey?.let { drawFan(canvas, it) }
        if (expanded) drawGrid(canvas)
    }

    /** 半透明底蓋住按鍵區（工具列不蓋，☰ 還能點），再畫選單 */
    private fun drawOverlay(canvas: Canvas) {
        canvas.drawRect(0f, toolbarHeight, width.toFloat(), height.toFloat(), fanShadePaint)
        canvas.drawRoundRect(overlayRect, corner, corner, overlayPanelPaint)
        for (key in overlayKeys) {
            canvas.drawRoundRect(key.rect, corner, corner, if (key === downKey) keyDownPaint else keyFnPaint)
            drawLabel(canvas, key)
        }
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
        val label = when {
            shifted && action is KeyAction.Text -> spec.label.uppercase()
            shifted && action is KeyAction.Jamo -> action.shifted.toString()
            else -> spec.label
        }
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

    private fun drawCenteredText(canvas: Canvas, text: String, r: RectF, paint: Paint) {
        canvas.drawText(text, r.centerX(), r.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
    }

    /** 候選字列：第一個（按空白會上的那個）用強調色；最右邊是展開鍵 */
    private fun drawCandidates(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, 0f, stripWidth(), toolbarHeight)
        canvas.translate(-candidateScroll, 0f)
        candidates.forEachIndexed { i, text ->
            val r = candidateRects[i]
            if (r.right < candidateScroll || r.left > candidateScroll + stripWidth()) return@forEachIndexed
            drawCenteredText(canvas, text, r, if (i == 0) candidateFirstPaint else candidatePaint)
            if (i > 0) canvas.drawLine(r.left, r.top + r.height() / 4, r.left, r.bottom - r.height() / 4, fanLinePaint)
        }
        canvas.restore()
        val btn = RectF(stripWidth(), 0f, width.toFloat(), toolbarHeight)
        canvas.drawRect(btn, keyFnPaint)
        drawCenteredText(canvas, if (expanded) "▴" else "▾", btn, textPaint)
    }

    /** 展開的候選字格：蓋住按鍵區 */
    private fun drawGrid(canvas: Canvas) {
        val top = gridTop()
        canvas.drawRect(0f, top, width.toFloat(), gridBottom(), bgPaint)
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), gridBottom())
        canvas.translate(0f, top - gridScroll)
        gridRects.forEachIndexed { i, r ->
            val inset = RectF(r.left + gap, r.top + gap, r.right - gap, r.bottom - gap)
            canvas.drawRoundRect(inset, corner, corner, if (i == pressedCandidate && candTouch == CandTouch.GRID) keyDownPaint else keyFnPaint)
            drawCenteredText(canvas, candidates[i], inset, if (i == 0) candidateFirstPaint else candidatePaint)
        }
        canvas.restore()
    }

    private fun candidateIndexInStrip(x: Float): Int {
        val cx = x + candidateScroll
        return candidateRects.indexOfFirst { cx >= it.left && cx < it.right }
    }

    private fun candidateIndexInGrid(x: Float, y: Float): Int {
        val gy = y - gridTop() + gridScroll
        return gridRects.indexOfFirst { it.contains(x, gy) }
    }

    /** 候選字列／候選字格的觸控開始；回傳 true 表示由候選字處理 */
    private fun onCandidateDown(event: MotionEvent): Boolean {
        if (candidates.isEmpty()) return false
        candTouch = when {
            event.y < toolbarHeight && event.x >= stripWidth() -> CandTouch.EXPAND
            event.y < toolbarHeight -> CandTouch.STRIP
            expanded && event.y < gridBottom() -> CandTouch.GRID
            else -> return false
        }
        candidateDragging = false
        candidateLongPressed = false
        downX = event.x
        downY = event.y
        candidateScrollStart = if (candTouch == CandTouch.GRID) gridScroll else candidateScroll
        pressedCandidate = when (candTouch) {
            CandTouch.STRIP -> candidateIndexInStrip(event.x)
            CandTouch.GRID -> candidateIndexInGrid(event.x, event.y)
            else -> -1
        }
        if (pressedCandidate >= 0) handler.postDelayed(candidateLongPress, candidateLongPressMs)
        invalidate()
        return true
    }

    /** 候選字列左右拖曳、候選字格上下拖曳；沒拖曳也沒長按就是選字 */
    private fun onCandidateTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val moved = if (candTouch == CandTouch.GRID) event.y - downY else event.x - downX
                if (!candidateDragging && kotlin.math.abs(moved) > touchSlop) {
                    candidateDragging = true
                    handler.removeCallbacks(candidateLongPress)
                }
                if (candidateDragging) {
                    when (candTouch) {
                        CandTouch.STRIP -> candidateScroll = (candidateScrollStart - moved).coerceIn(0f, maxCandidateScroll())
                        CandTouch.GRID -> gridScroll = (candidateScrollStart - moved).coerceIn(0f, maxGridScroll())
                        else -> Unit
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(candidateLongPress)
                if (!candidateDragging && !candidateLongPressed) {
                    when (candTouch) {
                        CandTouch.EXPAND -> {
                            expanded = !expanded
                            if (expanded) { layoutGrid(); gridScroll = 0f }
                        }
                        CandTouch.STRIP, CandTouch.GRID -> if (pressedCandidate >= 0) listener.onCandidate(pressedCandidate)
                        CandTouch.NONE -> Unit
                    }
                }
                candTouch = CandTouch.NONE
                pressedCandidate = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(candidateLongPress)
                candTouch = CandTouch.NONE
                pressedCandidate = -1
                invalidate()
            }
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && onCandidateDown(event)) return true
        if (candTouch != CandTouch.NONE) {
            onCandidateTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backdropDown = false
                downKey = when {
                    overlayRows == null || event.y < toolbarHeight -> keyAt(event.x, event.y)
                    overlayRect.contains(event.x, event.y) ->
                        overlayKeys.minByOrNull { hypot(it.rect.centerX() - event.x, it.rect.centerY() - event.y) }
                    else -> { backdropDown = true; null }
                }
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
                // 點選單外的半透明底：關閉選單（和再按一次 ☰ 一樣）
                if (backdropDown && overlayRows != null) listener.onCommand(KeyAction.EditMenu)
                backdropDown = false
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
            is KeyAction.Bopomofo -> listener.onBopomofo(action.symbol)
            is KeyAction.Jamo -> {
                listener.onJamo(if (shifted) action.shifted else action.normal)
                if (shift == Shift.ONCE) { shift = Shift.OFF; invalidate() }
            }
            is KeyAction.FlickPunct -> listener.onText(flick(action, dx, dy))
            KeyAction.Space -> listener.onSpace()
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

    private companion object {
        /** 浮動選單佔按鍵區寬、高的比例 */
        const val OVERLAY_FRACTION = 0.8f
    }
}
