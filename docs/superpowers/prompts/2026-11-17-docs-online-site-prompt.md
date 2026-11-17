# docs 在线化站点执行提示词

- 日期：2026-11-17
- 主题：`docs-online-site`
- 修订：R0
- 工作模式：`mode=default`

本提示词可由新的执行者按当前仓库状态继续推进。生成提示词本身不构成推送、部署或回滚授权。

```text
仓库：F:/java/cm-agent
需求：使用 GitHub Pages 与 MkDocs Material 发布 docs，保持现有文档不迁移、不重写，先完成配置和本地构建验证，暂不推送。
当前模式：mode=default。未确定事项自主取舍并写入账本，不要求逐项确认。

开始前读取：
- F:/java/cm-agent/docs/superpowers/checklists/2026-11-17-docs-online-site-checklist.md
- F:/java/cm-agent/docs/superpowers/specs/2026-11-17-docs-online-site-design.md
- F:/java/cm-agent/docs/superpowers/plans/2026-11-17-docs-online-site.md
- F:/java/cm-agent/docs/superpowers/prompts/2026-11-17-docs-online-site-prompt.md
- F:/java/cm-agent/docs/superpowers/implementation/2026-11-17-docs-online-site-implementation-design.md
- F:/java/cm-agent/docs/superpowers/progress/2026-11-17-docs-online-site-ledger.md

执行范围：
1. 以 docs 为唯一 MkDocs 内容源，不迁移或重写现有 Markdown。
2. 维护根目录 mkdocs.yml、requirements-docs.txt、scripts/mkdocs_docs_links.py 和 .github/workflows/docs.yml。
3. 使用 Material 主题、中文搜索、Unicode 标题 slug 和 Mermaid。
4. docs 外源码、脚本、根目录文档和无索引目录链接只在构建期映射到 GitHub。
5. 构建产物写入已忽略的 dist/docs-site，不在仓库创建虚拟环境。
6. 严格构建成功后启动本地预览，检查首页、搜索、Mermaid 和响应式布局。

边界与安全：
- 不修改现有文档正文、入口导航或历史状态。
- 不提交、不推送、不启用 Pages、不部署线上站点。
- 不复制源码、脚本、凭据或生产配置到 docs。
- 不降低仓库既有权限、安全和多租户约束。
- 保留工作树中与本次无关的已有修改。

验证与记录：
- 运行 mkdocs build --strict，要求退出码为 0 且警告数为 0。
- 检查页面数、首页、搜索索引、中文锚点、站外链接和 Mermaid 输出。
- 运行本地预览并检查桌面与移动视口。
- 状态、命令结果、证据、遗留问题和未提交状态只写入进度账本。
- 如果构建失败，优先调整配置或构建钩子；确需修改现有文档时停止并说明冲突。

完成后按项目约定输出：变更摘要、验证命令与结果、影响范围、风险与注意事项、后续建议。
```

## 关联产物

- [清单](../checklists/2026-11-17-docs-online-site-checklist.md)
- [设计](../specs/2026-11-17-docs-online-site-design.md)
- [计划](../plans/2026-11-17-docs-online-site.md)
- [实现说明](../implementation/2026-11-17-docs-online-site-implementation-design.md)
- [进度账本](../progress/2026-11-17-docs-online-site-ledger.md)
