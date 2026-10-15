# VIVE Eagle 第三方 App 註冊調查與支援詢問

首次查詢：2026-10-08；最後更新：2026-10-15。此文件記錄公開文件、真機觀察與二進位流程追蹤；尚未取得 HTC 對註冊操作的回覆。各日期的測試保留當時套件名稱；applicationId 實驗見 §9，namespace 遷移與 Google 登錄假設見 §10，Limited 登錄後重測見 §11，白名單判定見 §12，實際 JSON 見 §13，sample 包名對照見 §14。

## 1. 目前結論

**已追到 VIVE Connect 的套件白名單拒絕分支，並取得端點的實際 HTTP 200 JSON。** Connect 檢查 `verifiedapps.android.includes(packageName)`；不為 true 時，回傳 SDK 所映射的未註冊錯誤。本次有效名單包含官方 sample，但不含主專案新舊包名。這條白名單比對路徑沒有查詢 Google Console 或比對我們 APK 的 SHA-256；官方 App 取得內部金鑰時另檢查自己的簽章。未擷取手機先前當次回應，不能排除手機另有下載問題。完整證據見 [SDK_AUTH_FLOW_ANALYSIS.md](SDK_AUTH_FLOW_ANALYSIS.md) 與 [JSON 取得結果](SDK_WHITELIST_CAPTURE_PLAN.md)。

官方 sample 曾在使用者允許第三方權限後 Connected；主專案更名、namespace 遷移及 Google Limited 登錄後仍未通過。SDK 已自動執行授權握手，未找到 App 端須另呼叫的註冊 API。下一步請 HTC 提供將 `com.beefsoup.ai.glass` 加入 SDK 白名單的方式，完整眼鏡握手另待真機在線測試。

| 問題 | 已確認的資訊 | 仍待確認 |
|---|---|---|
| 是否需要註冊 App？ | 官方 Android Setup 明列先註冊應用程式；目前 App 握手回報未註冊。 | 自助註冊入口、申請資格、審核方式及所需欄位。 |
| 配對成功是否足夠？ | 不足。Connect 在眼鏡握手前另檢查套件白名單。 | HTC 如何登錄開發用 App，以及此手機取得的名單是否有效。 |
| VIVE Connect 是否有開發者模式？ | 此次查閱的官方 Eagle 手冊與 SDK 文件未找到可驗證的啟用步驟。 | 消費版是否提供開發者選項、是否須 HTC 開通，或須另用測試版 VIVE Connect。 |
| 「應用程式權限」能否註冊新 App？ | 官方說明涵蓋相機／麥克風授權、編輯、移除及中斷既有 App 連線。Sample 呼叫 connect() 後，另出現使用者同意畫面，按允許後成功連線。 | 設定清單能否手動登錄套件、自訂 App 的同意流程是否相同。 |
| 是否可直接使用 Android 開發人員選項？ | 手機無線偵錯已可部署 APK。 | 沒有證據顯示 Android 偵錯設定會通過 HTC 的 App 註冊檢查。 |

「未找到公開操作」不表示開發者模式一定不存在；目前不應把連點版本號等一般 Android 操作當成 Eagle 的已確認流程。

## 2. 官方文件與使用方式

- [VIVE AI Glasses SDK：Android Setup](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/setup/)：列出 App 註冊、權限及 SDK 初始化要求，但未列出完整註冊申請操作。
- [VIVE Eagle：管理應用程式權限](https://www.vive.com/tw/support/vive-eagle/category_howto/managing-app-permissions.html)：VIVE Connect 的設定入口可進入「應用程式權限」。新 App 連線時會要求相機／麥克風權限，允許後出現在清單；一次僅能啟用一個第三方 App 連線。
- [HTC VIVE 台灣聯絡入口](https://contact.vive.com/hc/zh-tw)：建議提交下方詢問，請客服轉交 **VIVE AI Glasses SDK／VIVE Eagle 開發者支援團隊**。這是聯絡入口，尚未確認它能直接受理 App 登錄。
- [HTC 開發者支援](https://developer.vive.com/tw/support/)：可作為查詢 SDK 支援管道的補充入口。

目前沒有官方依據可確認「註冊 VIVEPORT 開發者帳號」就會自動讓 Eagle 接受我們的 Android App。

## 3. 可提供給 HTC 的現況

| 項目 | 資料 |
|---|---|
| 專案 | 學生專題：AI 眼鏡會議紀錄、即時翻譯與相機 OCR |
| 向 HTC 申請的 Android applicationId | `com.beefsoup.ai.glass`（2026-10-09 更改；原為 `edu.ncku.aiglass`） |
| 目前包名對照實驗 | 2026-10-15 暫用 `com.htc.viveglass.viveglasssample`；一般錯誤 `-1`，未完成正式登錄或完整握手，見 §14。 |
| namespace／Kotlin package | `com.beefsoup.ai.glass`（§9 後完成遷移，見 §10） |
| Google 登錄 | 使用者回報 Limited 帳戶、套件已註冊、簽章與本機 APK 相同，S23 Ultra 已加入授權裝置；未直接查詢 Google API，見 §11。 |
| App 版本 | `0.1.0`，versionCode `1`，目前為 Debug APK |
| SDK | `Android_ViveGlassSDK_0.6.0_beta`；執行時回報 `0.6.0` |
| VIVE Connect | `com.htc.glassesca`，`1.4.1387452`，versionCode `200077` |
| 手機 | Samsung S23 Ultra `SM_S9180`，Android 16／API 36 |
| 眼鏡 | VIVE Eagle，已在 VIVE Connect 完成配對；韌體版本待補 |
| 授權入口 | SDK 解析到 `com.htc.glassesca.sdk.SdkAuthActivity` |
| 已觀察錯誤 | 原套件 `edu.ncku.aiglass` 與新套件 `com.beefsoup.ai.glass` 均曾回報 `connect(): handshake failed: The application is not registered.`；新套件測試條件與結果見 §9。 |

目前 APK 簽署憑證 SHA-256：

```text
2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe
```

此為簽署憑證指紋，並非 APK 檔案雜湊。已確認 Connect 的 Android 白名單分支比對套件名稱；HTC 登錄申請是否另要求憑證、帳號或裝置資料，尚待確認。完整部署與指紋紀錄見 [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md)。

## 4. 可直接提交的詢問草稿

**主旨：VIVE Eagle Android SDK 0.6.0 開發用 App 註冊方式／開發者模式詢問**

HTC VIVE 支援團隊您好：

我們是成功大學的學生專題團隊，正在開發 VIVE Eagle 的會議音訊紀錄、即時翻譯與相機 OCR 應用。已取得 VIVE Eagle，並在 Samsung S23 Ultra（Android 16）透過 VIVE Connect 完成配對。

目前使用 Android_ViveGlassSDK_0.6.0_beta，VIVE Connect 版本為 1.4.1387452。我們原本使用 Android App 套件名稱 `edu.ncku.aiglass`，安裝後呼叫 SDK connect()，Logcat 顯示：

```text
ViveGlassKit: SDK version: 0.6.0
AuthManager: resolved to com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity
ViveGlassKit: connect(): handshake failed: The application is not registered.
```

2026-10-09 已將 applicationId 與 namespace 更改為 `com.beefsoup.ai.glass`，保留同一 SDK 與簽署憑證。Google Android Developer Console 的 Limited 帳戶已登錄此套件、相同簽署憑證及 S23 Ultra 授權裝置；同一 APK 在眼鏡不在手邊的條件下重測仍回報未註冊。

我們進一步追蹤 VIVE Connect 的授權事件，看到 `isAppInSDKWhitelist()` 讀取 `verifiedapps`，Android 分支使用套件名稱比對；檢查不通過時設定 `ERROR_UNREGISTERED_APP = 2`，Native 將其轉成業務碼 `-2`。2026-10-09 已由電腦取得該端點的 HTTP 200 JSON，Android 清單包含官方 sample，但不包含 `com.beefsoup.ai.glass` 或原包名；未擷取手機先前當次回應。想請協助提供將我們的開發用 App 加入白名單的流程。

官方 Android Setup 要求先註冊 App，但我們未找到具體申請入口。同一手機上，未修改的官方 sample（`com.htc.viveglass.viveglasssample`）使用同一 Debug 簽署憑證，呼叫 connect() 後跳出 VIVE Connect 同意畫面，按允許即可 Connected。請協助確認：

1. 學生團隊如何申請將 `com.beefsoup.ai.glass` 加入 SDK 白名單？是否有自助入口或需由 SDK 團隊登錄？更新後如何確認生效與刷新快取？
2. 註冊需要哪些資料：applicationId、簽署憑證指紋、HTC 帳號、裝置識別，或其他資訊？Debug 簽章能否用於開發？
3. 消費版 VIVE Eagle／VIVE Connect 是否有受支援的開發者模式或本機測試 App 登錄功能？若有，請提供啟用步驟與適用版本。
4. SDK 0.6.0 與此 VIVE Connect 版本是否相容？是否需要指定眼鏡韌體、SDK 版本或開發用 VIVE Connect？
5. 註冊是否綁定單一手機／眼鏡／帳號？日後更換簽章或提供給其他團隊手機測試時，如何更新登錄？
6. 是否有官方支援的診斷方式，能分辨白名單未收錄與名單下載／登入失敗？

我們可補充簽署憑證指紋、眼鏡韌體版本及精簡 Logcat。請協助轉交 VIVE AI Glasses SDK 開發者支援窗口，謝謝。

## 5. 後續工作順序

1. 使用 §4 草稿請 HTC 提供將目前包名加入 SDK 白名單的流程，以及受支援的診斷與刷新方式；維持官方 App 的有效登入及可用網路。
2. HTC 確認後，以同一 APK 重測並保存本次程序日誌；沒有眼鏡時不能把一般失敗或某個錯誤消失當成完整授權通過。
3. 眼鏡重新開機並與 VIVE Connect 連線後，確認 sample 已斷線，重測主專案完整授權與相機／麥克風權限；補上韌體版本。
4. 連線與權限通過後，依 [真機驗收順序](HARDWARE_VALIDATION.md#註冊完成後的驗收順序)，先驗證短音訊，再進行背景音訊、音影同流及長時間耗電／熱測試。

### SDK 版本補充

[官方 0.6.1 版本說明](https://developer.vive.com/resources/vive-ai-glasses-sdk/download/latest-release/061/) 記載 2026-09-30 發布，修正麥克風方向設定不生效的問題。此修正與後續麥克風驗收有關；文件沒有說明它能解決 App 未註冊錯誤。是否升級，先依 HTC 相容性回覆決定。

## 6. 官方 sample 與 SDK 程式追蹤（2026-10-08）

### 檢查範圍

- 本機官方資源：`D:/Project/NCKU-Proj/Android_ViveGlassSDK_0.6.0_beta/ViveGlassSample`。
- Sample 的 `MainActivity.kt`、`ViveGlassKitManager.kt`、`GlassTabModel.kt`、Manifest 與 Gradle 設定。
- 同一份 SDK `viveglass_client-0.6.0.aar` 的公開方法、Manifest、`ViveGlassKit`、`AuthManager` 與 `AuthProxyActivity`。
- 手機目前 VIVE Connect `1.4.1387452` 的 `SdkAuthActivity` 呼叫端識別流程。

### Sample 的結果

1. `MainActivity.kt` 建立 `ViveGlass()` 與 `ViveGlassKit(applicationContext)`，未輸入 App ID、開發者 Token 或註冊金鑰。
2. `ViveGlassKitManager.kt:523` 的 `setSimulator(false)` 選擇真機 adapter；`:493` 的 `connect()` 只呼叫 `glass.connect(viveGlassClientCallback)`。
3. 同一個 manager 在 `:177` 處理 `ERROR_UNREGISTER_APP`；沒有在此錯誤後呼叫額外的註冊方法。
4. Sample 自己的 Manifest 沒有 HTC 註冊用的 `meta-data`；SDK AAR 的 Manifest 自動帶入 AUTH action 的 `<queries>` 與 `AuthProxyActivity`。
5. Sample 的 applicationId 為 `com.htc.viveglass.viveglasssample`。當日主專案為 `edu.ncku.aiglass`；是否因套件名稱或其他條件而不同，須以對照測試及 HTC 回覆確認。
6. Sample 中的 `registerForActivityResult`、`registerReceiver` 是 Android 結果回呼／廣播監聽的註冊，沒有執行 HTC App 登錄。

### SDK 已自動完成的軟體流程

```text
App：glass.connect(callback)
  → ViveGlassKit.connect()：背景執行 connectInternal()
  → AuthManager.performHandshakeSync(context)
  → AuthProxyActivity
  → action：com.htc.viveglass.action.AUTH
     extra_request_type：app_connect
     附帶 App 名稱、圖示與 SDK 產生的臨時公鑰
  → VIVE Connect：SdkAuthActivity
     從 getCallingActivity()/getCallingPackage() 取得 Android 套件名稱
  → VIVE Connect 回傳授權結果
     result_status_code = -2
  → AuthManager：UnRegisteredAppException
  → ViveGlassKit：ERROR_UNREGISTER_APP
```

上圖中的 `-2` 是由 SDK 例外映射與已觀察的握手錯誤確認；此次沒有直接擷取 VIVE Connect 回傳 Intent 的原始內容。

SDK 的 `ViveGlass`／`ViveGlassKit` 公開方法沒有獨立的 `registerApp()`。內部 `performHandshakeSync()` 已由 `connect()` 呼叫，不應再從 App 手動並行呼叫。臨時公鑰由 SDK 產生，不是必須向 HTC 取得的 API Key。

`AuthManager.handleResult()` 會將 VIVE Connect 回傳的 `result_status_code = "-2"` 轉為未註冊例外；目前 App 已走到這個分支。因此，**目前沒有發現漏做 sample 註冊步驟的證據**。仍未確認拒絕條件究竟是 Connect 本機登錄、HTC 帳號／服務、眼鏡韌體或其他機制；不能直接把它定義成人工白名單問題。

JADX 對整份 AAR 有部分反編譯錯誤；關鍵的 `app_connect` Intent 與 `-2` 例外映射另以 JDK `javap` 位元碼交叉確認。原始研究輸出保存在忽略目錄 `captures/2026-10-08/`，不作為可編譯 SDK 原始碼使用。

### 下一個有意義的對照測試

使用未修改程式碼與 applicationId 的官方 sample，在同一手機、同一 VIVE Connect 與同一副眼鏡上，選擇 **Eagle** 並按 **Connect**。先只測連線及授權，不啟動錄音或相機。

| 結果 | 能得出的結論 | 後續 |
|---|---|---|
| Sample 成功、主專案仍回報未註冊 | 官方流程在此環境可用；須查兩個 App 的識別或授權差異。 | 保持主專案套件名稱，向 HTC 確認自訂 App 登錄方式；不要把成功原因直接歸為套件白名單。 |
| Sample 也回報未註冊 | Sample 本身沒有可用的額外註冊呼叫；主專案程式碼不是唯一可能原因。 | 提供兩個 App 的結果，請 HTC 確認 Connect、帳號、韌體與開發模式要求。 |
| 出現相機／麥克風授權提示 | 流程已到授權畫面。 | 記錄選擇與後續連線結果；出現提示本身不等於最終連線成功。 |
| 回報其他錯誤 | 此次對照被其他條件阻擋。 | 依實際錯誤處理 Bluetooth 權限、未配對、韌體版本或授權取消等條件。 |

### 對照測試準備結果

- 官方 sample 未修改程式碼或套件名稱，`:app:assembleDebug` 成功，耗時 1 分 37 秒。
- APK 確認為 `com.htc.viveglass.viveglasssample`，顯示名稱 `ViveGlassSample`，版本 `1.0`。
- 簽署憑證 SHA-256 與主專案相同：`2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe`。因此兩個 APK 此次使用不同套件名稱、相同簽章；其餘程式與資源仍有差異，結果不能直接證明拒絕僅依套件名稱。
- 已透過無線 ADB 安裝到 S23 Ultra 的 Android user 0。啟動指令回報 `Status: ok`，目前停在系統的 Android 權限授予 Activity。
- 使用者允許「附近的裝置」，於 Device Mode 選 **Eagle**、按 **Connect**，並確認另有 VIVE Connect 第三方 App 授權畫面；按允許後 sample 顯示 **Connected**。

### 官方 sample 的連線結果

使用者回報與 PID `7888` 的 Logcat 相符，2026-10-08 16:21 的紀錄包含：

```text
AuthManager: buildAuthIntent: resolved to com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity
AuthProxyActivity: onActivityResult: requestCode=1001 resultCode=-1 hasData=true
ViveGlassKit: connect()--
BleConnection: returnHandshakeResult [...]: CONNECTED, current connectionState: CONNECTED
```

這裡 Android Activity 的 `resultCode=-1` 是 `RESULT_OK`，與前文 Intent extra 裡的 `result_status_code` 是不同欄位。

目前可確認同一裝置組合可完成 SDK 授權握手及眼鏡連線；尚未證明自訂 App 需要人工登錄，也未驗收 sample 的音訊／相機串流或長時間連線。Sample 的應用程式 Logcat 保存在 `captures/2026-10-08/official-sample-launch.log`。

下一步：先在 sample 按 Disconnect，再以主專案按連接，觀察是否出現 VIVE Connect 同意畫面並按允許；將實際結果與 SDK 日誌比對。即使 sample 曾成功，也不能假設主專案已被自動授權。

### 本次停止點

使用者已關閉眼鏡並離開實驗室，本次不再進行真機操作。主專案重新授權的對照測試尚未執行；先前的未註冊錯誤原因仍待確認。已保存 sample 成功連線的日誌；此次沒有修改兩個 App 的 Android 程式碼或套件名稱。

## 7. 假期間沒有眼鏡時的測試（2026-10-09）

### 可以先驗證的範圍

在已安裝 VIVE Connect、保留既有配對資料的 S23 Ultra 上，可以嘗試主專案的 SDK connect()，檢查 Android App 間的呼叫。手機維持 Bluetooth 開啟並授予附近裝置權限，避免在授權流程前就被權限檢查阻擋。測試不啟動錄音或相機。

| 測試項目 | 無眼鏡時的判讀 |
|---|---|
| SDK 找到官方 AUTH Activity | 可以驗證，觀察 `buildAuthIntent` 是否解析到 `com.htc.glassesca.sdk.SdkAuthActivity`。 |
| 真正啟動 VIVE Connect 並收到返回 | 可以嘗試，觀察畫面與 `AuthProxyActivity.onActivityResult`；Connect 可能要求先連接眼鏡。 |
| 顯示第三方 App 同意畫面並按允許 | 可以嘗試；是否在眼鏡離線時允許進行，尚未實測。 |
| 完整 SDK 授權握手成功 | 有條件；需 Connect 返回有效的成功結果與金鑰／配對資訊，不能保證無眼鏡時能取得。 |
| 眼鏡 BLE 握手、Connected、串流 | 留待眼鏡實機測試。 |

### 為何不能直接保證離線鑑權成功

SDK 0.6.0 的 `ViveGlassKit.connectInternal()` 執行順序是：

```text
Android Bluetooth 權限與 SDK 連線狀態檢查
  → AuthManager.performHandshakeSync()
  → mImpl.connect()：嘗試眼鏡 BLE 連線
  → 日誌 connect()--
```

因此授權握手在眼鏡 BLE 連線之前完成，有機會把兩階段分開驗證。但 VIVE Connect 的成功返回分支要求非空的 `eaeKey` 與既有 `pairedEae` 記錄；SDK 還會驗證返回資訊並產生共享金鑰。現有程式證據尚未確認眼鏡離線時 Connect 能否取得本次握手所需金鑰，不能推定只要曾配對就能鑑權成功。

上述 Connect 分支以 `SdkAuthActivity` 的 simple 反編譯模式補查，輸出保存於 `captures/2026-10-08/connect-auth-simple/`；這是靜態程式分析。同日稍後執行的手機離線測試見 §8。

### 驗收證據

1. **找到入口**：本次日誌解析到官方 `SdkAuthActivity`。
2. **收到返回**：本次 `AuthProxyActivity` 收到 Activity result；Android 的 `resultCode=-1` 只表示 `RESULT_OK`，不等於其中的 HTC 業務授權成功。
3. **握手通過**：同一次主專案連線嘗試出現 `ViveGlassKit: connect()--`，且前面沒有 handshake failure。依此版本程式路徑，表示 `performHandshakeSync()` 已正常返回，並已發起下一段眼鏡連線。其後因眼鏡離線而失敗，不應把這次結果記成整體連線成功。
4. **仍被阻擋**：若 Connect 要求連接眼鏡、回報握手失敗或超時，記錄為「入口驗證到此階段、完整握手未通過」，不可只因沒有 `ERROR_UNREGISTER_APP` 就判定通過。

測試日誌須屬於本次主專案程序與時間，不能引用前一日 sample 成功的 `connect()--`。不要清除 Connect 資料或解除眼鏡配對，以維持可比較的環境。

官方 simulator／模擬音訊可繼續驗證 pipeline、狀態機與儲存流程；其通過結果不計入真實 VIVE Connect 授權驗收。

## 8. 無線 ADB 重連與主專案離線嘗試（2026-10-09）

- 使用者提供手機無線偵錯主頁的新連接埠後，`adb connect` 成功；`adb devices -l` 顯示 `SM_S9180` 為 `device`。
- 手機為 Android 16，VIVE Connect 仍為 `1.4.1387452`。
- 在 Android user 0 未找到主專案，重新安裝既有 `0.1.0` Debug APK 成功；啟動 `edu.ncku.aiglass/.MainActivity` 回報 `Status: ok`，PID 為 `8786`。附近裝置的 Bluetooth scan／connect 權限均已授予。
- 未取得眼鏡的情況下，02:06:38 與 02:07:28 的兩次主專案 connect() 均解析到官方 `SdkAuthActivity`，並收到 Activity 返回，隨後回報同一錯誤：

```text
AuthManager: buildAuthIntent: resolved to com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity
AuthProxyActivity: onActivityResult: requestCode=1001 resultCode=-1 hasData=true
ViveGlassKit: connect(): handshake failed: The application is not registered.
```

**已通過 App 間授權入口呼叫與結果返回的驗證，完整 SDK 授權握手仍未通過。** 未出現本次主專案的 `connect()--`。此離線結果仍不能單獨判定是套件登錄問題、使用者同意流程或離線眼鏡所需資訊造成。

本次主專案 Logcat 保存於忽略目錄 `captures/2026-10-09/offline-auth-launch.log`。官方 sample 仍安裝在 Android user 0，但本次尚未執行其離線對照測試。

後續已執行的離線 sample 對照見 §10。

## 9. 更換 applicationId 的對照實驗（2026-10-09）

### 假設與變因

使用者提出：HTC 文件的「Register the application」可能指 VIVE Connect 依 Android 套件名稱完成本機登錄／使用者授權，而非另到網站申請。此解釋與 sample 顯示同意畫面的觀察相容，但尚未確認登錄如何完成，以及是否受眼鏡在線狀態影響。

本次只修改 `app/build.gradle.kts` 的 `defaultConfig.applicationId`：

```kotlin
applicationId = "com.beefsoup.ai.glass"
```

- 保留 `namespace = "edu.ncku.aiglass"`、Kotlin package、SDK `0.6.0` 與原 Debug 簽署憑證，聚焦在安裝套件身分的差異。
- [Android 官方文件](https://developer.android.com/build/configure-app-module) 說明：`applicationId` 決定安裝身分及 `Context.getPackageName()` 的結果；namespace 用於產生 `R`／`BuildConfig`，可獨立設定。最終 Manifest 的 package 使用 applicationId。
- 依 §6 的 Connect 呼叫端識別路徑，新 APK 應以 `com.beefsoup.ai.glass` 被識別；此次未直接擷取 Connect 內部收到的套件值。
- 指定 applicationId 本身不會向 HTC 發出登錄請求。實驗檢查新身分的 `connect()` 是否帶出同意畫面，以及完整授權握手是否通過。
- 新舊套件分別安裝；新套件的權限與私有資料獨立。既有 VIVE Connect 配對資料保留，眼鏡目前不在手邊。

### 已完成的建置與部署檢查

| 檢查 | 結果 |
|---|---|
| 建置 | `:app:assembleDebug` 成功，1 分 7 秒 |
| APK applicationId | `aapt2 dump badging` 確認 `com.beefsoup.ai.glass` |
| Manifest／BuildConfig | Manifest package 與 `BuildConfig.APPLICATION_ID` 均為新 ID；Application、Activity、Service 仍指向正確的 `edu.ncku.aiglass` 類別 |
| SDK 授權入口 | 合併 Manifest 仍含 AUTH action 的 queries 與 SDK `AuthProxyActivity` |
| 簽章 | `apksigner verify --print-certs` 通過；憑證 SHA-256 與 §3 相同 |
| APK 檔案 SHA-256 | `A65EEBF6F8BF8A08DA45DAB1E981152913968576B0755C6FB4A0F5B9D7855A35` |
| 安裝 | 無線 ADB `install -r -t --user 0` 成功；新舊套件均存在 |
| 啟動 | `com.beefsoup.ai.glass/edu.ncku.aiglass.MainActivity`，`Status: ok`，本次 PID `24168` |

### 授權測試與判讀

已請使用者授予附近裝置權限、關閉模擬音訊並按「連接 VIVE Eagle」，若 Connect 顯示同意畫面則按允許。手機套件檢查確認新 App 的 Bluetooth scan／connect 權限已授予。PID `24168` 在手機日誌時間 02:22:24–02:22:27 執行了一次真實 SDK `connect()`，結果如下：

```text
AuthManager: buildAuthIntent: resolved to com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity
AuthProxyActivity: onActivityResult: requestCode=1001 resultCode=-1 hasData=true
ViveGlassKit: connect(): handshake failed: The application is not registered.
```

日誌保存於忽略目錄 `captures/2026-10-09/beefsoup-auth.log`。新套件通過安裝、啟動、Bluetooth 權限與官方授權 Activity 呼叫／返回檢查，**完整授權握手失敗**；此次沒有 `connect()--`。使用者是否看見／允許 Connect 同意畫面，仍待其畫面回報，不能由 Android `RESULT_OK` 推定已同意。

本次驗收依 §7：AUTH Activity 返回後，須確認 `performHandshakeSync()` 通過並出現 `connect()--`。僅有 Android `RESULT_OK` 或同意畫面不足以判定通過。

此次結果只支持本次條件下「更名不足以通過握手」；不能直接證明網站登錄、套件白名單或眼鏡離線是原因。下一個對照是在相同離線條件下測官方 sample：若 sample 也失敗，須優先確認離線條件；若 sample 的完整握手仍通過，則可進一步縮小到 App 識別或授權差異。兩種結果都須在眼鏡可用時重新測新套件，不應直接判定唯一根因。

## 10. namespace 遷移與 Android 開發人員驗證假設（2026-10-09）

依使用者要求，namespace 與主程式／單元測試／裝置測試的 Kotlin package 均已改為 `com.beefsoup.ai.glass`，29 個檔案移至對應目錄；Service 的 START／STOP action 同步更新。applicationId 與 Debug 簽署憑證保留。此節是 §9 後續操作，不改寫前一次實驗當時保留舊 namespace 的紀錄。

- 主 APK 與裝置測試 APK 建置成功；11 項既有單元測試全部通過。
- 合併 Manifest 的 Application、Activity、Service 均為新 namespace；測試 package 為 `com.beefsoup.ai.glass.test`，target package 為 `com.beefsoup.ai.glass`。
- S23 Ultra 的合成音訊前景錄製／停止／保存測試通過，`OK (1 test)`；不計為眼鏡串流驗收。
- 重新啟動新元件 `com.beefsoup.ai.glass/.MainActivity` 成功，PID `30813`。確認模擬音訊關閉後按連接，02:32:41–02:32:43 仍回報 `The application is not registered.`，沒有 `connect()--`。日誌：`captures/2026-10-09/namespace-auth.log`。
- 未修改的官方 sample 選 Eagle 後，PID `31316` 的兩次離線 connect() 均得到 `host app returned failure: status=failed code=-1`，沒有 `connect()--`。日誌：`captures/2026-10-09/sample-offline-auth.log`。**sample 離線也未通過，但它的錯誤不同，不能判定兩個 App 有相同根因。**

使用者說明「註冊」指 Google 的 Android 開發人員身分驗證，及套件名稱／簽署憑證 SHA-256 登錄。目前 [Google 官方 FAQ](https://developer.android.com/developer-verification/guides/faq) 明列 ADB 安裝不需要此驗證；目前沒有 HTC 文件或已檢查的授權路徑證據，證明 Google 登錄是 HTC 握手的前提。不能排除 Connect 其他程式路徑另行查詢 Google 的可能，但也不能將 HTC 的未註冊錯誤當成 Google 的狀態 API 回覆。

此階段使用者尚無 Console 帳號，尚未提交 Google 登錄。已核對並匯出公開憑證、保存同一 APK 作為前後比較基準，並整理帳戶類型、登錄步驟、所有權挑戰與結果判讀。完整材料見 [ANDROID_DEVELOPER_VERIFICATION.md](ANDROID_DEVELOPER_VERIFICATION.md)。

上述為當時準備狀態；同日稍後使用者已完成 Limited 登錄，最新對照如下。

## 11. Google Limited 登錄後重測（2026-10-09）

使用者回報 Android Developer Console 帳戶為 Limited，`com.beefsoup.ai.glass` 顯示已註冊，登錄 SHA-256 為 `2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe`，並已將 S23 Ultra 加入授權裝置。這是使用者提供的 Console 狀態，不記為 Full 身分驗證或 Google API 查詢通過。

從手機取回已安裝的 base APK，檔案雜湊與登錄前保存的副本相同，簽章也與上述值一致；App、SDK 與 VIVE Connect 版本及 Bluetooth 權限維持原條件。沒有 rebuild 或重新安裝。

1. 主 App 冷啟動 PID `20655`，手機日誌時間 03:00:01–03:00:03 的 connect() 仍回報 `The application is not registered.`。
2. 保留資料並重啟 VIVE Connect 與主 App 後，PID `22503` 於 03:01:56–03:01:58 的 connect() 再次回報同一錯誤。

兩次均成功啟動官方 AUTH Activity 並收到返回，但沒有 `connect()--`。VIVE Connect UI 同時顯示「無法連線至眼鏡」。**Limited 套件登錄與裝置授權不足以通過此次離線 HTC 握手；眼鏡在線後的第三方同意與授權仍須驗證。** 不推定唯一根因，也不要求使用者單為此錯誤升級付費帳戶。

APK、兩次日誌與 UI 快照保存於 `captures/2026-10-09/google-registered-retest/`；詳細變因與結果見 [ANDROID_DEVELOPER_VERIFICATION.md §8](ANDROID_DEVELOPER_VERIFICATION.md#8-limited-登錄後的離線重測2026-10-09)。

## 12. ViveGlassKit 與 Connect 白名單分支定位（2026-10-09）

依使用者要求，繼續從 `ViveGlassKit` 往上游追蹤。SDK 本身只把 AUTH Activity 返回的業務碼 `-2` 映射為未註冊。Connect 的 Native 層將此結果對應到 JS 事件 `2`；反組譯其 Hermes v96 bundle 後，找到明確設定事件 `2` 的白名單拒絕分支。

`isAppInSDKWhitelist()` 的 Android 判定是 `verifiedapps?.android?.includes(packageName) === true`；檢查開關為 true，取得的資料使用 12 小時模組記憶體快取。名單透過 HTC 配置服務取得，需官方 App 的存取金鑰；匿名請求收到 403，未因此取得名單。若下載方法回傳失敗結果並返回 undefined，也會得到同一未註冊判定。

**主專案優先處理的是 HTC 套件白名單或其資料取得，眼鏡離線是後續完整握手的另一個障礙。** 不再把更名、namespace 或 Google Full 帳戶升級當作已知修復方式。過去 §8–11 的觀察仍有效；最新程式證據將當時尚未定位的拒絕範圍縮小。

完整流程圖、函式 ID、錯誤碼區分、材料雜湊與後續驗收見 [SDK_AUTH_FLOW_ANALYSIS.md](SDK_AUTH_FLOW_ANALYSIS.md)。本次沒有修改 App、SDK 或官方 APK。

## 13. 取得白名單 HTTP 200 JSON（2026-10-09）

同日稍後從官方 APK 的 arm64 split 取得 `libapikey.so`，追到 `GeneralModule.getAiModelAccessKey()` → `ApiKeyManager.g()` → `getNativeKey(13)`。還原指定項目的解碼流程後，由電腦執行一次指定端點 GET，取得有效 JSON；沒有改動手機或 App 的授權判定，也沒有輸出／保存明文金鑰。

Android 清單 50 筆（49 個不重複包名），包含 `com.htc.viveglass.viveglasssample`，不包含 `com.beefsoup.ai.glass` 或 `edu.ncku.aiglass`。這證明目前該端點的名單確實未收錄主專案，足以導致既有拒絕分支；不代表已讀到手機先前的快取／請求，也不代替 HTC 的申請回覆。完整 [原始 JSON](evidence/htc_verified_apps_2026-10-09.json) 及 [取得紀錄](SDK_WHITELIST_CAPTURE_PLAN.md) 已保存。

## 14. 主專案改用官方 sample 包名（2026-10-15）

使用者回報仍無眼鏡、HTC 尚未回信，要求先做包名對照。僅將 applicationId 改為 `com.htc.viveglass.viveglasssample`，保持 namespace 與 SDK／Debug 簽章，建置並安裝成功。

同日原包名基準仍回報未註冊；sample 包名的本專案 APK 改為 `host app returned failure: status=failed code=-1`，沒有未註冊訊息，也沒有 `connect()--` 或 Connected。結果支持包名與此次拒絕有關；不把一般錯誤改變當作正式 HTC 登錄、白名單內部流程的動態證明或完整授權成功。

本次未使用 Proxy、未替換伺服器 JSON、未修改官方 Connect APK，未改變向 HTC 申請的正式包名。詳細條件與可還原方式見 [SAMPLE_PACKAGE_EXPERIMENT.md](SAMPLE_PACKAGE_EXPERIMENT.md)。
