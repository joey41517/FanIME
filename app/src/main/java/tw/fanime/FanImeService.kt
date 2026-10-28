package tw.fanime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import tw.fanime.zhuyin.Syllable
import tw.fanime.zhuyin.ZhuyinDictionary
import tw.fanime.zhuyin.ZhuyinEngine

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

    /** true = 注音、false = 英文；預設注音 */
    private var zhuyinMode = true
    /** 詞庫在背景載入，載入完成前是 null */
    @Volatile private var engine: ZhuyinEngine? = null
    private var shownCandidates: List<ZhuyinEngine.Candidate> = emptyList()
    /** 聯想詞：上字之後、還沒開始打下一個字時顯示在候選字列 */
    private var associations: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Thread {
            val start = android.os.SystemClock.elapsedRealtime()
            val dict = ZhuyinDictionary.load(assets)
            val e = ZhuyinEngine(dict)
            loadUserPhrases(e)
            engine = e
            android.util.Log.d("FanIME", "dictionary loaded in ${android.os.SystemClock.elapsedRealtime() - start} ms")
        }.start()
    }

    // 使用者記錄的詞：存在 App 私有目錄，一行一個「詞<TAB>讀音」，讀音是以空白分隔的 Syllable.encode()
    private val userPhraseFile get() = java.io.File(filesDir, "user_phrases.tsv")

    private fun loadUserPhrases(e: ZhuyinEngine) {
        val file = userPhraseFile
        if (!file.exists()) return
        file.forEachLine(Charsets.UTF_8) { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val reading = line.substring(tab + 1).split(' ').mapNotNull { Syllable.decode(it) }
            if (reading.isNotEmpty()) e.restoreUserPhrase(line.substring(0, tab), reading)
        }
    }

    private fun phraseLine(text: String, reading: List<Syllable>) =
        text + "\t" + reading.joinToString(" ") { it.encode() } + "\n"

    /** 上字之後檢查引擎有沒有自動記錄新詞，有的話存檔並提示 */
    private fun saveLearnedPhrase() {
        val (text, reading) = engine?.takeLearned() ?: return
        userPhraseFile.appendText(phraseLine(text, reading), Charsets.UTF_8)
        notify("已記錄「$text」")
    }

    /** 長按候選字：如果是使用者記錄的詞，就從詞庫刪除 */
    override fun onCandidateLongPress(index: Int) {
        val e = engine ?: return
        val text = shownCandidates.getOrNull(index)?.text ?: return
        if (!e.isUserPhrase(text)) {
            notify("「$text」是內建詞，不能刪除")
            return
        }
        e.removeUserPhrase(text)
        userPhraseFile.writeText(e.userPhrases().joinToString("") { (t, r) -> phraseLine(t, r) }, Charsets.UTF_8)
        notify("已從詞庫刪除「$text」")
        refreshCandidates()
    }

    private fun notify(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 輸入框的游標或組字狀態改變。有些 App 會自己結束組字（例如自動存檔、重新整理輸入框），
     * 這時輸入法的組字狀態要跟著清空，不然之後的倒退、上字會和畫面對不上。
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val e = engine ?: return
        if (e.isEmpty) return
        val ic = currentInputConnection ?: return
        if (candidatesStart == -1) {
            // 這個通知可能是之前操作的舊通知，直接問輸入框現在還有沒有組字中的文字（Android 13+）
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
            val snapshot = ic.takeSnapshot() ?: return
            if (snapshot.compositionStart == -1) {
                e.clear()
                associations = emptyList()
                refreshCandidates()
            }
        } else if (newSelEnd < candidatesStart || newSelStart > candidatesEnd) {
            // 使用者把游標點到組字範圍外：組字中的內容就留在原地，結束組字
            ic.finishComposingText()
            e.clear()
            refreshCandidates()
        }
    }

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
            engine?.clear()
            associations = emptyList()
            updateComposing()
            showMode(Mode.Main)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 鍵盤收起時，把還在組字的內容上字，避免留下半成品
        commitComposing()
        super.onFinishInputView(finishingInput)
    }

    /**
     * 把組字中的內容全部上字（整句最佳結果），回傳上字的文字。
     * 打標點、切英文等「順便上字」的情況不顯示聯想詞，所以這裡會清掉聯想詞。
     */
    private fun commitComposing(): String {
        associations = emptyList()
        val e = engine
        if (e == null || e.isEmpty) {
            refreshCandidates()
            return ""
        }
        val text = e.commitAll()
        currentInputConnection?.commitText(text, 1)
        updateComposing()
        saveLearnedPhrase()
        return text
    }

    /** 依引擎狀態更新輸入框裡的組字文字和候選字列 */
    private fun updateComposing() {
        val preedit = engine?.preedit().orEmpty()
        val ic = currentInputConnection
        if (preedit.isEmpty()) {
            // 組字內容刪光了：先把輸入框裡的組字文字清掉再結束組字。
            // 只呼叫 finishComposingText 會把畫面上剩下的組字文字（例如「ㄔ」「列」）直接確認上字
            ic?.setComposingText("", 1)
            ic?.finishComposingText()
        } else {
            ic?.setComposingText(preedit, 1)
        }
        refreshCandidates()
    }

    /** 組字中顯示候選字；沒有組字時顯示聯想詞（沒有就恢復工具列） */
    private fun refreshCandidates() {
        val e = engine
        if (e != null && !e.isEmpty) {
            shownCandidates = e.candidates()
            if (::keyboard.isInitialized) keyboard.setCandidates(shownCandidates.map { it.text })
        } else {
            shownCandidates = emptyList()
            if (::keyboard.isInitialized) keyboard.setCandidates(associations)
        }
    }

    /** 主動上字（選候選字、空白、Enter）之後，顯示接在後面的聯想詞 */
    private fun showAssociations(committed: String) {
        val e = engine
        associations = if (zhuyinMode && e != null && e.isEmpty) e.associations(committed) else emptyList()
        refreshCandidates()
    }

    /** 回傳可用的注音引擎；詞庫還沒載入完時提示使用者 */
    private fun readyEngine(): ZhuyinEngine? = engine ?: run {
        Toast.makeText(this, "詞庫載入中，請稍候", Toast.LENGTH_SHORT).show()
        null
    }

    override fun onBopomofo(symbol: String) {
        val e = readyEngine() ?: return
        associations = emptyList()
        if (!e.input(symbol) && e.isEmpty) {
            // 沒有注音時按聲調鍵：直接打出聲調符號
            currentInputConnection?.commitText(symbol, 1)
            refreshCandidates()
            return
        }
        updateComposing()
    }

    override fun onCandidate(index: Int) {
        val e = engine ?: return
        val ic = currentInputConnection ?: return
        val c = shownCandidates.getOrNull(index)
        if (c == null) {
            // 點的是聯想詞：直接上字，再接著找下一層聯想詞
            val word = associations.getOrNull(index) ?: return
            ic.commitText(word, 1)
            showAssociations(word)
            return
        }
        // 選字：沒選到最後只會固定那一段（繼續組字）；選到最後才整串上字
        val text = e.pick(c)
        if (text == null) {
            updateComposing()
            return
        }
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        updateComposing()
        ic.endBatchEdit()
        saveLearnedPhrase()
        showAssociations(text)
    }

    override fun onSpace() {
        val e = engine
        if (zhuyinMode && mode == Mode.Main && e != null && !e.isEmpty) {
            // 有正在組的注音 → 一聲；否則 → 整句上字
            if (e.space()) updateComposing() else showAssociations(commitComposing())
            return
        }
        commitComposing()
        currentInputConnection?.commitText(" ", 1)
    }

    private fun showMode(newMode: Mode) {
        mode = newMode
        if (!::keyboard.isInitialized) return
        when (newMode) {
            Mode.Main -> keyboard.setRows(if (zhuyinMode) Layouts.zhuyin else Layouts.english)
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
        // 打標點或符號前，先把組字中的內容上字
        commitComposing()
        currentInputConnection?.commitText(text, 1)
        // 符號表沒釘選時，打完一個符號就回主鍵盤
        if (mode is Mode.Table && !pinned) showMode(Mode.Main)
    }

    override fun onBackspace() {
        // 組字中：刪注音或音節
        if (engine?.backspace() == true) {
            updateComposing()
            return
        }
        // 聯想詞顯示中按倒退：先收起聯想詞，同時刪字
        if (associations.isNotEmpty()) {
            associations = emptyList()
            refreshCandidates()
        }
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
        // 組字中按 Enter：只上字，不換行
        if (engine?.isEmpty == false) {
            showAssociations(commitComposing())
            return
        }
        // 聯想詞顯示中按 Enter：收起聯想詞，照常換行
        commitComposing()
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
        // 工具列、編輯功能表的操作都會動到游標或文字，先上字
        commitComposing()
        when (action) {
            KeyAction.Settings -> notYet("設定頁")
            KeyAction.Voice -> startVoiceInput()
            KeyAction.TradSimp -> notYet("繁簡切換：之後的版本")
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
        commitComposing()
        zhuyinMode = !zhuyinMode
        showMode(Mode.Main)
    }

    override fun onFanSelect(target: FanTarget) {
        commitComposing()
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
