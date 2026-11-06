# 本机客户端页实现说明

> 需求：本机客户端页（`local-client.html`）
> 主题：`local-client-page`
> 日期：2026-11-03
> 修订：R3
> 修订日期：2026-11-05
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-05
> 最后复核：2026-11-05

## 实际页面交付

`local-client.html` 已提供：

- “连接新电脑”配对区，要求粘贴客户端 `pair` 输出。
- 配对包设备名称、平台预览和设备风险说明。
- 已配对设备列表、设备数量和设备详情。
- 设备刷新、活跃/已撤销状态、二次确认撤销和 `expectedVersion` 条件更新。
- 设备详情显示平台、设备标识、配对时间、状态版本和最近更新时间。
- 授权边界说明：作用对象、生效时间、服务端约束和结果未知。
- 顶部状态明确为“控制台服务已连接”，不代表任何设备在线；设备列表仅显示服务端配对状态。

## 关键代码位置

- [local-client.html](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/local-client.html)：配对表单、设备列表/详情和边界说明。
- [app.js](../../../cm-agent-console/src/main/resources/META-INF/resources/assets/app.js)：配对包解析、预览、提交确认、设备加载、详情和撤销。
- `LocalClientController`、`LocalClientPairingService`、`LocalClientAuthorizationService`：配对、设备和 Run 授权服务端链路。
- `cm-agent-local-client`：本机客户端身份、配对和受控执行模块。

## 页面调用

- `POST /api/local-client/pairings`：登记客户端签名配对包。
- `POST /api/local-client/pairings/{id}/confirm`：确认配对并绑定当前主体/tenant。
- `GET /api/local-client/devices`：读取当前账号设备。
- `POST /api/local-client/devices/{id}/revoke`：撤销设备。

配对登记、确认和撤销要求 `agent:run`，设备列表要求 `agent:read`。页面不调用本机文件、网络或命令接口。聊天页负责选择已配对设备、`ASK_ON_EFFECT`/`ASK_ON_RISK`/`FULL_ACCESS` 模式和类别；Run 终态后授权失效。

设备身份在客户端本机生成并保存；私钥优先进入系统密钥链，不可用时由本地口令保护。浏览器确认后，客户端轮询配对状态并落盘设备标识；客户端退出、轮询超时或落盘失败时，不得把服务端已确认等同于设备已可执行。

浏览器确认成功后的页面提示改为“服务端已确认配对，请确认本机客户端也显示配对完成”，不再直接宣称设备已连接。撤销失败会刷新设备列表并提示状态变化或结果未知，不自动再次提交。

## 当前实现边界

- Windows 客户端已有一轮签名配对、授权、文件、回环网络、命令和撤销证据。
- Linux 仅完成部分本地原语容器测试，macOS 没有实机验收。
- 真实远程 TLS、跨实例路由、长期空闲、多客户端限流、安装包签名和生产运维未完成。
- 页面明确不显示在线状态，这是产品边界，不是遗漏。
- 页面顶部已统一为“控制台服务已连接”，并通过辅助文本和静态回归明确其不代表设备在线。
- R1 已移除重复 `local-projects.js`、无关 `project-development.js` 和 `skills.js`，保留本页所需公共资源。

## 与旧工作包的关系

本组 R0 按 `local-client.html` 重写需求，合并原授权分析/实施中的设备客户端和配对管理内容；R1 补全授权模式、RBAC、配对落盘、设备管理和系统状态边界。项目页授权、`project_*` 工具和项目内 `FULL_ACCESS` 见[本地项目页设计](../specs/2026-11-03-projects-page-design.md)。

## R1 实施与验证

- `app.js` 调整配对完成提示、设备总数文案、系统健康文案和撤销异常处理；撤销版本冲突或网络未知只提交一次。
- 设备列表按钮增加 `aria-pressed` 与 `aria-controls`；设备选择、二次撤销和刷新重绘后恢复原控件焦点，详情区标题移到内容之前。
- 本机设备工作区在 1400px 以下改为纵向堆叠，1080px 以下整页单列，避免 1280/1366 常见桌面宽度压缩详情列。
- 新增设备读取专用 `role="status"` 实时区域，刷新失败同时显示原因并供读屏树读取。
- 新增 `local-client-page.test.cjs` 覆盖配对包校验、服务端确认后的客户端落盘提示、活跃/已撤销列表、详情字段、二次撤销、版本冲突和网络未知。
- `LocalClientPairingHttpIntegrationTest` 增加 `agent:run`/`agent:read` 权限分离验证，并显式关闭与本测试无关的本地项目和工作区能力、使用临时私有卷与测试密钥，避免依赖外部环境变量或 JDBC。
- 完整 Node 套件 225 项通过；`ConsoleResourceTest` 16 项通过。
- Playwright Chromium 在 1366×768、1280×720 和 390×844 完成真实页面检查：无横向溢出，焦点轮廓为 2px 实线，Tab/Enter 可选择设备、保持设备焦点并触发二次撤销确认；可访问性树可读出设备选中态、区域标题和错误状态。
- 本轮未重启业务服务、未连接真实客户端、未执行文件/网络/命令副作用；浏览器验收使用隔离静态夹具，未提交或部署。

## 关联文档

- [本机客户端页清单](../checklists/2026-11-03-local-client-page-checklist.md)
- [本机客户端页设计](../specs/2026-11-03-local-client-page-design.md)
- [本机客户端页计划](../plans/2026-11-03-local-client-page.md)
- [本机客户端页提示词](../prompts/2026-11-03-local-client-page-prompt.md)
- [本机客户端页账本](../progress/2026-11-03-local-client-page-ledger.md)


## R2 首次连接指引实现（2026-11-04）

T15 已实施：`local-client.html` 增加获取、构建、生成配对包、核对、终端等待落盘、启动客户端及聊天页选择单轮授权步骤；说明目标设备可不同于浏览器所在电脑、远程 HTTPS、服务端默认能力限制和不可逆风险。Windows 与 Linux/macOS 类路径命令区分分隔符。命令区可键盘聚焦并水平滚动，配对面板取消 sticky，避免长说明在短视口被困住。`app.js` 的页面摘要和配对成功提示同步指向目标电脑；相关 v2 入口更新缓存版本。未修改安全策略、配对 API、数据库或服务配置。

## R3 可执行 fat JAR 实现（2026-11-05）

- [cm-agent-local-client/pom.xml](../../../cm-agent-local-client/pom.xml) 增加 Maven Shade Plugin 3.6.0，在 `package` 阶段附加 `all` 分类器产物，写入 `com.cmagent.localclient.LocalClientApplication` 主类，合并服务资源并移除依赖原始签名文件。
- 普通 `cm-agent-local-client-0.1.0-SNAPSHOT.jar` 继续作为薄 JAR 生成；可执行产物为 `cm-agent-local-client-0.1.0-SNAPSHOT-all.jar`。
- [local-client.html](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/local-client.html) 改为构建一个 fat JAR、只复制该文件到目标电脑，并统一使用 `java -jar ... pair` 与 `java -jar ... run`。
- [local-client-page.test.cjs](../../../cm-agent-console/src/test/js/local-client-page.test.cjs) 更新首次连接回归，检查 `package` 构建命令、`-all.jar`、目标电脑只需 JDK 21，并禁止出现过时的 `target/classes` 和 `dependency:copy-dependencies` 指引。

实际构建产物大小分别约 4.73 MB 和 90.96 KB；fat JAR 清单包含 `Main-Class`，内嵌 core、Jackson 与 JNA 类，且未保留依赖签名。该实现只改变分发方式，不改变客户端身份存储、配对协议、设备授权或服务端安全边界。
