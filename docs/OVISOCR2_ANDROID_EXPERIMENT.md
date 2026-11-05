# LG VELVET 端側 OvisOCR2 實驗（2026-10-29）

## 範圍與裝置

目標是比較相同靜態圖片的手機端 ML Kit 與 OvisOCR2 辨識結果，並測試 OvisOCR2 是否能在手機離線推論。此階段不需要眼鏡，也不代表已完成 APK 整合、連續串流、錄音並行或實際眼鏡驗收。

測試設備為 LG VELVET（ADB 回報 `VELVET`、SoC `SM7250`、Android 16、`arm64-v8a`），使用無線 ADB。開始基準測試時電量 25%、未充電、電池溫度 28.8°C。當時 `/proc/meminfo` 的 `MemTotal` 約 5.59 GB、`MemAvailable` 約 681 MB；這是動態快照，不是可用模型記憶體的保證。資料分區可用空間約 86 GB。

## ML Kit 比較基準

使用 App 既有的 `com.google.mlkit:text-recognition-chinese:16.0.1`，直接以原圖建立 `InputImage`，時間由呼叫 `process` 至收到結果，兩張各執行一次；測量包含可能的模型初始化，不可視為延遲統計或眼鏡影像管線時間。測試共 2 項通過。

使用者先前提供的照片，**642 ms**，輸出如下；圖片右側日期已被截斷，無法以照片確認完整日期：

```text
0am0口
|Hel。M.l
你好世界
Lhlt! 2026lo/
```

專案合成 `whiteboard_fixture.jpg`，**395 ms**，輸出如下。瓶身 `DAILY WATER` 也被辨識，因為此測試輸入整張圖，沒有做語意選區：

```text
PROJECT REVIEW
Deadline: Friday
Owner: Alice
Action: review the design
DAILY
WATER
```

這些只是單圖例子，不能推算實際白板、中英日混合文字或眼鏡影像的整體準確率。

### 重現 ML Kit 基準

在專案根目錄、已安裝 JDK 17、Android SDK 及已連接測試手機的 PowerShell 執行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

若要比較自己的圖片，將 PNG 放至 App 的專屬外部目錄；下例用 `<image.png>` 代表自己的檔案路徑。照片沒有放進 Git 或 APK。沒有此圖片時，對應測試會跳過，合成白板測試仍會執行。

```powershell
adb shell mkdir -p /sdcard/Android/data/com.htc.viveglass.viveglasssample/files
adb push '<image.png>' /sdcard/Android/data/com.htc.viveglass.viveglasssample/files/ocr-evaluation.png
adb shell am instrument -w -r -e class com.beefsoup.ai.glass.OcrComparisonTest com.htc.viveglass.viveglasssample.test/androidx.test.runner.AndroidJUnitRunner
adb shell rm /sdcard/Android/data/com.htc.viveglass.viveglasssample/files/ocr-evaluation.png
```

Instrumentation 輸出的 `ocr_elapsed_ms` 是耗時，`ocr_text_base64` 是 UTF-8 辨識文字的 Base64，避免 Windows 主控台字元編碼破壞輸出。測試碼見 [OcrComparisonTest.kt](../app/src/androidTest/java/com/beefsoup/ai/glass/OcrComparisonTest.kt)。

## OvisOCR2 首次手機端嘗試

實驗使用本機 Ollama 同一個社群量化版本，兩個 GGUF 檔案分別為文字模型 `OvisOCR2-Q4_K_M.gguf`（557,867,136 bytes）和影像 projector `mmproj-OvisOCR2-f16.gguf`（204,987,040 bytes）；兩者 SHA-256 已和本機 Ollama blobs 核對。實驗資料放在儲存庫外的 `D:/Project/NCKU-Proj/ovis-android-experiment/`。

使用 `llama.cpp` commit `f7c01c5226cac1de1052c17920ff38cdefea0ae6`，以 Android NDK 28.0.13004108、CMake 3.31.5、Ninja、Release、`arm64-v8a`、API 29 建立 CPU 後端。首次執行時電量 24%、電池溫度 30.8°C。以下是當次裝置端命令：

```sh
/system/bin/time -v /system/bin/timeout 300 /data/local/tmp/ovis-android-experiment/llama-mtmd-cli \
  -m /data/local/tmp/ovis-android-experiment/OvisOCR2-Q4_K_M.gguf \
  --mmproj /data/local/tmp/ovis-android-experiment/mmproj-OvisOCR2-f16.gguf \
  --image /data/local/tmp/ovis-android-experiment/whiteboard_fixture.jpg \
  -p 'Extract all readable text from this image in natural reading order. Preserve the original text without translation or paraphrasing.' \
  -c 4096 -n 200 --temp 0 -t 4 --jinja --no-warmup
```

日誌顯示模型及 projector 載入後開始影像編碼；最後一筆進度為 `n_chunks = 1 (done = 1, total = 3)`，之後無線 ADB 連線中斷。**沒有收到辨識文字或推論退出碼**，因此無法判定該次推論結果，也不能將中斷歸因於記憶體。重新連線後，手機 uptime 顯示裝置沒有重新啟動，且未發現殘留推論程序。原始日誌位於實驗資料夾的 `whiteboard_fixture.jpg.log`。

## 第二次測試：主動中止

為實驗關閉推理模式，在上述 llama.cpp commit 的實驗副本額外修改 mtmd CLI，讓 `--reasoning off` 將 `enable_thinking=false` 傳給實際的 chat template。修改檔保留於儲存庫外的 `D:/Project/NCKU-Proj/ovis-android-experiment/llama-reasoning-off.patch`；這不是上游未修改版本。

第二次使用同一張合成白板圖、CPU 4 執行緒、4096 context 與 `--reasoning off`。在記錄到皮膚溫度上升後，以人工 SIGTERM 中止；`/system/bin/time` 記錄執行 **32.74 秒**，截至中止的峰值 RSS 為 **799,740 KiB**。監測紀錄中最高 HAL skin 感測值為 **51.951°C**，當時 HAL 與系統回報 thermal status 為 0。這些是該次中止前的觀測，並非完整推論的峰值或穩態效能；沒有得到 OCR 結果，也沒有證據顯示發生 OOM。溫度值是系統感測器回報，不等同對外殼實際觸感的量測。

原始輸出與監測紀錄分別為儲存庫外的 `D:/Project/NCKU-Proj/ovis-android-experiment/whiteboard_fixture.jpg.run2.log` 和 `whiteboard_fixture.jpg.run2.monitor.log`。後續以較低負載重試，結果如下。

## 第三次測試：縮圖辨識成功

將合成白板圖縮小為 512×348，使用同一實驗版 CLI、CPU 2 執行緒（`-t 2 -tb 2`）、4096 context、`--reasoning off`，取得以下完整辨識文字：

```text
PROJECT REVIEW

Deadline: Friday

Owner: Alice

Action: review the design

DAILY

WATER
```

程序正常結束，單次實測總時間 **30.27 秒**，`time -v` 峰值 RSS **956,448 KiB**；文字包含整張圖上的白板及瓶身內容。取樣紀錄中的最高 HAL skin 為 **52.795°C**、最高 Battery Service 電池溫度為 **45.8°C**，取樣時系統 thermal status 均為 0。這是單次短測，而且開始時設備已較熱；不代表長時間運作安全、溫度不會再上升，或錄音不會受干擾。

此次 Ovis 使用縮圖，前面的 ML Kit 使用原圖；兩者輸入尺寸及測量範圍不同，不能將秒數當成公平的模型速度比較。CLI 日誌另有 token position 警告，影響尚未釐清。原始輸出和監測紀錄位於儲存庫外的 `D:/Project/NCKU-Proj/ovis-android-experiment/whiteboard_512.jpg.run2.log` 與 `whiteboard_512.jpg.run2.monitor.log`。此結果僅證明該次手機端 CLI 實驗成功，尚未整合 APK，也未測試眼鏡串流或並行錄音。

## 原尺寸白板後續測試：過熱中止

縮圖測試後，以同一 CLI 與 CPU 2 執行緒測試原尺寸 1000×680 白板圖。開始監測時 HAL skin 已為 53.005°C。推論進行 **58.37 秒**時仍在影像編碼，系統回報 thermal status **3（severe）**，因此人工送出 SIGTERM；程序退出碼 **143**，沒有 OCR 輸出。`time -v` 記錄截至中止的峰值 RSS 為 **890,280 KiB**，不是完成一次推論所需的完整峰值。監測中最高 HAL skin 讀值為 **55.752°C**；此讀值是裝置感測器報告，測試也沒有將高溫單獨歸因於模型，因為測試期間有其他裝置負載及充電。

原始輸出與監測紀錄位於儲存庫外的 `D:/Project/NCKU-Proj/ovis-android-experiment/whiteboard_fixture.jpg.run3.log` 和 `whiteboard_fixture.jpg.run3.monitor.log`。此輪後暫停以降低溫度，再進行下述使用者照片裁切測試。

## 使用者照片裁切比較

原圖為 1327×2364；取像素座標左上 `(460, 1000)` 至右下 `(1327, 1850)`，以 Lanczos 縮為 512×502、JPEG 品質 95。此裁切是實驗時手動選定，不能視為 App 已具備自動選區。原圖右側日期被截斷，無法從影像確認完整日期。

LG 上以同一張 512×502 裁切圖，OvisOCR2 CLI 使用 CPU 2 執行緒、`--reasoning off`，正常結束（exit 0），單次耗時 **42.82 秒**，峰值 RSS **968,484 KiB**，輸出：

```text
Hello World!

你好世界

こんにちは！2026/10/11
```

日期 `2026/10/11` 是模型輸出，不可認定為圖片中的完整真值。取樣時最高 HAL skin 讀值為 **52.604°C**，系統 thermal status 為 0；這不是長時間熱穩定測試。原始輸出及監測資料位於儲存庫外的 `D:/Project/NCKU-Proj/ovis-android-experiment/user-tablet-crop-512.jpg.run4.log` 和 `user-tablet-crop-512.jpg.run4.monitor.log`；裁切記錄在 `user-crop-info.txt`。

同一張裁切圖由既有 ML Kit 中文辨識器處理，單次 **502 ms**，測試通過並輸出：

```text
|Hell。W.!
|你好世界
Lhlt! 2026/o/
```

此個案中，OvisOCR2 輸出的英中日可見文字較接近照片內容；不代表其日文或日期完全正確，也不能由單張圖片推算整體準確率。兩者計時範圍不同：Ovis 包含 CLI 啟動與模型載入，ML Kit 為 `process` 呼叫至結果；因此數字僅作該次操作紀錄，不能當作嚴謹的速度基準。兩次均為靜態圖片，尚未測試眼鏡串流與並行錄音。ML Kit 測試結束後已移除 App 外部專屬目錄的暫存圖片。
