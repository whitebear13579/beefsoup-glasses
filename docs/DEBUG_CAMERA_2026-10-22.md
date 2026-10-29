# Debug camera tab — 2026-10-22

## Behavior

Debug builds expose a fifth tab, 測試. Connect the glasses, then use 開始預覽, 停止預覽 and 擷取並儲存. The capture is the currently displayed stream frame, not a separate high-resolution still-camera request. JPEGs are published to `Pictures/AI Glass Debug` using MediaStore pending writes, with no additional storage permission.

Preview reuses the existing H.264 decoder with OCR disabled. Display conversion targets the selected 5/10/15 fps (default 15), retains the latest pending frame, and preserves the original frame aspect ratio. All H.264 reference frames still reach the decoder. This is a foreground diagnostic screen, not background recording. Leaving the tab, backgrounding the activity, disconnecting, startup timeout or an SDK stream error stops the preview. Meeting recording and test streaming cannot run simultaneously. The SDK provides audio with the camera stream; this diagnostic discards it.

The runtime creates the controller only for BuildConfig.DEBUG; navigation and rendering are also gated. The original application ID remains `com.htc.viveglass.viveglasssample`, without a suffix.

## SDK references

The user supplied the [VIVE AI Glasses SDK](https://developer.vive.com/resources/vive-ai-glasses-sdk/) reference. The current [Android video-streaming API](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/viveglass/startvideostreamingwithvideoconfig/) matches the project's adapter: video/audio formats, separate media callbacks, stream events, return-code handling, camera and microphone permissions. No SDK migration was required.

Photo publication follows [Android shared-media storage](https://developer.android.com/training/data-storage/shared/media), including IS_PENDING and RELATIVE_PATH. Failed writes remove only their newly created gallery entry.

## Validation

- Debug and Release APK builds succeeded. Generated Release BuildConfig.DEBUG is false; no device-side Release installation was performed.
- JVM suite: 14 passing tests.
- Connected LG VELVET / Android 16: **17 device tests passed in 33.546 seconds** (11 UI, 3 encoded-video/OCR, 3 camera-controller/storage tests).
- Added coverage: capture gating before first frame, capture dispatch, leaving-tab cleanup, permission completion after cancellation, active-meeting exclusion, stale SDK events after restart, decoding preview without OCR, and publication of a readable JPEG to the correct gallery directory.
- Gallery test deletes only the photo it creates. No existing meetings or photos are removed.
- Native screenshot `captures/2026-10-22/camera/debug-camera-fixture.png` uses a synthetic blank bitmap, not a real glasses image.

Live glasses streaming and physical capture remain to be checked while wearing the glasses. Software tests do not establish hardware throughput, orientation, thermal behavior or image quality.


## Quality and frame-rate controls

The test page now provides Material dropdowns for SDK VideoQuality (Default, Low, Medium, High, Full HD) and preview refresh target (5, 10, 15 fps). Labels read resolution from the installed 0.6.0 SDK enum's public width/height fields; its Full HD enum is FULL_HD. Selection is retained for the app runtime, and locked while permission/startup/streaming is active. Stop preview before changing either setting.

The selected quality is passed through GlassLink into VideoStreamingFormat; meeting recordings retain their previous default. Preview decoder dimensions are initialized from that selection. The SDK stream remains configured at 15 fps and its default bitrate. Preview scheduling uses media timestamps; 15 fps is a target rather than measured hardware throughput. Frames may be dropped under load, particularly at Full HD. Saving still captures the displayed stream frame.

Reference: [HTC VideoQuality](https://developer.vive.com/resources/vive-ai-glasses-sdk/documentation/android/enumerations/videoquality/). Debug and Release builds pass after these changes. Added device regressions verify dropdown selection/locking, transport of the selected quality into the stream, three preview frames spaced at 15 fps, and Full HD-sized encoded-video decoding.

Quality/FPS validation result: **21 device tests passed in 39.57 seconds** (12 UI, 5 video, 4 camera/storage); JVM suite remains 14 passing. Native dropdown screenshot is `captures/2026-10-22/camera/quality-menu.png`. Updated Debug APK installed and reopened with the original application ID. Actual glasses frame rate at each resolution still needs hardware measurement.
