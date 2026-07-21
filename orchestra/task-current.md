# Task — TikTok 46.0.3 double-tap-comment update (project A)

## Goal
Restore double-tap-opens-comments on TikTok `com.ss.android.ugc.trill` 46.0.3
(versionCode 460003), OnePlus 15 / PLK110. Module `dev.tiktok.doubletapcomment`.

## Root cause (see docs/reverse-engineering-46.0.3.md)
Both legs of the flow broke on 46.0.3:
- Comment-open: old `Kb0`/`jc0`/`cc0` removed from `IVideoCommentAbility`; new
  no-arg open is `Ob0()`.
- Ability binding: bind method rotated `Nq`/`Xq`/`br` → `up(VideoItemParams)`,
  so the registry never populated.
Aid-resolution chain (`Qb`/`LJIIIIZZ`/`LJLIIL`) and field path `LLJI`→`LL` already
covered by existing fallback candidate lists — no change there.

## Change (MainHook.kt)
1. In `hookCommentAbilityBinding`, add `hookAllAfter(commentClass, "up") { … }`
   alongside `Nq`/`Xq`/`br`.
2. `COMMENT_OPEN_METHODS = listOf("Kb0", "jc0", "cc0", "Ob0")`.

## Machine-checkable acceptance
1. `./gradlew assembleDebug` (or assembleRelease) succeeds; APK emitted.
2. Reinstall + force-stop TikTok; logcat `DoubleTapComment` shows
   `installed hooks: 2` (or 3 with diagnostics) and, on a feed double-tap,
   `opened comment panel via Ob0 aid=…`.
3. On-device: double-tap central video → comment panel opens; video not liked;
   right-side like/comment buttons still work.

## Files
- app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt
