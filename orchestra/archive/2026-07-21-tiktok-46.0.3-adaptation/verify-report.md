# Verify Report — TikTok 46.0.3 double-tap comment (project A)

Date: 2026-07-21 · Device: OnePlus 15 / PLK110 / 3B166Q00SX000000 · TikTok
`com.ss.android.ugc.trill` 46.0.3 (versionCode 460003)

## Result: PASS (after one repair round)

### Round 1 — up + Ob0
- Build/install OK; `installed hooks: 2`; `opened comment panel via Ob0` fired.
- Partial failure reported by user: worked on first videos, failed after
  scrolling. Log: digg chain resolved the current aid, but the aid→ability
  registry was frozen at ~6 entries that never matched the current aid
  (`double tap has no ability for aid=… registry=[…6 stable…]`).

### Repair — recycled-cell resolution
- Root cause: `up` (= `VideoCommentAssem.onBind`) fires on bind, but TikTok
  reuses a small pool of comment-assem instances and rebinds them on scroll
  without re-invoking `up`; registry keys go stale while the live binding tracks
  the current video.
- Fix: `CommentAbilityRegistry.findByLiveAid` — at double-tap, scan pooled
  abilities by their live bound aid (`LLJI.LL` → aweme → getAid) instead of the
  stale registration key.

### Round 2 — on-device machine evidence (DoubleTapComment log)
After scrolling 5+ videos to force recycling, every double-tap opened comments:
```
18:12:53 opened comment panel via Ob0 aid=#7117071e
18:13:47 opened comment panel via Ob0 aid=#5991f9f5
18:13:57 opened comment panel via Ob0 aid=#fd20408d
18:14:07 opened comment panel via Ob0 aid=#71a96f3a
18:14:17 opened comment panel via Ob0 aid=#1f80363c
```
5/5 across distinct recycled videos; zero `swallowed`/`no ability` post-fix.
Comment panel opening also confirmed visually (adb screencap: "136 条评论").
