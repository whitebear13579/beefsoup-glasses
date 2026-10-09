# 實作與驗證狀態（2026-10-02）

本文件把 [SPEC](SPEC.md) 的目標與目前能提供的證據分開。以下「通過」僅代表列出的測試環境，不代表 VIVE Eagle 真機驗收。

> 以下是 2026-10-02 的實作快照。2026-10-08 起已取得眼鏡並完成 S23 Ultra 無線部署；最新硬體／授權狀態見 [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md)。2026-10-09 已定位 Connect 的套件白名單拒絕分支，並取得端點的實際 JSON，確認主專案新舊包名皆未收錄，見 [白名單取得結果](SDK_WHITELIST_CAPTURE_PLAN.md) 與 [授權流程分析](SDK_AUTH_FLOW_ANALYSIS.md)；主專案完整握手與眼鏡串流仍未通過。

| 能力 | 目前狀態 | 可重現證據／剩餘驗證 |
|---|---|---|
| HTC 0.6.0 SDK 接入 | 已編譯、已串接公開 `startAudioStreaming` 與一次 `startVideoStreaming` 的雙 callback | 眼鏡尚未取得；VIVE Connect、權限、真實 AAC/H.264、BLE 時間戳及長時穩定性未驗證。 |
| 音訊優先封存 | 已實作 AAC→10 秒 M4A、16 kHz PCM、4 秒窗口／0.5 秒重疊、PTS 缺口檢查與有界接收佇列 | Pixel 9a Android 17 使用合成 AAC，驗證可播放 M4A 與 PCM 產出；60 分鐘與斷線仍待實機。 |
| 前景服務與 UI | 已實作連線、開始、停止、狀態、待轉譯量、匯出與確認刪除 | 模擬來源的前景服務生命週期在 Pixel 9a 通過。Samsung S23 Ultra Android 16、來電／鎖屏／權限撤回待真機。 |
| 影像 OCR | 已實作 H.264 解碼、約 1 fps 抽幀、手機端 ML Kit 中文 OCR | 模擬器已以 H.264 編碼文字影格驗證完整解碼→OCR 管線；眼鏡的實際 SPS 尺寸、轉向、壓縮文字可讀率待測。 |
| PC STT／翻譯／摘要 | 已實作 FastAPI WebSocket、Whisper、Ollama，摘要只保留本會議來源 ID | 本機合成英文語音經 API 得到英文逐字稿、繁中翻譯與附 `[a:0]` 來源摘要；真實雙語會議品質與 p95 延遲尚未量測。 |
| 斷線與重送 | PCM 先存手機私有目錄，PC 結果核對 session／epoch／sequence／PTS 才確認；最多 4 段在途，App 可讀回最近會議 | 模擬 WebSocket 測試覆蓋錯 session、ACK 去重；PC 重啟後可用手機已保存逐字稿／OCR 重建摘要證據。需實際 LAN 故障注入與長時負載。 |
| Wi-Fi Direct | 技術預研，未實作 | Wi-Fi 6E 不能證明 VIVE Eagle 公開 SDK 可提供 P2P 媒體；待 HTC 或真機能力證據。 |
| Qualcomm AI Hub 端側 STT／LLM | 尚未部署 | 需確認 Samsung S23 Ultra 的實際 SoC、取得可用模型資產／tokenizer、依 AI Hub 編譯與 profiling，並執行同機品質／熱／耗電測試。現有 PC 基線與手機 OCR 可先持續開發。 |

## 已執行的測試

- `:app:testDebugUnitTest`：狀態轉換、晚到事件、AAC/H.264 解析、PCM 重採樣／窗口、逐字稿亂序去重與 PTS 缺口。
- `:app:connectedDebugAndroidTest`：Pixel 9a／Android 17 上的 Compose 操作、私有儲存與刪除、WebSocket 重送與跨會議拒絕、ML Kit OCR、H.264→OCR、AAC→M4A／PCM、前景服務開始／停止。
- `python -m pytest server -q`：token、會議隔離、音訊 validation、序號衝突、來源引用、長會議分批摘要、模型失敗重試與 PC 重啟證據重建。
- 真正的 VIVE Eagle 眼鏡與 Samsung S23 Ultra 沒有連到此開發環境；**未執行** SPEC 的 15／30／60 分鐘續航、溫度、BLE 頻寬、跨模態時間校準與 Android 16 手機實測。這些仍是發佈阻擋條件。

## 接下來的硬體驗收順序

1. 取得眼鏡與其韌體版本，安裝 VIVE Connect；記錄 Samsung S23 Ultra SoC、Android 16 build、HTC SDK 0.6.0 回傳的授權與連線事件。
2. 先做 10–15 分鐘 audio-only，確認實際 AAC ASC/ADTS、PTS、麥克風方向與 M4A 可播，再做 60 分鐘、背景與斷線恢復。
3. 再開一次音影同流，記錄 H.264 SPS/實際尺寸、OCR 真實白板／投影片 CER、音訊缺口、熱與電量；依結果調整影像組態及降載。
4. 以標註語料量測 STT／翻譯／摘要正確率與 p95 端到端延遲。選定手機本機模型後，才啟動 Qualcomm AI Hub 編譯和真機 profiling。
