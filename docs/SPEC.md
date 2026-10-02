# VIVE Eagle 長時間會議紀錄、即時翻譯與相機 OCR 系統 SPEC

> 文件狀態：已確認實作基準 v0.2（2026-10-02，Asia/Taipei；新增測試與工程品質規劃）  
> 對象：Android App／AI 模型／系統測試成員  
> 實作邊界：本文件只定義方案與驗收條件；在團隊確認規格前不撰寫應用程式碼。  
> 基準資源：本機 `Android_ViveGlassSDK_0.6.0_beta`、`report` 與團隊的 [vive-glass-sdk 逆向分析](https://github.com/wolf-yuan-6115/vive-glass-sdk)。下文凡標「逆向推定」者，仍須在真機重現。

## 1. 專案目標與決策摘要

### 1.1 交付目標

1. 在 VIVE Eagle 與 Android 手機連線時，連續擷取會議語音，產生有時間戳的逐字稿、跨語翻譯與可追溯來源的會議摘要。
2. 需要辨識白板、投影片、文件時，從眼鏡相機影像擷取清晰畫面做 OCR，將結果併入會議時間軸與術語表。
3. App 允許手機端推論；若指定手機無法在目標耗電、發熱和延遲內執行，允許把 **AI 運算** 卸載到同一區域網路上的電腦。眼鏡到手機的 SDK 媒體鏈路仍由官方 SDK 管理。
4. 長時間運作要可觀測、可降載、可恢復；任何音訊缺口必須標示，不能以模型文字假裝錄到不存在的內容。

### 1.2 目前建議的架構決策

| 議題 | v0.1 決策 | 理由／轉向門檻 |
|---|---|---|
| 眼鏡 → 手機媒體 | 以官方 SDK 0.6.0 的 BLE 串流為實作基線 | 團隊逆向分析指出該版公開流程使用 BLE GATT；先量到真機上限，再判斷要不要換路徑。 |
| 音訊與影像 | 一次 `startVideoStreaming`，分別接 `audioBufferCallback` 與 `videoBufferCallback` | 官方 API 已支援這種模式；不必嘗試同時開兩個互斥的 SDK 串流。 |
| Wi-Fi Direct | 技術預研分支，不列為核心功能前置條件 | Wi-Fi 6E 僅說明無線網路能力，未證明 Wi-Fi P2P 與第三方即時媒體 API 可用。 |
| 第一條可運作路徑 | 先做 audio-only 長時錄製與 STT，再擴成音影同流 | 先找出會議最重要的穩定性及延遲瓶頸。 |
| OCR | 影像串流解碼後選幀；不預設能邊串流邊 `captureImage` | 0.6.0 逆向分析指出拍照與影像串流有資源衝突。 |
| Qualcomm AI Hub | 模型選擇、轉換、量化、編譯、profiling 與匯出 | AI Hub 是開發／部署工作流程；會議期間的推論應在手機或電腦執行，不把 Hub 當遠端串流 API。 |
| 眼鏡回饋 | 手機文字為基線；眼鏡語音播報列為早期真機驗證目標 | 優先試 Android 系統藍牙媒體播放；同時錄音的回音與 BLE 競爭要量測。SDK `speakText` 不適合 0.6.0 同時串流。 |
| UI／服務 | Kotlin + Jetpack Compose 管理畫面；錄製狀態及管線由前景服務持有 | UI 重建不能中斷串流；Compose 不承擔媒體熱路徑。 |

## 2. 已知事實、推定與尚待驗證

| 事項 | 目前證據 | 判讀 |
|---|---|---|
| VIVE Eagle 標示 Wi-Fi 6E、Bluetooth 5.3、235 mAh 電池 | [HTC 規格](https://www.vive.com/us/product/vive-eagle/spec/) | **官方已知**；產品規格沒有承諾公開 Wi-Fi Direct 媒體 API，官方的「音樂／通話續航」也不能推算相機串流續航。 |
| Android `startVideoStreaming` 同時提供影像、音訊兩個 callback，需眼鏡相機與麥克風授權，視訊位元率範圍 200–800 kb/s | [HTC Android API](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/viveglass/startvideostreamingwithvideoconfig/)；本機範例 `ViveGlassKitManager.kt:643–673` | **官方 API／範例已知**；實際可持續吞吐、幀率、影像清晰度仍需實測。 |
| 0.6.0 對外直播走 BLE GATT；音影為獨立媒體特徵值與獨立解碼／派送流程；存在未啟用的 Wi-Fi 痕跡和可選 Bluetooth Classic 程式碼 | [團隊逆向分析：BLE 與封裝](https://github.com/wolf-yuan-6115/vive-glass-sdk/blob/main/sdk/04-ble-and-framing.md)、[媒體串流](https://github.com/wolf-yuan-6115/vive-glass-sdk/blob/main/sdk/06-media-streaming.md) | **0.6.0 靜態逆向推定**；Wi-Fi 相關常數／類別不能當作現成傳輸功能。 |
| 0.6.0 串流與 SDK 內建 STT／TTS、拍照互斥 | [團隊逆向分析：資源衝突](https://github.com/wolf-yuan-6115/vive-glass-sdk/blob/main/sdk/02-public-api.md) | **逆向推定**；實作時以官方 API 與真機錯誤事件雙重確認。 |
| 0.6.0 範例視訊預設 H.264 約 856×480、30 fps、500 kb/s，附 AAC 44.1 kHz 立體聲 32 kb/s；音訊單流範例為 AAC 16 kHz 單聲道 | 本機 `ViveGlassKitManager.kt:593–603, 637–673` | **範例設定**，不是長時、低延遲或 OCR 品質保證。 |
| Android Wi-Fi Direct 需要兩端均支援 Wi-Fi P2P，並完成裝置發現、組群與應用層傳輸 | [Android Wi-Fi Direct 文件](https://developer.android.com/develop/connectivity/wifi/wifip2p) | **平台機制已知**；手機的 `WifiP2pManager` 不會替眼鏡創造可用的 P2P 服務。 |

### 2.1 Wi-Fi 6E 是否代表可用 Wi-Fi Direct？

**答案：不能直接推論。** Wi-Fi 6E 是 Wi-Fi 無線能力／頻段世代；Wi-Fi Direct 是額外的點對點協定、韌體行為、裝置角色與應用程式權限。即使眼鏡可連一般 Wi-Fi、可經 VIVE Connect 匯入檔案，也不足以證明第三方 App 能透過 P2P 接收**即時**相機與麥克風資料。公開 0.6.0 API 沒有傳輸方式切換參數；團隊對這版 AAR 的逆向結果亦未找到已啟用的 Wi-Fi 媒體路徑。

Wi-Fi Direct 的高理論吞吐**可能**改善 720p／OCR 的可用位元率，但「較快」不等於「較穩」：需一併量測 P2P 建立時間、斷線重連、2.4／5／6 GHz 環境干擾、手機上網同時性、眼鏡發熱與電池消耗。Wi-Fi 6E 標示也不保證 P2P 會使用 6 GHz。若媒體仍由 SDK 固定送 BLE，手機另外建立 P2P 連線對眼鏡→手機吞吐沒有幫助。

**導入 Wi-Fi Direct 的四道門檻（全部通過才排入產品路徑）：**

1. HTC 官方確認該眼鏡韌體與 SDK 可讓第三方存取 Wi-Fi P2P，或提供受支援的眼鏡端傳輸擴充介面。
2. 真機能在維持 VIVE Connect 配對／授權下完成手機與眼鏡 P2P 連線，並確認手機可維持必要網路連線。
3. 有**受支援且獲授權**的即時 AAC／H.264 輸出至 P2P socket；僅發現 P2P 裝置、Wi-Fi SSID 或內部 UDP 類別皆不算通過。
4. 相同場景 A/B 測試證明 OCR 可讀性或延遲改善，且達到本文件第 10 節的長時穩定、耗電及溫度門檻。

若第 1 或第 3 點不可行，保留 BLE 版本並降低來源影像負載；電腦算力則使用**手機與電腦間的一般 Wi-Fi LAN**，無須讓眼鏡支援 Wi-Fi Direct。P2P 技術預研只使用廠商許可的 API，不以未公開韌體協定作為交付前提。

## 3. 系統邊界與資料流

### 3.1 文字架構圖

```text
VIVE Eagle：麥克風陣列 + 相機
    │ 官方 SDK 0.6.0 建立工作階段；目前公開路徑推定為 BLE GATT
    ▼
Android 手機：VIVE Connect 授權／ViveGlassKit
    │ 1 個 video-with-audio session → 音訊 callback / 影像 callback / 事件 callback
    ├─ 音訊優先管線：持久化 → AAC 解碼 → PCM/重取樣 → VAD/切塊 → STT
    │                                      → 穩定逐字稿 → 術語翻譯 → 手機字幕
    ├─ 影像管線：H.264 解碼 → 選幀/清晰度門檻 → OCR → 圖文事件
    └─ 時間軸/儲存：逐字稿 + 翻譯 + OCR + 缺口標記 → 增量摘要/會後總結
                                             │
                                             ├─ 模式 A：手機端已部署模型推論
                                             └─ 模式 B：手機以區域網路安全通道
                                                        將必要資料交給電腦推論
```

這裡的「一個有音訊的影像流」指**一次 SDK 串流工作階段**，並非必然是一條交錯的音影位元流。0.6.0 逆向資料顯示音訊、影像有獨立 BLE 媒體通道與 SDK 派送佇列；App 使用兩個 callback 進入各自的管線。SDK 已處理其內部封包與加密，App 不重作底層 BLE 解密。

### 3.2 範圍與不依賴事項

- **必做**：眼鏡音訊、長時逐字稿與翻譯、會後摘要、手機 UI、資料儲存與缺口標記、真機續航測試。
- **第二階段必做**：同一 SDK 串流下的取幀 OCR、文字證據合併、影音同步與降載。
- **依量測啟用**：手機 AI Hub 部署版模型、電腦卸載、Wi-Fi Direct。目標手機型號未定前，不能承諾 NPU 上的特定模型性能。
- **輸出介面**：目前確定的逐字稿／翻譯顯示在手機 Compose UI；眼鏡端文字覆蓋畫面不是現有 SDK 公開能力。HTC [官方 Live Translation 說明](https://www.vive.com/hk/support/vive-eagle-en/category_howto/using-live-translation.html)顯示產品本身可從眼鏡喇叭播放翻譯，但未承諾第三方 App 在 SDK 錄製期間可並行播音。因此額外驗證手機 TTS 經 Android 系統藍牙媒體路由到眼鏡；0.6.0 SDK `speakText` 與串流互斥，不放入並行路徑。

## 4. 串流控制與音影格式

### 4.1 連線、授權、生命週期

1. 使用 [HTC Android Setup](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/setup/) 註冊 App，透過 VIVE Connect 完成眼鏡配對、SDK 初始化、相機及麥克風授權；手機 Android 權限與眼鏡資源授權分開檢查。
2. 由使用者在前景畫面開始會議。服務建立 session ID、開始時間、語言與資料保存選項，開啟持續通知，再呼叫 SDK 開始音訊單流或音影同流。
3. 監聽同步回傳值與 `StreamingEventCallback`；將低電量、過熱、停止、權限錯誤和連線狀態寫入 session 事件表。
4. 停止時先關閉 SDK 工作階段並收尾 callback，再沖洗音訊切片、等待已接收任務處理完成，標示最後時間戳。重連或模式切換建立新 stream epoch，避免舊緩衝誤接成連續內容。

建議狀態：`IDLE → AUTHORIZING → CONNECTING → AUDIO_ONLY 或 AV → DEGRADED／RECONNECTING → STOPPING → FINALIZING → READY`。所有 SDK 開／關流動作由單一控制器序列化；不能因重試而同時啟動兩個串流。

### 4.2 可配置格式與起始組態

| 場景 | 起始設定（提案） | 測試／調整 |
|---|---|---|
| 長會議、無 OCR | `startAudioStreaming`；先以官方範例 16 kHz／單聲道／32 kb/s AAC | 驗證麥克風模式、音質、連續運作與長時間斷訊。 |
| 會議 + OCR | `startVideoStreaming`；先用 `DEFAULT` 856×480、15 fps、500 kb/s H.264，附 AAC 32 kb/s | 15 fps 屬逆向所列允許值，但真機需驗證；若 SDK／韌體拒絕，回到範例 30 fps 或其他允許值。先確認實際 SPS 尺寸和旋轉。 |
| OCR 文字太小 | 以 `HIGH` 1280×720、8／15 fps、最高 800 kb/s 進行短時間 A/B 試驗 | 位元率上限可能造成嚴重壓縮；只在文字辨識與穩定性均改善時採用。 |
| 視訊中音訊格式 | 優先試 16 kHz／單聲道以減少 STT 前處理；若韌體拒絕或品質差，回到範例 44.1 kHz／立體聲，手機下混及重取樣 | 不能把 audio-only 範例的格式支援，直接推定到 video-with-audio 模式。 |

兩路資料在 SDK callback 中只做範圍檢查、依 `ByteBuffer` 的有效區間複製或轉交有所有權的緩衝、記錄 `MediaCodec.BufferInfo` 與接收時間，立即返回。`ByteBuffer` 的生命週期可能由 SDK 控制，不能把原物件長留給非同步推論。解碼及模型推論不得在 callback 執行緒執行。

**會議收音驗證：**官方範例選朝向佩戴者的麥克風設定，但會議需要涵蓋其他與會者。比較朝向佩戴者與周遭收音，在座位距離、音量、噪音下的 WER／CER。團隊逆向分析指出 0.6.0 的 audio-only 指令可能沒有正確傳遞麥克風方向選項；不能只看 App 要求的 enum，須以真機錄音結果或 HTC 回覆確認。若眼鏡收不到遠端講者，考慮手機麥克風作為明確標示來源的備援，而不是把劣質音訊交給 LLM 猜測。

**資源互斥限制：**0.6.0 逆向分析指出 `captureImage` 不能與視訊串流同時工作，SDK 內建 STT／TTS 亦與正在使用的串流衝突。因此 STT、翻譯、OCR 和摘要都用手機／電腦端模型，OCR 從現有視訊影格選取。若降載到 audio-only，必須先停止視訊工作階段，再開始音訊工作階段；記錄切換造成的音訊缺口，不承諾無縫。

### 4.3 眼鏡語音回饋的驗證路徑

1. 手機產生短句翻譯的語音，試經 Android 正常媒體輸出路由送往眼鏡喇叭；此路徑與 SDK 的 `speakText` 分開。先在 audio-only，再在音影同流時測試能否並行，以及音訊焦點、延遲與連線干擾。
2. 每段播音均標記開始／結束時間，檢查眼鏡麥克風是否重新拾取自身播出的翻譯。若有回授，測試回音消除、播音音量及減少播報頻率；不能以靜音眼鏡麥克風為主要解法，否則會漏錄會議內容。
3. 預設採手機可讀字幕；耳機播報由使用者選擇，優先播放重要句子或按鍵請求，並控制播音佇列長度，避免翻譯永遠追不上談話。未通過並行測試前，不把眼鏡連續語音播報列為已保證的交付能力。

## 5. 低延遲 Pipeline 設計

### 5.1 原則

- **音訊保真優先**：先確保擷取與持久化，再做推論；翻譯不得阻塞 STT，OCR 不得阻塞音訊。佇列必須有大小與時間上限。
- **多級並行，有限資源排程**：接收、解碼、STT、翻譯、OCR、摘要可管線化；同一手機 NPU／GPU 若互相爭用，以量測結果採單一推論排程器或分時，不假定並行一定更快。
- **先局部再穩定**：UI 可顯示「暫定」逐字稿；翻譯只對句末已穩定文字發布版本，修訂需可追蹤，避免閃爍與重複術語。
- **摘要低優先**：累積文字達時間或字數門檻再更新；會後重新依完整資料整理最終摘要。

### 5.2 音訊快路徑

| 階段 | 輸入 → 輸出 | 實作規格 |
|---|---|---|
| A0 擷取／寫入 | AAC callback → 帶序號媒體片段 | 由 callback 交給短佇列；先存帶格式與時間戳的可恢復分段，再送解碼。不能任意串接 AAC 位元組當成可播放檔；容器／AAC header 依真機格式驗證。 |
| A1 前處理 | AAC → PCM → 單聲道／模型採樣率 | 使用 Android `MediaCodec` 解碼；必要時下混與重取樣，檢測靜音、過載、時戳跳躍。無需經 `AudioTrack` 播放，以免喇叭回授。 |
| A2 切塊 | PCM → 帶時間範圍的 utterance | VAD 與 4–8 秒滑動片段起步，重疊約 0.5 秒；極長句設硬上限。這是待調參數，須比較 2–4 秒窗口的延遲與錯字率。 |
| A3 STT | utterance → 暫定／穩定詞句 | 保存語言、自動偵測結果、來源時間與模型版本；重疊片段依時間及文字對齊去重。推論落後時保留已錄音，UI 顯示積壓時間。 |
| A4 翻譯 | 穩定詞句 + 術語表 → 目標語文字 | 先保留人名、專有名詞、數字及縮寫，再翻譯；輸出與原句同時間範圍，需可人工修訂。 |
| A5 摘要 | 已定稿句子 + OCR 證據 → 增量／最終摘要 | 約每 1–3 分鐘或累積足量資料觸發一次，會後完成全局整理；每項決策／待辦附來源句與時間。 |

**模型限制：**[Qualcomm AI Hub 的 Whisper-Tiny 頁面](https://aihub.qualcomm.com/models/whisper_tiny)提供 30 秒輸入規格；它是候選 ASR，不等於原生無限長串流引擎。若短片段補零後速度／準確率不合格，須比較真正增量式 ASR 或電腦端 ASR；不可只因模型列在 Hub 就宣稱達到即時需求。

### 5.3 影像／OCR 路徑

| 階段 | 輸入 → 輸出 | 實作規格 |
|---|---|---|
| V0 解碼 | H.264 callback → YUV／硬體可用影格 | `MediaCodec` 硬體解碼，尊重 SPS／PPS、keyframe、實際尺寸與旋轉；高負載時**在解碼後**挑幀，不能任意丟失 H.264 相依幀。 |
| V1 候選選取 | 15/30 fps → 約 0.5–2 張／秒 | 依新場景、文字區域、清晰度、曝光及重複率選幀；低價值影格可丟。只降低手機 OCR 工作，不會減少眼鏡已送出的 BLE 頻寬。 |
| V2 OCR | 候選影格 → 文字框／語言／信心值 | 先偵測文件／白板 ROI、去旋轉與透視校正，再辨識繁中／英文等目標語；連續幀文字相同時合併、保留最佳畫面時間。 |
| V3 融合 | OCR 觀測 + 逐字稿 → 時間軸事件 | OCR 字詞可作術語候選；字幕翻譯使用已確認的術語版本。摘要能引用「某時刻白板／投影片」的 OCR 證據，但低信心辨識只作提示，不當作確定事實。 |

若 480p／500 kb/s 的實際字形難辨，後處理裁切無法還原壓縮失掉的細節；需提高**來源端**影像品質、靠近文字、調整拍攝角度，或接受此場景無法由持續直播完成。拍照 API 的資源衝突與切換音訊缺口須以真機決定是否值得支援「手動暫停串流拍高解析照片」。

### 5.4 時間同步、背壓與優先權

1. 所有片段保存 `session_id`、`stream_epoch`、來源 PTS（若有）、手機單調時鐘接收時間、序號及媒體類型。每次起流估計音影 PTS 到手機單調時鐘的對映；遇 PTS 重設、回繞、重連，建立新 epoch。牆上日期只用於人類顯示。
2. 以影格／音訊時間範圍融合資料。OCR 取的是影格時間，不是 OCR 模型完成時間；翻譯的時間範圍繼承原語句。若 SDK PTS 在兩路不共時基，以共同可見事件／接收時間校準並在驗收時量測誤差。
3. 佇列優先級：媒體擷取與音訊寫入 ＞ AAC 解碼／STT ＞ 翻譯 ＞ 影格選取／OCR ＞ 增量摘要。圖像佇列超過上限時保留最新且清晰的候選；摘要可延後。音訊推論可延後但錄音不得**無聲丟棄**；寫入無法跟上時立即報錯、記錄缺口並停止或要求使用者處理。
4. 預設上限提案：callback 交接低於 10 ms；音訊持久化佇列不超過 5 秒、STT 積壓警示 15 秒、停止新 OCR 於 STT 積壓 5 秒、OCR 候選佇列最多 3 張。數值經 15／30／60 分鐘壓測調整。
5. 每階段記錄接收、排隊、開始、完成時間，匯出 p50／p95 延遲與排隊長度；以真實會議口音、專業詞及雜訊測試，不用單次模型推論耗時代替使用者感受。

## 6. AI 運算與 Qualcomm AI Hub 整合

### 6.1 部署流程

1. 固定至少一支**實際開發手機**的 SoC、RAM、Android 版本與可用推論 runtime；同時記錄電腦規格。沒有目標型號前不選定最終模型。
2. 為 STT、OCR、翻譯／摘要各準備最小候選。用 [AI Hub Workbench](https://workbench.aihub.qualcomm.com/docs/hub/faq.html) 在相容裝置上轉換、量化、編譯、profiling，下載模型資產與檢查數值輸出；模型授權、檔案大小與繁中／英文品質一併審核。
3. Android App 透過對應 runtime（例如 AI Hub 匯出的 QNN 或 TFLite 路線）載入**已部署於手機**的資產。模型前處理／tokenizer／後處理必須與編譯版對齊，並以相同語料比較 CPU 基線與 NPU 版輸出。
4. 同機量測同時跑 STT、OCR、翻譯時的總延遲、RSS、耗電與熱節流；若 NPU 不支援某算子或發生 CPU fallback，按整條 pipeline 的測值做選擇，不以單一模型 profiler 數字下結論。
5. 模型選擇由 `device_profile`／測試結果配置；高溫或低電時關閉影像與增量摘要，盡量保持 STT。Hub 是模型部署工具，不處理正式會議的串流資料。

| 任務 | 第一輪候選與基線 | 選型門檻 |
|---|---|---|
| STT | Whisper-Tiny 的 AI Hub 路線與一個真正支援增量解碼的 ASR 基線 | 實際語料 WER／CER、4–8 秒窗口推論耗時、RAM、長時熱節流；支援中英混語。 |
| OCR | Android 現成端側文字辨識器作功能基線；再比較可部署到目標手機的 AI Hub 視覺模型 | 白板／投影片可讀率、每張延遲、手機耗電；不因要使用 Hub 而重訓一個已夠用的 OCR。 |
| 即時翻譯 | 術語表 + 專用翻譯模型或小型多語指令模型 | 中英雙向專有名詞、數字及否定句正確率；模型能否在 STT 同時運作。 |
| 增量／會後摘要 | 目標手機可承受的小型量化多語指令模型；若無法達標則先用電腦模式 | 不超過裝置記憶體與熱預算；長會議採分段摘要再合併，輸出只引用已定稿逐字稿／OCR，保留來源 ID。 |

模型名稱、參數量與量化格式在手機型號和測試語料確定後記入下一版 SPEC。會議摘要提示與驗證需禁止把低信心 OCR 或缺口內的內容寫成肯定句；專業術語表版本要隨每個翻譯結果保存。

### 6.2 電腦卸載模式

- 手機保留 SDK 連線、分段錄製、時間軸與 UI；只將推論所需的 PCM 片段及已選中的 OCR 影格／裁切區，透過區域網路安全通道送至電腦。預設不傳完整視訊、不自動上傳雲端。
- 訊息攜帶 session ID、epoch、序號、來源 PTS、語言及任務類型；結果帶相同 ID。電腦回覆可亂序，手機依 ID 合併。斷線重連後按序號補送未確認的持久化片段，避免重複字幕與遺失會議內容。
- 使用配對／短效權杖、TLS（或同等的已驗證安全通道）與用戶可見的卸載開關。LAN 消失時手機持續錄製；若手機模型可用則降級到本機推論，否則保留待處理片段並標示「轉譯延後」。
- 傳輸介面提案：WSS 控制通道傳 session／epoch／序號與確認訊息，二進位訊息傳音訊片段及精選 OCR 裁切；服務端按序號去重並回覆模型版本、來源時間範圍。若現場沒有 AP，可在真機驗證後使用手機熱點建立一般 LAN。
- PC 模式的 Wi-Fi 傳輸位於**手機 ↔ 電腦**；不改變眼鏡 ↔ 手機的 SDK 傳輸，亦無須 Wi-Fi Direct。

## 7. App 模組與資料規格

| 模組 | 單一職責 | 主要輸出 |
|---|---|---|
| `GlassSession` | SDK 初始化、VIVE Connect 授權、連線／串流狀態與權限 | 狀態事件、原始 callback 緩衝 |
| `MeetingService` | 擁有會議生命週期、前景通知、重連與降載 | session／epoch／健康狀態 |
| `MediaIngest` | 音訊優先交接、時間戳與可恢復分段 | AAC 分段、H.264 影格索引 |
| `AudioPipeline` | 解碼、VAD、切塊、STT、重疊去重 | 原文句子及 confidence |
| `TranslationPipeline` | 目標語設定、術語表、句子翻譯與修訂 | 翻譯句子 |
| `FeedbackOutput` | 手機字幕與可選的 Android 系統藍牙語音播報、回授量測 | 可見／可聽翻譯與播音事件 |
| `VisionPipeline` | 硬體解碼、選幀、OCR、去重 | OCR 觀測與參考影格 |
| `SummaryPipeline` | 增量摘要及會後決策／待辦整理 | 有來源引用的摘要項目 |
| `InferenceGateway` | 手機模型或電腦模式切換、佇列與結果合併 | 推論結果與積壓指標 |
| `MeetingStore` | 片段、文字、狀態與刪除／匯出 | 可恢復會議資料 |
| `Compose UI` | 顯示字幕、翻譯、OCR、狀態與使用者控制 | 不持有媒體串流 |

**必要資料欄位：**

- `MeetingSession`：ID、開始／結束時間、來源裝置／韌體／SDK／手機型號、語言對、模式、授權狀態、保存策略。
- `MediaSegment`：session ID、epoch、媒體類型、序號、PTS 範圍、接收時間、編碼格式／採樣率／尺寸、檔案路徑、校驗值、缺口旗標。
- `TranscriptSegment`：來源時間範圍、原文、語言、暫定／定稿、模型版本、來源 media ID、人工修訂。
- `TranslationSegment`：指向原文版本、目標語、譯文、使用的術語表版本、推論來源、修訂版本。
- `OcrObservation`：影格時間、ROI、文字、語言、信心值、重複群組、參考圖片 ID。
- `SummaryItem`：類型（重點／決策／待辦／風險）、內容、來源逐字稿／OCR ID、產生時間、人工確認狀態。
- `HealthEvent`：斷線、權限、低電、過熱、模式切換、佇列溢位、媒體缺口與原因。

儲存策略：預設只保留必要 AAC 音訊與文字；完整視訊不保存，OCR 參考畫面在使用者開啟功能且明確允許後才存精選影格。App 使用私有儲存空間並提供會議刪除、保留天數設定及匯出；未經使用者選擇不送雲端。若課堂／研究場域有錄音告知或同意要求，產品操作流程須配合場域規範。

## 8. Android 執行與 IDE／建置規格

- Kotlin + Jetpack Compose 作為 UI 與狀態顯示。音訊、影像、推論使用獨立工作執行緒／協程及有界佇列；畫面訂閱經過節流的狀態，避免每幀觸發 Compose 重組。
- 會議服務在使用者可見畫面啟動，顯示錄製中通知與停止按鈕；Android 版本／target SDK 對應的 [foreground service 類型與權限](https://developer.android.com/develop/background-work/services/fgs/service-types) 須在實作時逐一確認。眼鏡連線通常考慮 `connectedDevice`；只有 App 自行以 Android 麥克風擷取時才依實際用途宣告 `microphone`，不能把 FGS 當成繞過系統背景限制的保證。
- WorkManager 只排程會後摘要、資料匯出或非即時整理，不執行持續 BLE callback。App 進背景、螢幕關閉、來電、VIVE Connect 重啟、系統回收、權限撤銷均要真機測。
- IntelliJ IDEA 可作 Kotlin 編輯器，但 Android SDK、Gradle、模擬器／真機部署、Logcat 與 Compose 預覽支援需依已安裝的 Android 插件確認；若工具鏈受限，使用 Android Studio 執行與除錯並不改變 Kotlin／Compose 選型。正式環境以真機測試為準，模擬器不能驗證 BLE 媒體、眼鏡電池與熱行為。
- 專案版本固定記錄 SDK 0.6.0 beta／VIVE Connect／眼鏡韌體。未來若採新版 SDK，重新執行授權、串流、互斥和長時測試，不直接沿用本文件對 0.6.0 的逆向結論。

## 9. 功能互動與降級規則

| 事件 | 系統動作 | UI／資料結果 |
|---|---|---|
| OCR 使用者啟用 | 音訊單流先停止，再啟動音影同流；測量切換時間 | 明示短暫中斷可能性，記錄音訊缺口；成功後顯示 OCR 狀態。 |
| 手機溫升／眼鏡低電／BLE 影像積壓 | 先減少 OCR 選幀；若來源頻寬仍高，停音影流改音訊單流 | 顯示「影像暫停、繼續錄音」，保留已取得音訊；切換缺口可見。 |
| STT 推論積壓 | 停止新 OCR 與增量摘要，優先處理 STT；必要時只錄音 | 字幕標示延遲秒數，不捏造尚未辨識的字句。 |
| 眼鏡斷線 | 關閉當前 epoch，記缺口，有限次重連 | 重連成功開新 epoch；失敗時通知並保留已錄內容。 |
| PC 卸載斷線 | 停止送新任務，保存未確認片段；可切手機模型 | 不重複顯示重送後的結果；顯示推論模式。 |
| 儲存空間不足／持久化失敗 | 阻止繼續宣稱完整錄製；提示並安全停流 | 缺口、失敗原因與最後成功位置可見。 |
| 權限撤回 | 停止相應 SDK 工作階段 | 不自動重啟；使用者重新授權後才能繼續。 |

降載的因果關係須清楚：**減少手機 OCR 抽幀只省手機算力，不省已經從眼鏡送出的無線流量；降低眼鏡端需求必須調低 SDK 請求的幀率／尺寸／位元率，或停掉影像來源。** 視訊設定若不能運行時修改，需一次受控停流與重啟並標記缺口。

## 10. 效能預算、驗證矩陣與驗收門檻

### 10.1 無線／儲存初估

- 500 kb/s 視訊 + 32 kb/s AAC ≈ **532 kb/s 編碼負載**，約 **239 MB／小時**；800 kb/s + 32 kb/s ≈ **374 MB／小時**。這只是編碼資料量換算，尚未含 BLE 協定開銷、重傳與封裝；因此不能用標示的「BLE 2M PHY」直接認定穩定可用。
- 單獨 32 kb/s AAC 約 **14.4 MB／小時**。音訊長期保留的儲存成本較低；影像成本主要是無線、相機與手機解碼／OCR 的能源負擔。
- 235 mAh 眼鏡規格與音樂／通話續航不適用於推估「連續相機 + 麥克風 + BLE」時間。先量測 15／30／60 分鐘，再決定可宣稱的會議長度與是否需要充電配件。

### 10.2 量測方案

| 測試 | 方法 | 必須記錄 |
|---|---|---|
| BLE 基線 | 相同場地依序測 audio-only、480p／15 fps／500 kb/s、720p 候選；每組 15／30／60 分鐘，並在有人走動／Wi-Fi 壅塞場景重測 | 眼鏡與手機電量、溫度／熱狀態、實得位元率、音訊缺口、影格時間戳、斷線、重連時間。 |
| OCR 品質 | 拍真實投影片、白板、A4 文件，遠／近與不同光線各取樣；人工標註文字 | 字元錯誤率、可讀率、錯誤專業詞、從影格時間到 OCR 出現的 p50／p95。 |
| 音訊／翻譯 | 台灣國語、英語、混合語、會議室噪音、兩人輪流說話；標註逐字稿與術語 | WER／CER、術語正確率、穩定逐字稿與翻譯完成的 p50／p95、缺口標示正確性。 |
| 並行管線 | 同時錄音、OCR、STT、翻譯及摘要，與 audio-only 基線比較 | 各佇列高水位、模型排隊時間、CPU／NPU 使用、熱節流後延遲、App RSS。 |
| 背景與故障 | 螢幕鎖定、切 App、來電、權限撤銷、VIVE Connect 中止、拔掉網路、空間不足 | 是否仍錄製、通知狀態、可恢復範圍、缺口與重送是否正確。 |

### 10.3 暫定驗收指標（需求目標，非現有能力聲明）

1. **長時可靠性**：目標 60 分鐘會議不崩潰；任何缺口均被偵測並標示。無故遺失的音訊不可接受。若實得電池續航小於 60 分鐘，交付說明須明列供電條件並以真機數據修正目標。
2. **延遲**：穩定逐字稿在語句結束後 p95 ≤ 5 秒；翻譯 p95 ≤ 8 秒；已選 OCR 影格到文字出現 p95 ≤ 3 秒。若模型／硬體達不到，優先保錄音並重新協商「即時」的展示指標。
3. **融合**：逐字稿／OCR 的跨模態時間對齊誤差，經校準後目標 ≤ 1 秒；時間戳不可靠時顯示「約略時間」並保留來源資料，不假造精確對齊。
4. **OCR**：先以團隊自製、含真實白板與投影片的測試集訂可讀率門檻；沒有資料集前不承諾「準確辨識各種遠距文字」。
5. **熱與電**：每一組 15／30／60 分鐘測試都記錄眼鏡與手機電量斜率、裝置回報熱狀態與使用者可接受的表面熱感。任何過熱事件須能降級到 audio-only 或停止，不能靠事後摘要掩蓋遺失。

## 11. 里程碑與完成條件

| 階段 | 工作 | 可檢查的完成物／通過條件 |
|---|---|---|
| M0 真機能力盤點 | 固定眼鏡韌體、VIVE Connect、0.6.0 SDK、目標手機；確認授權、回傳 codec/PTS、允許幀率、BLE 穩定性及資源互斥 | 10–15 分鐘原始 callback 紀錄與能力表；Wi-Fi Direct 四門檻的前兩項結論；更新此 SPEC 的假設。 |
| M1 長時音訊基線 | 音訊單流、私有儲存、時間戳、前景通知、異常缺口、60 分鐘測試 | 可回放／解碼的音訊、完整 health events；無未標示缺口。 |
| M2 STT／翻譯 | 串流切塊、去重、術語表、暫定及定稿句、手機 UI；驗證系統藍牙播音與錄製並行 | 真實會議語料的 WER／CER、術語率、p95 延遲；播音回授與 BLE 競爭的真機結論。 |
| M3 同流影像／OCR | 一次 video-with-audio session、獨立影像管線、選幀 OCR、時間軸合併 | 同時錄音與 OCR 的實際資料，清晰度與無線負載 A/B 報告。 |
| M4 AI Hub／電腦模式 | 指定手機上模型編譯與 profiling、必要時 LAN 卸載與重送 | 相同語料的手機／PC 對照數據；斷線後結果不重複、不遺失已錄片段。 |
| M5 長時與展示 | 過熱／低電降級、會後摘要證據引用、資料刪除匯出、故障演練 | 60 分鐘端到端測試、量測報告、可重現展示流程與已知限制。 |

每一階段只在上階段產出的真機資料足以支撐下一階段時擴功能。Wi-Fi Direct 不阻擋 M1–M5；若 HTC 提供正式介面，再新增獨立傳輸實驗並與 BLE 對照。每個里程碑的測試門檻與證據要求見第 15 節；功能完成不等於通過驗收。

## 12. 待團隊確認的規格決策

1. **目標手機**：廠牌／型號／SoC／RAM／Android 版本，以及是否允許長會議時插電。此項決定 AI Hub 可用模型與熱預算。
2. **語言對與輸出形式**：繁中↔英文是否為第一優先？逐字稿是單語或雙語並排？是否要求說話者分離？分離說話者會增加模型成本，先列延伸功能。
   同時確認主要聲源是佩戴者、遠端講者，還是整間會議室；這決定眼鏡麥克風模式及是否需要手機麥克風備援。
3. **會議長度**：驗收是 30、60、90 或 120 分鐘？可否在眼鏡充電狀態下開會？需先拿到真機續航數據才能承諾。
4. **OCR 使用方式**：常開、自動場景判斷，或手動觸發？主要拍投影片、白板或紙張？目標文字大小與距離需用樣本確定。
5. **資料與算力**：會議內容可否出手機到校內電腦？是否需完全離線？原始音訊保留多久？匯出格式是否要 Markdown／PDF／SRT？
6. **眼鏡上的回饋**：手機字幕是否足夠；若需眼鏡語音回饋，是每句播報、重點播報或按鍵請求播報？可先以一般系統藍牙音訊測回授／串流並行。目前 SDK 不承諾在串流中顯示文字或呼叫 `speakText`。
7. **Wi-Fi Direct 調研結果**：若 HTC 無法提供公開即時媒體介面，是否同意將其保持為研究結論而非成品必要功能？

## 13. 可延伸亮點

- 以 OCR 偵測到的投影片標題／圖表詞彙更新術語表，讓後續專業翻譯更一致，但來源及信心值可追蹤。
- 會後摘要附「此句來自 12:34 的發言及 12:36 的白板」證據連結，讓使用者能核對而非只閱讀模型結論。
- 針對講者差異、口音及領域術語做個人化詞典與離線評估；在電量與熱預算允許後再加入說話者分離。
- 建立可重現的 BLE／AI Pipeline 壓測資料集與儀表板，讓專題能量化延遲、能耗及不同模式的品質取捨。

## 14. 來源與版本註記

- [HTC VIVE Eagle 官方規格](https://www.vive.com/us/product/vive-eagle/spec/)
- [HTC Live Translation 使用說明](https://www.vive.com/hk/support/vive-eagle-en/category_howto/using-live-translation.html)
- [HTC Android SDK Setup](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/setup/)
- [HTC `startVideoStreaming` API](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/viveglass/startvideostreamingwithvideoconfig/)
- [Android Wi-Fi Direct 文件](https://developer.android.com/develop/connectivity/wifi/wifip2p)
- [Android Foreground Service 類型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Qualcomm AI Hub Workbench FAQ](https://workbench.aihub.qualcomm.com/docs/hub/faq.html)、[Whisper-Tiny 模型頁](https://aihub.qualcomm.com/models/whisper_tiny)
- [團隊 vive-glass-sdk 逆向 repo](https://github.com/wolf-yuan-6115/vive-glass-sdk)；具體比對 `sdk/02-public-api.md`、`sdk/04-ble-and-framing.md`、`sdk/06-media-streaming.md`、`sdk/09-caveats-and-source-map.md`。
- 本機參考：`D:\Project\NCKU-Proj\Android_ViveGlassSDK_0.6.0_beta\ViveGlassSample\app\src\main\java\com\htc\viveglass\viveglasssample\ViveGlassKitManager.kt` 與 `D:\Project\NCKU-Proj\report`。本文件未更動這些資源。

## 15. 測試與驗收計畫

### 15.1 目的、邊界與測試證據

本節是**尚未執行的測試規格**，不表示 App、測試程式或真機結果已存在。實作後，每項已交付功能均須有需求 ID、至少一個可觀察結果的測試案例、可重現測試輸入、測試層級與執行紀錄。測試以「錯誤會造成甚麼實際損害」選例：錄音缺口未標、串流重複啟動、舊 callback 污染新會議、轉譯亂序、OCR 錯誤被當成事實、敏感會議資料意外上傳，均比 getter/setter 或畫面是否存在更值得測。**只呼叫 SDK method 並斷言其被呼叫，不算功能通過。**

測試結論需分成「模擬行為通過」、「Android 平台行為通過」、「VIVE Eagle 真機通過」三種；Fake SDK 或模擬器不能替真機背書。錄製至少一份真實外部行為的黃金 trace，供後續 callback replay 與回歸測試；授權、錄音和錄影須使用團隊允許的測試資料。每個失敗案例保留 SDK／App／VIVE Connect／韌體與手機版本、場景、輸入摘要、期望／實際結果、來源時間及已遮蔽個資的 log。

### 15.2 測試層級與執行環境

| 層級 | 執行位置與資料來源 | 可證明的內容 | 明確不能證明的內容／執行時機 |
|---|---|---|---|
| L0 靜態檢查 | Gradle 建置、Kotlin 編譯、Android lint、依賴與 Manifest 審查 | 型別／資源／宣告錯誤、已知危險權限或明文網路設定 | 不能證明會議錄音、API 回傳或模型品質。每次變更執行。 |
| L1 本機 JVM 單元測試 | 純 Kotlin 邏輯、固定媒體 metadata、假時鐘與排程器 | 狀態轉移、時序、資料驗證、去重、排程、失敗恢復決策 | 不執行 Android codec、BLE 或 VIVE Connect。每次變更執行。 |
| L2 元件／整合測試 | Android 模擬器或受控測試裝置；Fake SDK callback、合法 AAC/H.264 測試片段、假推論器／PC 測試服務 | App 與服務、Codec、儲存、Compose UI、通知、跨模組資料流 | 不證明眼鏡 RF、實際續航、韌體行為。合併前執行核心流程，夜間跑完整矩陣。 |
| L3 真機 SDK 契約測試 | 實際 VIVE Eagle、VIVE Connect、指定 Android 手機；可觀察 SDK call/return/callback 與媒體輸出 | 授權、起停流、音影同流、資源衝突、音訊路由、斷線／重連等實際行為 | 不用逆向推定代替結果。每個 SDK／VIVE Connect／韌體版本變更、M0／M1／M3／M5 前執行。 |
| L4 長時／品質／壓力測試 | 真機、真實或已授權測試語料、15／30／60 分鐘場景 | 端到端延遲、準確率、無線吞吐、熱、耗電、資料完整性、故障恢復 | 模擬器效能數據不納入硬體宣稱。階段驗收與展示前執行。 |

對純協程邏輯使用 Kotlin `kotlinx-coroutines-test` 的 `runTest`／虛擬時間，以可重現的事件順序驗證超時與排隊；**真實執行緒競爭**另用 L2/L3 壓力與交錯測試，因為虛擬時間的單執行緒測試無法證明多執行緒安全。Android [測試策略](https://developer.android.com/training/testing/fundamentals/strategies)也區分本機單元、元件、功能及裝置層級；[Compose 測試](https://developer.android.com/develop/ui/compose/testing)以語意樹和使用者動作斷言畫面行為。測試依賴的具體版本等專案開始建置時才依已選 Android Gradle Plugin／Compose／Kotlin 版本鎖定。

### 15.3 需求與測試可追溯矩陣

| 需求 ID／對應模組 | 必測使用者結果 | 核心案例 ID | 最高必要層級 |
|---|---|---|---|
| R01 `GlassSession` 授權／連線 | 已授權且已配對才可起流；拒絕後有恢復指引 | ST01、ST02、API01、HW01 | L3 |
| R02 `MeetingService` 狀態與背景 | 起停流唯一、通知可停止、重連後不混 epoch | ST03–ST10、INT01、INT10、HW02 | L3 |
| R03 `MediaIngest` 音訊保存 | 音訊可解碼／可恢復，缺口必被標示 | U01–U04、INT02、HW03 | L3／L4 |
| R04 `AudioPipeline` | 實際語音能轉成時間正確的定稿句，重疊不重複 | U05–U07、AI01、AI06、INT03 | L4 |
| R05 `TranslationPipeline` | 術語、數字和否定不丟；修改後版本對應原句 | U08、AI02、INT03 | L4 |
| R06 `VisionPipeline`／OCR | 解碼選幀後有可信 OCR；低信心不當作事實 | U09–U11、INT04、AI03、HW04 | L4 |
| R07 `SummaryPipeline` | 重點／決策／待辦有有效來源，不跨會議引用 | U12、AI04、INT05 | L4 |
| R08 `InferenceGateway` 手機／PC | 斷線後不遺失已保存片段、不重複輸出 | U13、API05–API08、INT06 | L2／L3 |
| R09 `FeedbackOutput` | 文字可讀；選用眼鏡播音時不污染錄音 | UI01、HW05、AI05 | L3／L4 |
| R10 `MeetingStore` 隱私／匯出 | 刪除確實移除資料；未同意時無 PC 傳輸 | U14、API09、INT07、SEC01–SEC03 | L2／L3 |
| R11 `Compose UI` | 開始、停止、權限、降級、延遲與錯誤狀態可理解且可操作 | UI01–UI06、INT08 | L2／L3 |
| R12 效能與降載 | STT 優先於 OCR；高熱／低電有可見降級與量測結果 | U15、INT09、HW06、PERF01 | L4 |
| R13 Wi-Fi Direct 預研 | 只在第 2.1 節四道門檻全部通過後加入交付功能 | HW07 | L3／L4，條件式 |
| R14 輸入／設定 validation | 無效格式、語言、token、PTS 或 payload 不能建立錯誤 session 或污染資料 | U01、U16、API07、SEC03 | L1／L2；格式相容性到 L3 |

規則：一旦增加、修改或取消需求，就同步調整此矩陣與對應案例；未實作／無真機條件的功能標為「未驗證」或「延後」，不可算已通過。R13 的預研結果即使是「不支援」也可完成研究結論，但不得說 Wi-Fi Direct 功能通過。

### 15.4 狀態轉換測試：輸入、預期與副作用

以 §4.1 的狀態模型作外部可觀察契約。`FAILED` 表示需使用者處理的終止錯誤；其時不再宣稱正在錄製。下表每列至少測成功與列出的錯誤分支，Fake SDK 驅動所有合法／非法事件順序；關鍵路徑再以 L3 真機驗證。

| ID | 初始狀態 + 事件 | 預期狀態與可觀察結果 | 層級 |
|---|---|---|---|
| ST01 | `IDLE` + 開始、缺相機／麥克風或 VIVE Connect 拒絕 | 停在 `AUTHORIZING` 至授權結束；拒絕回 `IDLE`／`FAILED` 並顯示原因；**不得呼叫起流或產生空白會議** | L1→L3 |
| ST02 | `AUTHORIZING` + 授權成功但未配對／`connect` 失敗 | `CONNECTING` 後進可恢復錯誤；通知使用者配對，無活躍媒體工作 | L1→L3 |
| ST03 | `CONNECTING` + `CONNECTED` + 音訊起流成功 | `AUDIO_ONLY`；恰好一個 stream epoch，一筆錄製中通知，音訊保存開始 | L1→L3 |
| ST04 | `CONNECTING` + `CONNECTED` + 視訊起流同步回傳失敗或非同步錯誤 | 不進 `AV`；釋放候選資源，顯示錯誤；允許依政策回 audio-only，但記錄切換與缺口 | L1→L3 |
| ST05 | `AUDIO_ONLY` + 啟用 OCR／`AV` + 關閉 OCR | 嚴格依序 stop 舊流→確認結束→start 新流；新 epoch；切換間隔標 gap；永不兩流並行 | L1→L3 |
| ST06 | `AUDIO_ONLY`／`AV` + 連續兩次開始或快速 start/stop/start | 只接受一個操作序列；不重複分配 session、通知、callback；最終狀態與最後有效指令一致 | L1→L2 |
| ST07 | `AV` + 低電／過熱／音訊積壓 | 先停 OCR；必要時 `DEGRADED` 並切 audio-only 或安全停流；原因、缺口與 UI 一致 | L1→L3 |
| ST08 | 任何活躍流 + BLE 斷線，舊 callback 延後抵達 | `RECONNECTING`，封閉舊 epoch；過期 callback 不入新會議／新 epoch；有限次重連後明確失敗 | L1→L3 |
| ST09 | 任意可錄製狀態 + 使用者停止／通知停止／權限撤回 | `STOPPING → FINALIZING → READY` 或明確 `FAILED`；停止接受新媒體，已收音訊沖洗一次，無殘留前景通知 | L1→L3 |
| ST10 | `STOPPING`／`READY` + 重複 stop、晚到 `STARTED`／晚到 PC 結果、App 被重建 | stop 冪等；晚到事件不重啟串流、不污染摘要；重開後可見已保存會議與最後健康狀態 | L1→L3 |

對每個轉換同時斷言**狀態、SDK 動作次數、儲存結果、HealthEvent、通知／畫面**，而非只斷言 enum。用表驅動測試覆蓋允許與拒絕的事件組合；無效事件不可使 session 或 stream epoch 倒退。涉及系統殺進程的恢復須 L2/L3 另外測，單純重建 Activity 無法代表進程死亡。

### 15.5 外部 API／method 契約測試

App 的 SDK 邊界先用可設定回傳值與 callback 順序的 Fake 驗證**自身處理方式**，再用官方 0.6.0 AAR + 真機驗證**廠商行為**。依 [HTC Android Setup](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/setup/) 和本機範例 `ViveGlassKitManager.kt` 實際使用的 method 建案例。當 SDK 同步回傳、例外和非同步事件彼此矛盾時，以可觀察到的最保守狀態處理並記錄事件；不得只看 `isVideoStreaming()` 或單次回傳值就宣稱已錄製。

| ID／邊界 | 注入／執行條件 | 斷言：對使用者有意義的結果 | 層級 |
|---|---|---|---|
| API01 `connect`、`disconnect`、`checkPermission`、`requestPermission` | 未註冊／未配對、相機或麥克風拒絕、權限撤回、先回授權事件再連線事件；真機完成 VIVE Connect 授權 | 不能越過授權直接起流；錯誤可區分並可重試；斷線清理資源；重新授權後才恢復 | L1/L2 + L3 |
| API02 `startAudioStreaming`、`stopAudioStreaming`、`isAudioStreaming` | 有效 16 kHz 範例格式、SDK 回 `-1`、callback `STOPPED` 晚到、重複 stop | 成功後 AAC 可解碼且時間增長；失敗時無假錄音；重複 stop 不多寫片段；狀態查詢與事件衝突時不掩蓋錯誤 | L1/L2 + L3 |
| API03 `startVideoStreaming`、`stopVideoStreaming`、`isVideoStreaming` | 有效音影格式、缺相機／麥克風授權、資源衝突、格式被拒、重複 start | 成功時兩個 callback 都收到可解碼且有時間戳資料；失敗後不留半開管線；不能與 audio-only 同時工作 | L1/L2 + L3 |
| API04 `StreamingBufferCallback`／`StreamingEventCallback` | ByteBuffer 有非零 position／offset、size=0 + EOS、codec config/keyframe、錯序／晚到音影、低電／過熱事件 | 僅讀 `BufferInfo.offset..offset+size` 合法範圍；回呼後資料仍正確；EOS 可收尾；影像 decoder 不因合法 config/frame 旗標錯誤而崩潰；健康事件可見 | L1/L2 + L3 |
| API05 PC WSS 配對／送片段 | 正確短效 token、到期／錯誤 token、TLS 驗證失敗、會議模式未允許 PC | 有效配對才送必要音訊／OCR ROI；拒絕或憑證錯誤時不送資料，手機仍可錄音並提示推論延後 | L2 + LAN 真機 |
| API06 PC ack／重送／結果合併 | 第 N 片斷線、重連補送、回覆亂序／重複、伺服器重新啟動 | 每個 session+epoch+序號至多一份可見定稿結果；已保存未確認片段可補送；不能錯接其他會議 | L1/L2 + LAN 真機 |
| API07 PC 訊息驗證 | 缺欄位、負時間、超大 payload、錯 session ID、偽造模型版本、超時 | 入口拒絕或隔離不合法訊息；不寫入 MeetingStore／不更新 UI；記錄不含會議內容的錯誤指標 | L1/L2 |
| API08 `InferenceGateway` 本機↔PC | 模型載入失敗、PC 斷線、重連、手動切換 | 來源片段只處理一次或以穩定 ID 去重；UI 清楚顯示模式與積壓，已錄音不被卸載錯誤牽連 | L1/L2 + L3 |
| API09 資料匯出／刪除 | 完成中的會議禁止刪除或先安全停流；匯出後刪除；App 重開 | 匯出含同時間軸原文、翻譯、OCR 及來源引用；刪除後 App 私有音訊／精選影格／索引與待上傳佇列均不可再讀 | L2/L3 |
| API10 禁止的 SDK 並行方法 | 錄製中觸發 `captureImage`、`startTranscription`、`speakText` 或第二次起流 | App 自身明確拒絕／提示受限，不送出相互衝突的 SDK 請求；L3 再確認 0.6.0 真機資源衝突 | L1/L2 + L3 |

`BufferInfo` 的 `offset`、`size`、`presentationTimeUs` 與 `flags` 意義以 [Android 官方 MediaCodec.BufferInfo](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo) 為依據；零長度 EOS 不能當普通空白影格，也不能遺失終止訊號。從 Fake 推得的例外順序僅驗證 App 防護，不取代 HTC 真機契約結果。`stop*` 或 `is*` 的 SDK 具體回傳／異常若與官方文件或 0.6.0 行為不同，更新契約記錄與 SPEC，不寫一個只會符合 Fake 的測試。

### 15.6 純邏輯 method／validation 測試

下表針對**輸入→外部可觀察輸出或副作用**撰寫，不直接測 private helper、常數值或 Kotlin／Android 標準函式本身。時間、排程、模型輸出和檔案失敗均可注入，避免固定 `sleep` 與偶發測試。

| ID／待測方法或行為 | 最小但有效的輸入 | 必須斷言的結果 |
|---|---|---|
| U01 callback 緩衝複製／驗證 | 有效區間不從 0 開始、回呼後原緩衝被覆寫；再給 `offset+size` 超界與負 size | 正確保存原始有效 byte；越界資料被拒且錯誤可觀測；不讀越界、不把空資料當有效音訊。 |
| U02 時間對映／epoch | 音影 PTS 不同起點、重連後 PTS 重設、亂序與重複序號 | 融合時間維持合理單調；重連新 epoch 不把 0 當舊會議時間；重複片段不多寫。 |
| U03 AAC 分段／沖洗 | 音訊跨檔界、正常停止、寫入中斷／磁碟滿 | 完整片段可解碼且校驗值吻合；未完成段不可假裝完成；中斷最後位置與缺口可查。 |
| U04 音訊持久化背壓 | 注入慢磁碟，使 5 秒上限耗盡 | 拒絕繼續宣稱完整錄製、立即通知／健康事件；不得默默覆蓋最舊 AAC。 |
| U05 VAD／切塊 | 前後靜音、跨 4–8 秒界的句子、短暫停頓、長句、兩片重疊 0.5 秒 | 語句時間範圍覆蓋有效語音，長句受硬上限控制；沒有超長積壓或無端截詞。 |
| U06 STT 暫定／定稿合併 | 同一句在重疊片段重複、晚到的較舊結果、空輸出、錯語言 | 畫面只呈現一份可追溯定稿；舊暫定不覆蓋新定稿；空輸出不生成幽靈句。 |
| U07 STT 積壓控制 | 推論器延遲 20 秒並持續接收 AAC | 錄音持續保存，UI 顯示延遲；OCR／摘要依優先級暫停，不形成無界記憶體。 |
| U08 術語翻譯／版本 | 「NCKU」、型號、日期／數字、否定句；同一句人工修訂後重新翻譯 | 專有詞、數字、否定語意不被規則覆寫；譯文指向正確原文版本與術語表版本，舊回覆不覆蓋新版本。 |
| U09 H.264 影格選取 | I/P 幀依賴、SPS/PPS 更新、解碼後模糊／清晰兩幀、佇列溢位 | 對編碼資料維持 decoder 所需相依順序；只在解碼後丟低價值候選；保留最新清晰幀且佇列有界。 |
| U10 OCR 觀測合併 | 同一白板文字跨 10 幀、角度改變、低信心單幀、明顯新文字 | 重複事件合併、時間來源仍指向影格；低信心不自動進確定術語表；新文字可成獨立事件。 |
| U11 音影同步 | 同一可見／可聽事件搭配不同 PTS 基準、100 ms 抖動、一次 2 秒跳躍 | OCR 和音訊標記同一事件時符合可設定容差；大跳躍標示不可信／新 epoch，不能假造 1 秒內精度。 |
| U12 摘要證據約束 | 有效逐字稿與 OCR ID、跨 session ID、指向已刪除來源、來源缺口 | 有效項目可回點原始句／畫面；錯 session、無來源或缺口中的「決策」不以確定事實輸出。 |
| U13 PC 任務去重與重試 | 同序號重送 3 次、ack 遺失、亂序回覆、過期 epoch | 最終僅一份定稿輸出；重試停止條件可觀測；舊 epoch 不更新現行字幕。 |
| U14 保留／刪除規則 | 不同 session 保留天數、過期但正處理中的 session、刪除後重啟 | 只清理指定會議及其相關媒體／衍生資料；不得誤刪其他會議；重啟後不重建已刪項目。 |
| U15 自適應降載排程 | 音訊 queue 高水位 + OCR backlog + 低電事件同時發生 | 音訊持久化優先、停止新增 OCR／摘要，必要時切換 audio-only；事件只觸發一次有效切換。 |
| U16 設定與邊界驗證 | 視訊位元率 199999／200000／800000／800001、空語言／不支援語言、超長術語、負 PTS、過大 PC payload | 邊界內設定被接受；邊界外由 App 明確拒絕或可見回退；不依賴 SDK 默默轉成 500 kb/s，也不建立無效 session。 |

**參數驗證清單：**會議語言／目標語是否支援、空或過長術語、模型資產版本／校驗值、位元率 200–800 kb/s、SDK 支援的幀率 enum、採樣率／聲道的實際可用性、PTS 單位／溢位、codec 旗標、buffer 邊界、session／epoch／序號、PC payload 大小及 token 期限。設定不合法時 App 明確拒絕或回退並告知使用者，不能依賴 SDK 靜默替換參數；格式在真機上不被接受時須保存實際生效設定供分析。

### 15.7 Android 模擬器與整合測試

L2 用同一組**合法錄製素材**重放 SDK callback：至少有 AAC-only、H.264+AAC、SPS/PPS 更新、音影 callback 交錯、異常結束及斷線後新 epoch。每個 fixture 要有音訊樣本數／長度、影格數／尺寸、PTS、SHA-256 與人工標註文字；測試素材可用團隊自錄測試句和白板，不放真實未授權會議內容進版控。Fake SDK 模仿**已觀察到**的 callback 形狀，另可注入超出契約的壞資料來驗證 App 防護；它不是重新實作 SDK 的 BLE 協定。

| ID／情境 | 施加的刺激 | 端到端預期與證據 |
|---|---|---|
| INT01 Activity／服務生命週期 | 開始錄音→切至背景→鎖屏→旋轉／重建 Activity→由通知停止 | 會議只屬於一個前景服務；沒有重開串流或 duplicate session；通知與畫面狀態一致；停止後無留存錄製通知。 |
| INT02 callback→檔案→解碼 | 餵入合法 AAC 範本及零長度 EOS，中途一次模擬進程死亡 | 已提交片段可重新開啟與解碼，片段時間／校驗值與標註一致；中斷區間有 gap，不產生假完成檔。 |
| INT03 音訊→STT→翻譯→UI | 餵含中英專業詞的標註測試語料，假推論器有暫定、修訂、空回覆與延遲 | 原文與譯文按來源時間排序；暫定更新後僅一份定稿；術語版本正確；推論慢時延遲標示可見。 |
| INT04 音影同流→OCR | 餵清晰投影片與模糊／重複影格，實際 Android `MediaCodec` 解碼 | 麥克風路徑不中斷；選幀符合上限；OCR 只對清晰候選輸出；影格時間與 OCR 事件對應。 |
| INT05 逐字稿+OCR→摘要 | 發言與白板同一時間軸，其中 OCR 有低信心文字 | 摘要只引用有效來源 ID，低信心內容保留提示而非未經核實的決策；來源連結可跳回。 |
| INT06 PC 模式網路故障 | 傳到一半切 Wi-Fi、伺服器重啟、重連，故意回送重複／亂序結果 | 手機不停錄；待確認片段補送；每條結果恰好一次顯示，離線狀態與積壓時間可見。 |
| INT07 隱私／保存 | PC 模式關閉、錄製一段、匯出後刪除；另測保留期限 | 無非預期網路請求；刪除後媒體、影格、索引、暫存及 PC 待送項目無殘留；匯出可讀且不跨 session。 |
| INT08 使用者路徑 | 初次授權、拒絕、再次授權、開始錄音、啟 OCR、低電降級、停止、檢視摘要 | 每一步皆有可理解 UI／通知，錯誤附下一步；缺口不藏在只有 debug log 看得到的位置。 |
| INT09 同時負載／競態 | 同時輸入連續 AAC、15/30 fps H.264、慢 STT、慢 OCR，再觸發 stop／重連 | 接收 callback 不被模型阻塞；佇列上限生效；未標音訊丟失為零；清理後沒有活躍 codec／服務／任務。 |
| INT10 Android 權限／FGS 契約 | Android 10、13／14 與當前 target SDK 對應裝置；允許／拒絕藍牙權限、背景起流與手機麥克風備援 | `connectedDevice`／必要時 `microphone` 類型與通知遵守平台限制；權限撤回或背景起流被系統拒絕時 App 可見失敗且無假錄製。 |

Android 10（官方最低相容基準）、權限行為變動的 Android 13／14，以及開發時最新支援版本，至少覆蓋代表性模擬器／裝置組合；target SDK 決定最終權限與前景服務矩陣。`MediaCodec`、FGS 通知、實際儲存與 Compose 語意測試在 Android runtime 執行；模擬器缺少真實眼鏡鏈路，**不能**將 INT01–INT09 的綠燈解讀為 BLE、VIVE Connect 或續航通過。

### 15.8 Compose UI／可用性測試

Compose UI 測試優先用可存取名稱、role、state 與文字查找元素；`testTag` 只用在沒有穩定使用者語意的節點。這同時驗證 TalkBack 所需的語意，而非把 UI 測試寫成依賴節點階層的脆弱快照。ui-ux-pro-max 的 Jetpack Compose 指南指出需要 semantics／`contentDescription`，Impeccable native audit 強調系統返回、字體縮放、觸控尺寸、狀態可見性與平台慣例；這些在功能出現後變成下列可驗收案例，並以 [Compose 官方測試文件](https://developer.android.com/develop/ui/compose/testing) 校準 API。

| ID | 互動／顯示驗證 | 預期結果 |
|---|---|---|
| UI01 | 點「開始會議」後成功、等待、失敗三種狀態；點通知「停止」 | 錄製指示、開始時間、停止功能與實際服務同步；失敗不顯示「錄製中」。 |
| UI02 | 權限拒絕、藍牙斷線、低電、過熱、儲存滿、PC 離線 | 錯誤以文字與 TalkBack 公告呈現，附可執行下一步；不能只靠紅色圖示或只出 Toast。 |
| UI03 | 暫定句→定稿句→人工修訂；翻譯晚到 | 原文／譯文關聯明確、修訂不造成重複字幕；長會議列表滾動時仍可操作停止。 |
| UI04 | OCR 關閉／開啟及降級回 audio-only | 相機錄製狀態、切換缺口及資源受限原因清楚；操作不會誤觸第二個 SDK 串流。 |
| UI05 | 大字體、窄螢幕與橫向、明／暗主題、系統列／鍵盤顯示 | 主要操作不被裁切，至少 Android 48 dp 可點區，文字與狀態具可讀對比，系統 Back 可預期。 |
| UI06 | TalkBack／無障礙焦點走過開始、停止、OCR、模式與錯誤 | 唸出可理解名稱與狀態；焦點順序合理，停止會議不需要依靠視覺辨識圖示。 |

UI01–UI05 適合 Compose instrumented test；UI06 以自動語意檢查加**真人 TalkBack 探索**驗證。視覺截圖只用來找版面回歸，不能替代可操作性、語意或狀態斷言。效能上的列表捲動／大量字幕重組以真機 trace 或 Android [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview) 量測，模擬器數字不當硬體效能宣稱。

### 15.9 AI、資安、真機與效能測試

**模型資料集與判定：**先建有授權的獨立評估集，至少涵蓋繁中、英文與中英混語、專有詞、口音、近／遠講者、安靜／噪音、投影片／白板／紙張、強反光／晃動／小字。訓練／調參與驗收素材分開；版本、採樣率、影像壓縮與人工標註規則固定。模型輸出品質用 CER／WER、術語正確率、翻譯中數字／否定正確率、OCR 字元錯誤率、摘要來源引用有效率量化；不能拿「某一段看起來合理」或精確比對自由生成文句當作模型通過證據。第 10.3 節的 p95 與跨模態時間目標同時驗證。

| ID | 故障／品質試驗 | 通過準則／記錄 |
|---|---|---|
| AI01 | 同一語料比較 CPU、AI Hub 匯出手機模型、PC 模型；短片段與長句 | 記錄模型版本、pre/postprocess、CER/WER、p95、峰值 RAM；選型須同時達品質與熱預算。 |
| AI02 | 翻譯標註集的專有詞、數字、否定、跨句指代 | 量化每類錯誤；重大錯譯可追到原文與模型版本；門檻依團隊標註基線於 M2 確定。 |
| AI03 | OCR 真實 480p／720p 壓縮影格與標註圖 | 量化文字可讀率／CER、壞幀誤讀率；若 480p 不達標，明示限制而非只通過高清靜態圖片測試。 |
| AI04 | 摘要對標註會議，注入音訊缺口、錯 OCR、跨會議片段 | 每個決策／待辦可點回本會議來源；沒有來源或來源不確定時明示不確定，不輸出無根據的確定結論。 |
| AI05 | 邊錄音邊播短翻譯，對比只錄音基線 | 是否產生聲學回授、WER 改變、BLE 延遲／丟幀、耳機播音延遲可量化；若污染錄音，預設關閉連續播報。 |
| AI06 | 載入正確與錯誤版本的 AI Hub 模型資產／tokenizer／前後處理，於指定手機執行固定測試輸入 | 資產校驗或版本不符時拒絕推論；正確版本與離線基準的輸出差異在預先核准容差內，且沒有意外 CPU fallback 未被記錄。 |
| SEC01 | PC 模式關閉／token 到期／TLS 驗證失敗 | 不外傳語音或影像；錯誤 log 無原始會議內容與 token。 |
| SEC02 | 匯出／刪除／保留到期與備援片段 | 僅指定 session 的私有資料、暫存及待送項目被清除；不得清到其他會議。 |
| SEC03 | PC 回應含錯 session、惡意大資料、無效時間戳與 OCR payload | 拒絕合併、限制大小、無崩潰或跨會議資料污染。 |
| HW01 | 真機配對、授權拒絕／重授、VIVE Connect 重啟 | 起流權限與狀態和實際眼鏡一致；記錄韌體與 VIVE Connect 版本。 |
| HW02 | 真機背景／鎖屏／來電／藍牙切換／App 進程被終止 | 通知、停止與重啟政策正確；每段缺口可辨識；系統限制如無法自動重啟須清楚呈現。 |
| HW03 | 60 分鐘 audio-only、不同麥克風模式與講者距離 | AAC 可恢復、未標示缺口為零；收音品質依 CER/WER 與聲源位置量化。 |
| HW04 | 60 分鐘 video-with-audio，480p 起步；符合安全條件再試 720p | 實際影格尺寸、音訊缺口、OCR 品質、BLE 吞吐、眼鏡／手機電量與熱狀態均入報告。 |
| HW05 | 手機系統藍牙音訊→眼鏡播報，同時 SDK 音訊／音影串流 | 可以並行且不造成不可接受的回授／斷流，才將眼鏡播報標為可交付。 |
| HW06 | 人為低電／熱壓力及來源位元率切換 | 降級與通知符合 §9，切換造成的音訊空窗有 HealthEvent；過熱不得持續高負載。 |
| HW07 | 若 HTC 開放 P2P：發現、組群、取得**公開媒體**、與 BLE A/B | 逐項滿足 §2.1 四門檻才宣稱 Wi-Fi Direct 可用；需有吞吐、斷線、耗電、熱與 OCR 比較證據。 |
| PERF01 | 15／30／60 分鐘並行負載＋可控推論減速／無線壅塞 | 記 p50/p95、callback 耗時、各 queue 高水位、RSS、熱節流、耗電斜率；任何無界增長或未標音訊丟失均失敗。 |

**性能門檻的狀態：**§10.3 的 5 秒／8 秒／3 秒與 60 分鐘是暫定產品目標。M0/M1 真機基線後，團隊在不降低資料完整性要求的前提下將硬體可達值寫回 SPEC，並同時鎖定測試條件；不能事後只挑有利的一次測試說通過。每種長時組態至少記多次獨立執行、最差一次與分位數，裝置起始電量與室溫盡量一致。穩定性測試的判準是**未被發現或標示的音訊資料遺失為零**，不是畫面「還顯示錄製中」。

### 15.10 測試資料、CI 與里程碑放行

**測試資料準備（開發 M0–M1 時建立，現在只定規格）：**

1. 可公開使用的短 AAC、H.264+AAC 與壞資料 fixture；記錄編碼參數、PTS、預期片段／影格數及 SHA-256。另保留只供授權成員使用的真機 trace，不能隨意納入公開 repo 或 CI artifact。
2. 人工標註的中／英／混語語音、專業詞、投影片與白板評估集；與模型調參集分離。每個語料附授權、聲源距離、噪音與標註者，讓 WER／CER 有解釋性。
3. Fake SDK 設定檔只模擬**邊界事件與時序**：成功、同步拒絕、非同步錯誤、亂序、晚到、斷線、低電、過熱。測試應能藉改變一次輸入事件或刪掉關鍵處理分支而失敗；若不會失敗，這個案例沒有足夠保護力。
4. PC 測試服務能控制 ack 遺失、重複、延遲、亂序與憑證錯誤；只使用假資料。硬體測試前清理舊配對狀態並記錄手機、眼鏡及 VIVE Connect 版本。

**執行節奏與放行規則：**

| 時點 | 必跑項目 | 不通過時的處理 |
|---|---|---|
| 每次變更／PR | L0、受影響的 L1、核心狀態／API 契約與新增缺陷的最小回歸測試 | 編譯、重要分支或資料完整性案例紅燈即不合併。 |
| 合併前或每日 CI | 完整 L1、Android 模擬器核心 L2、Compose 使用者路徑；重要契約 fixture replay | 失敗先定位程式、fixture 或環境問題；沒有證據不得標為 flaky 後忽略。 |
| M0 | HTC SDK 真機 API 契約、授權及 10–15 分鐘原始 callback／媒體格式量測 | 未確認真機 callback／互斥行為，不宣稱媒體架構可用。 |
| M1 | R01–R03、R10 的音訊保存／權限／背景／缺口測試與 60 分鐘 audio-only 真機試驗 | 有未標音訊缺口、不能恢復或誤顯示錄製中，即停在 M1 修復。 |
| M2 | R04–R05、R09 的標註語料品質、延遲與播報回授測試 | STT／翻譯未達已凍結門檻則標為未達標，調模型／需求；播報不過只交付手機文字。 |
| M3 | R06、R07 的影格 OCR、來源引用、音影同步與 60 分鐘音影同流真機試驗 | 相機 OCR 妨礙錄音或引用不可信時不開啟 OCR 成品模式。 |
| M4 | R08、R10 的手機／PC 對照、LAN 斷線重送、模型資產驗證與隱私測試 | token／TLS／跨會議資料錯誤是阻擋缺陷；PC 模式可關閉但不能默默使用。 |
| M5 展示／發佈候選 | 全部**已宣告交付**功能的 L2+L3 必要測試、L4 長時／效能、正式打包版本的核心真機流程、操作手冊與限制說明 | P0/P1 開放缺陷、未標資料遺失、未驗證真機關鍵路徑或未達同意目標，均不標成完成。 |

P0：會議資料洩漏、跨會議污染、不可見的音訊遺失、錄製狀態與實際不符等；P1：核心錄音／翻譯／OCR 路徑失效、無法停止或無法恢復、不可接受的長時崩潰；P2：有替代路徑且使用者可理解的局部功能問題；P3：不影響任務的細節。缺陷處置記錄影響、可重現步驟、根因假說、最小修復與**能實際失敗的**回歸案例。若需求刻意縮減（例如眼鏡播報或 PC 模式延後），更新交付範圍與 UI，不能以跳過測試偽裝通過。

**證據包格式：**每輪驗收保存需求 ID → 測試 ID → 版本／commit → 裝置／韌體／Android／SDK → fixture hash／語料版本 → 執行時間 → 結果／指標 → 缺陷連結。留 logcat、測試報表、性能 trace 的必要摘要；公開報告對人名、語音及 token 去識別。覆蓋率只用來找漏測分支，不以單一百分比代替上述行為與硬體證據。[Android 官方測試指南](https://developer.android.com/training/testing/fundamentals/what-to-test)也指出，不應撰寫只驗證 framework／library 本身的低價值單元測試。

## 16. 工程技能與文件查證的使用規劃

| 資源 | 此專案的實際用途 | 何時使用／產出 | 不適用範圍 |
|---|---|---|---|
| `ponytail` | 實作前檢查 SDK／Android 原生能力、現有元件與最小可驗證設計；測試只針對有分支、資料損失或故障風險的行為，不為覆蓋率灌水 | 每個模組設計與 PR review；記錄刪除的多餘抽象、可重用的原生 API 與保留的必要防護 | 不能以「精簡」為由刪掉授權、錯誤處理、資料完整性或本節要求的測試。 |
| `fuck-my-shit-mountain` | 程式庫實際出現後，先建立入口／資料流／信任邊界地圖，再做 evidence-backed 的 `testing`、`stability`、`concurrency`、`data-integrity`、`privacy`、`ai-safety` 等面向稽核 | M2/M3 對變動範圍做稽核；M5 對交付範圍做完整稽核；產出有嚴重度、證據、失敗場景、最小修復及回歸案例的報告 | 目前只有 SPEC，沒有 App 程式碼；**不產生虛構問題、分數或已完成稽核報告**。真正執行時依該 skill 規定確定 mode／語言／輸出格式。 |
| `ui-ux-pro-max-skill` | 查 Jetpack Compose 的無障礙語意、明確錯誤回饋、狀態可見性與 Android 觸控規範 | UI 設計前做小範圍資料查詢；UI01–UI06 變成可自動與手動驗證的標準 | 不以通用 Web aria／像素規則替代 Android Compose／dp 的平台規範。 |
| `impeccable` | UI 成形後做 native `shape`／`audit`／`harden`：錄音狀態、TalkBack、大字體、暗色、系統返回與錯誤恢復 | UI 規格審查與 M5 前的有證據 UI 稽核；產出具體可修缺陷，不憑空給品質分數 | 目前無 UI 實作與 `PRODUCT.md`；本輪只規劃驗收，不啟動完整視覺設計或宣稱已通過稽核。 |
| `threejs-skills` | 若未來新增網頁 3D 會議空間或空間化視覺化，再評估相應技能 | 有明確 3D 功能需求時才選用 | 現階段是 Kotlin + Compose 手機 App，VIVE Eagle 公開 SDK 也無已驗證的眼鏡 3D 顯示 API；不加入 Three.js 依賴或 3D 測試。 |
| Context7 MCP | 取得已選 package／版本的近期 API 文件，對照官方文件核對簽章、權限、測試工具與棄用事項 | 開始實作與 SDK 升版前，逐項記錄文件 URL、查閱日期、套件版本、API 層級及決策 | 此次執行環境未提供可呼叫的 Context7 工具；目前依 HTC、Android、Kotlin 與 Qualcomm 官方來源。Context7 查詢結果也不取代真機驗證。 |

**查證優先序：**專案固定依賴版本與本機 0.6.0 範例 → HTC／Android／Kotlin／Qualcomm 官方版控文件 → Context7（若當前環境可連）做相同版本快速檢索 → 真機輸入／輸出 trace。文件與 AAR 或韌體不一致時，把差異寫入能力表與測試案例，不以最新網頁的敘述直接覆蓋本機版本的實際行為。本文新增測試規劃參考：[Android 測試策略](https://developer.android.com/training/testing/fundamentals/strategies)、[Compose 測試](https://developer.android.com/develop/ui/compose/testing)、[Kotlin 協程測試](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)、[MediaCodec.BufferInfo](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo)。

