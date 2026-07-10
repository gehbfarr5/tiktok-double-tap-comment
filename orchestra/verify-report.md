# Verify Report — TikTok 45.9.3 double-tap comment fix

**Verdict: PASS**

Confidence: high — static, build, and real-device functional checks all passed. Device evidence is the strongest signal and it is unambiguous (module log shows the new fallback method firing, screen shows the comment panel open, like counter/heart icon unchanged).

## 1. Static check — PASS

`git diff --name-only HEAD` shows only:
```
app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt
```
No other tracked file changed (README.md/build.gradle untouched). The untracked `orchestra/.baseline`, `.codex-last.md`, `.codex-status`, `.done`, `cost-ledger.tsv` are orchestration bookkeeping, not source.

`git diff HEAD -- .../MainHook.kt` confirmed all four claimed changes, all additive (no old candidate removed):

- `hookCommentAbilityBinding()`: new `hookAllAfter(commentClass, "br")` block added **alongside** the existing `"Nq"` and `"Xq"` hooks (both still present, lines just above/below).
- `TikTokReflect.currentAwemeAidFromDigg`: `firstMethodResult(diggComponent, "Qb", "zb")` → `firstMethodResult(diggComponent, "Ub", "Qb", "zb")` (old two kept, `Ub` prepended).
- Same function, cell-resolver candidates: `firstMethodResult(viewPagerAbility, "LJLIL", "BR", "LJLIIL")` → adds `"MR"` in front, old three kept.
- `COMMENT_OPEN_METHODS`: `listOf("Kb0", "jc0")` → `listOf("Kb0", "jc0", "cc0")`, old two kept.
- `hookGestureDiagnostics()` present and untouched (grep confirms function still defined and still called from `handleLoadPackage`).

## 2. Build check — PASS

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/... ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
```
`BUILD SUCCESSFUL in 6s`. Deleted the old APK first to force a real rebuild; new `app/build/outputs/apk/debug/app-debug.apk` regenerated (812K, fresh timestamp).

## 3. Device functional check — PASS (real device, OnePlus 15 / PLK110 / 3B166Q00SX000000)

- USB ADB worked directly (`adb devices` showed the serial with no `adb connect` needed), root confirmed (`id` → `uid=0(root) context=u:r:ksu:s0`), user 0 `RUNNING_UNLOCKED`.
- AndroMeld MCP was disabled this session ("MCP control is disabled in AndroMeld") — fell back to plain ADB/uiautomator for everything, per the skill's screen was in AOD/keyguard state; unlocked with the documented Direct Pattern Injection sequence (`showing=true`→`false`, `deviceLocked=1`→`0` confirmed via `dumpsys window policy` / `dumpsys trust`).
- Confirmed installed TikTok is exactly the target build: `dumpsys package com.ss.android.ugc.trill` → `versionName=45.9.3 versionCode=450903`.
- Installed the freshly built APK via root `pm install --user 0 -r -g` after staging to `/data/local/tmp` (no installer UI dialog appeared).
- Module was **not yet present** in `/data/adb/lspd/config/modules_config.db` (first install on this rebuilt device). Enabled it and added `com.ss.android.ugc.trill` scope through the LSPosed Manager app UI (org.lsposed.manager), driven via `uiautomator dump` + `input tap`/`input text` (no AndroMeld needed). Verified directly in the DB (pulled `.db`+`.db-wal`+`.db-shm` together to see uncommitted WAL state):
  ```
  module: (124, 'dev.tiktok.doubletapcomment', '.../base.apk', 1, 0)   -- enabled=1
  scope:  (124, 'com.ss.android.ugc.trill', 0)
  ```
- Force-stopped and relaunched TikTok. Module log (`/data/adb/lspd/log/modules_*.log`, tag `DoubleTapComment`) showed:
  ```
  DoubleTapComment: loading in com.ss.android.ugc.trill
  DoubleTapComment: installed hooks: 2
  ```
  (Prior log lines from an earlier same-day pre-fix test session at 01:12 still show the *old* failure mode for contrast: `none of [Qb, zb] worked ... double tap swallowed; comment ability unavailable` — confirming the bug was real and reproducible before the fix.)
- Performed a real double-tap (two `input tap` at ~120ms apart) on a feed video. Log immediately recorded:
  ```
  DoubleTapComment: opened comment panel via cc0 aid=#af723623
  ```
  — the exact new fallback method (`cc0`) added by this fix. Screenshot confirmed the comment panel/sheet was genuinely open (comment input box, comment list UI visible).
  Like button state was checked before/after: heart icon stayed hollow/outlined and like counter stayed at "91.5万" (unchanged) — confirming the native like was correctly suppressed, not just that some panel opened.
- Repeated on a second, different video (swiped to a new feed item) for reproducibility: log showed
  ```
  DoubleTapComment: opened comment panel via cc0 aid=#f23ff377
  ```
  and the resulting panel header correctly read "7033 条评论" matching that (different) video, confirming correct aid binding, not a stale/cached one.
- The comment *list content* itself failed to load in both tests (`出错了 / 稍后重试`, and a `无网络连接` banner on the first attempt) — this is TikTok's own comment-list network fetch failing, not a module problem: `ping 8.8.8.8` from the device succeeded (69–99ms RTT) confirming real internet connectivity, and the module's job (open the panel, block the like) is fully evidenced by the logcat/LSPosed-log markers independent of whether TikTok's comment list itself renders.

## Findings

None that block PASS. Minor observations for awareness, not defects in the diff:
- This TikTok/module install had never been enabled in LSPosed on this (recently rebuilt) device — that's environment setup, not a code issue, and is now done.
- Comment-list content failed to fetch server-side during testing; unrelated to the hook (confirmed real network connectivity via ping) and not something the module controls.

## Confidence

High. All three levels (static, build, device) are positive, and the device-level evidence is the least ambiguous kind: the exact new candidate name (`cc0`) appears in the success log line, tied to two different videos' correct `aid`s, with the like counter/icon unchanged both times.
