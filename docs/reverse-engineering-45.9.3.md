# TikTok 45.9.3 Reverse Engineering Notes

Date: 2026-07-10

## Target

- Package: `com.ss.android.ugc.trill`
- versionName: `45.9.3`
- versionCode: `450903`
- Pulled via `pm path` + `adb pull` from the OnePlus 15 (PLK110), base.apk only
  (136MB; splits are feature-gated dynamic modules — camera/kakao/line/etc. —
  not relevant to feed/homepage code).
- Decompiled with `jadx 1.5.5` (`jadx -d jadx-base --show-bad-code -j4 base.apk`,
  ~223k units, finished with 2091 non-fatal errors, normal for an app this
  obfuscated). Output not committed (gitignored `reverse/` convention from the
  sibling Post Blocker project — kept locally under this session's scratchpad,
  not copied into this repo to avoid bloating it; re-run the same pull+jadx
  command against the current APK if this needs revisiting).

## Method

Confirmed all 5 previously-known anchor classes still exist **by full class
name** in 45.9.3 (no wholesale renaming):
`DiggPanelComponent`, `VideoCommentAssem`, `IVideoCommentAbility`,
`PublishTabProtocol`, `TabAbilityAssem`. Only *internal method names* rotated,
consistent with per-release ProGuard/R8 member obfuscation. Searched each
class's current method list, cross-referencing against the last known-good
names from `docs/repair-2026-06-27-tiktok-45.7.3.md` and the exhausted
candidate list already in the uncommitted `MainHook.kt` working tree (`Kb0`,
`jc0`, `Qb`, `zb`, `LJIIIIZZ`, `LLLLLLIL`, `l41`, `LJLIL`, `BR`, `LJLIIL` — all
confirmed failed on 45.9.3 per last night's log: `none of [Qb, zb] worked`).

Note on JADX "renamed from" comments: JADX sometimes displays a different
method name than what's actually in the dex bytecode (interface-collision
avoidance). Where this occurs it's called out explicitly below — the
**bytecode name is what must be used in the Xposed hook**, not JADX's display
name.

## Findings — Video Resolution Chain (digg double-tap → current Aweme)

`DiggPanelComponent.handleDoubleClick(MotionEvent)` — **unchanged**, same
class, same signature. Safe to keep as the hook target. Note: the method body
itself grew substantially since 45.7.3 (now includes duet-mode checks,
paid-collection checks, and a login gate before the original digg animation
runs) — full `XC_MethodReplacement` still works for our purposes (we only
want to suppress the like) but be aware TikTok added more gating logic here
that a full replace bypasses.

- Old `DiggPanelComponent.Qb()` / `.zb()` → **new: `DiggPanelComponent.Ub()`**
  (confirmed, `public final IViewPagerComponentAbility Ub()`, no rename
  comment — real bytecode name). Same role: returns the
  `IViewPagerComponentAbility` for the current feed cell.
- Old direct-Aweme shortcut candidates on the viewPagerAbility (`LJIIIIZZ`,
  `LLLLLLIL`, `l41`) — **`LJIIIIZZ()` still exists** on the
  `IViewPagerComponentAbility` interface itself
  (`Aweme LJIIIIZZ();` in `IViewPagerComponentAbility.java`), unchanged. This
  candidate never actually got tried last night because the outer `Qb`/`zb`
  call already failed first — once `Ub` is added as a candidate, this
  fallback should fire correctly without further changes.
- Old current-cell candidates (`LJLIL`, `BR`, `LJLIIL`) → **new:
  `IViewPagerComponentAbility.MR()`** (returns `C0S4Y`, confirmed on the
  interface). Then `C0S4Y.getAweme()` — **name unchanged**, confirmed present
  on the `C0S4Y` interface (`Aweme getAweme();`).

Recommended new candidate order (keep old names too, per this project's
established multi-version-fallback convention):
`firstMethodResult(diggComponent, "Ub", "Qb", "zb")`.

## Findings — Comment Ability Binding (VideoCommentAssem)

- `VideoCommentAssem.onParentSet()` and `.onViewCreated(View)` —
  **both unchanged**, same names/signatures. The existing fallback hooks on
  these two remain valid with no changes needed.
- Old `Nq(...)` / `Xq(VideoItemParams)` (bind method registering the
  ability) → **new candidate: bytecode name `br`**, displayed by JADX as
  `lp(VideoItemParams videoItemParams)` with an explicit
  `/* JADX INFO: renamed from: br */` comment — **the hook must target `"br"`,
  not `"lp"`**. Confirmed as the sole method on `VideoCommentAssem` taking a
  bare `VideoItemParams` parameter, overriding `InterfaceC690140R5i`.
- The old field-path used for `boundAwemeAidFromCommentAbility`
  (`getObjectField(commentAbility, "LLJI")` → `getObjectField(reusedScope,
  "LL")`) is **fully unchanged** — confirmed `this.LLJI` field of type
  `C708530Rqn`, and `c708530Rqn.LL` cast to `VideoItemParams`, used
  identically throughout `VideoCommentAssem.java`. No change needed to
  `boundAwemeAidFromCommentAbility`.

Recommended: add `hookAllAfter(commentClass, "br") { ... }` alongside the
existing `Nq`/`Xq` hooks (keep old ones per multi-version convention).

## Findings — Comment Open Method (IVideoCommentAbility)

The interface itself changed shape. Current `IVideoCommentAbility` members:
`Bs2()` (Rect), `LY(float, float)`, `cc0()`, `lX1(String)`,
`xe1(CharSequence, String)`, `xk2(int)`. **`Kb0` and `jc0` no longer exist on
this interface at all** — confirms last night's total failure
(`none of [Kb0, jc0] worked` was implicit; the interface doesn't even declare
them anymore).

- **`cc0()` is the new open-comment-panel method** — confirmed:
  `@Override // com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility
  public final void cc0() { nq(2, LJJIJIIJIL()); }` in `VideoCommentAssem`.
  No rename comment — `cc0` is the real bytecode name. Semantically matches:
  no-arg override of the exact interface the module already targets
  (`COMMENT_ABILITY_CLASS`), delegates to an internal `nq(int reason, View)`
  with a constant reason code (`2`), consistent with "open with source=2".
- Other interface members are plausibly related to a secondary tap-based open
  path (`Bs2()` returns bounds, `LY(float,float)` takes coordinates,
  matching a "get button rect, then simulate tap inside it" pattern) but
  **`cc0()` alone is sufficient and matches the existing call convention**
  (`XposedHelpers.callMethod(ability, method)`, no args) — no need to pursue
  the coordinate-based path unless `cc0()` turns out to have side conditions
  not visible from this reading.

Recommended: `COMMENT_OPEN_METHODS = listOf("Kb0", "jc0", "cc0")` (keep old
names for older TikTok builds per project convention).

## Confidence and Unresolved

High confidence on all four findings above — each is a direct, unambiguous
class/method-signature match cross-referenced against the semantic role
described in the 45.7.3 repair docs, not a guess. Not yet verified against a
live device (Phase 1 is decompile-only, no hook code was written or
installed this pass). Two things worth confirming during Phase 2/3
implementation, not blockers to starting:

1. Whether `cc0()` alone reliably opens the panel in all content types
   (the project's known past issue was photo/image content behaving
   differently from video — worth specifically testing that content type).
2. Whether `Ub()` can return `null` under any content types the way `Qb`/`zb`
   used to (the diff already logs `"none of [...] worked"` when all
   candidates return null, so this will self-report if it happens).
