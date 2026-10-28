package tw.fanime

/** 按鍵按下去之後要做的事 */
sealed interface KeyAction {
    /** 只佔位置、不畫也不能按（例如 asdf 列兩側的半格縮排） */
    data object Spacer : KeyAction
    /** 只顯示文字、不能按（例如符號表的頁碼） */
    data object Label : KeyAction
    data class Text(val text: String) : KeyAction
    /** 注音符號（含聲調），交給注音引擎組字 */
    data class Bopomofo(val symbol: String) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    /** 中英切換（階段 2 才接上注音） */
    data object ToggleLanguage : KeyAction
    /** 扇形鍵：滑向某個方向放開，開啟該方向的選項 */
    data object Fan : KeyAction
    /** 斜向四方向的標點鍵：↖ ↗ ↙ ↘，輕點輸出 [tap] */
    data class FlickPunct(
        val upLeft: String, val upRight: String,
        val downLeft: String, val downRight: String,
        val tap: String,
    ) : KeyAction
    // 鍵盤上方的工具列
    data object Settings : KeyAction
    data object Voice : KeyAction
    data object TradSimp : KeyAction
    data object CursorLeft : KeyAction
    data object CursorRight : KeyAction
    data object EditMenu : KeyAction
    data object HideKeyboard : KeyAction
    /** 編輯功能表裡的各項操作 */
    data class Edit(val op: EditOp) : KeyAction
    // 符號表專用
    data object TableBack : KeyAction
    data object TablePrev : KeyAction
    data object TableNext : KeyAction
    data object TogglePin : KeyAction
}

/** 編輯功能表的操作（仿照使用者習慣的 3×4 配置） */
enum class EditOp {
    SELECT_OR_COPY, LINE_START, UP, LINE_END,
    CUT, LEFT, SELECT_ALL_OR_STOP, RIGHT,
    PASTE, DELETE_BACKWARD, DOWN, DELETE_FORWARD,
}

/** [weight] 是這顆鍵佔一列的相對寬度，一般鍵為 1 */
data class KeySpec(val label: String, val action: KeyAction, val weight: Float = 1f)

typealias KeyRow = List<KeySpec>

/** 扇形的選項，依序從左上排到右 */
enum class FanTarget(val label: String) {
    HALF("半"),
    FULL("全"),
    EMOJI("😀"),
    KAOMOJI("(^^)"),
    KOREAN("한"),
    JAPANESE("あ"),
}

/**
 * 符號表：每頁 8 欄 × 4 列 = 32 格。
 * 內容是自己整理的（依 Unicode 半形、全形字元分組），不照搬超注音的排列。
 */
data class SymbolTable(
    val pages: List<String>,
    /** 底列在「返回」和「⌫」之間的額外按鍵 */
    val bottom: List<KeySpec>,
) {
    fun cells(page: Int): List<String> = pages[page].codePointsAsStrings()
}

private fun String.codePointsAsStrings(): List<String> =
    codePoints().toArray().map { String(Character.toChars(it)) }

object Layouts {
    private fun letters(chars: String): KeyRow = chars.map { KeySpec(it.toString(), KeyAction.Text(it.toString())) }

    /** 英文鍵盤：數字列 + QWERTY，底列配置仿照使用者習慣的操作方式 */
    val english: List<KeyRow> = listOf(
        letters("1234567890"),
        letters("qwertyuiop"),
        listOf(KeySpec("", KeyAction.Spacer, 0.5f)) + letters("asdfghjkl") + listOf(KeySpec("", KeyAction.Spacer, 0.5f)),
        listOf(KeySpec("⇧", KeyAction.Shift, 1.5f)) + letters("zxcvbnm") + listOf(KeySpec("⌫", KeyAction.Backspace, 1.5f)),
        listOf(
            KeySpec("中", KeyAction.ToggleLanguage, 1.2f),
            KeySpec("✦", KeyAction.Fan, 1.2f),
            KeySpec("?:\n,!", KeyAction.FlickPunct("?", ":", ",", "!", ","), 1.2f),
            KeySpec("␣", KeyAction.Space, 3.6f),
            KeySpec(".", KeyAction.Text("."), 1.2f),
            KeySpec("⏎", KeyAction.Enter, 1.6f),
        ),
    )

    private fun bopomofo(symbols: String): KeyRow = symbols.map { KeySpec(it.toString(), KeyAction.Bopomofo(it.toString())) }

    /** 注音鍵盤：標準大千排列，聲調在第一列；ㄦ 和 ⌫ 放在底列 */
    val zhuyin: List<KeyRow> = listOf(
        bopomofo("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ"),
        bopomofo("ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ"),
        bopomofo("ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ"),
        bopomofo("ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ"),
        listOf(
            KeySpec("英", KeyAction.ToggleLanguage, 1.2f),
            KeySpec("✦", KeyAction.Fan, 1.2f),
            KeySpec("？、\n，！", KeyAction.FlickPunct("？", "、", "，", "！", "，"), 1.2f),
            KeySpec("␣", KeyAction.Space, 2.4f),
            KeySpec("。", KeyAction.Text("。"), 1f),
            KeySpec("ㄦ", KeyAction.Bopomofo("ㄦ"), 1f),
            KeySpec("⌫", KeyAction.Backspace, 1.2f),
            KeySpec("⏎", KeyAction.Enter, 1.4f),
        ),
    )

    /** ASCII 的 32 個標點剛好一頁 */
    val halfWidth = SymbolTable(
        pages = listOf(
            "!@#$%^&*" +
                "()[]{}<>" +
                "-_+=/\\|~" +
                "'\"`:;,.?",
        ),
        // 表格裡的箭頭是「打出箭頭符號」，移動游標用上方工具列
        bottom = listOf(
            KeySpec("↑", KeyAction.Text("↑")),
            KeySpec("↓", KeyAction.Text("↓")),
            KeySpec("←", KeyAction.Text("←")),
            KeySpec("→", KeyAction.Text("→")),
        ),
    )

    val fullWidth = SymbolTable(
        pages = listOf(
            // 第 1 頁：中文常用標點與括號
            "，、。．・…：；" +
                "？！～―（）「」" +
                "『』【】《》〈〉" +
                "“”‘’〔〕＠＃",
            // 第 2 頁：全形 ASCII 符號與常用圖形
            "＄％＾＆＊＋－＝" +
                "／＼｜＿＜＞［］" +
                "｛｝＂＇｀￥￠￡" +
                "§※○●△▲□■",
        ),
        bottom = listOf(
            KeySpec("全形空白", KeyAction.Text("　"), 4f),
        ),
    )

    /** 鍵盤上方的工具列：設定、語音、繁簡、游標左右、編輯功能表、縮小鍵盤 */
    val toolbar: KeyRow = listOf(
        KeySpec("⚙", KeyAction.Settings),
        KeySpec("🎤", KeyAction.Voice),
        KeySpec("繁", KeyAction.TradSimp),
        KeySpec("◀", KeyAction.CursorLeft),
        KeySpec("▶", KeyAction.CursorRight),
        KeySpec("☰", KeyAction.EditMenu),
        KeySpec("⌄", KeyAction.HideKeyboard),
    )

    /** 編輯功能表；[selecting] 為 true 時第一顆顯示「複製」、第七顆顯示「停選」 */
    fun editMenu(selecting: Boolean): List<KeyRow> {
        fun k(label: String, op: EditOp) = KeySpec(label, KeyAction.Edit(op))
        return listOf(
            listOf(k(if (selecting) "複製" else "開選", EditOp.SELECT_OR_COPY), k("行首", EditOp.LINE_START), k("上移", EditOp.UP), k("行尾", EditOp.LINE_END)),
            listOf(k("剪下", EditOp.CUT), k("左移", EditOp.LEFT), k(if (selecting) "停選" else "全選", EditOp.SELECT_ALL_OR_STOP), k("右移", EditOp.RIGHT)),
            // 前刪 = Backspace（刪游標前面），後刪 = Delete（刪游標後面）
            listOf(k("貼上", EditOp.PASTE), k("前刪", EditOp.DELETE_BACKWARD), k("下移", EditOp.DOWN), k("後刪", EditOp.DELETE_FORWARD)),
        )
    }

    /** 依符號表和頁碼組出鍵盤：4 列 × (8 個符號 + 右側翻頁欄)，加上底列 */
    fun table(table: SymbolTable, page: Int, pinned: Boolean): List<KeyRow> {
        val cells = table.cells(page)
        val pageCount = table.pages.size
        val side = listOf(
            if (page > 0) KeySpec("▲", KeyAction.TablePrev) else KeySpec("", KeyAction.Spacer),
            KeySpec("${page + 1}/$pageCount", KeyAction.Label),
            if (page < pageCount - 1) KeySpec("▼", KeyAction.TableNext) else KeySpec("", KeyAction.Spacer),
            KeySpec("", KeyAction.Spacer),
        )
        val grid = (0 until 4).map { r ->
            cells.subList(r * 8, minOf(r * 8 + 8, cells.size)).map { KeySpec(it, KeyAction.Text(it)) } + side[r]
        }
        val bottom = listOf(KeySpec("返回", KeyAction.TableBack, 1.5f)) +
            table.bottom +
            listOf(
                KeySpec("⌫", KeyAction.Backspace, 1.5f),
                // 釘選：留在符號表連續輸入；單次：點一個符號就回原本的鍵盤
                KeySpec(if (pinned) "釘選" else "單次", KeyAction.TogglePin, 1.2f),
            )
        return grid + listOf(bottom)
    }
}
