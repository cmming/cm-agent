# 技能沙箱配置文档对齐：设计

日期：2026-10-08；修订：R0；工作模式：`mode=default`。

背景：部署说明和当前代码已支持本地/远程 Docker 及 SSH KEY/PASSWORD，配置总目录未收录沙箱属性，仍有“服务端不会执行资源”的旧描述。

目标：建立部署可查阅的权威配置目录，区分执行策略、租户凭据管理与部署默认连接，突出最近账号密码新增的 `ssh-auth-type`、`password-file`。范围与清单一致，非目标为新增配置能力、变更默认值、重新实现端点或替换历史测试证据。

自主假设 A1：用户的“对齐”指更新配置说明及已有入口，不要求改动运行配置。依据是现有代码已交付能力，用户当前要求配置文档。本次没有未决交互决定。

方案：总配置文档列全 20 项叶子属性与 15 个可选 profile 显式环境别名；表格默认值依据配置类，范围依据 validate 和连接校验。提供完整 PASSWORD YAML 与 LOCAL/KEY/PASSWORD/TLS 材料矩阵。说明空 mode 的本地兼容、远程端口不自动默认、目标精确允许列表、主密钥独立性与租户默认优先级。README 与端点说明链接到目录，避免重复配置表。

安全约束：示例只使用 example.invalid 和部署 Secret 路径；不生成密钥或密码。KEY/TLS 不混入密码，PASSWORD 不混入私钥/证书；控制台 API 字段与部署属性分开。保留隔离、租户与权限边界。

验收：20 项属性与 15 个显式环境别名均可检索；默认值和取值范围吻合；旧执行说明修正；六份文档与入口链接有效；仅指定文档改变，暂存区和 HEAD 不变。

关联文档：[待办清单](../checklists/2026-10-08-skill-sandbox-config-docs-checklist.md) · [执行提示词](../prompts/2026-10-08-skill-sandbox-config-docs-prompt.md) · [计划](../plans/2026-10-08-skill-sandbox-config-docs.md) · [实现说明](../implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-sandbox-config-docs-ledger.md)。
