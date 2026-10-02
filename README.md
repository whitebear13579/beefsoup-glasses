# AI 眼鏡會議紀錄原型

本專案實作 [系統 SPEC](docs/SPEC.md) 的 Android 與本機電腦基線：VIVE Eagle SDK 音訊／音影同流接入、AAC 分段保存、PCM 分段推論、手機 OCR、逐字稿／翻譯、附來源 ID 的會後摘要。眼鏡與手機之間使用 HTC SDK 的公開介面；手機與電腦之間使用 WebSocket。Wi-Fi Direct 尚無公開媒體 API 證據，未當作必要傳輸路徑。

## 工作區與建置

- Android：JDK 17、Android SDK Platform 36、Gradle Wrapper；`minSdk 29`、`targetSdk 36`。
- HTC SDK：本機需有同層 `../Android_ViveGlassSDK_0.6.0_beta/ViveGlassSample/repository`，其中包含 `com.htc.viveglass.sdk:viveglass_client:0.6.0`。若路徑不同，修改 [settings.gradle.kts](settings.gradle.kts) 的 Maven 路徑。AAR 不複製到此 Git 儲存庫。
- Windows PowerShell：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'`，接著執行 `./gradlew.bat :app:assembleDebug`。用 IntelliJ IDEA 開啟此資料夾即可匯入 Gradle 專案；模擬器／Logcat 可用 Android SDK 工具或 Android Studio。
- 真機模式先安裝 HTC VIVE Connect，依 SDK 流程配對、授予眼鏡麥克風／相機權限。**尚未取得 VIVE Eagle，因此 SDK callback、韌體相容性與連續錄製時間尚未通過真機驗證。**

## App 操作

1. Pixel 9a 模擬器先開啟「使用模擬音訊」並連線；此來源只會產生合成 AAC，方便驗證前景服務、分段 M4A、PCM 與 UI，**不代表眼鏡音質或 BLE 效能**。
2. 真機選擇「連接 VIVE Eagle」。音訊模式使用 `startAudioStreaming`；「相機 OCR + 音訊」使用一次 `startVideoStreaming` 的兩個 callback。OCR 在手機解碼抽幀，不回傳完整視訊至電腦。
3. 錄製前可啟用電腦推論，輸入 WebSocket 網址及權杖。模擬器對本機 PC 可用 `ws://10.0.2.2:8765/ws`；區域網路一律使用有可信憑證的 `wss://.../ws`。權杖只保留於畫面記憶體，不寫入會議檔。
4. 停止後，私有儲存空間的 `meetings/<UUID>/audio/` 含已提交的 M4A 片段；`timeline.jsonl` 含事件、逐字稿、OCR、摘要與故障標記；`pending/` 含尚未收到結果的 PCM。重開 App 可讀回最近會議，並按「補送待轉譯音訊並產生摘要」。畫面的「匯出」會交由 Android 分享選單輸出 JSONL，也可在確認對話框後刪除最近會議及其音訊。

錄製以音訊持久化優先。音訊佇列溢位、寫入失敗、SDK 錯誤會停止並留下健康事件；影像處理積壓時可略過影格。若 App 在 M4A `.partial` 尚未封存時終止，最近會議會顯示異常結束；該片段不宣稱可播放。模擬模式不支援相機 OCR，需眼鏡或測試 H.264 來源。

## 電腦推論服務

在 `server/` 建立 Python 3.11+ 虛擬環境，安裝 `requirements.txt`；安裝並啟動 Ollama，確認 `ollama list` 有要使用的多語模型。預設示範模型為此工作站已有的 `jaahas/qwen3.5-uncensored:4b`，可設定 `AI_GLASS_OLLAMA_MODEL` 換成團隊評估通過的模型。Whisper `base` 會在首次使用時下載；可設定 `AI_GLASS_WHISPER_MODEL`、`AI_GLASS_WHISPER_DEVICE`、`AI_GLASS_WHISPER_COMPUTE`。

```powershell
cd server
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt
$env:AI_GLASS_TOKEN = '<自行產生的測試權杖>'
./.venv/Scripts/python.exe app.py
```

服務預設只聽 `127.0.0.1:8765`，適合 Android 模擬器的 `10.0.2.2`。若要對 Samsung 手機開放區網，設定 `AI_GLASS_HOST=0.0.0.0`、`AI_GLASS_TLS_CERT`、`AI_GLASS_TLS_KEY`；服務會拒絕無 TLS 的區網監聽。手機須使用可驗證主機名與信任鏈的 WSS 憑證。`/health` 僅回報服務存活，不代表模型品質或眼鏡連線成功。

PC 端以 [faster-whisper](https://github.com/SYSTRAN/faster-whisper) 辨識 16 kHz PCM；以 [Ollama Generate API](https://docs.ollama.com/api/generate) 執行翻譯與 JSON 摘要。摘要依 80 條證據分批處理，輸出只保留有本會議來源 ID 的項目。這項來源檢查能防止不存在的引用；內容正確性仍須依 SPEC 的標註語料人工評估。翻譯與摘要會因 PC 負載、模型初次載入與上下文大小而延遲。

## 測試

```powershell
./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
python -m pytest server -q
```

`connectedDebugAndroidTest` 需已啟動的 Android 模擬器或手機。測試涵蓋狀態轉換與晚到事件、AAC/H.264 封包解析、重採樣／重疊切塊、持久化與重送、WebSocket API 契約、來源核對、Compose 操作、Android `MediaCodec` 音訊封存、ML Kit OCR 與模擬來源的前景服務生命週期。完整真機驗收、60 分鐘錄製、溫度／耗電、BLE 斷線、眼鏡影像尺寸與 Qualcomm AI Hub 端側模型仍依 [SPEC §15](docs/SPEC.md) 待實機與模型資產驗證。

## AI Hub 的位置

Qualcomm AI Hub 用來評估、轉換、量化與編譯**手機端模型**，不是會議串流 API。現階段 Android 端使用 ML Kit 作 OCR 基線，STT／翻譯／摘要使用可運作的 PC 推論；Samsung S23 Ultra 的實際 SoC、AI Hub 可用模型與資產版本需在真機確認後再加入手機端推論適配層。未驗證前，App 會明示「待轉譯」而不假裝已有離線 AI 結果。
