package tw.fanime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast

class FanImeService : InputMethodService(), KeyboardView.Listener {

    /** 目前顯示的是主鍵盤、符號表，還是 Emoji 面板 */
    private sealed interface Mode {
        data object Main : Mode
        data class Table(val table: SymbolTable, val page: Int) : Mode
        data object Emoji : Mode
        data object EditMenu : Mode
    }

    private lateinit var keyboard: KeyboardView
    private lateinit var emojiPanel: EmojiPanel
    private lateinit var root: android.widget.FrameLayout

    private var mode: Mode = Mode.Main
    /** 符號表預設釘選：點完符號留在表裡，方便連續輸入 */
    private var pinned = true
    /**
     * 編輯功能表的「開選」狀態：開啟後移動游標會延伸選取範圍。
     * 很多 App 不理會 Shift+方向鍵，所以選取範圍由輸入法自己記：
     * [anchor] 是開選時的位置（固定不動），[focus] 是會跟著移動的那一端。
     */
    private var selecting = false
    private var anchor = 0
    private var focus = 0

    override fun onCreateInputView(): View {
        keyboard = KeyboardView(this, this)
        emojiPanel = EmojiPanel(this, object : EmojiPanel.Listener {
            override fun onEmoji(emoji: String) = onText(emoji)
            override fun onBackspace() = this@FanImeService.onBackspace()
            override fun onBack() = showMode(Mode.Main)
        })
        root = android.widget.FrameLayout(this).apply {
            addView(keyboard)
            addView(emojiPanel)
        }
        showMode(mode)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 換到新的輸入框時回到主鍵盤
        if (!restarting) {
            selecting = false
            showMode(Mode.Main)
        }
    }

    private fun showMode(newMode: Mode) {
        mode = newMode
        if (!::keyboard.isInitialized) return
        when (newMode) {
            Mode.Main -> keyboard.setRows(Layouts.english)
            is Mode.Table -> keyboard.setRows(Layouts.table(newMode.table, newMode.page, pinned))
            Mode.EditMenu -> keyboard.setRows(Layouts.editMenu(selecting))
            Mode.Emoji -> Unit
        }
        keyboard.activeToolbarAction = if (newMode == Mode.EditMenu) KeyAction.EditMenu else null
        val emoji = newMode == Mode.Emoji
        // Emoji 面板沿用鍵盤的高度，避免切換時整個畫面跳動
        if (emoji) {
            emojiPanel.layoutParams = emojiPanel.layoutParams.apply { height = keyboard.height }
            emojiPanel.setPadding(0, 0, 0, keyboard.bottomInset)
        }
        emojiPanel.visibility = if (emoji) View.VISIBLE else View.GONE
        keyboard.visibility = if (emoji) View.INVISIBLE else View.VISIBLE
    }

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        // 符號表沒釘選時，打完一個符號就回主鍵盤
        if (mode is Mode.Table && !pinned) showMode(Mode.Main)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        // 有選取文字時刪掉選取範圍，否則刪游標前一個字（surrogate pair 例如 emoji 也要整個刪）
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onCommand(action: KeyAction) {
        when (action) {
            KeyAction.Settings -> notYet("設定頁")
            KeyAction.Voice -> startVoiceInput()
            KeyAction.TradSimp -> notYet("繁簡切換：注音完成後才有作用")
            KeyAction.CursorLeft -> moveCursor(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyAction.CursorRight -> moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT)
            KeyAction.EditMenu -> showMode(if (mode == Mode.EditMenu) Mode.Main else Mode.EditMenu)
            KeyAction.HideKeyboard -> requestHideSelf(0)
            is KeyAction.Edit -> onEdit(action.op)
            else -> Unit
        }
    }

    private fun onEdit(op: EditOp) {
        val ic = currentInputConnection ?: return
        when (op) {
            EditOp.SELECT_OR_COPY -> if (selecting) {
                ic.performContextMenuAction(android.R.id.copy)
                stopSelecting()
            } else {
                startSelecting()
            }
            EditOp.CUT -> { ic.performContextMenuAction(android.R.id.cut); selecting = false }
            EditOp.PASTE -> { ic.performContextMenuAction(android.R.id.paste); selecting = false }
            EditOp.SELECT_ALL_OR_STOP -> if (selecting) stopSelecting() else selectAll()
            EditOp.LINE_START -> moveCursor(KeyEvent.KEYCODE_MOVE_HOME)
            EditOp.LINE_END -> moveCursor(KeyEvent.KEYCODE_MOVE_END)
            EditOp.UP -> moveCursor(KeyEvent.KEYCODE_DPAD_UP)
            EditOp.DOWN -> moveCursor(KeyEvent.KEYCODE_DPAD_DOWN)
            EditOp.LEFT -> moveCursor(KeyEvent.KEYCODE_DPAD_LEFT)
            EditOp.RIGHT -> moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT)
            EditOp.DELETE_FORWARD -> {
                // 有選取範圍時刪掉選取的內容，否則刪游標後面一個字
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(0, 1)
                selecting = false
            }
            EditOp.DELETE_BACKWARD -> { onBackspace(); selecting = false }
        }
        showMode(mode) // 更新「開選／複製」「全選／停選」標籤
    }

    private fun extracted(): android.view.inputmethod.ExtractedText? =
        currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)

    private fun startSelecting() {
        val et = extracted() ?: return
        anchor = et.startOffset + et.selectionEnd
        focus = anchor
        selecting = true
    }

    private fun selectAll() {
        val et = extracted()
        if (et == null) {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            return
        }
        anchor = et.startOffset
        focus = et.startOffset + et.text.length
        currentInputConnection?.setSelection(anchor, focus)
        selecting = true
    }

    /** 取消選取：游標停在移動端 */
    private fun stopSelecting() {
        selecting = false
        currentInputConnection?.setSelection(focus, focus)
    }

    /**
     * 移動游標。一般狀態送方向鍵給 App；「開選」狀態下由輸入法自己算新位置，
     * 再直接設定選取範圍（anchor 到 focus）。
     */
    private fun moveCursor(code: Int) {
        if (!selecting) {
            sendKey(code)
            return
        }
        val et = extracted() ?: return
        val text = et.text.toString()
        val local = (focus - et.startOffset).coerceIn(0, text.length)
        focus = et.startOffset + TextNav.move(text, local, code)
        currentInputConnection?.setSelection(anchor, focus)
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** 切到系統裡支援語音的輸入法（例如 Google 語音輸入），講完會自動切回來 */
    private fun startVoiceInput() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val voice = imm.enabledInputMethodList.firstNotNullOfOrNull { ime ->
            imm.getEnabledInputMethodSubtypeList(ime, true)
                .firstOrNull { it.mode == "voice" }
                ?.let { ime to it }
        }
        if (voice == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            notYet("找不到語音輸入法")
            return
        }
        switchInputMethod(voice.first.id, voice.second)
    }

    override fun onToggleLanguage() {
        // 階段 2 接上注音鍵盤
    }

    override fun onFanSelect(target: FanTarget) {
        when (target) {
            FanTarget.HALF -> showMode(Mode.Table(Layouts.halfWidth, 0))
            FanTarget.FULL -> showMode(Mode.Table(Layouts.fullWidth, 0))
            FanTarget.EMOJI -> showMode(Mode.Emoji)
            FanTarget.KAOMOJI -> notYet("顏文字：階段 3")
            FanTarget.KOREAN -> notYet("韓文：階段 4")
            FanTarget.JAPANESE -> notYet("日文：階段 5")
        }
    }

    private fun notYet(msg: String) {
        Toast.makeText(this, "尚未完成 — $msg", Toast.LENGTH_SHORT).show()
    }

    override fun onTableBack() = showMode(Mode.Main)

    override fun onTablePage(delta: Int) {
        val m = mode as? Mode.Table ?: return
        val page = (m.page + delta).coerceIn(0, m.table.pages.size - 1)
        showMode(m.copy(page = page))
    }

    override fun onTogglePin() {
        pinned = !pinned
        showMode(mode) // 重畫底列的「釘選／單次」標籤
    }
}
