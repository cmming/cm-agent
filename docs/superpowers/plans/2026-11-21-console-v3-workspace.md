# V3 聊天工作区实施计划

日期：2026-11-21；主题：`console-v3-workspace`；修订：R0；工作模式：`mode=default`；任务：T0～T5。

## 实施顺序

1. **T0：建立当前契约。** 读取适用 AGENTS、console/server 模块 POM、README 和必要配置说明。先由 CodeGraph 定位，再针对未覆盖的 HTML、脚本和测试复核。盘点十一个页面、版本化导航、登录/退出/刷新、聊天与项目 DOM、共享客户端 API 和安全边界。记录不能直接复用的路径，不先复制独立 Site 的业务数据。
2. **T1：新增版本化外壳。** 在 `cm-agent-console/src/main/resources/META-INF/resources/console/v3/` 建立页面和必要样式/布局适配；共享脚本以显式 V2/V3 白名单、同版本 `returnTo` 和壳挂载/释放接缝处理版本。通过 ConsoleController 为 V3 根及固定页面短路径建立映射，不改变原有默认入口。直接 URL、同文档跳转、刷新与退出均按版本工作，保留 V2；V3 登录后默认聊天。
3. **T2：连接聊天与项目。** 将保留业务含义的项目、会话、Agent、输入与消息区域组合进新外壳，按需放置文件、运行、审批上下文。复用当前 SSE、草稿、停止锁、审批恢复、断线核对、文件引用及三种授权策略；必要适配集中在 V3 视图层，避免复制服务端判断。
4. **T3：迁入管理工作流。** 逐项核对能力总览、Agent、模型、工具、技能、运行、审计、本机客户端和本地项目入口；保留创建/编辑/删除、授权/调试、技能版本资源、运行详情、审计分页和设备配对等当前能力。依现有权限控制展示，所有写操作仍由真实服务端作最终判断。
5. **T4：按风险验证。** 先运行受影响 Node 测试及必要语法/资源检查，再在 JDK 21 下运行适用 console/server 测试。使用真实后端检查登录、跨页刷新、列表、会话以及可控的关键审批/管理操作；真实模型、项目/本机能力按环境条件验证。浏览器批量检查桌面与窄屏布局、输入、局部滚动、键盘、失败和空状态，并独立评审必要问题。Docker/JDBC/Flyway/Testcontainers 仅通过 `ssh rocky` 的项目容器执行，先核对待验源码。
6. **T5：文档与交付。** 更新实际 V3 入口、兼容入口、使用差异、发布说明和作用域明确的视觉说明。实现说明写实际变化，账本写状态与证据；检查六份互链、修订、任务及验收一致。最终按仓库格式给出命令结果、限制、文件与提示词链接。

## 文件与验证接缝

- 已核实资源根：`cm-agent-console/src/main/resources/META-INF/resources/console/v2/`；拟新增 V3 同级目录。
- 共享逻辑线索：`cm-agent-console/src/main/resources/META-INF/resources/assets/`；只改 T0 确认影响版本路由或视图生命周期的接缝。
- 测试线索：`cm-agent-console/src/test/js/` 及 console/server 当前 Java 静态资源与路由测试；具体类名由 T0 复核，不能把线索当成已修改文件。
- 生产说明：根 `README.md`、`docs/release-notes.md` 和当前模块相关说明；不以工作包代替日常使用说明。
- 参考源码 `F:/java/cm-agent-sites/cm-agent-codex-20261120/` 只用于布局，不作为本次业务客户端的数据或执行来源。

不确定的环境、凭据、模型、设备与资源权限应先只读核实。若不能完成某项必需真实验收，账本记录确切原因和受影响项；不得仅因实现有代码就标为通过。提交、推送或生产部署不包含在本计划默认授权中。

关联：[清单](../checklists/2026-11-21-console-v3-workspace-checklist.md) · [提示词](../prompts/2026-11-21-console-v3-workspace-prompt.md) · [设计](../specs/2026-11-21-console-v3-workspace-design.md) · [实现说明](../implementation/2026-11-21-console-v3-workspace-implementation-design.md) · [账本](../progress/2026-11-21-console-v3-workspace-ledger.md)
