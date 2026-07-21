# TikTok 46.0.3 Reverse Engineering Notes (Double Tap Comment)

Date: 2026-07-21

## Target
- Package: `com.ss.android.ugc.trill`, versionName `46.0.3`, versionCode `460003`
- Device: OnePlus 15 / PLK110 / Android 16
- `jadx 1.5.5 --no-res` decompile of pulled base.apk (136MB). Uncommitted scratchpad.

## Method
All 4 anchor classes exist by full name (`DiggPanelComponent`,
`VideoCommentAssem`, `IVideoCommentAbility`, `IViewPagerComponentAbility`).
Only member names rotated. Same method as `reverse-engineering-45.9.3.md`.

## Findings — Video resolution chain (digg double-tap → current Aweme)
- `DiggPanelComponent.handleDoubleClick(MotionEvent)` — **unchanged** (hook target
  stable).
- viewPager accessor: `public final IViewPagerComponentAbility Qb()` — current
  bytecode name is **`Qb`**, already in the module's candidate list
  `firstMethodResult(diggComponent, "Ub","Qb","zb")`. No change needed.
- `IViewPagerComponentAbility.LJIIIIZZ()` (returns `Aweme`) — **unchanged**,
  already in `firstAwemeAidFrom(..., "LJIIIIZZ", ...)`. Direct current-aweme
  shortcut still valid.
- current-cell accessor: `IViewPagerComponentAbility.LJLIIL()` (returns
  `InterfaceC734110Sqw`) — present, already in
  `firstMethodResult(viewPagerAbility, "MR","LJLIL","BR","LJLIIL")`. Then
  `getAweme()`/`getItem().getAweme()` semantic, unchanged.

→ The aid-resolution reflection chain already contains all current-valid names;
no edit required there (multi-version fallback lists happened to cover 46.0.3).

## Findings — Comment ability binding (VideoCommentAssem)
- `onParentSet()` and `onViewCreated(View)` — **unchanged**.
- Bind method `Nq`→`Xq`→`br` → **`up`** (46.0.3):
  `public final void up(VideoItemParams videoItemParams)` — sole method taking a
  bare `VideoItemParams`. **Add `hookAllAfter(commentClass, "up")`.**
- Bound-aweme field path **unchanged**: `this.LLJI` (type rotated
  `C708530Rqn`→`C721400SRn`) → `.LL` cast to `VideoItemParams` → `getAweme()`.
  `boundAwemeAidFromCommentAbility` needs no change (field names `LLJI`/`LL` stable).

## Findings — Comment open method (IVideoCommentAbility)
Interface reshaped again. Current no-arg void member is **`Ob0()`**:
```java
@Override // IVideoCommentAbility
public final void Ob0() { Xp(2, LJJIJL()); }   // reason code 2 → open comment panel
```
Old `Kb0`/`jc0`/`cc0` no longer exist on the interface. **Add `Ob0` to
`COMMENT_OPEN_METHODS`** (keep old names per convention).

## Summary of required edits (MainHook.kt)
1. `hookCommentAbilityBinding`: add `hookAllAfter(commentClass, "up") { … }`.
2. `COMMENT_OPEN_METHODS = listOf("Kb0","jc0","cc0","Ob0")`.
Everything else (digg chain, field path, onParentSet/onViewCreated) unchanged.

## Why it was fully broken on 46.0.3
Both legs failed: (a) all three old open methods `Kb0/jc0/cc0` gone from the
interface → `invokeCommentOpenIfMatches` always failed → "double tap swallowed";
(b) bind method rotated to `up`, unhooked → registry never populated → no ability
for aid. Adding `up` + `Ob0` restores both legs.

## Confidence
High on `up` (sole bare-`VideoItemParams` method) and `Ob0` (sole no-arg void
`@Override` of the targeted interface, `Xp(2, …)` matching the old `cc0` pattern).
Runtime confirmation via `DoubleTapComment` logcat on device.
