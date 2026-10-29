package tw.fanime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import tw.fanime.english.EnglishDictionary
import tw.fanime.english.EnglishEngine
import tw.fanime.japanese.JapaneseDictionary
import tw.fanime.japanese.JapaneseEngine
import tw.fanime.korean.KoreanDictionary
import tw.fanime.korean.KoreanEngine
import tw.fanime.chinese.TradSimpConverter
import tw.fanime.zhuyin.Syllable
import tw.fanime.zhuyin.ZhuyinDictionary
import tw.fanime.zhuyin.ZhuyinEngine

class FanImeService : InputMethodService(), KeyboardView.Listener {

    /** 目前顯示的是主鍵盤、符號表，還是 Emoji、顏文字面板 */
    private sealed interface Mode {
        data object Main : Mode
        data class Table(val table: SymbolTable, val page: Int) : Mode
        data object Emoji : Mode
        data object Kaomoji : Mode
        data object EditMenu : Mode
    }

    private lateinit var keyboard: KeyboardView
    private lateinit var emojiPanel: EmojiPanel
    private lateinit var kaomojiPanel: KaomojiPanel
    /** 使用者可編輯的顏文字清單、符號表；檔案有改過才重新解析 */
    private val kaomojiList by lazy { UserTextFile.kaomoji(this).cached(tw.fanime.kaomoji.KaomojiList::parse) }
    private val symbolTables by lazy { UserTextFile.symbols(this).cached(tw.fanime.symbols.SymbolList::parse) }
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

    private enum class Lang { ZHUYIN, ENGLISH, JAPANESE, KOREAN }

    /** 韓文：組字不需要詞庫；字詞提示的詞表在背景載入 */
    private val korean = KoreanEngine()
    private var koreanShown: List<String> = emptyList()

    /** 目前的語言；預設注音。日文、韓文從扇形進入，左下角回到 [baseLang] */
    private var lang = Lang.ZHUYIN
    private var baseLang = Lang.ZHUYIN
    private val zhuyinMode get() = lang == Lang.ZHUYIN

    /** 日文引擎；詞庫在背景載入，載入完成前是 null */
    @Volatile private var japanese: JapaneseEngine? = null
    private var japaneseShown: List<JapaneseEngine.Candidate> = emptyList()
    // 使用者選過的日文寫法：一行一個「讀音<TAB>寫法」
    private val japanesePrefFile get() = UserData.internal(this, UserData.JAPANESE_PREFS)

    private fun loadJapanesePrefs(e: JapaneseEngine) {
        val file = japanesePrefFile
        if (!file.exists()) return
        file.forEachLine(Charsets.UTF_8) { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) e.restorePreference(line.substring(0, tab), line.substring(tab + 1))
        }
    }

    /** 選字後如果有新的偏好就存檔（同讀音只留最新的，所以整個檔案重寫） */
    private fun saveJapanesePref() {
        val e = japanese ?: return
        e.takeLearned() ?: return
        japanesePrefFile.writeText(e.preferences().entries.joinToString("") { (r, s) -> "$r\t$s\n" }, Charsets.UTF_8)
    }
    /** 詞庫在背景載入，載入完成前是 null */
    @Volatile private var engine: ZhuyinEngine? = null
    private var shownCandidates: List<ZhuyinEngine.Candidate> = emptyList()
    /** 聯想詞：上字之後、還沒開始打下一個字時顯示在候選字列 */
    private var associations: List<String> = emptyList()

    /** 英文字詞提示；詞表在背景載入，載入完成前是 null（照常打字，只是沒有提示） */
    @Volatile private var english: EnglishEngine? = null
    private var englishShown: List<String> = emptyList()

    /**
     * 簡體模式（工具列的「繁／簡」）：注音照常打、詞庫和自動記錄的詞都還是繁體，
     * 只在顯示候選字和上字時轉成簡體。轉換表在背景載入，載入完成前照原樣輸出。
     */
    @Volatile private var tradSimp: TradSimpConverter? = null
    private val prefs by lazy { getSharedPreferences(UserData.PREFS, MODE_PRIVATE) }
    private var simplified = false

    /** 中文輸出：簡體模式時轉成簡體 */
    private fun zh(text: String): String = if (simplified) tradSimp?.toSimplified(text) ?: text else text

    override fun onCreate() {
        super.onCreate()
        simplified = prefs.getBoolean(PREF_SIMPLIFIED, false)
        loadedDataStamp = UserData.stamp(this)
        Thread {
            val start = android.os.SystemClock.elapsedRealtime()
            val dict = ZhuyinDictionary.load(assets)
            val e = ZhuyinEngine(dict)
            loadUserPhrases(e)
            engine = e
            val mid = android.os.SystemClock.elapsedRealtime()
            val en = EnglishDictionary.load(assets)
            loadEnglishWords(en)
            english = EnglishEngine(en)
            englishDict = en
            val end = android.os.SystemClock.elapsedRealtime()
            val ja = JapaneseEngine(JapaneseDictionary.load(assets))
            loadJapanesePrefs(ja)
            japanese = ja
            val jaEnd = android.os.SystemClock.elapsedRealtime()
            korean.dictionary = KoreanDictionary.load(assets)
            val koEnd = android.os.SystemClock.elapsedRealtime()
            tradSimp = TradSimpConverter.load { assets.open(it) }
            android.util.Log.d(
                "FanIME",
                "dictionaries loaded: zhuyin ${mid - start} ms, english ${end - mid} ms, " +
                    "japanese ${jaEnd - end} ms, korean ${koEnd - jaEnd} ms",
            )
        }.start()
    }

    // 使用者打過的英文字：一行一個「字<TAB>次數」
    private var englishDict: EnglishDictionary? = null
    private val englishWordFile get() = UserData.internal(this, UserData.ENGLISH_WORDS)

    private fun loadEnglishWords(dict: EnglishDictionary) {
        val file = englishWordFile
        if (!file.exists()) return
        file.forEachLine(Charsets.UTF_8) { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) dict.restoreUse(line.substring(0, tab), line.substring(tab + 1).toIntOrNull() ?: 1)
        }
    }

    private fun saveEnglishWords() {
        val dict = englishDict ?: return
        englishWordFile.writeText(dict.userWordCounts().joinToString("") { (w, c) -> "$w\t$c\n" }, Charsets.UTF_8)
    }

    // 使用者記錄的詞：存在 App 私有目錄，一行一個「詞<TAB>讀音」，讀音是以空白分隔的 Syllable.encode()
    private val userPhraseFile get() = UserData.internal(this, UserData.USER_PHRASES)

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

    /** 載入使用者資料時的版本號；設定頁改過資料後版本號會變 */
    private var loadedDataStamp = 0L

    /** 設定頁改過資料（刪了記錄的詞、還原備份）：重新讀取記錄的詞、英文用字、日文選字和繁簡設定 */
    private fun reloadUserDataIfChanged() {
        val stamp = UserData.stamp(this)
        if (stamp == loadedDataStamp) return
        val e = engine ?: return
        val en = englishDict ?: return
        val ja = japanese ?: return
        loadedDataStamp = stamp
        e.userPhrases().forEach { (text, _) -> e.removeUserPhrase(text) }
        loadUserPhrases(e)
        en.clearUserWords()
        loadEnglishWords(en)
        ja.clearPreferences()
        loadJapanesePrefs(ja)
        simplified = prefs.getBoolean(PREF_SIMPLIFIED, false)
        if (::keyboard.isInitialized) keyboard.simplified = simplified
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
        if (!isComposing()) return
        val ic = currentInputConnection ?: return
        if (candidatesStart == -1) {
            // 這個通知可能是之前操作的舊通知，直接問輸入框現在還有沒有組字中的文字（Android 13+）
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
            val snapshot = ic.takeSnapshot() ?: return
            if (snapshot.compositionStart == -1) {
                clearComposing()
                associations = emptyList()
                refreshCandidates()
            }
        } else if (newSelEnd < candidatesStart || newSelStart > candidatesEnd) {
            // 使用者把游標點到組字範圍外：組字中的內容就留在原地，結束組字
            ic.finishComposingText()
            clearComposing()
            refreshCandidates()
        }
    }

    /** 注音、英文或日文有沒有正在組的內容 */
    private fun isComposing() =
        engine?.isEmpty == false || english?.isEmpty == false || japanese?.isEmpty == false || !korean.isEmpty

    private fun clearComposing() {
        engine?.clear()
        english?.clear()
        japanese?.clear()
        korean.clear()
    }

    override fun onCreateInputView(): View {
        keyboard = KeyboardView(this, this)
        keyboard.simplified = simplified
        keyboard.applySettings(KeyboardSettings.load(prefs))
        emojiPanel = EmojiPanel(this, object : EmojiPanel.Listener {
            override fun onEmoji(emoji: String) = onText(emoji)
            override fun onBackspace() = this@FanImeService.onBackspace()
            override fun onBack() = showMode(Mode.Main)
        })
        kaomojiPanel = KaomojiPanel(this, object : KaomojiPanel.Listener {
            override fun onKaomoji(text: String) = onText(text)
            override fun onBackspace() = this@FanImeService.onBackspace()
            override fun onBack() = showMode(Mode.Main)
            override fun onEdit() = openActivity(
                android.content.Intent(this@FanImeService, FileEditorActivity::class.java)
                    .putExtra(FileEditorActivity.EXTRA_FILE, FileEditorActivity.FILE_KAOMOJI),
            )
        })
        root = android.widget.FrameLayout(this).apply {
            addView(keyboard)
            addView(emojiPanel)
            addView(kaomojiPanel)
        }
        showMode(mode)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 設定頁可能改了鍵盤參數或資料（刪了記錄的詞、還原備份）
        // 主題換了（設定頁）就重建整個鍵盤畫面；系統切換深淺色時輸入法本身會重建
        if (::keyboard.isInitialized && keyboard.palette != Palette.of(this)) setInputView(onCreateInputView())
        if (::keyboard.isInitialized) keyboard.applySettings(KeyboardSettings.load(prefs))
        Palette.applySystemBars(window?.window, Palette.of(this))
        reloadUserDataIfChanged()
        // 換到新的輸入框時回到主鍵盤
        if (!restarting) {
            selecting = false
            clearComposing()
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
        if (!korean.isEmpty) {
            val text = korean.commit()
            currentInputConnection?.commitText(text, 1)
            updateComposing()
            return text
        }
        val ja = japanese
        if (ja != null && !ja.isEmpty) {
            // 日文：整串轉換後上字（和空白鍵一樣）
            val text = ja.commitConverted()
            currentInputConnection?.commitText(text, 1)
            updateComposing()
            return text
        }
        val en = english
        if (en != null && !en.isEmpty) {
            // 英文：正在打的字照原樣上字
            val word = en.commit()
            currentInputConnection?.commitText(word, 1)
            saveEnglishWords()
            updateComposing()
            return word
        }
        val e = engine
        if (e == null || e.isEmpty) {
            refreshCandidates()
            return ""
        }
        val text = e.commitAll()
        currentInputConnection?.commitText(zh(text), 1)
        updateComposing()
        saveLearnedPhrase()
        return text
    }

    /** 依引擎狀態更新輸入框裡的組字文字和候選字列 */
    private fun updateComposing() {
        val en = english
        val ja = japanese
        val preedit = when {
            !korean.isEmpty -> korean.preedit()
            ja != null && !ja.isEmpty -> ja.preedit()
            en != null && !en.isEmpty -> en.current
            else -> zh(engine?.preedit().orEmpty())
        }
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

    /** 組字中顯示候選字（注音或英文提示）；沒有組字時顯示聯想詞（沒有就恢復工具列） */
    private fun refreshCandidates() {
        val e = engine
        val en = english
        val ja = japanese
        shownCandidates = emptyList()
        englishShown = emptyList()
        japaneseShown = emptyList()
        koreanShown = emptyList()
        val list = when {
            !korean.isEmpty -> korean.suggestions().also { koreanShown = it }
            ja != null && !ja.isEmpty -> ja.candidates().also { japaneseShown = it }.map { it.text }
            e != null && !e.isEmpty -> e.candidates().also { shownCandidates = it }.map { zh(it.text) }
            en != null && !en.isEmpty -> en.suggestions().also { englishShown = it }
            else -> associations.map(::zh)
        }
        if (::keyboard.isInitialized) keyboard.setCandidates(list)
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

    /** 韓文字母：整個詞維持在組字中（到空白或標點才上字），候選字列顯示字詞提示 */
    override fun onJamo(jamo: Char) {
        associations = emptyList()
        korean.input(jamo)
        updateComposing()
    }

    override fun onCandidate(index: Int) {
        val ic = currentInputConnection ?: return
        if (koreanShown.isNotEmpty()) {
            // 韓文提示：整個詞換成選的詞（不自動補空白）
            val word = korean.commit(koreanShown.getOrNull(index) ?: return)
            ic.beginBatchEdit()
            ic.commitText(word, 1)
            updateComposing()
            ic.endBatchEdit()
            return
        }
        val ja = japanese
        if (ja != null && japaneseShown.isNotEmpty()) {
            // 日文選字：沒選到最後只固定那一段；選到最後才整串上字
            val text = ja.pick(japaneseShown.getOrNull(index) ?: return)
            saveJapanesePref()
            if (text == null) {
                updateComposing()
                return
            }
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            updateComposing()
            ic.endBatchEdit()
            return
        }
        val en = english
        if (en != null && englishShown.isNotEmpty()) {
            // 英文提示：只上字，不自動補空白（使用者偏好）
            val word = en.commit(englishShown.getOrNull(index) ?: return)
            ic.beginBatchEdit()
            ic.commitText(word, 1)
            updateComposing()
            ic.endBatchEdit()
            saveEnglishWords()
            return
        }
        val e = engine ?: return
        val c = shownCandidates.getOrNull(index)
        if (c == null) {
            // 點的是聯想詞：直接上字，再接著找下一層聯想詞
            val word = associations.getOrNull(index) ?: return
            ic.commitText(zh(word), 1)
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
        ic.commitText(zh(text), 1)
        updateComposing()
        ic.endBatchEdit()
        saveLearnedPhrase()
        showAssociations(text)
    }

    override fun onSpace() {
        // 日文：空白鍵整串轉換後上字
        if (japanese?.isEmpty == false) {
            commitComposing()
            return
        }
        val e = engine
        if (zhuyinMode && mode == Mode.Main && e != null && !e.isEmpty) {
            // 有正在組的注音 → 一聲；否則 → 選第一個候選字（和點它一樣，沒選到最後就繼續確認下一段）
            if (e.space()) updateComposing() else onCandidate(0)
            return
        }
        commitComposing()
        currentInputConnection?.commitText(" ", 1)
    }

    private fun showMode(newMode: Mode) {
        mode = newMode
        if (!::keyboard.isInitialized) return
        when (newMode) {
            // 編輯功能表浮在主鍵盤上面，底下照樣畫主鍵盤（蓋一層半透明底）
            Mode.Main, Mode.EditMenu -> keyboard.setRows(
                when (lang) {
                    Lang.ZHUYIN -> Layouts.zhuyin
                    Lang.ENGLISH -> Layouts.english
                    Lang.JAPANESE -> Layouts.japanese(baseLabel())
                    Lang.KOREAN -> Layouts.korean(baseLabel())
                },
            )
            is Mode.Table -> keyboard.setRows(Layouts.table(newMode.table, newMode.page, pinned))
            Mode.Emoji, Mode.Kaomoji -> Unit
        }
        keyboard.setOverlay(if (newMode == Mode.EditMenu) Layouts.editMenu(selecting) else null)
        keyboard.activeToolbarAction = if (newMode == Mode.EditMenu) KeyAction.EditMenu else null
        val emoji = newMode == Mode.Emoji
        val kaomoji = newMode == Mode.Kaomoji
        // Emoji、顏文字面板沿用鍵盤的高度，避免切換時整個畫面跳動
        for (panel in listOf(emojiPanel, kaomojiPanel)) {
            panel.layoutParams = panel.layoutParams.apply { height = keyboard.height }
            panel.setPadding(0, 0, 0, keyboard.bottomInset)
        }
        if (emoji) emojiPanel.showRecent()
        // 每次打開都重新讀清單（可能剛在設定頁或電腦上改過），回到首頁
        if (kaomoji) kaomojiPanel.show(kaomojiList())
        emojiPanel.visibility = if (emoji) View.VISIBLE else View.GONE
        kaomojiPanel.visibility = if (kaomoji) View.VISIBLE else View.GONE
        keyboard.visibility = if (emoji || kaomoji) View.INVISIBLE else View.VISIBLE
    }

    override fun onText(text: String) {
        // 日文鍵盤：字母是羅馬拼音；長音和日文標點也放進組字中，最後一起轉換
        val ja = japanese
        if (lang == Lang.JAPANESE && mode == Mode.Main && ja != null) {
            if (text.length == 1 && text[0].isLetter() && text[0].code < 0x80) {
                ja.input(text.lowercase())
                updateComposing()
                return
            }
            if (text in JAPANESE_COMPOSING_SYMBOLS) {
                ja.inputKana(text)
                updateComposing()
                return
            }
        }
        // 英文鍵盤打字母：累積成正在打的字，顯示字詞提示
        val en = english
        if (!zhuyinMode && mode == Mode.Main && en != null && en.accepts(text)) {
            associations = emptyList()
            en.append(text)
            updateComposing()
            return
        }
        // 打標點或符號前，先把組字中的內容上字
        commitComposing()
        currentInputConnection?.commitText(text, 1)
        // 符號表沒釘選時，打完一個符號就回主鍵盤
        if (mode is Mode.Table && !pinned) showMode(Mode.Main)
    }

    override fun onBackspace() {
        // 組字中：刪注音或音節；英文正在打的字刪最後一個字母；日文刪拼音或假名
        if (korean.backspace() || japanese?.backspace() == true || engine?.backspace() == true || english?.backspace() == true) {
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
        // 日文組字中按 Enter：照平假名上字（確定），不換行
        val ja = japanese
        if (ja != null && !ja.isEmpty) {
            currentInputConnection?.commitText(ja.commitKana(), 1)
            updateComposing()
            return
        }
        // 注音組字中按 Enter：照畫面上的樣子上字（選好的字 + 還沒確認的注音），不猜字、不記錄、不換行
        val e = engine
        if (e != null && !e.isEmpty) {
            associations = emptyList()
            currentInputConnection?.commitText(zh(e.commitAsShown()), 1)
            updateComposing()
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
            KeyAction.Settings -> openSettings()
            KeyAction.Voice -> startVoiceInput()
            KeyAction.TradSimp -> {
                simplified = !simplified
                prefs.edit().putBoolean(PREF_SIMPLIFIED, simplified).apply()
                keyboard.simplified = simplified
                refreshCandidates()
            }
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
        lang = when (lang) {
            Lang.ZHUYIN -> Lang.ENGLISH
            Lang.ENGLISH -> Lang.ZHUYIN
            Lang.JAPANESE, Lang.KOREAN -> baseLang // 日文、韓文的左下角：回到進入前的語言
        }
        baseLang = lang
        showMode(Mode.Main)
    }

    override fun onFanSelect(target: FanTarget) {
        commitComposing()
        when (target) {
            FanTarget.HALF -> showMode(Mode.Table(Layouts.halfWidth(symbolTables()), 0))
            FanTarget.FULL -> showMode(Mode.Table(Layouts.fullWidth(symbolTables()), 0))
            FanTarget.EMOJI -> showMode(Mode.Emoji)
            FanTarget.KAOMOJI -> showMode(Mode.Kaomoji)
            FanTarget.JAPANESE -> if (japanese == null) notify("日文詞庫載入中，請稍候") else enterLang(Lang.JAPANESE)
            FanTarget.KOREAN -> enterLang(Lang.KOREAN)
        }
    }

    /** 打開設定頁 */
    private fun openSettings() = openActivity(android.content.Intent(this, SettingsActivity::class.java))

    private fun openActivity(intent: android.content.Intent) {
        requestHideSelf(0)
        startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
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

    /** 從扇形進入日文或韓文；左下角會回到進入前的語言 */
    private fun enterLang(target: Lang) {
        if (lang == target) {
            showMode(Mode.Main)
            return
        }
        if (lang == Lang.ZHUYIN || lang == Lang.ENGLISH) baseLang = lang
        lang = target
        showMode(Mode.Main)
    }

    /** 日文、韓文鍵盤左下角的標籤：回到哪個語言 */
    private fun baseLabel() = if (baseLang == Lang.ZHUYIN) "中" else "英"

    override fun onTogglePin() {
        pinned = !pinned
        showMode(mode) // 重畫底列的「釘選／單次」標籤
    }

    companion object {
        /** 日文鍵盤上這些符號放進組字中（和前後的假名一起轉換），不會先把組字上字 */
        private val JAPANESE_COMPOSING_SYMBOLS = setOf("ー", "、", "。", "？", "！", "…")
        private const val PREF_SIMPLIFIED = "simplified"
    }
}
