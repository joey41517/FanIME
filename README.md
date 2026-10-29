# FanIME

FanIME 是一個 Android 輸入法，以注音和英文為主，靠左下角第二顆「扇形鍵」快速叫出其他輸入方式：按住扇形鍵往某個方向滑再放開，就切到那個選項；在中心附近放開則取消。

操作方式參考超注音，但這是 clean-room 的重新實作：沒有使用超注音的程式碼、圖示或資料。

## 功能

- **注音**
  - 可以不打聲調、可以簡拼（例如 ㄏㄏ → 好好）
  - 打的時候只顯示注音，再從候選字逐字或逐詞確認；按 ⌫ 可以取消上一次選的字
  - 逐字選完的詞會自動記下來，下次直接出現；長按候選字或在設定頁可以刪除
  - 上字後顯示聯想詞
- **英文**：字詞提示，會記住常打的字
- **扇形選單**：半形符號表、全形符號表、Emoji、顏文字、韓文、日文
  - **日文**：羅馬拼音輸入，轉換成漢字，會記住選過的寫法
  - **韓文**：兩套式組字，有字詞提示
  - **顏文字**、**符號表**：內容是文字檔，可以在手機的設定頁或接電腦直接編輯
- **繁簡切換**：注音照常打，輸出簡體字
- **工具列**：設定、語音輸入、繁／簡、游標左右移、編輯功能表（選取、複製、剪下、貼上、行首行尾…）、收起鍵盤
- **設定頁**
  - 主題：跟隨系統、深色、淺色
  - 按鍵高度、扇形提示時間、底部留白
  - 記錄的詞管理
  - 備份與還原（zip 檔）

## 隱私

輸入法看得到使用者打的每一個字，所以 FanIME **沒有申請網路權限**，所有資料（記錄的詞、選字習慣、設定）都只存在手機上。備份檔由使用者自己透過系統的檔案選擇器決定存放位置。

## 建置

需要 Android Studio（或 JDK 17 以上）。

```sh
./gradlew assembleDebug        # 產生 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 單元測試
```

安裝後到「設定 → 一般管理 → 鍵盤清單與預設」啟用 FanIME。

日文詞庫 `app/src/main/assets/japanese/dict.bin` 是用 `tools/build-ja-dict.ps1` 從 Mozc 的開源詞庫轉換的。

## 授權

程式碼採用 [Apache License 2.0](LICENSE)。

詞庫和圖示來自以下專案，各自依原本的授權發布，授權檔放在對應的資料夾裡：

| 資料 | 來源 | 授權 |
|---|---|---|
| 注音詞庫 `assets/chewing/` | [libchewing-data](https://github.com/chewing/libchewing-data) | LGPL-2.1-or-later |
| 英文字表 `assets/english/` | Android Open Source Project（LatinIME） | Apache 2.0 |
| 日文詞庫 `assets/japanese/` | [Mozc](https://github.com/google/mozc) 開源詞庫（IPAdic） | 見 `LICENSE-mozc-dictionary.txt` |
| 韓文字表 `assets/korean/` | [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) | CC BY-SA 4.0 |
| 繁簡對照 `assets/opencc/` | [OpenCC](https://github.com/BYVoid/OpenCC) | Apache 2.0 |
| 鍵盤圖示 `res/drawable/ic_*.xml` | Google Material Icons | Apache 2.0 |
