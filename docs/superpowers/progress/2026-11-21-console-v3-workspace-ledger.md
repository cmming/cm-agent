# V3 聊天工作区实施账本

- 日期：2026-11-21；主题：`console-v3-workspace`；修订：R1；工作模式：`mode=default`；任务：T0～T6。
- 工作包归档结论：当前。
- 主仓库分析基线：`e70d9f869470e073420fccbdc5041eca9046702c`。提交和服务核验见本账本末节。
- V3 依据[独立 Sites 原型](https://cm-agent-codex-workspace.poetic-lark-9729.chatgpt.site/)的布局实施，真实后端验收结果记录在本账本。

## 任务状态

| 编号 | 状态 | 当前证据与限制 |
|---|---|---|
| T0 | 完成 | 当前 V2 入口、共享脚本、DOM 与路由接缝已核对，实施采用显式版本白名单与布局生命周期复用 |
| T1 | 完成 | 十一个 V3 静态页及根/固定短路径已形成。V3 真实登录默认聊天，可刷新恢复并执行同版本导航。默认入口的修订见 T6 |
| T2 | 完成 | 普通聊天、停止和刷新恢复已验证。专用项目已通过系统文件夹选择登记，真实会话覆盖读取项目文件以及片段发送审批的允许、拒绝 |
| T3 | 完成 | 保留各页业务 DOM 与共享 API，八个管理页真实同源认证导航及只读资源检查已覆盖。各类管理写操作未逐项联调 |
| T4 | 完成 | Node 257/257，控制台资源测试 18/18，独立路由测试 3/3。桌面及 390px、820px 浏览器窗口覆盖本次关键路径。独立评审聚焦兼容性与手机源码 |
| T5 | 完成 | README、发布说明、V3 视觉文档与工作包已同步，实际地址和部署限制见本账本 |
| T6 | 验证中 | 默认入口切换至 V3，显式版本入口仍指向各自版本。单独记录本轮验证与发布 |

## 事实、假设与待核实项

- 用户明确要求使用前轮 Sites 布局、聊天优先、保留现有功能并接真实后端，对应 D1、D2。
- R0 的 D3 保留 V2 作为默认版本。R1 根据用户明确指令替换该决定，默认入口改为 V3，V2 显式入口继续可用。T0～T5 保留原记录，T6 负责本轮验收。
- D4～D6 保持既有安全与验证边界；真实环境、模型配置、设备、资源归属与授权按当前状态核实，不从历史记录推断可用。
- 当前 V2 资源根、十一页、共享 API/认证、布局 DOM 与版本生命周期均由当前源码调查约束；真实服务状态及检查仅对本阶段记录的环境和操作有效。

## 基线与初始命令证据

- 文档任务执行 `git rev-parse HEAD`，返回本账本所列分析基线。
- 文档任务执行 `git status --short`，已有五个无关脏文件：`.agents/skills/requirement-workpack/agents/openai.yaml`、`.impeccable/design.json`、`cm-agent-server/src/main/resources/application-local.yml`、`application-mysql.yml`、`application.yml`；另有六份 `2026-11-20-codex-layout-sites` 原型文档未提交。以上内容保留，不读取或记录敏感配置值。
- 文档任务读取当前工作包产物契约、原型设计与账本，作为独立实施与历史衔接依据；没有以旧原型的完成状态标记本包实现完成。
- 初始六文件检查：35 个相对链接均指向真实文件，修订与模式一致、代码围栏成对、行尾空白错误为 0；`git diff --check -- docs/superpowers` 无错误。该结果只覆盖文档契约，不证明 V3 业务实现或联调通过。
- 本包初始状态尚无 Java/Node 测试、服务启动、真实 API 读写或浏览器检查结果。Docker/JDBC/Flyway/Testcontainers 必须经 `ssh rocky` 执行，远程源码与待验本地状态需一致；主任务补充确切执行与限制后再记录。

## 实现与验证证据

以下记录本次实际运行的检查及其覆盖范围。

- Java 构建使用 `F:/java21` 的 JDK 21.0.11，Maven 3.9.4；不是初始 PATH 中的 JDK 17。
- `node --test cm-agent-console/src/test/js/*.test.cjs` 共 257 项全部通过，其中新增目录选择过期恢复、项目模式缺少新建条件时的焦点回归。
- `mvn -q -pl cm-agent-console -am test`：18 项全部通过；另隔离执行 `ConsoleControllerRoutingTest`，3 项全部通过。
- 当前 8080 服务上的十一个 V3 静态 HTML 页面均返回 HTTP 200。该证据只证明静态入口可达，不自行证明所有业务操作。
- 本地 DPAPI 凭据仅在登录进程内存中解密，未写入仓库、日志或响应说明。认证后 `/api/auth/me` 返回 200；当前主体读取到 6 个 Agent、5 个模型配置和 26 个工具。这些计数是本次环境快照，不是产品固定值。
- Ego 真实浏览器登录后进入 V3 聊天；使用当前租户配置的真实模型收到“V3联调成功”回复。真实停止返回“本轮已停止”，刷新后能恢复消息；未以 Site 预设回答替代真实模型调用。
- 五个真实项目可加载并切换，切换检查没有出现重复 ID；八个管理页在同文档导航下保持同源认证并完成切换。该结果不扩展为所有管理写操作矩阵已逐项验收。
- 静态兼容评审发现并修正关闭时手机侧栏的 `inert` 与 `aria-hidden` 状态。旧导航 `span` 宽度造成的中文竖排经 V3 专用 CSS 修正。手机专项源码复核发现 V2 规则隐藏普通会话面板、761–900px 将主区推离视口、无选中项目时新会话误收起侧栏，均已修正。评审覆盖上述范围。
- CUA 应用内浏览器的页面创建和读取曾超时，随后以 Ego 完成浏览器验收并取得截图。`agent-desktop` 因私有文件 DACL 拒绝未继续，原生目录选择由 CUA 完成，服务端返回选择票据后页面成功登记项目。
- 在 `F:/java/cm-agent-v3-acceptance-20261121` 创建仅含人工非敏感验收标记的 `README.md`，通过原生文件夹选择登记项目「V3联调验收-20261121」。使用 `ASK_ON_EFFECT` 验证项目聊天。允许和拒绝分别运行，比较各自的精确预览与运行结果。
- 允许分支先核对并允许纯测试输入，再核对 `project_read_file` 读取的 `README.md` 片段。勾选核对并提交允许后，真实模型续答返回只存在于验收文件的标记，运行状态为「成功」。
- 拒绝分支先核对并允许纯测试输入，等候 `project_read_file` 返回验收片段，在 390×844 手机模拟视口核对精确预览并提交拒绝。运行状态为「已拒绝」，会话未返回文件标记。独立读取会话列表、审批历史和消息均返回 HTTP 200，确认两条独立会话和两次不同运行。允许分支记录两次 `APPROVED`，拒绝分支依次记录 `APPROVED` 和 `DENIED`。消息内容与各自运行结果一致。
- Chrome 390×844 模拟视口验证初始关闭的导航隔离、抽屉开合、普通会话搜索、切换项目前后的新会话行为、项目输入区无整页横向溢出、上下文模态和关闭后焦点恢复，以及实际审批拒绝。820px 模拟视口验证聊天主区留在视口中。`mobile-final.json` 的 11 项交互检查均通过。
- 在 390×844 模拟视口从 V3 本地项目页打开专用项目的「文件」标签，再打开 `README.md`。真实文件文本接口 `/api/projects/{id}/text` 返回 HTTP 200，页面显示验收标记和 `0–96 / 96 字节`，无整页横向溢出。手机文件页以 DOM 和 HTTP 结果为证。
- 证据目录为 `C:/Users/chmi/.codex/visualizations/2026/11/20/01a1f96c-0199-7953-86fa-f78edde363c1/v3-work/evidence/`，详见 `real-allow.json`、`real-deny.json`、`server-history.json`、`mobile-final.json`、`mobile-file.json` 以及 `desktop-real-approval.png`、`mobile-real-approval.png`、`mobile-project.png`。
- 一次原生目录选择请求超过票据期限，服务端查询返回 HTTP 404 `PROJECT_SELECTION_NOT_FOUND`。前端现在识别该明确终态，显示服务端诊断编号并恢复手动选择入口。网络未知仍保留原请求核对，选目录与登记均不会自动重试。
- 产物包括十一个 V3 页面、五个 V3 样式/脚本、共享版本与项目布局接缝、ConsoleController 路由以及对应测试；README、`docs/release-notes.md`、`.impeccable/surfaces/console-v3.md` 与 `docs/design/console-v3/` 已同步。根设计 sidecar、原型文档及原有五个脏文件保留。
- 本次 Maven 检查使用控制台资源测试和独立 MockMvc 路由测试，无数据库依赖。未运行 JDBC/Flyway/Testcontainers，未进行 Rocky 容器验收。
- `git diff --check` 已通过，Git 仅提示部分原有文件的换行转换。

## R0 提交、发布与遗留

实现验收阶段，浏览器可从 [V3 聊天页面](http://192.168.5.149:8080/console/v3/chat.html) 访问。该阶段通过本机构建更新了静态资源，测试时的旧进程访问短路径曾返回 HTTP 404。最新源码中的短路径已通过独立路由测试。

- 2026-11-21 将 V3 代码、测试与文档提交为 `4da4e01b03aff4772b5118b59ca51d6da9c2a31b`，已快进推送到 `origin/master`。先前的 V2 项目布局提交 `e70d9f8` 是 V3 的代码基线，随历史推送。五个原有本地修改与六份旧 Sites 原型文档保留在工作区。重启后复核了这五个原有修改文件的 SHA-256，内容一致。
- 推送前复跑前端测试共 257 项，Maven 资源与独立路由测试共 21 项，均通过。没有运行数据库集成测试。
- 推送后在 IntelliJ IDEA 重新运行现有 `CmAgentServerApplication-mysql` 配置。8080 监听进程从 `15320` 更换为 `2260`，新进程于 13:05:22（北京时间）启动，`/actuator/health` 为 `UP`。
- 从局域网回查 `/console/v3/` 和 `/console/v3/chat`，分别以 HTTP 302 跳转至 V3 登录页和聊天页，目标页均为 HTTP 200，版本标记为 `v3`。`/console/v2/`、`/` 和 `/console/` 均以 HTTP 302 跳转至 V2 登录页，目标为 HTTP 200，版本标记为 `v2`。

文件读取覆盖项目聊天工具、审批精确预览与独立本地项目页的手机文件预览。管理页覆盖真实只读加载与切换，未逐项提交创建、编辑、删除、配对或撤销等写操作。手机端为桌面浏览器视口模拟。

## R1 默认入口验证与发布

- 变更基线为 `0f88fea7e41853869104868e92b963e1e34176c6`，本轮改动未提交。

关联：[清单](../checklists/2026-11-21-console-v3-workspace-checklist.md) · [提示词](../prompts/2026-11-21-console-v3-workspace-prompt.md) · [设计](../specs/2026-11-21-console-v3-workspace-design.md) · [计划](../plans/2026-11-21-console-v3-workspace.md) · [实现说明](../implementation/2026-11-21-console-v3-workspace-implementation-design.md)
