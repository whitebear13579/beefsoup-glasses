---
name: "AI 眼鏡會議紀錄"
description: "清楚記錄會議、理解翻譯、核對重要影像的原生 Android 操作介面。"
# Only explicit MeetingTheme overrides; all other colors remain Material3 defaults.
colors:
  primary-light: "#185F58"
  on-primary-light: "#FFFFFF"
  primary-container-light: "#C4EDE5"
  on-primary-container-light: "#003730"
  surface-light: "#F9FBF8"
  background-light: "#F9FBF8"
  primary-dark: "#91D3CB"
  on-primary-dark: "#003732"
  primary-container-dark: "#13504A"
  on-primary-container-dark: "#B2F0E7"
# Observed dp values, not CSS pixels or newly introduced Kotlin token constants.
spacing:
  dp-4: "4dp"
  dp-8: "8dp"
  dp-12: "12dp"
  dp-16: "16dp"
  dp-20: "20dp"
# Typography, rounded shapes and component defaults are inherited, not literal overrides.
---

# Design System: AI 眼鏡會議紀錄

## Overview

**Creative North Star: "看得懂的會議記錄與翻譯"**

延續既有 Jetpack Compose／Material3 與產品名稱，以簡單操作支援「記錄 → 翻譯 → 核對重要影像」。青綠色、系統多語字型、清楚文字狀態與真實照片構成視覺，不新增品牌、裝飾素材或假數字。模式為 Operate。

本文件合併本輪暫定 DESIGN.md 的已確認方向，以 `app/src/main/java/com/beefsoup/ai/glass/MeetingScreen.kt` 的 `MeetingTheme`／元件與 `MainActivity.kt` 的接線為實作依據。產品事實見 `PRODUCT.md`；預期驗收見 `docs/PRODUCT_EVOLUTION_SPEC.md`，既有結果見 `docs/PRODUCT_VALIDATION_2026-10-15.md`。操作外觀不構成模型品質、眼鏡聲學或長時可靠性的通過證據。

**版本與驗證邊界：** `app/build.gradle.kts` 固定 Compose BOM `2024.09.00`；本機該 BOM POM 對應 Material3／material3-android `1.3.0`、Compose UI／Foundation `1.7.0`。未覆寫的語意色、Typography、Shapes、元件尺寸、狀態、陰影及動畫均沿用此版本平台預設，不另發明數值。依本輪獨立 reviewer 的紀錄，F1、F3、F4 已解決；F2 部分完成，1.5 倍字級與 IME 可及性檢查未完成。先前 S23 Ultra 原生截圖涵蓋主要四頁、深色及較大字級，不能推定完整 1.5 倍字級／IME／TalkBack 或平板適配通過。使用者已離開實驗室，本次僅核對文件與原始碼，沒有新增硬體、建置或截圖驗證，亦非完整產品 UI 核准。

**Key Characteristics:**

- 原生 Material3 青綠語意色，跟隨系統明暗主題。
- 系統多語字型與 Material3 文字階層，操作簡單可讀。
- 四個固定目的地，錄製期間持續顯示停止並保存。
- 原文／譯文與照片／辨識文字可對照，人工確認重要證據。

## Colors

靜態青綠主色承接原有介面；明暗主題由 `isSystemInDarkTheme()` 選擇，不採用動態取色。

### Primary

前置 token 的 `primary-light`／`primary-dark` 對應 `primary`，同主題的 `on-primary-*` 為其文字角色；`primary-container-*`／`on-primary-container-*` 對應容器與內容。沿用 Material3 元件的角色分派，不把所有選取狀態改成主色。

### Neutral

淺色 `surface` 與 `background` 各自覆寫為前置 token。深色中性色，以及兩個主題的 `onSurface`、`onSurfaceVariant`、`surfaceContainerHigh`、outline、secondary、tertiary、error／errorContainer 等均為 Material3 `1.3.0` 的 `lightColorScheme()`／`darkColorScheme()` 預設；未建立自訂數值或色階。連線 Surface 使用 `surfaceContainerHigh`，錯誤 Surface 使用 `errorContainer`，輔助文字使用 `onSurfaceVariant`。

**The Semantic Status Rule.** 狀態必須有文字與可操作的復原入口；顏色只能輔助表達，不能單獨承載連線、錄製、播報或錯誤狀態。

## Typography

**字型與尺度：** `MeetingTheme` 僅傳入 `colorScheme`，沒有自訂 `Typography`、`FontFamily`、字級、字重、行高或字距。全部沿用 Material3 `1.3.0` 的預設文字樣式與 Android 系統多語字型回退，並跟隨系統 fontScale；不指定網路字型或獨立展示字體。前置資料因此不填入未覆寫的 typography 數字。

| 實際樣式 | 用途 |
| --- | --- |
| `headlineMedium` | 會議／翻譯未啟動時的主要說明 |
| `headlineSmall` | 錄製狀態與重點頁標題 |
| `titleLarge` | 逐字稿、摘要與設定群組標題 |
| `titleMedium` | 連線狀態、開關標題、譯文、重點時間與確認狀態 |
| `bodyLarge` | 逐字稿、辨識文字與主要說明 |
| `bodyMedium`／`bodySmall` | 原文、來源提示、播報／推論狀態與輔助說明 |
| 元件內建文字樣式 | 按鈕、導覽、標籤、欄位、對話框；未另設展示或等寬字體 |

**The Platform Type Rule.** 以既有 Material3 樣式表達階層；保留系統多語字型與字級縮放，不能把尚未完成的大字級檢查記為通過。

## Layout

`Scaffold` 提供 `TopAppBar`、底部操作區與 `NavigationBar`。內容以套用 Scaffold padding 的全幅 `LazyColumn` 呈現；內容邊距與項目間距均使用 `spacing.dp-20`，保留完整已保存逐字稿及譯句供捲動回看。四個目的地固定為「會議／翻譯／重點／設定」，系統返回由非會議頁回到會議。

首屏優先顯示連線狀態與明確開始入口；錯誤出現時置於清單首項，目的地或錯誤改變會要求回到清單頂端。未連線時提供連線操作，翻譯服務未設定時提供設定入口。工程位址、連線碼與私有路徑不佔主要操作頁；診斷和模擬來源僅在 Debug 設定頁展開。

`spacing.dp-16` 用於狀態容器內距與設定列間距，`dp-12`／`dp-8`／`dp-4` 用於照片、文字與控制間的既有間隔。`FlowRow` 讓語言選項和動作換行。固定開始／停止按鈕外距使用水平 `dp-20`、垂直 `dp-8`，最小高度為程式明定的 52 dp；手動保留影像按鈕明定最小高度 48 dp。這些是局部實作值，48 dp 觸控目標仍是既有可及性要求，未逐項實測保證。

`MainActivity` 啟用 edge-to-edge；全螢幕照片使用 `safeDrawingPadding()`。螢幕邊界與鍵盤避讓仍是原有要求；目前未在此畫面明示 `imePadding()`，不能將其缺口或未驗證的鍵盤行為固化為慣例。未建立平板斷點或自適應多欄規格。

**The Persistent Stop Rule.** STARTING、RECORDING、STOPPING 期間，每個目的地的導覽列上方均顯示停止並保存；STOPPING 顯示「正在保存…」並停用重複操作。

## Elevation & Depth

連線與錯誤以輕量 Surface 的語意容器色分層，長內容以 `HorizontalDivider` 分隔。畫面沒有自訂 elevation、陰影或動畫參數；按鈕、導覽、對話框的深度、ripple、焦點及狀態轉場沿用 Material3 `1.3.0` 預設。這不構成「禁止原生陰影」的規則，亦不推導 CSS shadow、動畫時長或曲線數值。

## Shapes

狀態 Surface 明確使用 `MaterialTheme.shapes.medium`；其圓角仍是 Material3 `1.3.0` 預設。按鈕、FilterChip、OutlinedTextField 與 AlertDialog 保留原生形狀及邊框，沒有自訂 Shapes／radius。照片以 `ContentScale.Fit` 顯示，未增加裝飾框或裁切造型；前置資料不另填 rounded 常數。

## Components

### Buttons

`Button` 承擔開始與停止；`OutlinedButton` 提供連線、設定、重試與手動保留；`FilledTonalButton` 用於確認重點；`TextButton` 用於編輯、排除、重播、分享與對話框動作。形狀、內距、文字與互動狀態沿用 Material3 `1.3.0`，僅「刪除目前會議」文字按鈕明確指定 `error` 色。啟用條件以實際連線、服務設定及錄製階段決定。

### Chips

`FilterChip` 提供繁體中文、English、日本語目標語；錄製中停用修改。選取／停用視覺均為原生預設，不另造狀態色。

### Cards / Containers

使用 Surface 表達連線與錯誤，沒有自訂 Card 視覺庫。錯誤直接顯示並設 `LiveRegionMode.Polite`，依狀態提供系統設定、VIVE Connect、重試與關閉入口。錄製、翻譯、視覺處理和播報各自保留文字狀態。

### Inputs / Fields

設定使用全幅單行 `OutlinedTextField`，服務位址採 URI 鍵盤，無效時用 `isError` 與 supportingText 說明；連線碼採密碼遮罩並只留於本次開啟期間，錄製中停用修改。`SettingSwitch` 以標題／說明與 Switch 配對，提供內容語意；自動播報開關用來靜音。缺少語音或音訊焦點失敗不應中止原始錄製，這是保留的產品要求，不能由介面接線推定已完成聲學驗收。

### Navigation

原生 `NavigationBarItem` 同時顯示圖示與文字，圖示來自 Material icons；選取、標籤字型、觸控與焦點外觀沿用 Material3 `1.3.0`。導覽維持四個目的地，停止控制位於其上方。

### Translation and photo review

譯句先列原文，再列譯文及重播操作；不自動把新內容捲到最底端而妨礙回看。重點依序呈現媒體時間／確認狀態、真實照片、辨識文字、來源提示與可展開的判斷原因。照片可開啟全螢幕原始影格；讀不到照片時直接說明並保留文字，空狀態只解釋如何開始。

編輯 AlertDialog 同時保留可放大的來源照片、可捲動內容、文字欄位、「取消」與「儲存並確認」。草稿非空才可保存；照片視窗關閉後繼續編輯。確認／修改／排除是獨立動作，排除照片與刪除整場會議都先由 AlertDialog 確認。人工確認後才作為摘要來源；此行為的品質與可及性仍受上述驗證邊界限制。

`.impeccable/design.json` 的 HTML／CSS 僅為原生角色的示意網頁預覽，並非 Android 渲染、尺寸或驗收依據。預覽沿用瀏覽器預設控制，沒有合成原生字級、圓角、陰影、色階或量測結果。

**The Evidence Pair Rule.** 重點必須保留照片、辨識文字、時間與來源提示的對照，以及確認、修改、排除操作；模型建議不得直接包裝成已確認會議事實。

## Do's and Don'ts

### Do:

- **Do** 延續原生 Material3、靜態青綠語意色、明暗主題與系統多語字型。
- **Do** 保留四個目的地、可見的連線／錯誤復原入口與持續可見的停止並保存。
- **Do** 用可回看的原文／譯文及照片／辨識文字，支援核對、取消、修改與明確確認。
- **Do** 保留 48 dp 觸控目標、系統返回、字級縮放及螢幕／鍵盤避讓要求，逐項區分實作與已驗證結果。

### Don't:

- **Don't** 新增品牌、裝飾資產、假數字，或以示範照片冒充使用者紀錄。
- **Don't** 把位址、權杖、私有路徑或開發診斷放回主要操作畫面。
- **Don't** 發明未覆寫的平台 token 數值，或把示意 HTML／CSS 當成 Android 外觀來源。
- **Don't** 將 F2 部分完成、未通過的產品能力或既有缺陷寫成設計慣例或完整產品 UI 核准。
