# VIVE Connect SDK 白名單取得結果與擷取方案

日期：2026-10-09。目標：取得官方 App 對 `/sdk/htc_verified_apps.json` 的實際 HTTP 回應，分辨包名未收錄、下載失敗與資料格式問題。流程背景見 [SDK_AUTH_FLOW_ANALYSIS.md](SDK_AUTH_FLOW_ANALYSIS.md)。

## 0. 已取得實際 JSON

本次從同一手機的官方 APK 還原指定端點使用的原生金鑰取得流程，僅解碼索引 `13`，再由電腦執行一次帶 `x-api-key` 的唯讀 GET。**實際回應為 HTTP 200，Content-Type 為 application/json。** 不是手機執行時擷取，也不是依程式碼猜測的資料範例。

- [完整原始回應](evidence/htc_verified_apps_2026-10-09.json)：保留原始 2,587 bytes。
- [回應證據](evidence/htc_verified_apps_2026-10-09.meta.json)：端點、取得時間、HTTP 狀態、ETag、Last-Modified、SHA-256；不含請求金鑰。
- 取得時間：2026-10-09 04:17（Asia/Taipei）；HTTP Date 為 2026-10-08 20:17:26 GMT。
- SHA-256：`047bca4a69615005a808ebcb1cb88da9b34c5ea7b85b6f5609f8d4b1b75a65d1`。
- JSON 根欄位為 `verifiedapps`，包含 `android` 和 `ios`。Android 有 50 筆、49 個不重複包名；iOS 有 23 筆。

| 包名 | 本次 android 清單 |
|---|---|
| `com.htc.viveglass.viveglasssample` | 包含 |
| `com.beefsoup.ai.glass` | 不包含 |
| `edu.ncku.aiglass` | 不包含 |

**目前端點的有效名單確實未收錄主專案，足以使已追到的白名單分支拒絕。** 與 sample／主 App 的既有結果相符；但沒有讀取手機先前那次請求或記憶體快取，不能排除手機另有下載問題。名單是此次快照，不保證永久不變或所有環境一致。

## 1. 本次已確認的資訊

### API key 的取得鏈

```text
JavaScript getVerifiedApps()
→ GeneralModule.getAiModelAccessKey()
→ new ApiKeyManager().g()
→ a(13)：檢查記憶體快取，否則 getNativeKey(13)
→ libapikey.so 的 JNI getter
```

已從同一手機的 `split_config.arm64_v8a.apk` 取得 `libapikey.so`。此 library 不在先前分析的 base APK 中，因此只搜尋 base APK 文字不足以找到此流程。

原生 getter 檢查目前 Context 的包名，再讀取 APK 簽章並執行 SHA-256 比對；通過後選取資料，使用 SHA-256／SHA-1 相關輸入呼叫 `decode()`。Native 包含 `incorrect package name`、`incorrect signature`、`correct signature` 訊息。

**這是 VIVE Connect 自己取得內部 API key 的身分檢查，與 SDK 白名單比對我們的 `com.beefsoup.ai.glass` 是兩層檢查。** 不把它解讀為 Google Console 要求我們登錄憑證，也不能認為把 library 放進我們的 Debug App 就能取得金鑰。

後續已還原該索引使用的 `AES/CBC/PKCS7Padding` 解碼：原生函式從官方簽章的 SHA-256／SHA-1 十六進位字串選取字元，產生解碼所需輸入，再解開特定加密項目。官方 APK 的 signer SHA-256 與 `get2()` 預期值相符，故選取索引 13，未逐項試探其他金鑰。

該金鑰只在診斷程序記憶體中用於此次指定 URL 的 GET；沒有輸出明文、保存到檔案或加入 Android App。此內部金鑰不是我們的 SDK App 登錄憑證；直接 GET 不會將主專案加入名單。

### 完整 JSON 的日誌並未啟用

Hermes `#41057` 計算 `JSON.stringify(verifiedApps)`，將 `verifiedApps from server: ...` 交給 `LogUtil.print()`；但同一 bundle 的函式 `#6932 print` 只有返回 `undefined` 的兩條指令，沒有輸出或存檔。因此不能只看到訊息字串就認為 Logcat／匯出日誌一定含完整 JSON。

Native logger `Ic.f` 使用 `context.getFilesDir()/logs/EagleCALog_yyyyMMdd.txt`，屬於 App 私有資料。它的 Logcat 輸出方法也是空實作。

### 手機與 APK 的限制

- 無線 ADB 仍連線；目前 shell 為 UID 2000，並未取得 root 存取能力。
- 手機上 Connect 版本仍為 `1.4.1387452`，targetSdk 36，未標記 debuggable。
- 實際 `run-as com.htc.glassesca pwd` 回報 `package not debuggable`。
- `/sdcard/Android/data/com.htc.glassesca/files` 沒有可用日誌；cache 只見 diskcache 目錄，尚未證明其中有白名單。
- APK Manifest 沒有 `networkSecurityConfig`，`allowBackup=false`。這支持平台預設 CA 信任規則，但尚未排除其網路程式另設 TrustManager 或 certificate pinning。
- 目前公開 Logcat 未找到白名單結果或相關下載錯誤；空結果不代表請求沒有發生。

## 2. 方法比較

本次已使用靜態分析取得回應；以下代理與執行時觀察保留作為需要比對「手機當次實際資料」時的備選方式。

| 方法 | 能取得的資訊 | 本專案可行條件 |
|---|---|---|
| Wireshark／一般封包擷取 | 加密連線、時間及流量；可見 metadata 依 TLS／代理設定而異 | 單靠封包無法讀 HTTPS 的 JSON 或 `x-api-key`。 |
| mitmproxy 等 HTTPS 代理 | 官方 App 的 HTTP 狀態、回應 body；可只保存指定端點 | App 必須使用代理、信任代理 CA；有 pinning 或自訂信任規則時需另評估。S23 Ultra 僅安裝使用者 CA 不保證可行。 |
| 執行時觀察回應／解析結果 | 正常請求取得的 HTTP body 或 `verifiedapps` 物件 | 可使用 Frida／debugger 等；通常需可觀測的測試裝置、root 環境或官方 debug build。Rootless Gadget 重包會改變簽章，可能使此 getter 失敗。 |
| 官方診斷匯出／HTC 提供回應 | 下載狀態、名單版本或相關包名是否收錄 | 可詢問 HTC；此版本一般日誌的完整名單輸出是空方法，不能承諾匯出檔含 JSON。 |
| 靜態分析原生 getter／decode | 還原特定金鑰取得流程，再請求指定端點 | 本次已成功取得 HTTP 200 JSON；不需修改手機信任設定。單用 strings 搜尋不足。結果不是手機的當次回應。 |

Android 預設只信任系統 CA；target API 24 以上的 App 須另行配置，才會信任使用者新增 CA，見 [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)。

Frida 官方說明 root、debugger 與 Gadget 等運作條件，見 [Frida Android](https://frida.re/docs/android/)。模擬器的代理與系統 CA 操作另見 [mitmproxy Android Emulator 指引](https://docs.mitmproxy.org/stable/howto/install-system-trusted-ca-android/)；該指引部分步驟僅在較舊 Android 版本測試，不直接套用到 Android 17。

## 3. 如仍需比對手機當次回應

只有在 HTC 已加入包名後手機仍拒絕，或需區分手機快取／網路行為時，才安排額外擷取。優先使用獨立測試環境觀察官方 App 的回應，先確認官方 APK 能正常執行並取得內部 key，再引入觀察工具。ABI、Play／完整性檢查與登入需求仍須實際驗證；不能承諾目前 x86_64 Pixel 9a AVD 一定能執行 S23 Ultra 的 arm64 split。

1. 記錄 Connect 版本、原始簽章與測試環境；先完成未插入工具的基準請求。
2. 若採 HTTPS 代理，確認 App 使用代理且 CA 被信任，再讀指定端點的 HTTP 回應。若 TLS 失敗，記為代理環境問題，不記為 HTC 註冊拒絕。
3. 若採執行時觀察，只針對指定端點的回應或 `verifiedapps` 解析結果，不修改返回值、白名單或授權狀態。觀察 body 時避免消耗原本要交給 App 的 stream。
4. 考量 12 小時模組記憶體快取，必要時保留資料重啟官方 App，以同一主 App 觸發新一次 connect；不解除配對或清除帳戶資料。
5. 先保存 HTTP 狀態與原始 body，再另存解析結果、取得時間及 SHA-256。若僅觀察解析物件，應標註為解析結果，而非原始 HTTP 回應。
6. 檢查 `android` 欄位是否為字串陣列、是否包含目前包名及官方 sample 包名。不能以非 200、HTML 錯誤頁、缺少欄位或 `null` 當成有效空白名單。
7. 僅取得 JSON 不代表眼鏡連線驗收通過；名單仍可能隨時間／環境改變。

此實驗的白名單檢查發生在裝置握手前，沒有眼鏡仍可能觸發請求；若官方 App 的啟動／初始化先要求配對或其他條件，須依實際行為記錄。

## 4. 本機證據

- `captures/2026-10-09/ConnectGeneralModule.java:828`：`getAiModelAccessKey`。
- `captures/2026-10-09/ConnectApiKeyManager.java`：`g()`、`a(int)` 與 JNI 宣告。
- `captures/2026-10-09/libapikey.so`：SHA-256 `33AEF6764E157E1CC7DD298BC38F632A4E61724FE8B36F34BDCB741546E710CF`。
- `captures/2026-10-09/apikey-getter-arm64.txt`：Native getter，位址 `0x1af5c`。
- `captures/2026-10-09/apikey-getcontextname-arm64.txt`、`apikey-getsignature-arm64.txt`：包名／簽章讀取。
- `captures/2026-10-09/apikey-decode-arm64.txt`、`apikey-keyderivation-arm64.txt`：指定項目的解碼與輸入衍生函式反組譯。
- `captures/2026-10-09/connect-print-noop.hasm`：Hermes `#6932`。
- `captures/2026-10-09/ConnectNativeLogger.java`、`ConnectLogModule.java`：日誌實作。
- `captures/2026-10-09/connect-manifest-tree.txt`：Manifest 檢查。
- `captures/2026-10-09/fetch_sdk_whitelist.py`：本次限定端點的診斷腳本，驗證 APK／library 雜湊及 signer 分支，金鑰僅存在程序記憶體；置於 Git 忽略目錄。
- `docs/evidence/htc_verified_apps_2026-10-09.json`、`.meta.json`：實際回應及不含金鑰的取得紀錄。

本次做 APK／程式分析、手機唯讀檢查與一次指定端點 GET；未執行 TLS 代理擷取、執行時掛鉤、重新簽署或安裝修改版官方 APK。

## 5. 下一步

請 HTC 提供學生團隊 SDK 白名單登錄方式，登錄 `com.beefsoup.ai.glass`。更新後先確認新的名單包含此包名，再保留資料重啟 Connect，以同一 APK 重測。沒有眼鏡時可以觀察未註冊錯誤是否改變，但完整握手、Connected、串流及長時錄製仍需眼鏡在線。支援詢問草稿見 [HTC_APP_REGISTRATION.md](HTC_APP_REGISTRATION.md)。
