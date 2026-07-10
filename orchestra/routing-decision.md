# Routing Decision

- **档位**: 质量优先 (`codex -m gpt-5.5`, Verifier = Opus)
- **判档理由**: 这是核心反射式 Hook 逻辑,直接决定模块随 TikTok 每次更新是否存活;方法名
  定位错误会导致模块整体失效且难以从日志之外的渠道发现,复杂度和风险都高于普通开发,不用
  默认成本档。
- **研究外包**: 不派。所需的方法名/类名定位已经在本次会话的 Phase 1(本地 jadx 反编译 +
  静态分析)里完成,写在 `docs/reverse-engineering-45.9.3.md`,是本地文件级分析,不属于
  GitHub/网络调研范围,不需要 `github-solution-research` 或 `orch-research.sh`。
