# Repair round 2 — FAIL on device: wrong VideoItemParams FQN + double-tap-to-like regression

## Verdict on the previous round
**FAILED on-device verification.** 10 double taps, 0 opened the comment panel,
10/10 `double tap swallowed`, every one logging
`blocked mismatched comment ability; expected=#<aid> actual=null`.

The superclass walk and the element-type work from round 1 are correct and must
be kept. Two separate defects remain.

---

## Defect 1 (root cause of the FAIL) — `VIDEO_ITEM_PARAMS_CLASS` is wrong

```kotlin
private const val VIDEO_ITEM_PARAMS_CLASS = "com.ss.android.ugc.aweme.feed.param.VideoItemParams"
```

That class **does not exist**. Verified against the decompiled 46.2.3 tree: the
only `VideoItemParams` is

```
com.ss.android.ugc.aweme.feed.model.VideoItemParams
```

(`.model.`, not `.param.`) — confirmed both by the import line in
`VideoCommentAssem.java` (`import com.ss.android.ugc.aweme.feed.model.VideoItemParams;`)
and by the decompiled file's location.

Failure chain:
1. `findClass(VIDEO_ITEM_PARAMS_CLASS, classLoader)` returns `null`.
2. `videoItemParamsFromOuterField` hits `?: return null` — for **every** field.
3. So the fast path (`LLJIJIL`, `LLJI`) **and** the entire superclass walk both
   return null on every probe.
4. `boundAwemeAidFromCommentAbility` returns null → the live-aid guard in
   `invokeCommentOpenIfMatches` sees `actual=null` → rejects → `Id0` is never
   invoked.

The same wrong constant also disables the bind-method signature fallback
(`videoItemParamsClass == null` → `emptyList()`); that went unnoticed only
because the `hs` fast path happened to hit.

### Required change

**(a) Correct the constant** to `com.ss.android.ugc.aweme.feed.model.VideoItemParams`.

**(b) Remove the hard dependency on it for field validation.** This refactor
exists to stop single hardcoded names from breaking the module — a hardcoded FQN
is exactly that failure mode, and the pre-refactor code did not need the class at
all. Validate the candidate **by behaviour** instead:

In `videoItemParamsFromOuterField`, a candidate `.LL` value is accepted when
`callMethodQuiet(candidate, "getAweme")` returns non-null **and**
`aidFromAweme(thatAweme)` yields a non-blank String. Keep the FQN check only as
a cheap pre-filter used **when** `findClass` succeeds; when `findClass` returns
null, fall through to the behavioural check rather than returning null.

Note `aidFromVideoItemParams` already works purely reflectively
(`callMethod(videoItemParams, "getAweme")`), so no class object is needed
anywhere on this path.

**(c) Bind-method signature fallback**: when `findClass(VIDEO_ITEM_PARAMS_CLASS)`
returns null, do not give up — match single-parameter methods whose parameter
type's simple name is `VideoItemParams`.

**(d) Logging gap.** The on-device log contained **neither**
`resolved bound-params field` **nor** `FAILED to resolve bound-params field`,
even though resolution failed on every call — so the "no silent swallowing" rule
was violated on precisely the leg that broke. Guarantee an outcome log:
- Log once per ability class when the `VideoItemParams` class itself cannot be
  resolved (`FAILED to resolve VideoItemParams class name=<fqn>`).
- Ensure every `return null` exit of `boundAwemeAidFromCommentAbility` other than
  the `commentAbility == null` guard is covered by a once-per-class failure log,
  including the cached-field path (a cached field that later stops resolving must
  log, then invalidate the cache entry so the next call re-resolves).

---

## Defect 2 — regression: double-tap-to-like is dead

`hookDiggDoubleTap` installs an `XC_MethodReplacement` on
`DiggPanelComponent.handleDoubleClick` and returns `null` unconditionally. When
`openCommentPanel` fails, the original method never runs, so TikTok's own
double-tap-to-like is destroyed too. On-device evidence: like counts unchanged
across all 10 taps. **The current build is strictly worse than not installing the
module** — a user who double-taps gets neither comments nor a like.

### Required change
Replace `XC_MethodReplacement` with `XC_MethodHook` and consume the event only
on success:

```kotlin
object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (openCommentPanel(param.thisObject)) {
            param.result = null   // consumed: skip TikTok's like handling
        } else {
            log("double tap not consumed; falling through to TikTok default")
            // do NOT set param.result → original handleDoubleClick runs → like still works
        }
    }
}
```

Setting `param.result` in a before-hook skips the original method; leaving it
unset lets the original run. This makes every future resolution failure degrade
to stock TikTok behaviour instead of breaking the gesture.

---

## Do NOT change / do NOT regress
- The round-1 superclass walk in `boundAwemeAidFromCommentAbility` (leaf→root,
  skip static, dedupe by name) — keep it.
- The per-`ClassLoader` `VideoItemParams` cache — keep it, just fix what it caches.
- `CommentAbilityRegistry.findByLiveAid`, the live-aid correctness re-check
  (a real mismatch must still be rejected — only the *unreadable* case changes),
  `DIAGNOSTIC_HOOKS = false`.
- Fast-path name lists `Yb`/`hs`/`Id0`/`LLJIJIL` — all four were confirmed
  correct against 46.2.3 bytecode; `Yb` and `hs` were observed resolving on
  device.
- Version `1.1.0` / versionCode `10`, README, and any docs already updated.

## Machine-checkable acceptance
1. `./gradlew :app:assembleDebug` succeeds.
2. `grep -c "feed.param.VideoItemParams" MainHook.kt` → **0**.
3. `MainHook.kt` no longer uses `XC_MethodReplacement` for `handleDoubleClick`.
4. On device (TikTok 46.2.3, module enabled and scoped to
   `com.ss.android.ugc.trill`), logcat grepped for `DoubleTapComment` shows:
   - `resolved bound-params field via fastpath name=LLJIJIL`
   - 5/5 double taps across scrolled/recycled videos →
     `opened comment panel via Id0 aid=#…`, panel visibly open
   - zero `swallowed`, zero `actual=null`, zero `FAILED to resolve`
5. Double-tap-to-like still works when the module deliberately cannot open
   comments (may be argued from the code path if not directly testable).

## Files
- `app/src/main/java/dev/tiktok/doubletapcomment/hook/MainHook.kt`
