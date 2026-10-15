# VIVE Eagle 在線串流與 OCR 真機測試（2026-10-15）

## 1. 結果摘要

本專案使用實驗 applicationId `com.htc.viveglass.viveglasssample`，已在 S23 Ultra 與實際 VIVE Eagle 完成 SDK 握手、Connected、純音訊錄製，以及相機與音訊同流。相機測試先發生一次原生崩潰；修正影像解碼輸出方式後，手機回歸測試與眼鏡重測通過。**串流與保存通過短時間測試，OCR 文字品質仍未通過驗收。**

這是白名單 sample 套件身分下的實驗成果；`com.beefsoup.ai.glass` 的正式 HTC 登錄仍待官方回覆。本次未重測原包名的在線授權，也未重新取得遠端白名單。

| 項目 | 本次結果 | 範圍限制 |
|---|---|---|
| SDK 授權與連線 | 日誌出現 `connect()--`、握手 `CONNECTED`，App 顯示來源已連線，收到真實媒體 | 僅實驗包名 |
| 純音訊 | 65.984 秒、7 個可解碼 M4A、19 段 PCM，正常停止 | 約一分鐘、App 保持前景 |
| 相機與音訊同流 | 修正後保存 21.107 秒音訊、3 個 M4A、6 段 PCM、20 筆 OCR | 短時間功能測試 |
| 解碼與 PCM 時間 | 封存音訊和去重後 PCM 時長一致；兩輪成功錄製均無健康警示或殘留 `.partial` | 不等同射頻層完全無丟包的證明 |
| OCR 品質 | 20 筆觀測中，14 筆包含正確的「你好世界」；英文與日文均有錯字 | 非標註語料的 CER 基準，不宣稱多語辨識通過 |
| 手機影像回歸 | 真實 H.264 編碼→MediaCodec 解碼→ML Kit，`OK (2 tests)` | 測試輸入為合成文字影格 |

精簡機器可讀證據：[vive_eagle_online_2026-10-15.json](evidence/vive_eagle_online_2026-10-15.json)。

## 2. 測試環境與操作

- 手機：Samsung S23 Ultra `SM_S9180`，SoC `SM8550`，Android 16／API 36；透過無線 ADB 操作。
- 眼鏡：VIVE Eagle，已透過 VIVE Connect 配對。韌體版號尚未記錄。
- VIVE Connect：`1.4.1387452`；SDK：`0.6.0`。
- applicationId：`com.htc.viveglass.viveglasssample`；namespace／Kotlin package：`com.beefsoup.ai.glass`。
- 所有真實錄製均確認「使用模擬音訊」和「啟用電腦推論」關閉；會議起始事件為 `demo=false`。
- 使用者確認配戴並授權由電腦操作開始／停止。錄製期間暫時延長螢幕逾時，測試結束後還原設定。
- 未解除配對、清除 Connect 資料或刪除錄音；修正版以相同套件和簽章覆蓋安裝，保留會議資料。

SDK 曾回報 `APP_INTERACTION_STOPPED_DEVICE_DOFFED`，並結束 App 互動及 BLE 連線。因此本次必須持續配戴。不能把這種明確的配戴狀態事件直接歸因於藍牙不穩，也不能推論所有斷線都只有這一個原因。

## 3. 純音訊驗證

先以 8.064 秒實錄確認媒體可解碼，再進行約一分鐘錄製。

主要會議 ID：`e5149e42-1742-4b49-96c5-ba10293f58c1`。

1. 連線後啟動 `startAudioStreaming`，確認畫面進入錄製中及前景服務實際運作。
2. 自確認錄製狀態後等待至少 60 秒，定期檢查時間軸未提前停止或出現健康事件，再操作停止。
3. 取回私有會議目錄，逐一以 PyAV 解碼所有 M4A，檢查封包 PTS 遞增、樣本值有限，以及 PCM 檔案長度／時間範圍。
4. 驗證最終正常停止事件、已保存狀態，以及沒有未封存的 `.partial`。

實際 AAC 為 **16 kHz、單聲道**。7 段的解碼時長依序為 10.048 秒 × 6、5.696 秒，共 **65.984 秒**。PCM 正規化為 16 kHz 單聲道，4 秒窗口／0.5 秒重疊，共 19 段；去除重疊後也是 65.984 秒。

另以本機快取 Whisper base 對第一段錄音做過離線辨識，能產出中文，但缺少使用者確認的完整口述原文，未計算 WER，也未驗收即時手機→PC 推論。診斷時先將音訊解碼為 NumPy，再呼叫模型，與既有服務接收 PCM 陣列的方式一致。

## 4. 相機崩潰與修正

### 首輪失敗

會議 ID：`9742066b-ac96-4e6e-8b34-ac997a34dd6e`。

音影同流已開始，隨後 App 的 `meeting-video` 執行緒收到 `SIGABRT`。Android 退出原因為 `APP CRASH(NATIVE)`；JNI 指出 `NewDirectByteBuffer` 收到空指標與非零容量，呼叫路徑為 `ImageReader.SurfaceImage.nativeCreatePlanes` → `getPlanes` → ML Kit `InputImage.fromMediaImage`。

這是原生層中止，無法靠外層 Kotlin `try/catch` 恢復。該會議沒有正常停止事件，留下 `audio-0.m4a.partial` 和已保存 PCM；原始資料已保留，不將此輪計為通過。

同輪解碼器日誌顯示可見尺寸為 **480×856**，實際解碼儲存高度為 **864**。舊實作固定建立 856×480 的 ImageReader，並把其 Surface 輸出的影像直接交給 ML Kit。上述尺寸與呼叫路徑均有日誌證據；沒有進一步宣稱是哪一層廠商驅動造成空平面。

### 修正方式

`VideoOcrPipeline` 改為：

```text
SDK H.264 → MediaCodec（flexible YUV420、無 Surface）
           → getOutputImage
           → 依實際 crop、rowStride、pixelStride 複製成獨立 NV21
           → 關閉 Image／釋放解碼輸出
           → ML Kit 非同步辨識
```

仍解碼參考影格，OCR 最多取約每秒一張，前一次辨識未完成時略過抽樣。OCR 不再持有解碼緩衝區；停止後的晚到成功／失敗回呼不再寫入新觀測。

依據：[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) 的 flexible YUV／`getOutputImage` 介面，以及 [ML Kit InputImage](https://developers.google.com/android/reference/com/google/mlkit/vision/common/InputImage) 的 NV21 byte array 輸入方式。

### 手機回歸

在相同 S23 Ultra 執行 `VideoOcrPipelineTest`：

- `encodedAnnexBVideoDecodesIntoPhoneOcr`：856×480 橫向文字影格。
- `portraitVideoWithPaddedDecoderHeightDecodesIntoPhoneOcr`：480×856 直向影格，涵蓋本次眼鏡尺寸及解碼補齊情境。

兩者都使用真正的編碼器、解碼器和 ML Kit，要求讀出 `MEET` 且沒有解碼／OCR 警告。回報 **`OK (2 tests)`**。不是假回傳文字的 mock 測試。本次未將此前其他測試的通過數重算為新結果。

## 5. 修正後相機與音訊重測

會議 ID：`b5d7a8ee-5212-4729-b8e5-93965ab8c27f`。

使用者配戴眼鏡，對準其提供的文字：

```text
Hello World!
你好世界
こんにちは! 202610/15
```

以單次 `startVideoStreaming` 接收音訊與影像兩個 callback。確認錄製中後計時約 15 秒再發出停止操作；加上起停與 UI 控制時間，實際保存 **21.107 秒**音訊，並非精確 15 秒媒體檔。

- AAC：**44.1 kHz、雙聲道**，3 個 M4A 全部可解碼，共 909 個 AAC frame。
- 正規化 PCM：16 kHz 單聲道，6 段，去重時長 21.107 秒；與 AAC 解碼的 21.106939 秒之差約 61 微秒，屬樣本數取整差異。
- 影像：日誌確認可見尺寸 480×856、解碼儲存高度 864；本次仍使用手機硬體解碼器，未強制改成軟體解碼。
- OCR：20 筆非空且相鄰內容不同的觀測；其中 14 筆包含正確中文「你好世界」，沒有完整正確的 `Hello World!` 或 `こんにちは!`。
- 第一筆 OCR 事件在會議 start 事件後約 2.089 秒寫入。這包含串流啟動及抽樣等待，**不是相機曝光至結果的量測延遲**。
- 正常停止並進入已保存；沒有健康事件、影像警告、殘留 `.partial` 或再次崩潰。

因此「實際影像可到手機並產生 OCR」已獲驗證；「三種語言能可靠讀對」未通過。現有依賴是 ML Kit 中文模型，日文模型未加入。英文錯誤的成因尚未用影格品質、字體和模型對照實驗拆解，不能單由這輪判定。

## 6. 保留材料與下一步

原始日誌、UI XML、錄製控制結果、M4A／PCM、失敗會議及分析 JSON 均保存在 Git 忽略的 `captures/2026-10-15/online-glasses/`。共享證據僅保留環境、摘要指標與失敗原因，不收錄實際語音內容或 API key。

接續驗收順序：

1. 記錄眼鏡韌體；修正 UI 在已連線後仍可能顯示「正在連接」的文字，避免反覆按連線。此次連線判定使用 Connected 回呼與媒體證據。
2. 用固定印刷字、固定距離／光源／頭部姿態，比對英文及中文的原文和辨識結果，再評估日文模型與可讀影格篩選；分開量測 CER 與辨識延遲。
3. 在相同手機進行 15／30／60 分鐘音訊、鎖屏／背景、來電及斷線測試，記錄眼鏡與手機耗電、溫度、PTS 缺口與封存完整性。
4. 開啟手機→PC 推論，驗證真實會議的串流 STT／翻譯／摘要、斷線補送及 p50／p95 延遲；再評估 Qualcomm AI Hub 手機模型。

本次沒有證明 Wi-Fi Direct 可用，也沒有量測 BLE 吞吐極限、長時續航或正式發佈資格。
