# 官方 sample 包名的授權對照實驗

日期：2026-10-15（Asia/Taipei）。§1–4 記錄當日較早、沒有眼鏡時的離線對照；§5 記錄稍後取得眼鏡的在線結果。HTC 支援尚未回覆。依使用者要求，將本專案 APK 的套件身分改為 `com.htc.viveglass.viveglasssample`，觀察是否仍回報未註冊。

## 1. 離線對照結果

**新包名版本沒有再回報 `The application is not registered.`，而是回報一般握手失敗 `status=failed code=-1`。完整授權仍未通過。**

| 版本 | 本次手機 Logcat 結果 |
|---|---|
| 原包名 `com.beefsoup.ai.glass` | `connect(): handshake failed: The application is not registered.` |
| 實驗包名 `com.htc.viveglass.viveglasssample` | `connect(): handshake failed: host app returned failure: status=failed code=-1` |

這組對照支持套件名稱影響目前的未註冊判定，與先前找到的白名單分支相符。Connect 同時顯示「無法連線至眼鏡」；但 `-1` 是一般錯誤，不足以判定唯一原因就是眼鏡離線。此次未動態讀取手機的白名單資料或完整內部分支，也不能把錯誤改變記為已完成正式 HTC 登錄。

## 2. 實際修改與建置驗證

- `app/build.gradle.kts` 的 `applicationId` 改為 `com.htc.viveglass.viveglasssample`。
- namespace／Kotlin package 仍為 `com.beefsoup.ai.glass`。Android 套件身分由 applicationId 決定；這次未再遷移程式類別。
- SDK 仍為 `0.6.0`，App 仍為 `0.1.0`／versionCode `1`，使用原 Debug 簽章。
- `:app:assembleDebug` 成功；`aapt2` 確認新套件名稱，Application、Activity、Service 仍指向正確的 Kotlin 類別。
- 實際啟動元件為 `com.htc.viveglass.viveglasssample/com.beefsoup.ai.glass.MainActivity`，無線安裝與啟動成功。
- 本 APK 是本專案的包名實驗版本；其程式仍是 AI 眼鏡會議紀錄原型。

| 材料 | SHA-256 |
|---|---|
| 基準 APK（保留的 2026-10-09 建置） | `7C37A40444D9821D36DEDA78908E46F2286EB9C5940475D2AC669FEA9DACA2E4` |
| 本次實驗 APK | `F8DC63EED9AFBA6FDC3CB1B0E8036B96C26507BBC4BF8DD79FC4DD3101957BAA` |
| 兩份 APK 的簽署憑證 | `2e7c47ac10f51e00ca624547dca34bbe41c75db795c74ba48e60ef116c357efe` |

基準使用保留的舊 APK，實驗版本由目前工作區重新建置；不把兩份二進位描述為除了包名以外逐位元相同。

## 3. 手機對照流程

環境：S23 Ultra `SM_S9180`、Android 16／API 36、VIVE Connect `1.4.1387452`，Android user 0。無線 ADB 已重新連線。

1. 安裝前確認手機未安裝主 App 或官方 sample，故未覆蓋原 sample。手機仍有先前的主專案裝置測試 APK；此次未使用該測試 APK。
2. 重新安裝保留的原包名 APK，授予附近裝置／通知權限；解鎖手機後，確認模擬音訊與 PC 推論均關閉。
3. 按一次「連接 VIVE Eagle」。PID `22157`，原始手機 Logcat 時間 `10-15 00:31:07.826` 開始、`00:31:09.740` 回報未註冊。
4. 安裝新包名實驗 APK，核對版本及相同權限；確認模擬音訊與 PC 推論均關閉。
5. 按一次「連接 VIVE Eagle」。PID `25825`，原始手機 Logcat 時間 `10-15 00:32:16.804` 開始、`00:32:18.638` 回報一般錯誤 `-1`。
6. 兩次均解析到 `com.htc.glassesca/com.htc.glassesca.sdk.SdkAuthActivity` 並收到 Activity 返回。兩次均沒有 `connect()--`，沒有 Connected 或串流驗收成果。
7. 實驗結束後停止兩個測試程序，再啟動新包名版本供後續操作；保留兩份 App 的資料，未清除 VIVE Connect 資料或解除眼鏡配對。

上述時間保留手機日誌原欄位，未另推定或轉換其時區。Android Activity `resultCode=-1` 仍是 `RESULT_OK`，與 HTC 業務碼 `-1` 不同。

此次未啟動錄製、相機、合成音訊 pipeline 或 PC 推論。驗收依本次 SDK 日誌與 Connected callback；App 的「正在連接」文字不能單獨表示握手仍在進行或已通過。本次只建置主 APK 並執行兩次授權對照，未重新執行或增加先前的單元／pipeline 測試通過數。

## 4. 證據與還原方式

- [精簡實驗證據](evidence/sample_package_auth_2026-10-15.json)：套件、APK／憑證雜湊、PID、SDK 事件與限制，不含金鑰。
- 原始日誌與 UI 快照保存在 Git 忽略的 `captures/2026-10-15/sample-package-experiment/`，包含 `baseline-auth.log`、`sample-auth.log`、前後 UI XML、Manifest／簽章核對及實驗 APK。
- 原始白名單快照仍為 [2026-10-09 JSON](evidence/htc_verified_apps_2026-10-09.json)；本次未重新取得端點 JSON，也沒有替換白名單回應。

如需回到向 HTC 申請的正式包名，將 applicationId 改回 `com.beefsoup.ai.glass` 並重新建置。namespace 已維持此名稱，不需再遷移 Kotlin 類別。日後若安裝未修改的官方 sample，它與實驗 App 具有相同 applicationId，須先確認簽章、版本與資料保存方式。

離線階段結束時，仍等待 HTC 提供正式登錄方式；後續在線驗證如下。

## 5. 同日稍後：眼鏡在線驗證

使用者取得 VIVE Eagle 並透過 Connect 連線後，本專案以相同實驗套件身分完成 `connect()--`、握手 `CONNECTED`、真實音訊及相機與音訊同流。曾觀察到摘下眼鏡觸發 `APP_INTERACTION_STOPPED_DEVICE_DOFFED` 並斷線，後續測試要求持續配戴。

- 純音訊：65.984 秒、7 個可解碼 M4A、19 段 PCM、正常停止，沒有健康事件或殘留 `.partial`。
- 相機首輪：ImageReader 平面存取造成 JNI 原生崩潰，失敗會議與未完成檔案保留。
- 修正：MediaCodec 改用可讀取的 flexible YUV 輸出，依 crop／stride 複製成獨立 NV21 後再交給 ML Kit；S23 的橫向／直向 H.264→OCR 回歸 `OK (2 tests)`。
- 修正後同流：21.107 秒音訊、3 個 M4A、6 段 PCM、20 筆 OCR，正常停止且沒有警告。中文可辨識，英文／日文仍有錯字，品質未通過。

這些是實驗包名下的在線成果，不能回推當日較早 `-1` 的唯一原因，也不能視為正式包名已加入 HTC 白名單。本次沒有重測原包名的在線授權。向 HTC 申請 `com.beefsoup.ai.glass` 的工作仍待完成。

完整測試、APK 雜湊及限制見 [真機測試紀錄](HARDWARE_SMOKE_TEST_2026-10-15.md) 與 [在線證據 JSON](evidence/vive_eagle_online_2026-10-15.json)。上文保留的 APK 雜湊是修正前版本；修正版為 `884A3B32C480F60BD8D514C94BC1FB6ACA2AAB86FF22CEA91F3E39E33CAE9E8D`。
