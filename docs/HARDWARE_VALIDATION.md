# VIVE Eagle 真機接入紀錄

## 2026-10-08：無線 ADB 部署與 SDK 註冊檢查

### 測試環境

| 項目 | 觀察結果 |
|---|---|
| 手機 | Samsung S23 Ultra，`SM_S9180`／`dm3q` |
| Android | Android 16，API 36 |
| 系統 build | `BP4A.251205.006.S9180ZHS8FZF5` |
| SoC | `ro.soc.model` 回報 `SM8550` |
| 電腦與手機連線 | Android 無線偵錯，`adb devices -l` 顯示手機為 `device` |
| VIVE Connect | 套件 `com.htc.glassesca`，版本 `1.4.1387452`，versionCode `200077` |
| Bluetooth | 手機系統回報開啟；使用者已在 VIVE Connect 完成眼鏡配對 |
| 眼鏡韌體 | 尚未取得版本資訊 |
| App | `edu.ncku.aiglass`，版本 `0.1.0`，versionCode `1` |
| HTC SDK | `0.6.0 beta`；執行時 Logcat 回報 `SDK version: 0.6.0` |
| 建置工具 | 工作區現有 AGP `9.1.1`、Kotlin `2.2.10`、Gradle `9.8.0`，JDK 17 |

### 已執行與結果

1. `:app:assembleDebug` 建置成功。此次沒有修改 Android 程式碼或既有建置設定。
2. APK 被標記為 `TEST_ONLY`；使用 `adb -s <手機連線識別> install -r -t app-debug.apk` 安裝成功。
3. `am start -W -n edu.ncku.aiglass/.MainActivity` 回報 `Status: ok`，確認啟動指令被接受。
4. SDK 找到 VIVE Connect 的授權 Activity：`com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity`。
5. SDK 握手被拒絕。2026-10-08 15:53:19 的原始訊息如下：

```text
E ViveGlassKit: connect(): handshake failed: The application is not registered.
```

**結論：**手機無線 ADB、APK 部署與 VIVE Connect 授權入口已確認；目前 `edu.ncku.aiglass` 的 SDK 握手尚未通過註冊檢查。音訊、音影串流、OCR、眼鏡 PTS、背景錄製、翻譯、續航及熱測試尚未通過真機驗收。使用者完成的眼鏡配對不能單獨證明第三方 App 已註冊。

### 可提供給 HTC 的 App 識別資料

以下識別資料來自此次 APK，可供 HTC 註冊流程確認；HTC 實際要求哪些欄位，仍需以其開發者流程為準。

- Android applicationId：`edu.ncku.aiglass`。
- App 名稱：AI 眼鏡會議紀錄。
- 使用情境：學生專題，會議音訊擷取、逐字稿／翻譯／摘要、相機 OCR。
- SDK：Android `0.6.0 beta`。
- APK 簽署憑證 SHA-256（目前為 Android Debug 憑證）：`2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe`。
- APK 簽署憑證 SHA-1：`c3889bb3f88800cbaa1d99db808bf917c280f956`。
- 此次 APK 檔案 SHA-256：`2591E17670C91FEE1D49A699A7F9E14E2CE0F557E1882263445FB52599EDF305`。檔案雜湊與簽署憑證指紋是不同資料。

[HTC Android Setup](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/setup/) 明列 App 註冊要求，但該公開頁面未提供完整申請操作。下一步確認團隊是否已有 HTC 聯絡窗口、已註冊的套件名稱或 VIVE Connect 開發者註冊指引，按受支援流程完成目前 App 的註冊。

公開文件調查、開發者模式的確認範圍與可提交 HTC 的詢問草稿，見 [HTC_APP_REGISTRATION.md](HTC_APP_REGISTRATION.md)。

### 官方 sample 對照測試準備

同日另檢查官方 `Android_ViveGlassSDK_0.6.0_beta` sample 與 SDK：未找到獨立 App 註冊 API，`connect()` 已自動啟動 VIVE Connect 授權握手。SDK 將 Connect 回傳的錯誤碼 `-2` 映射為未註冊例外；目前尚不能據此判定必須人工申請或其拒絕條件。

未修改的 `ViveGlassSample` 已建置成功，使用原套件名稱 `com.htc.viveglass.viveglasssample` 與同一 Debug 簽章，並安裝到 S23 Ultra 的 Android user 0。使用者允許附近裝置、選 Eagle 並按 Connect，另在 VIVE Connect 的第三方 App 授權畫面按允許後，sample 顯示 Connected。PID `7888` 的日誌亦確認授權結果返回、`connect()--` 與 BLE 握手 CONNECTED。**此組環境可以完成 SDK 連線；主專案須另確認其同意流程，串流與長時間運作仍未驗收。** 詳細證據與結果判讀見 [程式追蹤](HTC_APP_REGISTRATION.md#6-官方-sample-與-sdk-程式追蹤2026-10-08)。

使用者隨後關閉眼鏡並離開實驗室，主專案的重新授權測試留待下次進行。本次真機操作已停止。

### 註冊完成後的驗收順序

1. 關閉模擬音訊與電腦推論，確認 SDK 連線成功。
2. 取得眼鏡麥克風授權，錄製 60 秒固定中／英文測試句；停止保存，取回 M4A 檢查可播放性與實際時長。重複起停三次。
3. 10–15 分鐘純音訊，測桌面、鎖屏、通知停止；記錄開始／結束電量、缺口與 SDK 事件。
4. 3–5 分鐘音影同流，使用固定繁中／英文／數字紙張，確認 OCR 結果與音訊完整性。
5. 再測 Bluetooth 中斷、手動重新連線與電腦推論；最後進行 30／60 分鐘品質、耗電與熱測試。

此次 SDK 握手的 App Logcat 保存在本機忽略目錄 `captures/2026-10-08/initial-launch.log`。完整驗收條件仍依 [SPEC §15](SPEC.md)。

## 2026-10-09：無線 ADB 重連與無眼鏡授權入口測試

手機以最新無線偵錯連接埠重連成功，ADB 確認 S23 Ultra／Android 16。重新安裝既有主專案 `0.1.0` APK 至 Android user 0 並啟動成功，Bluetooth scan／connect 權限均已授予。

兩次離線 connect() 成功啟動官方 `com.htc.glassesca.sdk.SdkAuthActivity` 並收到返回，但 SDK 仍回報 `The application is not registered.`；沒有 `connect()--` 的握手通過證據。此次驗證了 App 間呼叫，尚未通過完整授權。眼鏡連線、串流與長時間運作不計入此次驗收。

日誌：`captures/2026-10-09/offline-auth-launch.log`。詳細結果見 [HTC_APP_REGISTRATION.md §8](HTC_APP_REGISTRATION.md#8-無線-adb-重連與主專案離線嘗試2026-10-09)。

### 同日：套件名稱更換實驗

依使用者要求，將 `applicationId` 改為 `com.beefsoup.ai.glass`，保留原 namespace、程式碼、SDK 與 Debug 憑證。建置成功，APK 的新套件身分、元件類別及簽章均已檢查；無線安裝到 Android user 0 成功，啟動回報 `Status: ok`，PID `24168`。原 `edu.ncku.aiglass` 仍保留在手機上。

新套件 PID `24168` 的 02:22:24–02:22:27 日誌確認一次 SDK connect()：成功呼叫官方 AUTH Activity 並收到返回，接著仍回報 `The application is not registered.`，未出現 `connect()--`。Bluetooth scan／connect 權限已授予；完整 SDK 授權未通過。使用者是否見到同意畫面仍待回報。

此次沒有眼鏡，不驗收 BLE 連線或串流；目前只能確認更換 applicationId 不足以通過本次離線握手。日誌：`captures/2026-10-09/beefsoup-auth.log`。變因、APK 雜湊與判讀標準見 [HTC_APP_REGISTRATION.md §9](HTC_APP_REGISTRATION.md#9-更換-applicationid-的對照實驗2026-10-09)。

### 同日：namespace 遷移回歸與官方 sample 離線對照

- namespace 與 29 個主程式／測試 Kotlin 檔案的 package 均已改為 `com.beefsoup.ai.glass`，Service action 同步更新。applicationId、SDK 與簽署憑證未變。
- 主 APK／測試 APK 建置成功，11 項既有單元測試通過。S23 Ultra 的 `MeetingRuntimeDemoTest.foregroundDemoSessionStartsStopsAndKeepsMedia` 回報 `OK (1 test)`；以合成音訊驗證新元件解析、前景錄製、起停與保存。
- 新啟動元件為 `com.beefsoup.ai.glass/.MainActivity`，PID `30813`。關閉模擬音訊後的真實 SDK 授權仍回報未註冊，未出現 `connect()--`。
- 官方 sample 在 Eagle mode 的兩次離線嘗試也未通過完整授權，但回報 `status=failed code=-1`；與主專案的未註冊錯誤不同，根因仍待確認。

APK 檔案 SHA-256：`7C37A40444D9821D36DEDA78908E46F2286EB9C5940475D2AC669FEA9DACA2E4`。此階段尚未完成 Google 開發者帳戶／套件登錄，材料與前後對照流程見 [ANDROID_DEVELOPER_VERIFICATION.md](ANDROID_DEVELOPER_VERIFICATION.md)；上述回歸不代表眼鏡 BLE、麥克風、相機或長時間驗收已通過。

### 同日稍後：Google Limited 登錄後重測

使用者已回報 Limited 帳戶的 `com.beefsoup.ai.glass` 顯示已註冊，登錄憑證與本機 APK 一致，S23 Ultra 也已加入授權裝置。從手機取回已安裝 APK，確認與登錄前基準副本的檔案 SHA-256 完全相同；此次沒有建置、重新安裝或更改程式碼。

主 App 冷啟動後測一次，另保留資料重啟 VIVE Connect／主 App 再測一次。兩次均找到官方授權 Activity、收到返回後回報 `The application is not registered.`，沒有 `connect()--`。官方 App UI 顯示「無法連線至眼鏡」。

結論：已報告完成的 Limited 登錄及裝置授權不足以讓本次離線 HTC 授權通過；不是 Full 身分驗證或眼鏡在線測試。下一步在取得眼鏡後測在線第三方同意／完整握手，再判定是否須 HTC 支援。原始證據保存於 `captures/2026-10-09/google-registered-retest/`；詳見 [Android 驗證對照 §8](ANDROID_DEVELOPER_VERIFICATION.md#8-limited-登錄後的離線重測2026-10-09)。

### 同日：SDK／Connect 二進位追蹤定位白名單判定

進一步檢查 SDK AAR、Connect Native DEX 與 Hermes bundle，已定位 `isAppInSDKWhitelist()`：Android 分支檢查 HTC 配置服務提供的 `verifiedapps.android` 是否含呼叫者 packageName，false 對應 JS 事件 2、HTC 業務碼 -2、SDK 未註冊例外。這個判定先於眼鏡握手；名單資料缺失也可能得到相同結果。

因此最新順序為先向 HTC 確認目前包名的 SDK 白名單收錄及名單取得狀態，再進行眼鏡在線授權、權限與串流驗收。此調查未改程式、未執行新的裝置測試，不增加測試通過數；未讀到手機當次名單。完整流程與證據索引見 [SDK_AUTH_FLOW_ANALYSIS.md](SDK_AUTH_FLOW_ANALYSIS.md)。

### 同日稍後：取得端點的實際白名單 JSON

從官方 APK 還原指定端點的金鑰取得流程，由電腦執行一次 GET，得到 HTTP 200、application/json。Android 清單包含官方 sample，不包含 `com.beefsoup.ai.glass` 或 `edu.ncku.aiglass`；完整 [原始 JSON](evidence/htc_verified_apps_2026-10-09.json) 及 [取得紀錄](SDK_WHITELIST_CAPTURE_PLAN.md) 已保存。

此結果確認目前端點有效名單未收錄主專案；下一步請 HTC 提供加入白名單的流程。未擷取手機先前當次回應、未重新測主 App、未修改授權判定，不增加裝置測試通過數。白名單更新後仍須重測完整握手與串流。
