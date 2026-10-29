# AI 眼鏡會議紀錄原型

本專案實作 [系統 SPEC](docs/SPEC.md) 的 Android 與本機電腦基線：VIVE Eagle SDK 音訊／音影同流接入、AAC 分段保存、PCM 分段推論、手機 OCR、逐字稿／翻譯、附來源 ID 的會後摘要。眼鏡與手機之間使用 HTC SDK 的公開介面；手機與電腦之間使用 WebSocket。Wi-Fi Direct 尚無公開媒體 API 證據，未當作必要傳輸路徑。

2026-10-15 新增使用者版本基線：四頁原生介面、逐句連續翻譯／Android TTS、本機視覺模型選重點、保留照片與 OCR、人工更正／確認／排除。規格見 [產品增量 SPEC](docs/PRODUCT_EVOLUTION_SPEC.md)，測試結果與限制見 [驗證紀錄](docs/PRODUCT_VALIDATION_2026-10-15.md)。

## 工作區與建置

- Android：JDK 17、Android SDK Platform 36、Gradle Wrapper；`minSdk 29`、`targetSdk 36`。
- HTC SDK：本機需有同層 `../Android_ViveGlassSDK_0.6.0_beta/ViveGlassSample/repository`，其中包含 `com.htc.viveglass.sdk:viveglass_client:0.6.0`。若路徑不同，修改 [settings.gradle.kts](settings.gradle.kts) 的 Maven 路徑。AAR 不複製到此 Git 儲存庫。
- Windows PowerShell：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'`，接著執行 `./gradlew.bat :app:assembleDebug`。用 IntelliJ IDEA 開啟此資料夾即可匯入 Gradle 專案；模擬器／Logcat 可用 Android SDK 工具或 Android Studio。
- 真機模式先安裝 HTC VIVE Connect，依 SDK 流程配對、完成 App 註冊並授予眼鏡麥克風／相機權限。**2026-10-15 已在實驗 sample 包名下，於 S23 Ultra／VIVE Eagle 通過 Connected、65.984 秒純音訊及修正後 21.107 秒相機與音訊同流測試。OCR 可產生文字，英文／日文品質仍未達標；長時與背景運作尚待驗收。** 正式包名 `com.beefsoup.ai.glass` 的 HTC 登錄仍待官方協助。詳見 [真機測試與修正](docs/HARDWARE_SMOKE_TEST_2026-10-15.md)、[白名單取得結果](docs/SDK_WHITELIST_CAPTURE_PLAN.md) 與 [真機接入紀錄](docs/HARDWARE_VALIDATION.md)。

## App 操作

> 2026-10-15 包名對照實驗：目前 applicationId 為 `com.htc.viveglass.viveglasssample`，namespace 仍為 `com.beefsoup.ai.glass`。當日較早的離線測試回報一般錯誤 `-1`；稍後實際連接並持續配戴眼鏡後，已通過握手與短時串流。這是實驗套件身分，向 HTC 申請的包名仍為 `com.beefsoup.ai.glass`，詳見 [實驗紀錄](docs/SAMPLE_PACKAGE_EXPERIMENT.md)。

1. 真機先在 VIVE Connect 配對，進入 App 按「連接眼鏡」並持續配戴；摘下可能結束互動及斷線。模擬器僅供工程測試：Debug「設定 → 開發診斷 → 使用模擬音訊」，合成 AAC **不代表眼鏡音質或藍牙效能**。
2. 「會議」可開始音訊記錄；啟用「保留重點影像」時，用一次 `startVideoStreaming` 同時取得音訊與視訊。手機解碼抽幀，PC 只接收候選 JPEG，不傳完整視訊。
3. 「設定」可啟用本機電腦，輸入服務位址與連線碼。模擬器可用 `ws://10.0.2.2:8765/ws`；Debug 手機可用 ADB reverse 加 `ws://127.0.0.1:8765/ws`；區網一律使用可信的 `wss://.../ws`。連線碼只保留於記憶體，不寫入會議檔。
4. 「翻譯」選繁中／英文／日文後開始連續翻譯，逐句顯示並自動播報，可靜音／重播。一般會議預設不播報。已由使用者確認聲音來自眼鏡，但也確認譯音重錄；同目標語高信心的抑制防護僅完成回放回歸，尚未眼鏡重測，不能宣稱無回音全雙工。
5. 「重點」對照照片與 OCR，可放大、修改／確認或排除。模型選中的內容先待確認，確認後才送作摘要來源；排除會刪除該照片。
6. 各頁錄製中均有「停止並保存」。私有 `meetings/<UUID>/audio/` 為 M4A，`images/` 為重點照片，`timeline.jsonl` 為事件，`pending/` 為待確認 PCM。重開讀回最近會議，可補處理／更新摘要、分享易讀文字，或確認刪除目前會議。

Debug 版本另有「測試」SDK 控制台：視訊品質／5–30 fps 目標、即時預覽與影格擷取、獨立眼鏡拍照及 JPEG 儲存品質、麥克風收音與 M4A 片段播放、SDK 連續轉錄／文字播報、權限與連線狀態、偏好語言及最近 40 筆事件。眼鏡拍照與串流擷取都先顯示照片，按「儲存照片」才存至 `Pictures/AI Glass Debug`；可先選擇保留原始格式或轉成 JPEG，也可放棄預覽。未儲存照片僅暫存於記憶體。測試音訊存於私有 `debug-audio/`。預覽、收音、轉錄與會議互斥；離頁或進入背景會停止測試串流，但 SDK 沒有取消已送出播報的 API。照片相機只提供 `ImageQuality.DEFAULT`，儲存品質選項控制 JPEG 重新壓縮。Release 不顯示控制台，套件名稱仍為原有 HTC sample 身分。詳見 [SDK 控制台與驗證](docs/SDK_CONSOLE_2026-10-22.md)。

錄製以音訊持久化優先。音訊佇列溢位、寫入失敗、SDK 錯誤會停止並留下健康事件；影像處理積壓時可略過影格。若 App 在 M4A `.partial` 尚未封存時終止，最近會議會顯示異常結束；該片段不宣稱可播放。模擬模式不支援相機 OCR，需眼鏡或測試 H.264 來源。

## 電腦推論服務

在 `server/` 建立 Python 3.12+ 虛擬環境（本機以 3.13 驗證，固定的 NumPy／PyAV 版本要求至少 3.12），安裝 `requirements.txt`；安裝並啟動 Ollama，確認 `ollama list` 有要使用的多語模型。預設示範模型為此工作站已有的 `jaahas/qwen3.5-uncensored:4b`，可設定 `AI_GLASS_OLLAMA_MODEL` 換成團隊評估通過的模型。Whisper `base` 會在首次使用時下載；可設定 `AI_GLASS_WHISPER_MODEL`、`AI_GLASS_WHISPER_DEVICE`、`AI_GLASS_WHISPER_COMPUTE`。

```powershell
cd server
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt
$env:AI_GLASS_TOKEN = '<自行產生的測試權杖>'
./.venv/Scripts/python.exe app.py
```

服務預設只聽 `127.0.0.1:8765`，適合 Android 模擬器的 `10.0.2.2`。若要對 Samsung 手機開放區網，設定 `AI_GLASS_HOST=0.0.0.0`、`AI_GLASS_TLS_CERT`、`AI_GLASS_TLS_KEY`；服務會拒絕無 TLS 的區網監聽。手機須使用可驗證主機名與信任鏈的 WSS 憑證。`/health` 僅回報服務存活，不代表模型品質或眼鏡連線成功。

PC 端以 [faster-whisper](https://github.com/SYSTRAN/faster-whisper) 辨識 16 kHz PCM；以 Ollama Chat API 執行翻譯與 JSON 摘要。視覺功能另需 `ollama pull qwen3-vl:2b-instruct`，預設由 `AI_GLASS_VISION_MODEL` 指定此 tag；`POST /vision/review` 與語音 WebSocket 分開。摘要依 80 條證據分批，只保留有本會議來源 ID 的項目；內容仍須人工評估。模型首次載入及共用 GPU 會影響延遲。

## 測試

```powershell
./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
python -m pytest server -q
```

`connectedDebugAndroidTest` 需已啟動的 Android 模擬器或手機。測試涵蓋狀態轉換與晚到事件、AAC/H.264 封包解析、重採樣／重疊切塊、持久化與重送、WebSocket API 契約、來源核對、Compose 操作、Android `MediaCodec` 音訊封存、ML Kit OCR 與模擬來源的前景服務生命週期。完整真機驗收、60 分鐘錄製、溫度／耗電、BLE 斷線、眼鏡影像尺寸與 Qualcomm AI Hub 端側模型仍依 [SPEC §15](docs/SPEC.md) 待實機與模型資產驗證。

本輪較早 APK 的 S23 完整套件 22 項通過；最新候選變更版本另通過 3 項裝置回歸與 JVM 14 項，server 防護版本 19 項通過。兩次眼鏡實測錄音約 45.8／48.4 秒；自動重點選取未通過，僅手動照片與原圖模型回放有結果。實際 gateway 測試必須配置私有測試連線檔，否則明確 skip；TTS 裝置測試需要已安裝繁中離線語音。重現步驟與版本界線見 [驗證紀錄](docs/PRODUCT_VALIDATION_2026-10-15.md)，本次變更及待辦見 [交接摘要](docs/DEVELOPMENT_HANDOFF_2026-10-15.md)。

## AI Hub 的位置

Qualcomm AI Hub 用來評估、轉換、量化與編譯**手機端模型**，不是會議串流 API。現階段 Android 端使用 ML Kit 作 OCR 基線，STT／翻譯／摘要使用可運作的 PC 推論；S23 Ultra 已確認為 SM8550，仍須選定 AI Hub 模型、取得資產並完成同機 profiling，才加入手機端推論適配層。未驗證前，App 會明示「待轉譯」而不假裝已有離線 AI 結果。
