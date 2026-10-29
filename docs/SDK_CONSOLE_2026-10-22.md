# Debug SDK console — 2026-10-22

The Debug-only 測試 destination now covers the core public operations exposed by the installed VIVE Glass Android SDK 0.6.0. Normal package identity remains `com.htc.viveglass.viveglasssample`.

## Controls and observations

| Area | Available behavior |
|---|---|
| Connection | Connect, disconnect, cached connection events, explicitly refreshed SDK `isConnected()` |
| Permissions | Read and request camera or microphone permission independently |
| Video | SDK quality options with actual enum dimensions; preview targets 5, 8, 10, 15, 24, 30 fps; SDK requested rate chosen from supported enum values; start/stop; displayed-frame capture into a still preview |
| Preview metadata | Decoded dimensions, media PTS, measured rate of frame updates delivered to UI; stream audio is discarded |
| Still camera | Native `captureImage(ImageQuality.DEFAULT)` and `onImageCaptured` response; separate from capturing a displayed video frame |
| Photo save quality | Original image bytes and MIME type unchanged, or JPEG 95/80 conversion; Android ImageDecoder applies orientation before conversion. Does not claim to change native sensor resolution |
| Audio | Surrounding microphone, SDK default mono audio configuration; private AAC→M4A segments; packet/byte counts and PCM peak; per-segment playback, stop and confirmed deletion |
| Transcription | SDK continuous transcription, stop and bounded result display; separate from the PC Whisper pipeline |
| Speech | SDK text-to-speech with user text and selected locale; success, unsupported locale, and error events |
| Metadata | Preferred locale, permission results, audio/video streaming flags, default audio/video bitrate, supported audio sample rates, application ID and integrated SDK version |
| Events | Latest 40 connection, permission, capture, stream, key, synthesis and transcription events; clear button. Transcript text capped at 8,000 characters |

There are no public battery-percentage, firmware-version or serial-number getters in the inspected `ViveGlass` API. Streaming low-battery/overheating and key events are shown when delivered; unavailable metadata is not fabricated.

## Lifecycle and constraints

- Media tests start only from explicit UI actions. Meeting recording and diagnostic operations have mutual-exclusion guards in both UI and runtime.
- Leaving the tab/backgrounding stops preview, audio recording/playback and SDK transcription. M4A finalization completes before another diagnostic can start.
- The SDK has no `stopSpeaking()` API. A previously sent utterance may finish after leaving the page; this is disclosed in the UI. New conflicting operations remain blocked while speaking is reported active. Disconnect is available from the console.
- Camera permission is requested alone for still capture. Photo callbacks have no request ID, so only one native capture is outstanding. After timeout the slot remains reserved until the old response arrives, preventing misattribution to a new request.
- A still result arriving after leaving the tab is discarded before previewing. Capturing never publishes to the gallery: a separate Save button is required. A user-requested save already underway may complete. Image writes use MediaStore `IS_PENDING`; failed writes remove only the newly created entry.
- Video stream is configured with the selected SDK resolution/rate. 10 fps preview requests a 15 fps source and samples it; 24/30 request corresponding native rates. Default stays 15. Frame conversion and UI publishing remain bounded; achievable throughput depends on the glasses/connection/phone/resolution.
- Diagnostic audio uses `files/debug-audio/<UUID>/`, never the meetings folder. Only currently listed diagnostic audio is affected by the delete action. Photos remain in `Pictures/AI Glass Debug`.
- The initial claim that 15 fps was the available ceiling was incorrect: the installed enum exposes FPS_5, FPS_8, FPS_15, FPS_24 and FPS_30. The app now exposes the higher rates.

## Sources

- [VIVE AI Glasses SDK](https://developer.vive.com/resources/vive-ai-glasses-sdk/)
- [Native still-image capture](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/viveglass/captureimage/)
- [ImageQuality: DEFAULT only](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/enumerations/imagequality/)
- Installed AAR public declarations for `ViveGlass`, `VideoStreamingFormat.FrameRate`, `ImagePayload`, callback interfaces, audio format and event enums were checked against implementation. No SDK dependency upgrade was needed.

## Verification

Validation uses fake SDK callbacks and synthetic encoded audio/video, not a real glasses session. Tests exercise still JPEG byte preservation and recompression, late-capture rejection, 30 fps timestamp sampling, Full HD decoding, selected SDK format/rate, metadata/event callbacks, exclusive speech/transcription operations, synthetic M4A creation, UI routing/actions and existing meeting screen regressions. A live glasses run is still required to establish actual capture quality, microphone routing, speech output, and sustained 30 fps.

Final validation: **28 device tests passed in 43.732 seconds** (13 UI, 6 encoded-video, 6 camera/storage, 3 SDK-console adapter/audio tests); **14 JVM tests passed**. Debug and Release APKs build successfully. Original-package Debug app installed and reopened on LG VELVET / Android 16. Native fixture screenshots and test log are in `captures/2026-10-22/sdk-console/`. `git diff --check` passed. No real glasses capture, microphone recording or speech output was automatically started.


## Preview-first capture and image-format correction

After the user reported “無效的 JPEG 照片”, the JPEG-only header/end-marker validation was removed. It rejected valid images of other formats and valid JPEGs containing trailing bytes. The exact original glasses payload was not retained, so its format is not established by this report.

Android ImageDecoder now identifies the MIME type, checks pixel limits, rejects partial/undecodable data and renders an orientation-aware preview (long edge capped at 1600 pixels). Original-save preserves bytes with the corresponding MIME/extension (e.g. PNG remains PNG); JPEG quality choices decode the full-resolution source for conversion. Unknown original MIME types can still be converted to JPEG if decodable. Decode errors report MIME, byte count, an eight-byte format header and decoder error code for diagnosis, without saving failed payloads.

Both native still capture and stream-frame capture now stage the photo in memory. Users inspect it, choose save quality, then press 儲存照片. 放棄預覽照片 clears only the staged photo; already saved gallery entries remain. Repeated Save is disabled after success. Unstored previews do not survive process termination.

Regression coverage adds PNG preview/original-save MIME and extension checks, padded valid JPEG acceptance, garbage rejection, no auto-save after capture, explicit save, and discard-without-publishing. This does not replace a fresh capture test on the user's glasses.

Preview-first validation: Debug and Release builds succeeded; 25 relevant device tests passed in 41.721 seconds (9 camera/storage, 13 UI, 3 SDK console), with 14 JVM tests passing. Updated original-package Debug APK installed and reopened. Native fixture screenshot and test log: `captures/2026-10-22/photo-preview/`.
