# 工作区目录选择进度账本

日期：2026-10-27；主题：workspace-directory-selection；修订：R0；模式：mode=default。

## 基线与本次范围

Git HEAD：`369418b3bdc08b096de7854f9d04c982ec8be050`。本次实际交付仅六份需求文档；业务代码未实施；提交状态：未提交。

开始时已有：修改 `.gitignore`、`cm-agent-server/src/main/resources/application-mysql.yml`、`cm-agent-server/src/main/resources/application.yml`、`docs/release-notes.md`；未跟踪 `.workbuddy/`、`cm-agent-server/src/main/resources/application-ok.yml`。暂存区为空，均保留。本次没有读取或输出这些配置中的凭据；相关配置能力依据配置类和生产说明核对。

## 任务状态

| 编号 | 状态 | 实际结果与证据 |
|---|---|---|
| T0 | 完成 | 已核查 AGENTS.md、相关 POM、README/配置/运维、代码和页面；生成六份文档并进行一致性检查，命令记录见下 |
| T1 | 待做 | catalog 和配置兼容尚未实现 |
| T2 | 待做 | 选择元数据、memory/JDBC、新迁移和双库合同尚未实现/执行 |
| T3 | 待做 | 目录/绑定接口、可选创建参数、诊断与权限测试尚未实现 |
| T4 | 待做 | 选择进入 Run/快照/恢复/清理尚未实现 |
| T5 | 待做 | 前端目录选择、运行展示与行为测试尚未实现 |
| T6 | 待做 | 本需求自动化、双库及 Linux 文件合同未执行 |
| T7 | 待做 | Provider、浏览器、进程重启、部署/回退及生产说明未执行 |

## 决策与假设

默认模式自主采用 A1 服务端路径、A2 只读、A3 登记目录清单、A4 会话绑定且首发后固定、A5 三态兼容、A6 Run 正文冻结；详见设计。它们不是用户逐项确认的决定。本轮未启用交互模式，没有待用户选择的决定或外部阻塞；实际部署环境及凭据未验证，不冒称不可用。

## 本次检查与证据

| 命令/操作 | 结果与适用范围 |
|---|---|
| `git status --short`、`git diff --cached --stat`、`git rev-parse HEAD` | 确认上述基线、已有脏文件和空暂存区 |
| `Test-Path .codegraph`、`Get-Command codegraph` | 索引目录及 CLI 存在 |
| `codegraph explore` 工作区/WorkspaceContextService/WorkspaceContextProperties 查询 | 成功返回当前源码与调用线索；部分精确查询返回相邻文件且截断，未覆盖部分直接复核，不假定图结果完整 |
| `rg --files`、定向 `rg -n`、`Get-Content` | 核对 WorkspaceContext 配置/运行链、Conversation/Run 接口、真实 chat.html/app.js、迁移与测试位置、相关模块 POM 和生产说明；未运行业务 |
| `.agents/skills/impeccable/scripts/impeccable.cmd context --target cm-agent-console/src/main/resources/static/console/v2/chat.html` | 载入 PRODUCT.md、DESIGN.md；传入路径不存在，后续确认真实路径为 META-INF/resources/console/v2/chat.html；仅用于沿用设计上下文，未浏览器验收 |
| PowerShell 工作包一致性检查 | 通过：六文件存在，31 个相对链接全部有效；每份均关联其余五份产物；日期/主题/R0/mode=default、T0 完成/T1–T7 待做、围栏与无模板占位符均通过 |
| `git -c core.autocrlf=false diff --no-index --check -- NUL <本次单个文件>`，逐一检查六份 | 通过：无空白错误输出；no-index 的差异退出码 1 不视为格式错误，未改动 Git 配置；首次校验脚本对此退出码及重复链接计数的判断已纠正 |
| `rg -n --pcre2` 敏感值模式扫描，仅限六份新增文件 | API Key/密码/secret 赋值、sk-密钥和 JWT 形态无命中；结合人工复核，文档仅含配置键、类型与验证要求，不含真实凭据 |
| 最终 `git status --short`、`git diff --cached --stat` | 仅新增本组六份工作包；原有修改/未跟踪项保留；暂存区仍为空，未提交 |

没有使用历史 Maven、Rocky、Provider 或浏览器通过结果作为本需求证明。本轮未运行 `java -version`、`mvn -v` 或测试：本次仅文档，没有构建；未来实施必须重新核对 Java 21。没有连接 Rocky，未执行 Docker/Testcontainers/JDBC/Flyway、真实模型或浏览器操作；这些属于 T6/T7 的待执行门禁，不能认为已通过。

## 后续实施条件

用户明确要求实施后，按 T1–T7 顺序推进，建立独立实施工作包引用本分析包。实施前重新核对目录可用性、权限、HEAD、补丁、迁移编号和实际目标环境。若 SSH、容器镜像、真实模型或目录权限发生确切阻塞，记录原始错误的脱敏摘要、受影响验收和继续条件，不越权降低安全默认值。

## 关联产物

- [清单](../checklists/2026-10-27-workspace-directory-selection-checklist.md)
- [提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)
- [设计](../specs/2026-10-27-workspace-directory-selection-design.md)
- [计划](../plans/2026-10-27-workspace-directory-selection.md)
- [实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-design.md)
