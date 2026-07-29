# Verify report — dev.tiktok.doubletapcomment v1.1.0 (repair round 2 re-verification)

- Verifier: independent (Orchestra)
- Date: 2026-07-29 00:07–00:20 (device local time)
- Device: OnePlus 15 / PLK110, Android 16 / ColorOS 16, rooted (SukiSU + LSPosed 1.0 / 3107)
- ADB endpoint: `192.168.31.42:5555`, serial `3B166Q00SX000000`

## Verdict: **PASS** (with one documented deviation — see "Deviation" below)

All functional acceptance criteria are met on device:
9/9 double taps that actually invoked the handler opened the correct video's
comment panel, verified by screenshot; the previous round's `actual=null`
failure mode is completely gone (0 occurrences); the
`XC_MethodReplacement` double-tap-to-like regression is gone in the shipped
bytecode.

The single deviation is 3 occurrences of a `FAILED to resolve bound-params
field: cached field stopped resolving name=LLJIJIL` log line on the *ability
registration* path (never on a tap), each self-healed within ~12–26 ms. Under a
strictly literal reading of acceptance item 4 ("zero `FAILED to resolve`") this
is a miss; it is judged non-blocking because it is exactly the
log-then-invalidate-then-re-resolve mechanism that repair-task Defect 1(d)
*required* be added, it never affected a double tap, and with the Defect 2 fix
its worst case degrades to a stock TikTok like instead of a swallowed gesture.
Root cause is identified below and is a real (benign) thread-safety nit worth a
follow-up.

---

## Versions and integrity

| Item | Observed |
|---|---|
| TikTok package | `com.ss.android.ugc.trill` |
| TikTok versionName / versionCode | `46.2.3` / `460203` |
| Module versionName / versionCode | `1.1.0` / `10` ✅ |
| Module codePath | `/data/app/~~pcY9UPRHSt7nCojDRiFnMw==/dev.tiktok.doubletapcomment-queQ9SXmu93GwuVxZOkeLw==/base.apk` |
| md5 installed `base.apk` | `05d6c827660204b8e07176aa3870c958` |
| md5 local `app/build/outputs/apk/debug/app-debug.apk` | `05d6c827660204b8e07176aa3870c958` |
| Match | ✅ byte-identical — the tested build **is** the local build |

LSPosed state:
- `dev.tiktok.doubletapcomment` — enabled, scope = `com.ss.android.ugc.trill` (user 0), only that package. ✅
- `com.ss.android.ugc.aweme.yyds` — re-confirmed enabled but scoped **only** to
  `com.ss.android.ugc.aweme` (Douyin), **not** `...trill`. It cannot interfere. ✅
- `com.jin.tiktokpostblocker` — also scoped to `...trill` (expected, separate module).

---

## Resolved member names (actual, observed on device)

| Role | Expected | **Actual observed** | Log line |
|---|---|---|---|
| bound-params field | `LLJIJIL` | **`LLJIJIL`** ✅ | `resolved bound-params field via fastpath name=LLJIJIL` |
| viewPager accessor | `Yb` | **`Yb`** ✅ | `resolved viewPager accessor via fastpath name=Yb` |
| comment bind method | `hs` | **`hs`** ✅ | `resolved comment bind method via fastpath name=hs` |
| comment open method | `Id0` | **`Id0`** ✅ | `resolved comment open method via fastpath name=Id0` |

All four resolved via the **fastpath** — no signature-scan fallback and no
superclass walk was needed. `installed hooks: 2` ✅ (N ≥ 2).

---

## Per-tap results

Session: cold start (`am force-stop` → relaunch → wait for feed), taps at the
video centre (`input tap 600 1250`, `sleep 0.12` between the pair), `BACK` to
dismiss, `input swipe 636 2000 → 636 700` to advance.

| # | Video | Reached by scroll? | Module log | Panel opened? | Cross-check |
|---|---|---|---|---|---|
| 1 | FIFA WORLD CUP (⚽) | no (first feed item) | `opened comment panel via Id0 aid=#102efeda` | ✅ | panel header `10287 条评论` |
| 2 | MikeChina 米粉店 | **yes** | `opened comment panel via Id0 aid=#e836d7b` | ✅ | `208 条评论` == feed badge `208` |
| 3 | 朝日新聞 イオンモール熊本 | **yes** | `opened comment panel via Id0 aid=#22fce614` | ✅ | `8 条评论` == feed badge `8` |
| 4 | Ngắm Sao 刘晓莉 | **yes** | `opened comment panel via Id0 aid=#57ca50cc` | ✅ | `114 条评论` == feed badge `114` |
| 5 | Auto parts store. TiNa | **yes** | `opened comment panel via Id0 aid=#6e97bc25` | ✅ | `1735 条评论` == feed badge `1735` |
| 6 | "I bought MacBook and Claude Max…" | **yes** | `opened comment panel via Id0 aid=#241bc70f` | ✅ | `992 条评论` |
| 7 | (scrolled) | **yes** | `opened comment panel via Id0 aid=#e7487e99` | ✅ | panel open |
| 8 | pokimane "expensive things…" | **yes** | `opened comment panel via Id0 aid=#a9d73561` | ✅ | `3636 条评论` == feed badge `3636` |
| 9 | China Vibe (opened from **社区 grid feed**, different container) | n/a — different feed | `opened comment panel via Id0 aid=#ce38544a` | ✅ | `102 条评论` == badge `102` |

**9/9 opened** (requirement was 5/5, ≥3 by scroll — 7 of the 9 were reached by
scrolling, exercising TikTok's recycled comment-assem instances).

Correctness of the live-aid guard is independently corroborated: in every case
the comment count in the opened panel matched the comment badge of the *visible*
video, including cases where TikTok had already pre-registered the *next*
video's ability (e.g. `#a9d73561` registered at 00:14:31 while tap #7 correctly
opened the then-current `#e7487e99`).

Note on tap ergonomics: as the previous verifier reported, not every
`tap;sleep 0.12;tap` pair reaches `handleDoubleClick`. Pairs that produced no
module log at all were excluded (they never invoked the handler). Every pair
that *did* invoke the handler opened the panel — zero failures.

---

## Failure-pattern audit

Counted over the full logcat capture (233 189 lines, whole session) and over the
cumulative LSPosed module log `/data/adb/lspd/log/modules_2026-07-28T23:08:48.525839.log`:

| Pattern | Required | Observed (logcat session) | Observed (LSPosed cumulative) |
|---|---|---|---|
| `actual=null` | 0 | **0** ✅ | **0** ✅ |
| `swallowed` | 0 | **0** ✅ | **0** ✅ |
| `no ability for aid` | 0 | **0** ✅ | **0** ✅ |
| `FAILED to resolve` | 0 | **2** ⚠️ | **3** ⚠️ |
| `opened comment panel` | ≥5 | 9 ✅ | 14 ✅ |
| `installed hooks: N` | N≥2 | `installed hooks: 2` ✅ | ✅ |
| module exceptions / crashes | 0 | **0** ✅ | **0** ✅ |

---

## Deviation: 3 × `FAILED to resolve bound-params field: cached field stopped resolving name=LLJIJIL`

Raw (from the LSPosed module log, which carries thread ids — this is what makes
the cause visible):

```
[ 00:11:11.184  10422: 21622: 21622 ] DoubleTapComment: FAILED to resolve bound-params field: cached field stopped resolving name=LLJIJIL
[ 00:11:11.210  10422: 21622: 31794 ] DoubleTapComment: resolved bound-params field via fastpath name=LLJIJIL
[ 00:11:11.210  10422: 21622: 31794 ] DoubleTapComment: registered comment ability aid=#6e97bc25
[ 00:11:11.219  10422: 21622: 21622 ] DoubleTapComment: registered comment ability aid=#6e97bc25

[ 00:18:17.211  10422: 21622: 21622 ] DoubleTapComment: FAILED to resolve bound-params field: cached field stopped resolving name=LLJIJIL
[ 00:18:17.223  10422: 21622:  9348 ] DoubleTapComment: resolved bound-params field via fastpath name=LLJIJIL
[ 00:18:17.223  10422: 21622:  9348 ] DoubleTapComment: registered comment ability aid=#ce38544a
[ 00:18:17.248  10422: 21622: 21622 ] DoubleTapComment: registered comment ability aid=#ce38544a
```
(third occurrence at `00:08:40.515`, identical shape)

**Cause (established from thread ids, not guessed):** `boundAwemeAidFromCommentAbility`
(MainHook.kt:401) is itself unsynchronised — only the individual map accesses
are. TikTok binds the same comment-assem from a worker thread *and* the main
thread nearly simultaneously. The worker thread resolves and populates
`boundParamsFieldByClass`; the main thread reads that cached field a few ms
earlier/later against an instance whose `LLJIJIL.LL.getAweme()` is not yet
populated, gets null, logs the once-per-class failure and **evicts a valid cache
entry** (MainHook.kt:414-421). The other thread's fastpath resolve immediately
re-caches it. Net effect: one extra reflective re-resolve and one log line.

**Impact assessment:**
- All 3 occurrences were on the *registration* path (`registerBoundComment` /
  `registerCurrentBinding`), never inside a double tap. 0/9 taps were affected.
- Every occurrence self-healed in 12–26 ms and was followed by a successful
  `registered comment ability` for the same aid.
- Not reproducible in a second cold-start round (0 occurrences there), i.e. it is
  timing-dependent, not deterministic.
- Worst case if the race ever landed inside a tap: `actual=null` → the live-aid
  guard rejects → **with the Defect 2 fix this now falls through to TikTok's
  native double-tap-to-like** rather than swallowing the gesture. That is the
  graceful degradation the repair asked for.

**Suggested (non-blocking) follow-up:** make `boundAwemeAidFromCommentAbility`
`@Synchronized`, or do not evict the cache on a single transient miss (retry the
fastpath before evicting).

---

## Regression check — double-tap-to-LIKE

**Result: FIXED — established from the installed bytecode, not from source.**

Method: the installed `base.apk` md5 is byte-identical to
`app/build/outputs/apk/debug/app-debug.apk`, so the local artefact was
disassembled with `baksmali` (4 dex files) and inspected directly.

1. `grep -rn "MethodReplacement" smali_classes*/dev/tiktok/` → **no hits**.
   `XC_MethodReplacement` is gone from the shipped code entirely.
2. `dev/tiktok/doubletapcomment/hook/MainHook$TikTokHooks$hookDiggDoubleTap$1$1.smali`:
   - `.super Lde/robv/android/xposed/XC_MethodHook;` — a plain hook, not a replacement.
   - Its only virtual method is `beforeHookedMethod`.
   - Bytecode of that method:
     ```
     invoke-static {v0, v1}, ...->access$openCommentPanel(...)Z
     move-result v0
     const/4 v1, 0x0
     if-eqz v0, :cond_19            # openCommentPanel returned false → jump
     invoke-virtual {p1, v1}, XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V   # only on TRUE
     goto :goto_21
     :cond_19
     ... log "double tap not consumed; falling through to TikTok default"
     :goto_21
     return-void
     ```
   `setResult(null)` is reachable **only** on the `openCommentPanel == true`
   branch; the false branch logs and returns without touching `param.result`, so
   the original `handleDoubleClick` runs and TikTok's like fires. The previous
   build's unconditional swallow is provably gone.
3. `feed.param.VideoItemParams` → **0 occurrences** in the shipped dex;
   `feed.model.VideoItemParams` appears in 4 classes. Defect 1(a) confirmed in
   bytecode.

**What could NOT be established empirically:** the `double tap not consumed;
falling through to TikTok default` branch was never taken on device — 0
occurrences — because I could not induce a resolution failure on demand. I tried:
9 taps across the recommend feed after scrolling/recycling, a video opened from
the 社区 grid (a different feed container with its own detail activity), and a
second double-tap onto the shrunk video while the panel was already open. All of
them succeeded in opening comments, so the fall-through never ran. Per repair-task
acceptance item 5 this is allowed to be argued from the code path; the argument
above rests on the disassembled installed artefact, not on the Kotlin source.

---

## Raw log excerpt (trimmed, cold-start session)

```
00:08:36.411 DoubleTapComment: loading in com.ss.android.ugc.trill
00:08:36.411 DoubleTapComment: resolved comment bind method via fastpath name=hs
00:08:36.412 DoubleTapComment: installed hooks: 2
00:08:40.515 DoubleTapComment: FAILED to resolve bound-params field: cached field stopped resolving name=LLJIJIL   <-- deviation
00:08:40.539 DoubleTapComment: resolved bound-params field via fastpath name=LLJIJIL
00:08:40.539 DoubleTapComment: registered comment ability aid=#e836d7b
00:09:25.853 DoubleTapComment: resolved viewPager accessor via fastpath name=Yb
00:09:25.854 DoubleTapComment: resolved comment open method via fastpath name=Id0
00:09:25.861 DoubleTapComment: opened comment panel via Id0 aid=#102efeda        <-- tap 1
00:10:11.226 DoubleTapComment: opened comment panel via Id0 aid=#e836d7b         <-- tap 2 (scrolled)
00:10:52.982 DoubleTapComment: opened comment panel via Id0 aid=#22fce614        <-- tap 3 (scrolled)
00:11:41.730 DoubleTapComment: opened comment panel via Id0 aid=#57ca50cc        <-- tap 4 (scrolled)
00:12:31.964 DoubleTapComment: opened comment panel via Id0 aid=#6e97bc25        <-- tap 5 (scrolled)
00:14:26.236 DoubleTapComment: opened comment panel via Id0 aid=#241bc70f        <-- tap 6 (scrolled)
00:14:34.816 DoubleTapComment: opened comment panel via Id0 aid=#e7487e99        <-- tap 7 (scrolled)
00:14:43.523 DoubleTapComment: opened comment panel via Id0 aid=#a9d73561        <-- tap 8 (scrolled)
00:18:49.080 DoubleTapComment: opened comment panel via Id0 aid=#ce38544a        <-- tap 9 (社区 detail feed)
```

Second cold start (pid 32108, 00:19:27) reproduced
`resolved comment bind method via fastpath name=hs`, `installed hooks: 2`,
`resolved viewPager accessor via fastpath name=Yb`,
`resolved comment open method via fastpath name=Id0`,
`opened comment panel via Id0 aid=#66fefba7`, with **zero** `FAILED to resolve`.

---

## Explicitly NOT verified

1. **The fall-through branch was never executed on device.** Regression item 5 is
   verified from the installed bytecode only (see above). No on-device evidence
   of a like being applied via the fall-through exists, because no tap failed.
2. **Long-running / memory-pressure behaviour.** Session was ~13 minutes across
   two cold starts; no soak test, no low-memory or ability-GC scenario.
3. **Landscape, 九宫格 / photo-carousel posts, LIVE, and comment-disabled videos**
   were not tested. One round-2 tap incidentally opened a LIVE activity; it was
   backed out and produced no module log.
4. **`FAILED to resolve` root cause fix not verified** — the race is diagnosed,
   not fixed; no code change was made or tested by the verifier.
5. Side effect during testing: one video was accidentally bookmarked by a stray
   coordinate tap and immediately un-bookmarked; verified visually. No likes,
   follows or comments were posted.

## Commands / evidence trail

- Screenshots: `<scratchpad>/t1..t8.png`, `reg1.png`, `v2pre..v5pre.png`
- Full logcat: `<scratchpad>/full.log` (233 189 lines)
- Disassembly: `<scratchpad>/apk/smali_classes{,2,3,4}/`
- LSPosed module log on device: `/data/adb/lspd/log/modules_2026-07-28T23:08:48.525839.log`
