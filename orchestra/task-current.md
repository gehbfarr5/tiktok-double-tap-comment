# Task — TikTok 46.2.3: signature-based double-tap comment hook

## Goal
Restore double-tap-opens-comments on TikTok `com.ss.android.ugc.trill` 46.2.3
(versionCode 460203), and make the hook survive future R8 member rotations
without a code change.

## Root cause
TikTok 46.0.3 → 46.2.3 rotated every remaining hardcoded name:

| Role | was | now |
|---|---|---|
| viewPager accessor on `DiggPanelComponent` | `Qb()` | `Yb()` |
| bind method on `VideoCommentAssem` | `up(VideoItemParams)` | `hs(VideoItemParams)` |
| open method on `IVideoCommentAbility` | `Ob0()` | `Id0()` |
| bound-params field on `VideoCommentAssem` | `LLJI` → `.LL` | `LLJIJIL` → `.LL` |

All four legs fail simultaneously, so the double tap is swallowed with no crash.

## Ground truth (already reverse-engineered — do NOT re-derive)
See `docs/reverse-engineering-46.2.3.md`. Key facts:

- `DiggPanelComponent.handleDoubleClick(MotionEvent)` — **unchanged**, keep as-is.
- `Yb()` is the **sole** no-arg method returning `IViewPagerComponentAbility`
  (a stable full class name) → uniquely resolvable by return type.
- `IViewPagerComponentAbility.LJIIIIZZ()` → `Aweme` — **unchanged**. Note two
  other no-arg `Aweme` members now exist (`LLLLLLLLLL()`, `j61()`), so return
  type is NOT unique here — keep `LJIIIIZZ` first in the existing name list and
  do not switch this leg to pure signature resolution.
- `hs(VideoItemParams)` is the **sole** method on `VideoCommentAssem` taking a
  bare `VideoItemParams` → uniquely resolvable by signature.
- `Id0()` is the **sole no-arg void member** of `IVideoCommentAbility` →
  uniquely resolvable by signature. Body: `rq(2, LJJIJLIJ())`; the literal
  reason code `2` is unchanged across releases.
- Bound-params field path: `this.LLJIJIL` (type `C028B`) → `.LL` cast to
  `VideoItemParams` → `getAweme()`. The inner `.LL` hop has been stable for 4
  releases; the outer field name rotated for the first time.
- The old cell accessor `LJLIIL()` is gone and the cell type rotated
  (`InterfaceC734110Sqw`→`InterfaceC1657806fI`). Since the direct `LJIIIIZZ()`
  shortcut still works, this fallback leg is **not** on the critical path —
  leave the existing name list alone, do not chase it.

## Change — `MainHook.kt`

Two-stage resolution (fast path by known name, then signature fallback) for the
three rotating members plus the field:

1. **viewPager accessor** (`TikTokReflect.currentAwemeAidFromDigg`): try names
   `Yb`, `Ub`, `Qb`, `zb`; if none resolves, scan `diggComponent`'s class for the
   sole no-arg method whose return type is assignable to
   `com.ss.android.ugc.feed.platform.panel.viewpager.IViewPagerComponentAbility`.

2. **bind method** (`hookCommentAbilityBinding`): try names `hs`, `up`, `br`,
   `Xq`, `Nq`; if none resolves, scan `VideoCommentAssem` for methods with
   exactly one parameter of type `VideoItemParams`
   (`com.ss.android.ugc.aweme.feed.param.VideoItemParams` — confirm the FQN from
   the existing code) and `hookAllAfter` each. Keep the existing `onParentSet`
   and `onViewCreated(View)` hooks unchanged.

3. **open method** (`invokeCommentOpenIfMatches`): try `Id0`, `Ob0`, `cc0`,
   `jc0`, `Kb0`; if none of those exists on the ability, resolve dynamically —
   scan the `IVideoCommentAbility` interface for no-arg `void` methods and call
   them. Preserve the existing correctness guard: the live bound aid must equal
   the expected aid **before** any invocation.

4. **bound-params field** (`TikTokReflect.boundAwemeAidFromCommentAbility`): try
   names `LLJIJIL`, `LLJI`; if neither yields a `VideoItemParams` via `.LL`,
   scan the assem's declared fields for one whose current value exposes an `LL`
   member holding a `VideoItemParams`, then read `getAweme().getAid()`. Cache the
   resolved field per class to avoid scanning on every call — this runs on the
   double-tap hot path and inside `findByLiveAid`'s loop.

## Do NOT regress
- Keep `CommentAbilityRegistry.findByLiveAid` and the recycled-cell resolution
  from 46.0.3 — it is an architectural fix, not a name adaptation. TikTok reuses
  a pool of comment-assem instances and rebinds on scroll **without** re-invoking
  the bind method, so registry keys go stale.
- Keep the live-aid re-check inside `invokeCommentOpenIfMatches`.
- Keep `DIAGNOSTIC_HOOKS = false`.

## Coding discipline (project rules — enforced at review)
- **No silent swallowing.** Each of the four resolutions must log its outcome:
  `resolved <role> via fastpath|signature name=<n>` or `FAILED to resolve <role>`.
  The whole point is that 46.2.3's breakage was invisible.
- Do not hardcode `LLJIJIL` outside the fast-path candidate list.
- Reuse existing helpers (`hookAllAfter`, `firstMethodResult`, `callMethodQuiet`,
  `log`); do not reimplement reflection plumbing.

## Machine-checkable acceptance
1. `./gradlew :app:assembleDebug` succeeds (JDK 17, `ANDROID_HOME` set); APK
   emitted.
2. `MainHook.kt` contains no standalone `hookAllAfter(commentClass, "up")`-style
   call sites for rotating names — names appear only inside candidate lists.
3. Install + force-stop TikTok; `logcat | grep DoubleTapComment` shows
   `installed hooks: 3` (or ≥2) and a `resolved` line for each of the four roles
   naming `Yb` / `hs` / `Id0` / `LLJIJIL`.
4. **Functional, on device**: 5 double-taps on 5 different videos including at
   least 3 reached by scrolling (to exercise recycled cells) — all 5 must log
   `opened comment panel via Id0 aid=#…` with the comment panel visibly open.
   Zero `swallowed` and zero `no ability for aid` lines.

## Files
- `app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt` (main)
- `app/build.gradle` (version bump to `1.1.0` / versionCode 10)
- `README.md` (Status: add 46.2.3)
