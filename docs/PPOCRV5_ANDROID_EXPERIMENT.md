# LG VELVET 端側 PP-OCRv5 實驗（2026-10-29）

## 範圍

測試 PaddleOCR 官方 `PP-OCRv5_mobile_det` 與 `PP-OCRv5_mobile_rec` ONNX 模型，以 Android CPU 在 LG VELVET（SM7250、Android 16、arm64-v8a）辨識靜態圖片。這是獨立 instrumentation 測試，並未整合本專案 APK，沒有連接眼鏡、測試串流或錄音並行，也不是標註資料集上的準確率評估。使用者照片未加入 Git 儲存庫。

## 模型與測試包

| 檔案 | 位元組 | SHA-256 |
| --- | ---: | --- |
| [偵測模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx) `inference.onnx` | 4,826,518 | `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d` |
| [辨識模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx) `inference.onnx` | 16,534,782 | `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092` |
| 辨識字典 `inference.yml` | 148,345 | `5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e` |

兩個模型合計 **21,361,300 bytes（約 21.4 MB）**，加字典約 21.5 MB。這不是執行時 RAM，也不包含 ONNX Runtime、OpenCV 或 App 程式碼。

以官方 [PaddleOCR Android 範例](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/README_en.md)的 SDK 為底，使用 ONNX Runtime 1.21.1、兩個 CPU 執行緒。原範例的 `com.quickbirdstudios:opencv:4.5.3` 在此手機載入時報缺少 `__sfp_handle_exceptions`；此實驗改用 `org.opencv:opencv:4.10.0`，並以本機 Android SDK 36 建置。限制為 arm64-v8a 的最終獨立 instrumentation APK 為 **65,320,634 bytes（約 65.3 MB）**，含四張測試圖片及測試程式，不等於正式 App 的大小或增量。本次包含圖片的測試套件已從手機移除；先前 Ovis 實驗的手機暫存檔不受影響。本機實驗資料仍保留在專案外的 `D:/Project/NCKU-Proj/paddle-android-experiment/` 及 `D:/Project/NCKU-Proj/ppocr-android-experiment/`。

## 辨識結果

以下為一次連續四圖測試；首次初始化 OCR 物件 603 ms，其中 SDK 記錄模型載入 417 ms。`wall` 是 `recognize(imageBytes)` 呼叫至結果的時間，含解碼，但不包含初始化。PSS 為呼叫後該程序記憶體快照，並非峰值；連續執行後可能保留先前配置。

| 圖片 | 尺寸 | wall | 執行後 PSS |
| --- | --- | ---: | ---: |
| 使用者照片手動裁切 | 512×502 | 408 ms | 229,273 KiB |
| 合成白板縮圖 | 512×348 | 530 ms | 265,490 KiB |
| 使用者照片原圖 | 1327×2364 | 2,756 ms | 642,828 KiB |
| 合成白板原圖 | 1000×680 | 809 ms | 648,572 KiB |

裁切圖取原圖像素 `(460,1000)` 到 `(1327,1850)`，以 Lanczos 縮放、JPEG 品質 95。原圖日期右側已被截斷。裁切圖完整結果為：

```text
Hello World !
你好世界
こんにちわ!20261101
```

英文、中文及日文可見筆跡比同圖 ML Kit 中文模型的 `|Hell。W.!`、`|你好世界`、`Lhlt! 2026/o/` 更接近，但日期及符號仍有錯誤；不得把完整日期當作真值。同圖 OvisOCR2 輸出見 [先前實驗](OVISOCR2_ANDROID_EXPERIMENT.md)，兩個實驗計時範圍不同，不能直接計算精確速度倍率。此裁切是手動操作，App 尚無相同的自動選區功能。

原尺寸照片的輸出為：

```text
THeeta
住装


Da
m。0n
Hell.
Wonld!
你好世界
こんにちわ!20261101
```

其中英文有錯字、背景被誤讀，日期也不可靠。兩種尺寸的合成白板圖均辨識出白板文字與旁邊的 `DAILY WATER` 瓶身文字；此流程只偵測文字，未依會議內容篩選相關區域。測試以 `OK (1 test)` 結束，四張圖片各有非空輸出；這個斷言不是準確率驗收。

## 重現

已測試的 SDK 來源是 PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，偵測模型 revision `e6f4fa85f00e168c862bc462aebca69eef9b3d3d`、辨識模型 revision `ed152b8b495f84de93cda5709d768548a9127622`。模型與字典的下載位置為兩個官方 Hugging Face repo 的 `resolve/<revision>/inference.onnx` 或 `inference.yml`。

在 PaddleOCR 的 `deploy/ppocr-android` 目錄：

1. 將兩個 ONNX 檔分別放到 `ppocr-sdk/src/main/assets/models/det/inference.onnx`、`models/rec/inference.onnx`；將辨識模型的 `inference.yml` 放到 `models/rec/inference.yml`。
2. 將本專案 [ComparisonTest.kt](../experiments/ppocr-v5/ComparisonTest.kt) 放到 `ppocr-sdk/src/androidTest/java/com/paddle/ocr/experiment/`；將測試圖片放入 `ppocr-sdk/src/androidTest/assets/`，命名為 `user-crop.jpg`、`whiteboard.jpg`、`user-full.png`、`whiteboard-full.jpg`。取得使用者照片需自行提供或使用先前實驗檔。
3. 在範例的版本表把 OpenCV 依賴改為 `org.opencv:opencv:4.10.0`；將 SDK `defaultConfig` 增加 `ndk { abiFilters += "arm64-v8a" }`。本機僅安裝 Android SDK 36，因此 app 與 SDK 的 `compileSdk` 均改為 36。
4. 設定 JDK 17 和 Android SDK 路徑，以 Gradle 8.14.5 執行 `:ppocr-sdk:assembleDebugAndroidTest`，再安裝產生的 `ppocr-sdk-debug-androidTest.apk`。以 `adb shell am instrument -w -r -e class com.paddle.ocr.experiment.ComparisonTest com.paddle.ocr.test/androidx.test.runner.AndroidJUnitRunner` 執行。輸出的 `ocr_report_base64` 是 UTF-8 JSON 的 Base64。

本次原始紀錄為 `D:/Project/NCKU-Proj/ppocr-android-experiment/instrumentation-all.log`；模型原始檔在同一目錄的 `models/`。每次測試的負載、裝置狀態及圖片會影響耗時與記憶體。

## 接入正式 App 與眼鏡短測

PP-OCRv5 已放入原本的 Android App，透過 ONNX Runtime 在手機執行，沒有額外 OCR App。未連接 PC 時，手動保留影格會執行本機 OCR；自動挑選會議相關畫面仍依賴 PC。CPU OCR 與模型初始化在影像解碼線程之外，最多同時處理一張，錄音流程不等待 OCR；停止後不接受遲到結果。相鄰會議共用單一 OCR 工作執行緒，避免前一次模型仍在運行時再開多組推論。

在 LG VELVET／VIVE Eagle 上：

- DEFAULT 串流實得 480×856，手動照片及音訊成功保存，但照片中的文字太小，OCR 僅得到零碎字元。
- SDK 獨立拍照取得 **1920×1440 HEIF**；SDK 目前只提供 `ImageQuality.DEFAULT` 這一種拍照模式。此照片保存在手機的 `Pictures/AI Glass Debug`。拍照與視訊串流有資源互斥，不能把該照片視為會議同流影格。
- 測試頁以 Full HD、15 fps 預覽遇到影像處理積壓而停止；改成 **Full HD、5 fps** 後，取得 **1080×1920** 影格，預覽短測顯示約 5.1 fps。該預覽使用原預設位元率，且不保存音訊。
- 會議改以 Full HD、5 fps、800 kb/s 請求後，實際保存 **1080×1920**、382,481 bytes 的照片，OCR 寫入本機會議資料（`model=pp-ocrv5-mobile`），仍有錯字和背景文字；四個 M4A 皆可解碼，分別約 10.008、10.008、10.008、4.435 秒，有正常 stop 事件、無 warning／health 事件。這是約 34.46 秒的單次試驗，不代表長時不丟音或 OCR 品質達標。當時使用 OpenCV 4.10.0；其後 App 升至 4.12.0 並用同一保存影格重跑本機模型，文字輸出一致，但未重跑眼鏡同流。

高解析度影格先用 JPEG 品質 95；若大於 2 MB，逐步降低品質而保留原解析度。手機保存和 PC 視覺服務均保留 2 MB 的上限；PC 允許最多 2,100,000 像素以容納 Full HD。獨立的使用者照片沒有加入 Git，也未放進正式 APK。

2026-10-29 最終版本的 Debug APK 約 **318.7 MB**（含多種 ABI），已覆蓋安裝到同一個 LG App，保留原有資料；為測試而安裝的 instrumentation 套件已移除。手機端 **23 項測試通過**，包括模型、同一眼鏡影格回放、Full HD H.264→OCR、手動擷取、圖片大小上限、預覽、照片確認和保存；[測試紀錄](evidence/ppocr-app-device-test-2026-10-29.txt)已移除實景 OCR 內容。Android JVM **14 項通過**，PC server **24 項通過**。Android 64 位原生函式庫 ELF 與 APK ZIP 均通過 16 KB 對齊檢查，但尚未在 16 KB page-size 裝置實測。
