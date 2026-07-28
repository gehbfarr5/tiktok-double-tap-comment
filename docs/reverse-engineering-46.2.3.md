# TikTok 46.2.3 Reverse Engineering Notes (Double Tap Comment)

Date: 2026-07-28

## Target
- Package: `com.ss.android.ugc.trill`, versionName `46.2.3`, versionCode `460203`
- Device: OnePlus 15 / PLK110 / Android 16
- `jadx 1.5.5 --no-res` decompile of pulled base.apk (159MB, 97599 classes,
  560 non-fatal errors). Uncommitted scratchpad.

## Method
All anchor classes exist by full name (`DiggPanelComponent`,
`VideoCommentAssem`, `IVideoCommentAbility`, `IViewPagerComponentAbility`).
Only member names rotated. Same method as `reverse-engineering-46.0.3.md`.

## Summary — every rotating name changed; the module is fully broken on 46.2.3

| Role | 46.0.3 | **46.2.3** |
|---|---|---|
| comment bind method | `up(VideoItemParams)` | **`hs(VideoItemParams)`** |
| comment open method | `Ob0()` | **`Id0()`** |
| bound-params field | `LLJI` → `.LL` | **`LLJIJIL`** → `.LL` |
| viewPager accessor | `Qb()` | **`Yb()`** |
| `handleDoubleClick(MotionEvent)` | — | **unchanged** |
| `IViewPagerComponentAbility.LJIIIIZZ()` → `Aweme` | — | **unchanged** |

## Findings — video resolution chain (digg double-tap → current Aweme)
- `DiggPanelComponent.handleDoubleClick(MotionEvent)` — **unchanged** (hook
  target stable across 4 releases).
- viewPager accessor: `public final IViewPagerComponentAbility Yb()` — rotated
  from `Qb`. **Not** in the module's candidate list
  `firstMethodResult(diggComponent, "Ub","Qb","zb")` → this leg is broken.
  Note the return type `IViewPagerComponentAbility` is a stable full class name,
  so this accessor is uniquely resolvable by return type at runtime.
- `IViewPagerComponentAbility.LJIIIIZZ()` (returns `Aweme`) — **unchanged**,
  already in `firstAwemeAidFrom(..., "LJIIIIZZ", ...)`. Direct current-aweme
  shortcut still valid. (Two other `Aweme`-returning no-arg members now exist:
  `LLLLLLLLLL()` and `j61()`, so return type alone is not unique here — keep
  `LJIIIIZZ` first in the name list.)
- current-cell accessor: old `LJLIIL()` is gone; the cell element type rotated
  `InterfaceC734110Sqw` → `InterfaceC1657806fI`, exposed via several members
  (`H()`, `J()`, `LJLILLLLZI()`, `Lq2()`, `lT()`). Since the direct
  `LJIIIIZZ()` shortcut still works, the cell-accessor fallback leg is not on
  the critical path — leave the existing name list, do not chase these.

## Findings — comment ability binding (VideoCommentAssem)
- `onParentSet()` and `onViewCreated(View)` — **unchanged**.
- Bind method `Nq`→`Xq`→`br`→`up` → **`hs`** (46.2.3):
  ```java
  public final void hs(VideoItemParams videoItemParams)
  ```
  Still the **sole** method taking a bare `VideoItemParams` → uniquely
  resolvable by signature.
- Bound-aweme field path **changed**: `this.LLJI` → **`this.LLJIJIL`** (type
  `C028B`), then `.LL` cast to `VideoItemParams` → `getAweme()`. The inner `.LL`
  hop is unchanged (stable for 4 releases), but the outer field name rotated for
  the first time. `boundAwemeAidFromCommentAbility` hardcodes `LLJI` → broken.

  For the signature-based rewrite: resolve the outer field by **shape** — the
  field on `VideoCommentAssem` whose current value exposes an `LL` member
  holding a `VideoItemParams`. Do not hardcode `LLJIJIL`.

## Findings — comment open method (IVideoCommentAbility)
Interface reshaped again. Full current member list:
```java
public interface IVideoCommentAbility extends C03PR {
    void Id0();                              // ← no-arg void: the open method
    void XZ1(String str);
    boolean c00(float f, float f2);
    void jo2(int i);
    Rect nw2();
    void qg1(CharSequence charSequence, String str);
}
```
Implementation confirms the role:
```java
public final void Id0() { rq(2, LJJIJLIJ()); }   // reason code 2 → open comment panel
```
`rq` is the rotated `Xp`; the literal **reason code `2` is unchanged** across
releases, which is the strongest role signal. All of `Kb0`/`jc0`/`cc0`/`Ob0` are
gone from the interface.

`Id0()` is the **sole no-arg void member** of the interface → uniquely
resolvable by signature.

## Why it is fully broken on 46.2.3
Three independent legs failed simultaneously:
1. `Yb` unhooked → digg chain cannot reach the viewPager ability → current aid
   unresolvable ("double tap swallowed; current aweme aid unavailable").
2. Bind method rotated to `hs`, unhooked → registry never populated.
3. All four known open methods absent from the interface → even with an ability
   in hand, `invokeCommentOpenIfMatches` would fail.

Additionally `LLJI` → `LLJIJIL` breaks `boundAwemeAidFromCommentAbility`, which
disables both the live-aid recycled-cell resolution and the correctness
re-check.

## Retained from 46.0.3 — do not regress
The recycled-cell fix (`CommentAbilityRegistry.findByLiveAid`) is an
architectural correction, not a name adaptation: TikTok reuses a small pool of
comment-assem instances and rebinds them on scroll **without** re-invoking the
bind method, so the registry key goes stale while the live binding tracks the
current video. Keep `findByLiveAid` and keep the live-aid re-check inside
`invokeCommentOpenIfMatches`. Note this fix *depends* on the bound-params field
path resolving correctly — with `LLJI` stale, it silently degrades to "no
ability for aid".

## Confidence
High on all four. `hs` (sole bare-`VideoItemParams` method), `Id0` (sole no-arg
void on the interface, body `rq(2, …)` matching the old `Ob0`/`cc0` pattern),
`Yb` (sole `IViewPagerComponentAbility`-returning no-arg method), and
`LLJIJIL`→`LL`→`VideoItemParams` (read directly off the decompiled body at
lines 378/385/424). Runtime confirmation via `DoubleTapComment` logcat on device.
