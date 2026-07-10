# TikTok 45.7.3 Comment Binding Coverage Repair

Date: 2026-06-27

## Device And App

- Device: OnePlus 15 global build, model `CPH2747`
- Target package: `com.ss.android.ugc.trill`
- TikTok version: `45.7.3`, `versionCode=450703`
- Module fixed version: `1.0.6`, `versionCode=8`

## User-Observed Symptoms

- Some videos did not open the comments panel on double tap.
- On image/photo content, double tap could open comments while another popup appeared.

## Root Cause

The double-tap hook itself was still active. Diagnostic logs showed the current
Aweme aid could be resolved, but the module often had no matching
`VideoCommentAssem` / `IVideoCommentAbility` in its aid registry:

```text
DoubleTapComment: double tap has no ability for aid=... registry=[...]
DoubleTapComment: double tap swallowed; comment ability unavailable
```

The missing registration came from an outdated bind-method hook. Older builds
hooked `VideoCommentAssem.Nq(...)`; TikTok `45.7.3` binds the comment component
through `VideoCommentAssem.Xq(VideoItemParams)`. The old hook therefore missed
many feed item changes, so the registry lagged behind the current video.

## Fix

- Keep replacing `DiggPanelComponent.handleDoubleClick(MotionEvent)` to suppress
  TikTok's native double-tap like path.
- Keep `Qb` / `zb` current-video resolution and `jc0` / `Kb0` comment opening.
- Register comment abilities from both bind names:
  - `VideoCommentAssem.Nq(...)` for older TikTok builds.
  - `VideoCommentAssem.Xq(VideoItemParams)` for TikTok `45.7.3`.
- Keep the aid match guard before invoking comment open, so stale component
  instances cannot open the wrong video's comments.

Native hook and coordinate click fallback were not needed for this repair.

## Diagnostic Notes

The temporary diagnostic build `1.0.6-diagnostic` added stack logging for
`PopupWindow.showAtLocation`, `Dialog.show`, and `View.performLongClick`.

Observed after the `Xq` repair:

- No new `double tap has no ability` entries in the new test window.
- No reproduction of the earlier photo long-press popup class `X.0h0x`.
- Popup classes seen during retest were TikTok comment UI helpers/toasts:
  - `com.ss.android.ugc.aweme.comment.keyboard.keyboardv2.KeyboardFakePopupWindow`
  - `X.0FMy` via `X.0ET1.LJIIJJI`

## Test Artifacts

Main run directory:

```text
/Users/jin/Desktop/tiktok-double-tap-comments/docs/test-logs/run-20260627-165757-xq-diagnostic
```

Important files:

```text
auto-cycle/
auto-cycle-robust/
lsposed-after-launch.log
lsposed-after-auto-cycle.log
lsposed-after-robust-cycle.log
dev.tiktok.doubletapcomment-1.0.6-diagnostic.apk
```

Final production APK was built after disabling diagnostic hooks.
