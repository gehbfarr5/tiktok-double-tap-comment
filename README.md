# TikTok Double Tap Comment

An LSPosed module that changes TikTok's feed double-tap gesture from **like** to **open comments**.

When the module is active, double-tapping a video in the TikTok feed opens the native comment panel and does not trigger TikTok's double-tap like behavior. The regular like button, comment button, single tap, long press, and feed swiping behavior remain handled by TikTok.

## Status

- Tested target: TikTok `45.5.4` / `versionCode 450504`
- Tested package: `com.ss.android.ugc.trill`
- Framework: LSPosed API 82+
- Android: tested on Android 16 with LSPosed
- Module package: `dev.tiktok.doubletapcomment`

This module hooks TikTok's internal implementation details, so TikTok updates can break it. If the hook no longer works after an app update, check the LSPosed logs for `DoubleTapComment`.

## Supported Target Packages

The default LSPosed scope includes:

- `com.ss.android.ugc.trill`
- `com.zhiliaoapp.musically`
- `com.zhiliaoapp.musically.go`

The implementation was validated on `com.ss.android.ugc.trill`. Other TikTok variants may need updated class names if their feed implementation differs.

## How It Works

TikTok's feed double-tap path in the tested build is:

1. `VideoViewCell` receives the touch event.
2. `GestureDetector` calls `C281500B3q.onDoubleTap(...)`.
3. The feed panel forwards to `IDiggComponentAbility.handleDoubleClick(...)`.
4. `DiggPanelComponent.handleDoubleClick(...)` performs the double-tap like behavior.

This module replaces:

```text
com.ss.android.ugc.feed.platform.panel.digg.DiggPanelComponent
  .handleDoubleClick(android.view.MotionEvent)
```

Instead of calling TikTok's original like logic, the replacement tries to resolve TikTok's native:

```text
com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility
```

and calls:

```text
Kb0()
```

That reuses TikTok's own comment-opening flow, including comment availability checks, existing UI behavior, and internal event dispatch.

If the comment ability cannot be resolved, the module still swallows the original double-tap event so double-tap like remains disabled.

## Build

Requirements:

- JDK 17
- Android SDK
- Gradle Wrapper included in this repository

Build debug:

```bash
./gradlew assembleDebug
```

Build release:

```bash
./gradlew assembleRelease
```

The release APK is generated at:

```text
app/build/outputs/apk/release/app-release.apk
```

The release build in this project is debug-key signed for easy local installation. Use your own signing configuration if you publish binaries.

## Install

1. Install the APK on the device.
2. Open LSPosed Manager.
3. Enable **TikTok Double Tap Comment**.
4. Select TikTok in the module scope.
5. Force-stop TikTok.
6. Reopen TikTok and test double-tap on a feed video.

Expected result:

- Double-tap opens the comment panel.
- The video is not liked.
- The right-side like button still works normally.
- The right-side comment button still works normally.

## Troubleshooting

Check LSPosed logs for:

```text
DoubleTapComment
```

Useful log messages:

- `installed hooks: 2` means the main hook and comment ability cache hook were installed.
- `opened comment panel via aid-cache` means the module resolved the comment ability bound to the current video.
- `opened comment panel via direct-scope` or `cached-scope` means a scope lookup was used after verifying the bound video id.
- `blocked mismatched comment ability` means a stale or recycled feed cell was rejected to avoid opening comments for the wrong video.
- `double tap swallowed; comment ability unavailable` means double-tap like was blocked, but the module could not safely open comments.

If TikTok updates and the module stops working:

1. Confirm the package is still selected in LSPosed scope.
2. Confirm TikTok was force-stopped after enabling the module.
3. Check whether the hook class still exists in the target build:
   `com.ss.android.ugc.feed.platform.panel.digg.DiggPanelComponent`.
4. Re-run reverse engineering on the new TikTok version and update the hook target if needed.

## Project Layout

```text
app/src/main/assets/xposed_init
app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt
app/src/main/java/dev/tiktok/doubletapcomment/ui/MainActivity.kt
app/src/main/res/xml/xposed_scope.xml
```

## Notes

- This module does not modify the TikTok APK.
- It only runs inside selected LSPosed target packages.
- It does not send network requests or collect analytics.
- The bundled `app/libs/api-82.jar` is used as a compile-only Xposed API dependency.

## License

MIT
