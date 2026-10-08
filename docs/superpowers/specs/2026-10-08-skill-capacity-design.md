# 技能容量同步设计

日期：2026-10-08；修订：R0；mode=default；分支 codex/skills-version-console；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443。

## 范围与决定

D1 已确认：用户明确选择同时提交手工容量调整，并先同步测试与配置文档。沿用这些值，不再扩大或缩小额度；补充证据而不把之前的旧值测试冒充当前通过。

Server 默认 ZIP 4 MiB/硬上限 16 MiB，文件 256/512，单资源 256 KiB/1 MiB，单 Run 累计读取及准备预算 16 MiB/16 MiB。解压总量 4 MiB、快照准备 8 MiB、尝试次数 32 保持；外部配置仍需满足校验。上传还受 multipart 容器限制约束。

独立解析器 SkillPackageLimits.defaults() 的保守历史值保留；服务端通过配置快照传入。不同默认值的职责已写入注释与生产配置文档。累计预算测试模拟多份资源均在单资源上限内、整体超过旧 256 KiB；验证新默认放行首次准备，管理员收紧后第二次达到精确边界，第三次仍拒绝且不进入后端。

非目标：改变模型上下文长度、无限执行、放宽权限/租户/审计或扩展资源类型；不部署或写真实凭据。

验收：新默认和硬边界测试通过，旧 64 KiB 断言失败消除，沙箱预算仍累计，原版 docx/pptx/pdf 在两库导入通过；显式暂存本轮路径，不把私有 application 配置或工具目录加入 Git。

## 关联产物

当前验收证据：Java 119+3 项及 Node 111 项通过；当前代码、测试与配置表一致。原版包在两库导入成功，不能据此认定 Python 运行依赖或真实模型执行已验证。

- [清单](../checklists/2026-10-08-skill-capacity-checklist.md)
- [提示词](../prompts/2026-10-08-skill-capacity-prompt.md)
- [计划](../plans/2026-10-08-skill-capacity.md)
- [实现说明](../implementation/2026-10-08-skill-capacity-implementation-design.md)
- [账本](../progress/2026-10-08-skill-capacity-ledger.md)
