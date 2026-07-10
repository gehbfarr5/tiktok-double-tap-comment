# Task: Fix TikTok double-tap-comment hook for TikTok 45.9.3

## Background

This LSPosed module hooks TikTok's feed double-tap gesture to open the comment
panel instead of triggering a like. It was last verified against TikTok
45.5.4/45.7.3. The device now runs TikTok 45.9.3
(`com.ss.android.ugc.trill`, versionCode 450903), where the previously-hooked
method names no longer resolve to the right behavior. Real-device testing on
2026-07-09 confirmed: double-tap still blocks the native like, but never opens
the comment panel (log: `none of [Qb, zb] worked on ...DiggPanelComponent` /
`double tap swallowed; comment ability unavailable`).

Fresh reverse engineering of the 45.9.3 APK (this session, decompiled via
jadx) is written up in `docs/reverse-engineering-45.9.3.md` — read it first,
it has the full detail and confidence notes behind every rename below.

## What to change

All in `app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt`.
This project's established pattern (see `docs/repair-2026-06-27-*.md`) is to
**add new candidate names alongside old ones** rather than replace them, so
the module keeps working across multiple TikTok versions. Follow that pattern
here too — do not delete the 45.5.4/45.7.3-era candidates.

1. Cell-resolver method used to get the currently-bound Aweme from the feed
   cell: old candidates `Qb`/`zb` (both confirmed dead on 45.9.3 by last
   night's log) — add new candidate `Ub()` on the same class
   (`DiggPanelComponent`, per the reverse-engineering doc) to the resolver
   candidate list.
2. A second cell-resolver path used candidates `LJLIL`/`BR`/`LJLIIL` on
   whatever interface backs the view-pager component — add new candidate
   `MR()` on `IViewPagerComponentAbility`, which returns `.getAweme()` same
   as the old candidates.
3. There's also a documented direct-Aweme shortcut, `LJIIIIZZ()`, that
   reportedly still exists on the interface in 45.9.3 but was never reached
   last night because the outer resolver call failed first. Confirm it's
   already wired as a candidate in the existing fallback chain (it may
   already be present, per the reverse-engineering doc's note that it just
   wasn't *reached*, not necessarily missing from the code) — if it's not
   already in the candidate list, add it.
4. Comment-ability binding refresh method: old candidates `Nq`/`Xq` — add
   new candidate whose **bytecode method name is `br`** (important: JADX's
   decompiled display name for this method is `lp` due to a rename
   collision artifact — the actual XposedHelpers hook target must be the
   string `"br"`, not `"lp"`; see reverse-engineering doc for why).
5. Comment-open method on `IVideoCommentAbility`: old candidates `Kb0`/`jc0`
   are **confirmed gone entirely** in 45.9.3 (not just renamed away from
   these specific names — the reverse-engineering doc found the interface
   itself no longer exposes anything under those names). Add new method
   `cc0()` to the `COMMENT_OPEN_METHODS` fallback list used by
   `invokeCommentOpenIfMatches` (this list already exists in the current
   working tree from prior WIP — extend it, don't replace).

## Constraints

- Do not remove or weaken the existing diagnostic hooks
  (`hookGestureDiagnostics`) — they were added specifically to help diagnose
  exactly this kind of version-drift failure and should stay.
- Do not add silent fallback/error-swallowing beyond what the existing
  `runCatching { ... }.getOrElse { log(...); false }` pattern already does —
  keep failures visible in the log with the same style of message
  (`"opened comment panel via $method aid=..."` / `"failed to invoke ...: ${it.message}"`).
- This is a targeted method-name update, not a redesign. Don't refactor
  surrounding structure beyond what's needed to add the new candidates.

## Acceptance (machine-checkable)

1. `./gradlew assembleDebug` succeeds and produces
   `app/build/outputs/apk/debug/app-debug.apk`.
2. `git diff` limited to `MainHook.kt` (plus trivial version bump in
   `README.md`/`build.gradle` if you choose to note the new supported
   version — optional, not required).
3. Do not touch `MainActivity.kt`, `docs/`, `.gitignore`, or any file outside
   the hook package.

## Files involved

- `app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt` (edit)
- `docs/reverse-engineering-45.9.3.md` (read only, reference)
- `docs/repair-2026-06-27-comment-binding-coverage.md`,
  `docs/repair-2026-06-27-tiktok-45.7.3.md` (read only, reference for the
  established multi-version-fallback pattern)
