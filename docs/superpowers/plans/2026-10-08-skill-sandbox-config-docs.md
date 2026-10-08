# 技能沙箱配置文档对齐：计划

日期：2026-10-08；修订：R0；工作模式：`mode=default`。

T0：读取规范、当前分支与 HEAD，先查 CodeGraph；若索引未包含沙箱符号则直接核对当前 Server 配置类、可选 profile、端点服务与部署说明。对已有文件建立哈希基线以保护用户改动。

T1（依赖 T0）：在 `docs/configuration.md` 增加 profile、完整属性与显式环境变量目录、认证材料矩阵、完整密码连接示例和运行期优先级；修正 Skill 文本资源与 Python 执行关系。窄范围修改 README 和端点文档，仅新增总配置目录链接。建立本组六份记录，保留旧工作包。

T2（依赖 T1）：从配置类提取叶子属性、从 profile 提取显式环境变量并逐一检查目录覆盖；复核默认值及范围，检查相对链接与代码围栏，执行指定文档的差异/空白检查并比较非目标文件哈希。结果写入账本，再同步清单和实现说明。

本轮不运行 Maven、Docker、数据库或浏览器，因为没有运行行为变更；不引用旧通过作为本轮新验证。不更新发布说明，因为没有新增发布行为。

关联文档：[待办清单](../checklists/2026-10-08-skill-sandbox-config-docs-checklist.md) · [执行提示词](../prompts/2026-10-08-skill-sandbox-config-docs-prompt.md) · [设计](../specs/2026-10-08-skill-sandbox-config-docs-design.md) · [实现说明](../implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-sandbox-config-docs-ledger.md)。
