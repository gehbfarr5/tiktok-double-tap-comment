# Routing Decision — TikTok 46.0.3 adaptation (Double Tap Comment, project A)

Date: 2026-07-21

## ① 判档理由
**质量优先.** Obfuscation-sensitive RE of TikTok 46.0.3; correctness-critical
(wrong member name silently swallows the gesture). Verifier = on-device functional
check (double-tap opens comments, video not liked), not build success alone.

## ② 研究外包决策
**Not outsourced.** The research is reverse engineering the pulled 46.0.3 base.apk
(local jadx decompile) + on-device runtime confirmation via the module's
`DoubleTapComment` logcat — neither Antigravity (`orch-research.sh`, no local tree,
scarce quota) nor GitHub search helps against per-release R8 member obfuscation.
Done in-session per `docs/reverse-engineering-45.9.3.md` method. Findings in
`docs/reverse-engineering-46.0.3.md`.

## Executor decision
RE necessarily in-session (decompile + device coupled; Codex has neither). Result
is a 2-line addition of already-computed name strings (`up`, `Ob0`) to MainHook.kt.
Executor = Claude-direct (ROUTING fallback-chain member) — delegating a known-answer
patch to Codex only re-derives what is already resolved, worse for token economy.
Quality gate preserved: independent Verifier + on-device verification.
