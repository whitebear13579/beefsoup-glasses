# Android 開發人員驗證與 HTC 授權對照

更新日期：2026-10-09。目的：驗證 Google 的開發人員／套件／簽署憑證登錄是否影響 VIVE Connect 授權，不預設兩者有關。

## 1. 目前判斷與執行範圍

使用者所指的是 **Google Android developer verification**：驗證開發者身分，再把 Android 套件名稱與簽署憑證連結至帳戶。

[Google 官方 FAQ](https://developer.android.com/developer-verification/guides/faq) 明確說明 ADB 安裝不要求這項驗證。本專案已成功透過 ADB 安裝、啟動與執行真機測試；目前錯誤發生在 HTC SDK 接收 VIVE Connect 授權結果之後。因此「Google 尚未登錄導致 Android 不准安裝」與目前觀察不符。

初始實驗另考慮 VIVE Connect 是否自行查詢 Google 登錄狀態。Google 確有 [Android Developer ID Status API](https://developer.android.com/developer-verification/guides/check-registration-status)。同日進一步反組譯已定位：Connect 在此次拒絕路徑使用 HTC 配置服務的 `verifiedapps.android` 套件白名單，沒有查詢 Google Status API 或比對憑證指紋。最新證據見 [SDK_AUTH_FLOW_ANALYSIS.md](SDK_AUTH_FLOW_ANALYSIS.md)；不把此結果外推成 HTC 後端所有規則的證明。

已完成 namespace 遷移、建置／回歸、HTC 授權基準測試與登錄材料準備。使用者本日稍後回報已建立 **Limited distribution** 帳戶，Console 的 `com.beefsoup.ai.glass` 顯示已註冊，登錄憑證指紋與 §2 一致，並已新增 S23 Ultra 為授權裝置。登錄後的兩次 HTC 離線重測仍失敗，詳細結果見 §8。Google Console 狀態依使用者回報記錄，未直接讀取其帳戶或執行 Google Status API；Full distribution 身分驗證路徑未測試。

## 2. 目前 App 的登錄資料

| 欄位 | 值 |
|---|---|
| applicationId | `com.beefsoup.ai.glass` |
| namespace／Kotlin package | `com.beefsoup.ai.glass` |
| 測試 APK package | `com.beefsoup.ai.glass.test`；不把它當作主 App 登錄 |
| 名稱 | AI 眼鏡會議紀錄／AI Glass Meeting |
| 版本 | `0.1.0`，versionCode `1` |
| 簽章 | 本機 Android Debug 憑證，與原主專案及本機官方 sample 相同 |
| 公開憑證 | `captures/2026-10-09/android-registration/debug-signing-certificate.pem` |
| HTC 對照 APK | `captures/2026-10-09/android-registration/baseline-namespace-debug.apk` |
| 對照 APK 檔案 SHA-256 | `7C37A40444D9821D36DEDA78908E46F2286EB9C5940475D2AC669FEA9DACA2E4` |

**簽署憑證 SHA-256（Google 套件登錄用）：**

```text
2E:7C:47:AC:10:F5:1E:00:CA:62:45:47:DC:A3:4B:BE:41:C7:5D:B7:95:C7:4B:A4:8E:60:EF:11:6C:35:7E:FE
```

若欄位要求不含冒號的格式：

```text
2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe
```

此值是 APK 實際簽署憑證的指紋，已由 `apksigner verify --print-certs` 確認，並與匯出 PEM 的 `keytool -printcert` 結果比對。它不是 APK 檔案雜湊。PEM 僅包含公開憑證；私鑰仍在原本的本機 keystore。

對照期間保留同一簽署金鑰。是否接受這個 Debug 憑證，須看 Console 的實際資格判定，不能事先保證。正式發佈另規劃持久管理的 release signing key；若此次改用新金鑰，必須先建立新金鑰的 HTC 授權基準，不能同時更換簽章並把結果變化歸因於 Google 登錄。

## 3. 帳號選擇

[Android Developer Console](https://android.google.com/developerconsole/developers) 適合僅在 Google Play 外發佈的 App；已有 Play Console 帳號者可使用 Play Console 管理，無須另建一個帳戶。[官方 Console 指南](https://developer.android.com/developer-verification/guides/android-developer-console)、[Play Console 指南](https://developer.android.com/developer-verification/guides/google-play-console)。

| 路徑 | 適用性 | 本次假設的限制 |
|---|---|---|
| Limited distribution | 學生／小型專題可用；免費，可分享至最多 20 個使用者明確授權的裝置，不要求政府身分證件。 | 可測小量登錄與分享流程，但不等同 Full distribution 的身分驗證條件。 |
| Full distribution | 完整開發者驗證；需依所在地提交官方身分文件，FAQ 列出 US$25 帳戶費用。 | 若要嚴格測「已驗證開發者＋套件／憑證登錄」假設，應記錄此路徑的驗證與登錄狀態。 |

來源：[Limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)、[Full distribution](https://developer.android.com/developer-verification/guides/full-distribution)、[費用 FAQ](https://developer.android.com/developer-verification/guides/faq)。

小量帳戶仍需要 Google 帳號、兩步驟驗證、Google payments profile 與聯絡 email。由本人在 Google 頁面完成這些帳戶操作。單為排查 HTC 的此錯誤，不建議立即付費；先完成已有材料的登錄準備及眼鏡在線對照。

## 4. 可以實際操作的登錄流程

依 [Google 套件登錄指南](https://support.google.com/android-developer-console/answer/16640821) 執行：

1. 開啟 Console，登入 Google 帳號，依選擇的帳戶類型完成要求；記錄 Full 或 Limited，不混用驗收結論。
2. 進入 **Package names／Packages → Register package name**，輸入 `com.beefsoup.ai.glass`，friendly name 可填「AI Glass Meeting」。
3. 登錄上方實際簽署憑證指紋。若要求選擇 eligible key，核對是否為同一指紋；沒有列出不代表可任意選其他金鑰。
4. Console 決定此套件走新名稱或既有名稱流程。手機已安裝不保證 Google 一定把它分類為既有名稱，以 Console 顯示為準。
5. 若要求證明私鑰所有權，下載／複製 Console 給出的完整 snippet。官方規定將它放進 APK assets 內名為 `adi-registration.properties` 的檔案，再用指定憑證對應的私鑰簽署 release APK 並上傳。**只有 Console 要求此挑戰時才製作對應驗證 APK，不建立假 snippet，也不把現有 Debug APK 當成已完成的所有權證明。** 保留 §2 的 HTC 對照 APK。
6. 以 Console 的套件與金鑰狀態確認完成；只到 Draft、In review 或完成帳戶建立，均不計為套件登錄完成。
7. Limited 帳戶若要求 QR code／連結與手機同意，依官方流程把 S23 Ultra 納入授權裝置；記錄此步驟與帳戶類型。

若只想查狀態而不提交登錄，可使用 Google 的 Status API；它需要已啟用 API 的 Google Cloud 專案與 API key。此次未配置此存取，也未發送狀態查詢。`REGISTERED`、`NOT_REGISTERED`、`REGISTERED_WITH_ANOTHER_CERTIFICATE_FINGERPRINT` 是 Google 的狀態值，與 HTC 的 `ERROR_UNREGISTER_APP` 不同；缺少憑證、403 或查詢失敗不能記成 `NOT_REGISTERED`。[狀態 API 指南](https://developer.android.com/developer-verification/guides/check-registration-status)

## 5. 目前已取得的測試基準

| 測試 | 結果與範圍 |
|---|---|
| 原始碼遷移 | 主程式、單元測試、裝置測試共 29 個 Kotlin 檔移至 `com/beefsoup/ai/glass`；對照 Git 原檔確認只改套件與相關 action 字串。 |
| 建置 | `assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功，1 分 29 秒。 |
| 單元測試 | 11 項通過，0 failure／error；涵蓋狀態機、音訊分段、媒體解析、PTS 與逐字稿時序。 |
| Manifest／簽章 | 主元件為新 namespace；instrumentation target 為新 applicationId；APK 簽章有效、憑證未變。 |
| S23 Ultra 回歸 | `MeetingRuntimeDemoTest.foregroundDemoSessionStartsStopsAndKeepsMedia` 通過，`OK (1 test)`；以合成音訊驗證 Activity／Application、前景 service、錄製起停與保存。不是眼鏡音訊驗收。 |
| 自訂 App 授權 | PID `30813`，02:32:41–02:32:43，真實 SDK 授權入口返回後仍回報 `The application is not registered.`；沒有 `connect()--`。 |
| 官方 sample 離線對照 | Eagle mode，PID `31316`；02:34:14 與 02:34:34 兩次均回報 `host app returned failure: status=failed code=-1`，沒有 `connect()--`。錯誤與自訂 App 不同，不推定相同根因。 |

環境：Samsung S23 Ultra／Android 16，VIVE Connect `1.4.1387452`，SDK `0.6.0`；眼鏡不在手邊。日誌保存於 `captures/2026-10-09/namespace-auth.log` 與 `sample-offline-auth.log`，皆在 Git 忽略目錄。官方 sample 在 2026-10-08 有眼鏡時曾完成授權與 Connected，見 [HTC_APP_REGISTRATION.md](HTC_APP_REGISTRATION.md)。

## 6. 登錄前後如何判讀

維持手機、VIVE Connect／SDK 版本、同一 applicationId／憑證、配對記錄、Bluetooth 權限與眼鏡在線狀態。登錄後重新使用 §2 保存的同一份 APK，避免 rebuild 或改簽章造成混淆。所有權驗證 APK 另行提交，不取代 HTC 對照 APK。

| 操作／結果 | 可支持的結論 |
|---|---|
| Full 身分驗證與同一套件／憑證登錄完成，再執行 SDK connect() | 可以比較正式 Google 登錄前後的 HTC 授權結果；保存狀態與本次 App PID／時間。 |
| Google 已完成登錄，HTC 仍回報相同未註冊錯誤 | Google 登錄不是此條件下通過 HTC 握手的充分條件；繼續查 HTC 授權流程。 |
| 登錄後 HTC 握手通過，出現 `connect()--` | 支持登錄可能有關；仍須排除眼鏡狀態、使用者同意及其他變化，並向 HTC 確認，不能只用一次成功建立因果。 |
| Limited 路徑已完成但 HTC 仍失敗 | 只驗證小量帳戶路徑，不能據此排除 Full verified identity 的假設。 |
| 只有 Activity `resultCode=-1` 或不再顯示某個錯誤 | 證據不足；仍要確認 SDK 完整握手正常返回。 |

眼鏡不可用時，整體 Connected、BLE 握手與串流仍不計為通過。取得眼鏡後以相同 App 測在線授權；Google 登錄測試與 HTC 真機驗收分別記錄。

## 7. 已補充的 Console 資料與後續

使用者已回報：Limited 帳戶、套件已註冊、§2 的憑證指紋，以及 S23 Ultra 已加入授權裝置。此次沒有要求改簽章或製作新的所有權驗證 APK。

最新程式追蹤已將目前拒絕定位到 HTC 套件白名單判定。下一步先確認該名單收錄與官方 App 的取得狀態，再於眼鏡在線時以同一 APK 驗證完整握手。不要只為此錯誤改用付費帳戶；若 HTC 明確要求 Google Full verification，再安排該條件的獨立驗證。

## 8. Limited 登錄後的離線重測（2026-10-09）

### 登錄與環境證據

- Console 的 Limited 帳戶、套件已註冊及 S23 Ultra 授權，依使用者回報；沒有將它記為 Full 身分驗證通過。
- 使用者回報的憑證 SHA-256：`2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe`。
- 從手機取回實際安裝的 `base.apk`；檔案 SHA-256 與 §2 的基準副本完全一致：`7C37A40444D9821D36DEDA78908E46F2286EB9C5940475D2AC669FEA9DACA2E4`。`apksigner verify --print-certs` 通過，實際簽署憑證也與登錄值一致。
- 手機仍為 S23 Ultra／Android 16，App `0.1.0`，SDK `0.6.0`，VIVE Connect `1.4.1387452`；Bluetooth scan／connect 權限已授予。眼鏡仍不在手邊。
- 此次沒有 rebuild、重新安裝、改程式碼、改簽章、清除 VIVE Connect 資料或解除配對；模擬音訊與電腦推論均關閉。

### 兩次測試結果

以下時間為手機 Logcat 的原始時間欄位。

| 嘗試 | 程序與操作 | 結果 |
|---|---|---|
| 登錄後重測 | App 冷啟動，PID `20655`；03:00:01–03:00:03 按「連接 VIVE Eagle」 | 找到官方 AUTH Activity、收到返回；隨後回報 `The application is not registered.`，沒有 `connect()--`。 |
| 重啟官方 App 後重測 | 停止並重新啟動 VIVE Connect（PID `22187`）及主 App（PID `22503`），保留資料；03:01:56–03:01:58 再連接 | 同樣回報未註冊，沒有 `connect()--`。重新啟動程序未改變結果；不代表已排除持久儲存或伺服器快取。 |

第二次主專案的關鍵日誌：

```text
AuthManager: buildAuthIntent: resolved to com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity
AuthProxyActivity: onActivityResult: requestCode=1001 resultCode=-1 hasData=true
ViveGlassKit: connect(): handshake failed: The application is not registered.
```

兩次返回時的 VIVE Connect UI 均出現「無法連線至眼鏡」及「請稍候，我們正在驗證與您的 VIVE AI 眼鏡的連線。」這證明官方 App 顯示眼鏡連線問題，但尚不能證明 SDK 未註冊錯誤的唯一根因就是眼鏡離線。

### 判讀與保存

**此環境下，完成 Limited 套件登錄與裝置授權不足以讓 HTC SDK 授權握手通過。** App 間呼叫通過，完整 HTC 授權、BLE 連線與串流未通過。不能把此結果外推為所有 Google 驗證與 HTC 都無關，也不據此要求使用者付費改用 Full 帳戶。

材料保存於忽略目錄 `captures/2026-10-09/google-registered-retest/`：

- `installed-base.apk`：手機實際安裝的 APK。
- `app-auth.log`：第一次新程序的授權日誌。
- `app-auth-after-vive-restart.log`：重啟官方 App 後的授權日誌。
- `before-ui.xml`、`after-ui.xml`、`vive-restarted-ui.xml`、`restart-before-ui.xml`、`restart-after-ui.xml`：當次 UI 快照。

下一步依 §7 執行眼鏡在線對照；若同一 App 在眼鏡在線且完成第三方同意後仍被拒絕，再將此次 Limited 登錄證據與 HTC 日誌提供給 HTC，確認它所指的 App 註冊機制。

## 9. 後續二進位追蹤修正了調查優先順序

同日已找到 VIVE Connect 的 `isAppInSDKWhitelist()`，檢查 HTC 配置服務提供的 Android 套件名稱名單。false 直接映射到未註冊，發生在眼鏡握手請求之前。因此可先向 HTC 確認 `com.beefsoup.ai.glass` 的白名單登錄與名單下載狀態，無須等待再次取得眼鏡才提出詢問；完整 Connected 與串流仍需真機。

同日稍後已由電腦取得端點的 HTTP 200 JSON：Android 清單包含官方 sample，但不包含主專案的新舊包名，見 [原始 JSON](evidence/htc_verified_apps_2026-10-09.json) 與 [取得紀錄](SDK_WHITELIST_CAPTURE_PLAN.md)。這確認目前有效名單未收錄主專案，與 §8 的失敗對照相符；不是手機當次流量擷取，不能排除其另有資料取得問題。Google 登錄實驗保留為歷史紀錄，後續不把 Full 升級當成目前錯誤的解法。詳見 [SDK_AUTH_FLOW_ANALYSIS.md](SDK_AUTH_FLOW_ANALYSIS.md)。
