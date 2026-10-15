# 使用者版本增量：實作與驗證（2026-10-15）

## 1. 範圍與結論

本輪承接 [增量 SPEC](PRODUCT_EVOLUTION_SPEC.md)，完成重點影像、連續翻譯／TTS 及原生操作介面的軟體基線。使用者確認加入本機視覺模型與人工確認，連續翻譯要逐句自動播報。

**軟體串接及兩次短時眼鏡實測已完成；尚不能等同一般使用者版本或眼鏡聲學驗收完成。** 本文件區分合成語料、現場錄製及現場資料的離線回放。當天較早的串流修正另見 [硬體 smoke test](HARDWARE_SMOKE_TEST_2026-10-15.md)，本輪實測見 §4.3–4.5。

環境：S23 Ultra（SM-S9180，Android 16／API 36）、JDK 17、本機 Python 3.13、RTX 4060 Laptop 8 GB。applicationId 仍為實驗 `com.htc.viveglass.viveglasssample`，namespace 為 `com.beefsoup.ai.glass`；正式 HTC 登錄仍待協助。

## 2. 已實作

| 路徑 | 實作與目的 |
|---|---|
| 影像候選 | 保留既有 H.264 解碼修正，全畫面文字穩定或 16×16 亮度縮圖穩定形成候選；至少間隔 8 秒、一次一筆視覺請求。失敗／忙碌可節流重試，新增候選／決策／錯誤事件。新版尚未做眼鏡自動選取重測。 |
| 語意選取 | PC `qwen3-vl:2b-instruct` 先找重要文字表面與 ROI；實際裁切後第二次 OCR，不把全圖 OCR 帶進第二次請求。手機／標籤預設排除。 |
| 照片與文字 | 只保存選中或手動保留的原始 JPEG，附媒體時間、模型、ROI 與辨識文字。人工確認前不進摘要；可更正或排除，排除刪除照片。 |
| 連續翻譯 | PCM 仍先落盤；跨切片累積句子、限制重疊去除，逐句翻譯與保存，停止沖洗尾句；重送結果不重複形成譯句。 |
| 播報 | Android 離線 TTS、語音檢查、音訊焦點、有界待播佇列、靜音／重播、停止與換會議世代隔離。補送歷史不自動播報。 |
| 操作介面 | 會議／翻譯／重點／設定四頁，固定停止，原文與譯文對照，照片放大與更正確認；工程資訊收進 Debug 診斷。分享輸出易讀文字。 |

PC 的音訊 WebSocket 與視覺 HTTP 分開；同一條語音請求內 STT 和翻譯仍依序執行。共享 GPU、模型載入、網路與聲學延遲不能由此表推定已解決。

## 3. 已執行的自動測試

| 測試層級 | 結果 | 實際驗證內容 |
|---|---|---|
| Android JVM（最新候選版本） | 14 tests，0 failures／errors／skips | Session 狀態與晚到事件、封包解析、PTS、PCM／逐字稿；候選穩定／節流、手機 OCR 空白時的穩定畫面備援、失敗重試、播報佇列去重／上限／清空。 |
| Python server（加入回授防護後） | 19 passed | token、跨會議／序號／PCM validation、重試／來源；跨切片句子、尾句重送、翻譯失敗不消耗句子、JPEG／ROI、拒絕類型、裁切與排除證據；新增同目標語高信心／低信心／一般模式行為。 |
| S23 完整裝置基線（較早 APK） | `OK (22 tests)`，49.86 秒 | MediaCodec AAC／H.264、480×864 padding、ML Kit、保存重啟／刪除、WebSocket 重送、前景服務、8 項 Compose 流程、照片修正／排除、錯誤復原入口、較早內容回看、實際 PC 推論與實際 TTS 合成。 |
| S23 最新變更回歸（目前 Debug APK） | `OK (3 tests)`，18.349 秒 | 兩種 H.264→OCR 解碼與實際手機 WebSocket→Whisper／LLM。覆蓋候選 callback／VideoOcrPipeline 介面及服務變更所影響的路徑；不是完整 22 項在新 APK 上重跑。 |
| 同一筆實際回傳再合成 | `OK (1 test)` | 讀取手機到 PC 的最新三句譯文，交給 S23 TTS；以 MediaExtractor 讀到非零音訊樣本。 |

這些不是逐 method 名稱各配一個空測試。斷言集中在錯 session 不能確認刪檔、失敗可重送、照片與文字一致、排除不能污染摘要、開始／停止控制、每句僅一次等行為。未接通真實 gateway 的 `LiveInferencePipelineTest` 會明確 skip，不能把缺設定當成功；本次已配置並實際通過。

目前有一則 Starlette／AnyIO `BlockingPortal` 棄用警告，以及 Gradle 10 相容性提示。最新 Debug APK 建置／安裝及上表指定回歸通過；Release 在較早 UI 修正版本建置通過，未對最新候選變更重建或於手機執行。初期失敗保留於 §5。原始輸出在忽略追蹤的 `captures/2026-10-15/product-evolution/`，去識別摘要、前後版本 APK hash 及驗證界線在 [證據 JSON](evidence/product_validation_2026-10-15.json)。不能把較早完整套件的結果冒稱為目前 APK 的完整重跑。

## 4. 實際模型與手機串接

### 4.1 已知英文語音

測試語音由 Windows SAPI 合成，PCM SHA-256 已記錄，不是眼鏡錄音：

> Hello everyone. We will finish the project tomorrow. Please review the meeting notes.

- PC 直接 probe 的 Whisper base 原文與參考句一致，繁中譯文為「大家好。我們將於明天完成專案。請審閱會議記錄。」
- S23 把同一 PCM 切成 3 段，經實際 Uvicorn WebSocket、Whisper、LLM，收到 3 個不重複的已定稿句子；待送量歸零。
- 手機收到的合併譯文：「大家好。 我們將於明天完成該專案。 請檢視會議記錄。」再交給 `zh-TW-language` 離線語音，產生 276,148 bytes 可解碼且非全零的 WAV。
- 這項合成測試驗證 TTS 波形產生，**沒有證明使用者已聽到聲音、輸出在眼鏡、或沒有回授**。

單次暖機 probe：STT 約 1.54 秒、文字翻譯約 1.32 秒；其他載入模型的試次翻譯曾約 11–17 秒。這不是語句結束至可聽播報的 p95，也不是長會議效能承諾。

### 4.2 混合場景的視覺 probe

| 合成測試圖 | 實際模型結果 | 單次時間 |
|---|---|---|
| 白板樣式文件＋旁邊 `DAILY WATER` 瓶身文字 | 選取主文字表面，分類為 `document`，ROI `[0.034, 0.078, 0.729, 0.895]`；裁切後只回傳 PROJECT REVIEW／Deadline／Owner／Action 四行，沒有瓶身字。 | 約 4.33 秒 |
| 個人手機畫面 | 分類 `phone`，`keep=false`，不形成照片重點。 | 約 1.71 秒 |

這兩張是程式繪製的 QA fixtures。不能由兩張圖推算實景 precision／recall、繁中／日文 CER、低光或遠距白板可讀率。第一張分類不是 `whiteboard`，也不能說模型已能精確分辨所有場景。

### 4.3 現場眼鏡連續翻譯

- 真實 `AUDIO`，連續翻譯開啟、目標繁中；5 段可解碼 M4A 合計 **45.824 秒**，16 kHz，13 段 PCM／逐字稿、4 筆定稿譯句。正常停止，pending=0，沒有 `.partial` 或健康錯誤事件。
- 使用者確認聽到中文播報，**聲音來自眼鏡**。因此已證明這次音訊路由；不推廣成所有裝置、耳機或情境都通過。
- 實際辨識包含 `Hello everyone, who will.`、`We will finish the project model.` 及 `Please review the the meeting notes.`；對照參考句，存在斷句、重複與 `tomorrow` 誤辨，不能報成正確翻譯。
- 後段出現中文原文及再翻譯。使用者確認現場沒有另外說中文，只有眼鏡播報，**譯音被再次收錄已確認**，無回音全雙工未通過。

### 4.4 現場相機＋音訊與原圖回放

- 真實 `AUDIO_VIDEO`，一般會議不自動播報；5 段可解碼 M4A 合計 **48.436825 秒**，44.1 kHz，14 段 PCM／逐字稿。正常停止、pending=0，沒有 `.partial` 或健康錯誤事件。
- 保存 1 張 480×856 JPEG，PTS **20.755729 秒**，來源為 `phone-ocr`／**手動保留**。此次沒有自動選取結果，不能把這張照片當作自動 pipeline 成功。
- 原圖為桌上平板的手寫文件，周圍有瓶子和另一支手機。手機 OCR 有 `Hell。Wa.I`、`你好世界`、`Chihh!` 等錯字，尚不適合直接作會議事實。
- 把**同一張原始照片**送到現有視覺服務離線回放，分類 `document`、保留=true、ROI `[0.247, 0.358, 0.999, 0.8]`，讀得 `Hello World!`、`你好世界`、`こんにちは! 2026/10/1`。日期右端已超出取景，不能評為完整正確；模型 reason 對日期另有不一致，也不能當真值。單次 16.65 秒包含新模型實例載入，不是暖機 p95。
- 原先沒有逐階段事件，不能確定此次停在候選門檻、服務設定或其他步驟。已修正「必須手機 OCR 穩定才送出」的弱點，增加穩定畫面備援及失敗重試／診斷。**新版本尚未做眼鏡重測。**

### 4.5 以真實錄音回放定位與防護

1. 相同英文錄音取 **17.5–29 秒**（11.5 秒上下文）交給同一 Whisper base，回得完整參考句及正確 `tomorrow`。支持四秒上下文不足是重要因素；尚未實作滾動 STT，也不證明是唯一原因。
2. 相同錄音 **31.5–39.5 秒**的播報回授被 Whisper 判為中文，信心約 **0.93046**。
3. 新增連續模式的「來源已是目標語、信心 ≥ 0.8」防護後，以該段真實回授通過實際模型＋API 回放：原文保留，`translation=""`、`utterances=[]`、`speechSuppressed=already_target_language`。
4. 這只是避免該類片段再次形成自動播報，**不刪除原音、不能辨別真正中文參與者、不是 AEC**。低信心、混語與同時說話仍需驗證。修正後尚未實際戴眼鏡重測。

使用者隨後因 Codex 前端當機而離開實驗室；本輪停止新硬體操作，不以後續人工回覆推定仍在配戴。原始錄音與相片只存本機忽略目錄，公開文件僅記結果摘要。

## 5. 本輪發現並處理的實際問題

| 問題／失敗證據 | 處理方式 | 回歸證據 |
|---|---|---|
| 既有文字 generate 呼叫把完成專案錯譯成空調專案 | 改用 chat API、temperature 0，保留來源為資料的提示 | 已知語音與真實手機 gateway 的 deadline／action 斷言通過；仍需較大語料集。 |
| `qwen3-vl:2b` thinking 消耗 token，JSON content 空或逾時 | 查能力與原始回應，改明確 `:2b-instruct` tag | 實際兩張視覺 probe 回傳有效結構。 |
| 模型用 0–1000 座標，與 API 正規化約定不同 | 明確模型座標格式，adapter 驗證後轉成 0–1 | 非法 ROI 測試與實際 ROI 通過。 |
| 說明只選白板，OCR 卻仍夾帶瓶身字 | 不以關鍵字黑名單過濾；實際裁切 ROI 後第二次 OCR | 同一測試圖不再包含瓶身文字。 |
| 試圖限制 JSON schema 的較大 maxLength，Ollama 回 grammar parse error | 移除該 schema 限制，保留服務端嚴格文字長度 validation | 真實模型回應可解析，超長結果仍會被拒絕。 |
| S23 鎖定／Dozing 導致 Compose 控制測試失敗 | 使用者解鎖後才跑互動測試，暫改逾時 | 完整套件含 8 項 Compose 通過；最後 ADB 已離線，暫時字級／逾時尚待還原，見交接摘要。鎖屏錄製仍獨立待測。 |
| 合成來源 stop 之後仍可能 callback，測試背景協程崩潰 | `DemoGlassLink` callback lock＋generation，修正測試不在回呼執行 JUnit assert | 全套 S23 無崩潰；不將合成來源修正冒稱眼鏡 SDK 問題。 |
| `/health` 正常但手機 WebSocket 沒有結果；Uvicorn 記錄缺少 upgrade 執行套件 | 在 requirements 明列 `websockets`，安裝並重啟服務 | 真實手機 WS 測試從逾時改為通過。 |
| 獨立介面檢查：錯誤只在診斷、編輯 OCR 遮住照片、早期文字不可回看、動態深色系統列對比失效 | 使用者錯誤與復原入口獨立顯示、編輯保留可放大照片、LazyColumn 顯示完整已保存文字、同步系統列明暗 | 增加權限／逾時／授權 UI 狀態、31 段逐字稿／41 譯句回看重播及照片編輯取消／確認回歸；截圖交獨立 reviewer 核對。 |
| 錯誤復原回歸觸發 `AwaitFirstLayoutModifier` IndexOutOfBounds 崩潰；兩個初始捲動協程同時等待佈局 | 改單一 `requestScrollToItem`，在下一次 remeasure 定位，不等待首次佈局 | 相同復原案例與完整 22 項通過。API 行為見 [Android LazyListState](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState)。 |
| Release 編譯指出 FlowRow 需要實驗 API opt-in | 在使用位置明確標記 ExperimentalLayoutApi | Debug 與 Release 建置通過。 |
| 真實錄音 `tomorrow` 被辨識成 `model` | 用相同音訊、較長上下文回放定位；保留原始錯誤，不以 LLM 猜字修正 | 長上下文回放正確；即時滾動 STT 尚未實作。 |
| 眼鏡中文譯音再次錄入並翻譯 | 連續模式加入同目標語高信心抑制 | 3 個 API 情境與真實回授回放通過，戴眼鏡重測尚未做。 |
| 相機沒有自動重點，手機多語 OCR 很差 | 穩定場景備援不再要求先讀對文字；單一請求、節流重試、階段診斷 | 新增 JVM 邊界測試及 S23 解碼回歸；原圖 VLM 回放可讀多語；自動選取仍待實機。 |
| 測試預熱遺漏 num_ctx，Ollama 嘗試 262144 context，載入逾時 | 預熱同正式請求明確限制 `num_ctx=4096` | 有界 context 可載入。這是測試 helper 失誤，正式模型請求原已限制 context。 |
| ADB key event 經注音輸入法使位址／連線碼內容錯誤 | 測試改用原生 accessibility `ACTION_SET_TEXT`；不把敏感內容印進日誌 | 兩個設定欄位可精確填入。Uri／Password 鍵盤提示本身沒有解決 key injection。 |
| 大字體＋IME 的來源照片編輯驗證未完成 | 保留原生測試失敗日誌，停止無限重試 | 一般字級流程已測；1.5 字級 helper 找不到 UiScrollable，不能當作 UI 通過或已確診佈局缺陷。 |

## 6. 重現重點

1. 依 README 建置 main／androidTest APK，指定單一手機 serial 安裝並執行 instrumentation；互動測試須螢幕可操作。
2. `server/.venv/Scripts/python.exe -m pytest server -q`；JVM 執行 `gradlew.bat :app:testDebugUnitTest`。
3. Ollama 需有文字模型及 `ollama pull qwen3-vl:2b-instruct`，可用 `AI_GLASS_VISION_MODEL` 指定。模型資產不加入 repo。
4. 啟動帶測試 token 的本機服務。Debug 可執行 `adb -s <serial> reverse tcp:8765 tcp:8765`，手機連 `ws://127.0.0.1:8765/ws`；這仍經 ADB 的無線連線，不是眼鏡 Wi-Fi Direct。
5. 真實 gateway 裝置 gate 需在 App 私有 `files/test-gateway.json` 放 `endpoint` 與 `token`，用 `run-as` 寫入並核對；禁止存到公開報告或一般會議事件。結束移除。一般使用者由設定頁輸入，不依賴此測試檔。
6. 指定 `LiveInferencePipelineTest` 通過後，再以 `-e liveResult true` 跑 `SpeechOutputTest`，確保用的是這次實際回傳文字。一般全套的 TTS fixture 是另一筆已保留的模型結果。

## 7. 尚待驗收與下一輪

- **眼鏡聲學**：已確認眼鏡發聲及回授存在；下一輪驗證同目標語防護，並比較滾動 STT 品質、混語、原語與播報重疊、靜音／重播及可懂度。
- **眼鏡視覺品質**：先證明新版自動候選→模型→保存完整路徑，再擴充白板／文件＋瓶身／手機及近期討論語料；比較 precision／recall、CER、漏選及無關保存。手動照片與離線回放不能取代此 gate。
- **TTS 故障注入**：引擎缺失／語音包缺失、焦點被搶、快速換會議／停止／解除靜音，實際聲音不得越界。現有純佇列測試與合成波形不能取代這些測試。
- **可靠性與資源**：15／30／60 分鐘、鎖屏／背景、來電、PC 離線／重啟、網路中斷、手機／眼鏡電量與熱、雙模型競爭及 p95 延遲。
- **產品完整度**：目前只讀回最近會議；歷史列表、搜尋、連線碼配對引導、標註語料、多語模型比較與 Qualcomm AI Hub 端側替代仍未完成。
- **介面**：獨立 reviewer 的四項修正中 F1／F3／F4 resolved，F2（大字體＋IME）partial，整體 disposition 仍為 fix；TalkBack、平板、Release UI 與完整主題切換矩陣未驗證。

上述未通過的條件不得以目前 22 項裝置測試取代；本輪是可測試的功能增量，尚非正式發佈。
