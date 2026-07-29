# Routing Decision — TikTok 46.2.3 adaptation + signature-based resolution (double-tap comment)

Date: 2026-07-28

## ① 判档理由

**默认档（质量优先）· Executor `codex -m gpt-5.6-sol` (medium) · Verifier = Opus.**

This is an **architecture change**, not a name patch: replacing hardcoded
obfuscated-name candidate lists (methods *and* a field path) with runtime
signature-based resolution, keeping the name lists as a fast path. Per the
routing table, 核心/架构/重构 → Verifier = Opus.

46.2.3 broke this module on three independent legs at once (viewPager accessor
`Qb`→`Yb`, bind method `up`→`hs`, open method `Ob0`→`Id0`) plus the bound-params
field `LLJI`→`LLJIJIL`. The field rotation is new — it had been stable for four
releases — which invalidates the assumption the previous adaptations rested on.
Failure mode is silent (double-tap gets swallowed, no crash), so build success
is not acceptance; on-device logcat evidence is required.

There is also a regression hazard worth Opus-level review: the 46.0.3
recycled-cell fix (`findByLiveAid`) **depends** on the bound-params field path
resolving correctly. A refactor that gets the field wrong degrades silently to
"no ability for aid" — the exact symptom the recycled-cell fix was written to
eliminate — and would look like a re-appearance of an old, already-solved bug.

## ② 研究外包决策

**不派。** The "research" is reverse engineering the pulled 46.2.3 `base.apk`
(jadx 1.5.5 decompile of the on-device APK). Local decompile analysis coupled to
device confirmation — not web research, not a GitHub-library search.
`orch-research.sh` (Antigravity) cannot read the local decompiled tree and its
quota is scarce; `github-solution-research` cannot help against per-release R8
member obfuscation of a closed-source app.

RE was completed in-session before dispatch; findings are ground truth in
`docs/reverse-engineering-46.2.3.md`, handed to the Executor as given facts so
it spends no budget re-deriving them.

## Executor decision

Executor = **Codex** (`orch-codex.sh`, `gpt-5.6-sol`, medium). The refactor spans
method resolution, field resolution, and the registry interaction in
`MainHook.kt` — real implementation work, unlike the 46.0.3 two-string patch that
was correctly executed Claude-direct.

Verifier = independent Opus sub-agent with on-device functional check
(5 double-taps across scrolled/recycled videos, logcat evidence).
