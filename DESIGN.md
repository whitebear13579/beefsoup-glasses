---
name: "AI 眼鏡會議紀錄"
description: "活潑、清楚的原生 Material 3 會議記錄介面。"
colors:
  primary-light: "#006B5E"
  primary-container-light: "#9CF3D9"
  secondary-container-light: "#FFDBCC"
  tertiary-container-light: "#D9EF97"
  surface-light: "#F9FAF4"
  primary-dark: "#80D6BD"
  primary-container-dark: "#005143"
  secondary-container-dark: "#743520"
  tertiary-container-dark: "#3C4D00"
  surface-dark: "#101511"
---

# AI 眼鏡會議紀錄 — Material 3

2026-10-22：依使用者「更大膽、更繽紛活潑、可重新安排畫面」及觸覺回饋要求更新。產品事實仍以 PRODUCT.md 為準。設計依據為 [Material 3 skill](https://github.com/hamen/material-3-skill/blob/master/skills/material-3/SKILL.md)，實作以 MeetingTheme.kt 與 MeetingScreen.kt 為準。

## 視覺方向

以 Material 3 的語意色、字級與形狀建立鮮明階層。薄荷青綠承載會議入口，深青綠承載進行中的錄製；暖桃色承載翻譯與摘要，萊姆綠承載重點影像。長篇逐字稿維持安靜的中性底色。所有色彩在 Theme 集中定義，元件使用成對的 container / onContainer 角色；保留系統明暗主題，不啟用桌布動態取色。

使用目前 Compose BOM 2024.09.00 的 Material3 元件；沒有為此次改版升級依賴，也不宣稱使用新版 Expressive MotionScheme 或形狀變形 API。系統多語字型與 sp 字級保留，displaySmall、headlineLarge、headlineSmall、titleLarge、titleMedium 透過 Typography 加重。大標題使用 displaySmall，內容標題使用 titleLarge，閱讀文字使用 bodyLarge。

## 結構與互動

- 會議與翻譯入口以大標題和用途說明開場；錄製中改為清楚的狀態、媒體計時與配戴提示。
- 眼鏡連線狀態與連線按鈕在主要區塊後方；錯誤始終置頂，保留復原動作。
- 開始與停止控制固定於底部操作區，最小高度 56 dp。STARTING / RECORDING / STOPPING 期間所有目的地皆有停止入口；保存中停用重複操作。
- 會議摘要移至逐字稿前方。逐字稿與所有譯句仍可捲動回看，不因新結果而強制捲動到底。
- 翻譯先呈現較醒目的譯文，再保留原文對照與重播。
- 重點以原圖、時間、確認狀態與 OCR 組成閱讀單位，保留放大、修改、確認、排除與刪除確認。僅已確認內容供摘要引用。
- 設定集中服務連線與語音資訊，工程診斷仍僅 Debug 可見。
- 小於 600 dp 使用底部 NavigationBar，較寬視窗使用 NavigationRail；主要內容限制 720 dp 並置中，避免長行。這是目前單欄閱讀介面的寬度調整，尚非摺疊鉸鏈感知的多欄配置。
- 主內容邊距與區塊間距為 24 dp，群組內為 8 / 16 dp。大入口使用 shapes.extraLarge、內容容器使用 shapes.large；元件形狀仍沿用 Material 預設。
- Scaffold 加入 IME 避讓；照片視窗保留 safeDrawingPadding。系統返回由次要目的地回會議。

## 觸覺回饋

使用 Compose LocalHapticFeedback（Android 原生觸覺 API），不新增振動權限、背景振動或自行指定震動時長。

- TextHandleMove：切換目的地、目標語、開關、連線與譯句重播。
- LongPress：開始、停止、手動保留照片、確認重點及儲存修正文句。
- 重選目前目的地、停用按鈕與自動背景結果不觸發觸覺。脈衝表示操作已觸發，不表示非同步工作已成功。
- 遵循裝置觸覺設定；實際手感依手機硬體而異。

## 驗證界線

主題中主要文字與表面配對的計算對比均高於 4.5:1。Android JVM、Compose 操作回歸及原生畫面檢查的本次結果見 docs/UI_REDESIGN_2026-10-22.md。

既有 .impeccable/design.json 是舊版 HTML 示意，不能當作目前 Android 視覺或驗收來源。真實眼鏡、音訊品質、回音、自動影像選取及長時錄製的限制沒有因介面改版而解除。

## Debug 相機測試

Debug 導覽增加第五個目的地「測試」（工具圖示）；Release 保留原四頁。直接呈現相機取景、預覽狀態、開始／停止與擷取儲存，保留既有 Material 配色、觸覺與 48 dp 觸控語意。沒有影格時停用擷取；照片完成後顯示相簿位置。測試預覽與會議互斥，離頁與進入背景均停止，避免畫面消失後仍使用相機。

測試頁的「影像品質」與「預覽更新率」使用 Material exposed dropdown。顯示 SDK 解析度與 5／8／10／15／24／30 fps 選項，預設目標 15 fps。預覽中停用選擇並提示先停止；每次選取提供輕觸覺回饋。

## SDK 控制台擴充

「測試」現在包含視訊／靜態拍照、連線與權限、音訊收音／播放、SDK 轉錄／播報及最近事件。使用 Material 分區標題、標準按鈕與下拉選單；互斥操作停用，錯誤直接顯示，未讀取資訊不顯示成已驗證值。JPEG 儲存品質清楚區分於 SDK 拍照解析度，30 fps 是請求目標而非效能承諾。實際預覽更新率與收到的尺寸分開呈現。SDK 播報沒有取消 API 的限制直接說明。

照片流程更新為「拍照預覽／擷取畫面 → 確認照片 → 儲存照片」，不自動寫入相簿。預覽顯示實際格式、尺寸與資料量；儲存品質可在確認時選擇原始格式或 JPEG 95／80。放棄預覽只清除記憶體，不刪除已儲存照片。
