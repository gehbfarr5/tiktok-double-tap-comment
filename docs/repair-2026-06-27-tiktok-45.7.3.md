# TikTok 45.7.3 Repair Log

Date: 2026-06-27

Device:

- OnePlus 15 global build `CPH2747_11.A.42_0420_202606022357`
- ADB target: `192.168.110.95:5555`
- TikTok package: `com.ss.android.ugc.trill`
- TikTok version: `45.7.3`, `versionCode=450703`

Module:

- Package: `dev.tiktok.doubletapcomment`
- Fixed version: `1.0.4`, `versionCode=5`
- Final APK: `/Users/jin/Desktop/tiktok-double-tap-comments/docs/test-logs/run-20260627-161238/dev.tiktok.doubletapcomment-1.0.4-release-final.apk`
- Final APK SHA-256: `b451de3c9a1f52056ff8e3670dddb6eff7ac9e27adf9212dd39aecc92a69f059`

## Root Cause

The module still intercepted TikTok's double-tap path, so double-tap like was blocked.
Opening comments failed because TikTok changed the internal methods used by the module:

- Old current-video resolver: `DiggPanelComponent.zb()`
- New resolver seen in TikTok 45.7.3: `DiggPanelComponent.Qb()`
- Old comment opener: `IVideoCommentAbility.Kb0()`
- New comment opener seen in TikTok 45.7.3: `IVideoCommentAbility.jc0()`

LSPosed logs before the fix repeatedly showed:

```text
DoubleTapComment: reflect call failed: com.ss.android.ugc.feed.platform.panel.digg.DiggPanelComponent.zb
DoubleTapComment: double tap swallowed; current aweme aid unavailable
DoubleTapComment: double tap swallowed; comment ability unavailable
```

## Fix

- Keep replacing `DiggPanelComponent.handleDoubleClick(MotionEvent)` so TikTok's native double-tap like path remains blocked.
- Resolve the active video from the panel through compatible candidates:
  - `Qb()` first for TikTok 45.7.3
  - `zb()` retained for the previously tested build
  - current Aweme candidates: `LJIIIIZZ`, `LLLLLLIL`, `l41`
  - current cell candidates: `LJLIL`, `BR`, `LJLIIL`
- Open comments through compatible ability methods:
  - `Kb0`
  - `jc0`
- Keep the existing `aid` match guard before opening comments, so a recycled or stale feed cell does not open comments for the wrong video.
- Quiet candidate probing so LSPosed logs only show success or full failure, not expected missing methods.

## Verification

Build:

```text
./gradlew :app:assembleRelease
BUILD SUCCESSFUL
```

Install:

```text
pm install -r -g /data/local/tmp/dev.tiktok.doubletapcomment-1.0.4-final.apk
Success
versionCode=5
versionName=1.0.4
```

Final LSPosed log:

```text
DoubleTapComment: loading in com.ss.android.ugc.trill
DoubleTapComment: installed hooks: 2
DoubleTapComment: opened comment panel via jc0 aid=#389892c4
```

Final visual verification:

- `/Users/jin/Desktop/tiktok-double-tap-comments/docs/test-logs/run-20260627-161238/tiktok-final-doubletap.png`
- Screenshot shows the native TikTok comments panel opened after a central feed double-tap.

## Notes

`/data/adb/lspd/config/modules_config.db` still reported the old module `apk_path`
after `pm install -r`, but the final TikTok process loaded the new code and opened
comments via `jc0()`. The database was not edited because the running behavior was
correct and editing LSPosed internals was unnecessary risk.
