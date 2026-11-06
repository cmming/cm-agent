# 本机客户端页账本

> 需求：本机客户端页（`local-client.html`）
> 主题：`local-client-page`
> 日期：2026-11-03
> 修订：R3
> 修订日期：2026-11-05
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-05
> 最后复核：2026-11-05

## 本次文档整理

本组由用户要求合并 2026-11-02 授权分析和实施中的设备客户端内容，按 `local-client.html` 重新组织。项目页授权内容和 10-29 项目工作区内容移入 `projects-page` 组；旧工作包文件已由用户要求删除。

文档整理阶段只新增/调整文档；随后 R1 实施阶段按 T12-T14 修改了页面、前端脚本和测试，未修改配置、数据库或运行服务，没有提交、推送或部署。

## 修订 R1（2026-11-04）

本次按复核建议补全：三种设备授权模式、聊天页选择边界、RBAC、密钥存储、浏览器确认后的客户端轮询与落盘、设备刷新/撤销/版本冲突、系统健康与设备在线区分。文档复核后已实施 T12-T14，并完成 Node 与定向 Java 验证；未做真实客户端、浏览器或多平台验收。

| 差异 | 类别 | R0 → R1 | 采用状态 |
|---|---|---|---|
| C-R1-1 | 新增 | 补全 `ASK_ON_EFFECT`、`ASK_ON_RISK`、`FULL_ACCESS` 的设备作用域 | 已采用；新增 T11 |
| C-R1-2 | 新增 | 补全 `agent:run`/`agent:read`、密钥存储和客户端最终落盘 | 已采用；新增 T12 |
| C-R1-3 | 新增 | 补全设备刷新、已撤销列表、详情字段、二次撤销和版本冲突 | 已采用；新增 T13 |
| C-R1-4 | 澄清 | 区分系统健康、服务端配对状态和设备在线状态 | 已采用；新增 T14 |

## 当前任务证据

| 任务 | 状态 | 证据与适用边界 |
|---|---|---|
| T0 | 完成 | 页面、`app.js`、设备接口和旧授权证据已核对；本组六份文档生成 |
| T1 | 完成 | 页面配对、风险说明、设备列表/详情和撤销区已实现 |
| T2 | 完成（实现与 Windows 历史证据） | Windows 签名配对包登记和确认有隔离实测；资源测试检查页面标识 |
| T3 | 完成（实现与历史证据） | 设备列表、详情、撤销和跨账号隔离有代码/测试证据 |
| T4 | 完成（页面文案） | Run 失效、服务端约束、UNKNOWN 不复用和风险文案已落地 |
| T5 | 完成（实现与历史证据） | 页面明确不显示设备在线状态，设备可用性由挑战/执行实际判断 |
| T6 | 完成（Chromium/Windows 本轮范围） | Playwright Chromium 覆盖 1366/1280 桌面、390px 窄屏、Tab/Enter、焦点恢复、可访问性树和错误实时区域；实际 NVDA/Narrator 与其他浏览器仍待验证 |
| T7 | 进行中 | Windows 客户端配对、授权、文件、回环网络、命令和撤销部分通过 |
| T8 | 待做 | Linux/macOS 真实桌面安装、配对和执行未完成 |
| T9 | 待做 | 真实远程 TLS、多实例、长期空闲、客户端限流和生产安装未完成 |
| T10 | 完成 | 已移除本机客户端页重复项目脚本和无关技能脚本 |
| T11 | 完成（历史实现，文档补录） | 三种设备模式和聊天页授权选择已有实现；本页不提供模式入口 |
| T12 | 完成 | RBAC、密钥存储和浏览器确认后的客户端落盘提示已补测试；本机页面不再直接宣称设备已连接 |
| T13 | 完成 | 设备列表/详情/二次撤销/版本冲突/网络未知已补专用 Node 回归 |
| T14 | 完成 | 顶部文案改为“控制台服务已连接”，并明确不代表设备在线 |

## R1 实施验证（2026-11-04）

- `node --test cm-agent-console/src/test/js/*.test.cjs`：225 项通过，0 失败。
- `mvn -q -pl cm-agent-local-client -am test`（JDK 21）：通过，覆盖系统凭据失败时的本地口令回退和设备身份恢复。
- `LocalClientPairingHttpIntegrationTest`（显式关闭本地项目/工作区，使用临时私有卷与测试密钥）：2 项通过，包含回环配对、挑战重放拒绝和 `agent:run`/`agent:read` 权限分离。
- `ConsoleResourceTest`：通过。
- `mvn -pl cm-agent-console,cm-agent-server -am -Dtest=ConsoleResourceTest,LocalProjectToolCatalogTest,LocalProjectControllerTest,LocalClientPairingHttpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`（JDK 21）：26 项通过，0 失败。
- `local-client.html` 已移除 `project-development.js`、两个 `local-projects.js` 引用和 `skills.js`；`local-client-page.test.cjs` 对页面文案、脚本引用、配对、列表和撤销逻辑进行静态与假 DOM 验证。
- Playwright Chromium 实测 1366×768、1280×720、390×844：`scrollWidth === innerWidth`；390px 当前导航自动居中；设备选中态读作 `pressed`，Tab/Enter 后焦点保持在重建后的设备按钮，撤销首击后焦点保持“再次点击确认撤销”。
- 设备读取注入 500 时，`localClientDeviceStatus` 的 `role="status"`、`aria-live="polite"` 可访问性节点包含脱敏原因和错误编号；正常页控制台 0 错误。
- 证据位于 `output/playwright/accessibility-20261104/.playwright-cli/`，包含桌面/窄屏截图、快照和焦点检查输出；该目录被版本控制忽略。

## 历史验证摘要

- 服务端配对/设备测试覆盖签名配对、挑战、重放拒绝、跨主体隔离、撤销和审计失败关闭。
- Windows 命令行客户端完成一次真实配对、单轮授权、文件、回环 HTTP、命令和设备撤销；使用临时测试环境，不代表生产部署。
- Linux 容器仅验证部分客户端文件/网络/命令原语；没有桌面安装或真实远程通道。
- macOS 没有实机环境，未验收。
- 项目页 R4 的 8080 和真实 Provider 证据属于 `projects-page`，不能证明本页或远程设备完成。

服务端和客户端测试报告主要位于 `cm-agent-server/target/surefire-reports/`、`cm-agent-local-client/target/` 等被忽略目录。本轮浏览器证据位于 `output/playwright/accessibility-20261104/`，不代表真实客户端端到端验收。

## 未验证与风险

- 真实远程 TLS、跨实例路由、客户端签名安装包、升级/卸载和长时间运行未验收。
- Linux/macOS 桌面、真实远程 Server、多客户端并发和限流未覆盖。
- 本轮读屏检查使用 Chromium 可访问性树，未用 NVDA、Narrator 或其他实际朗读软件逐句验证；Firefox/WebKit 未覆盖。
- 页面没有在线状态，不能把设备出现在列表理解为可执行操作。
- 命令继承用户账号权限，可能产生不可逆外部副作用；停止不是回滚。
- 本页脚本引用已清理；后续新增依赖仍需保持最小集合，避免重新引入项目页或技能页脚本。

## 提交状态

未提交、未推送、未部署。本轮修改了 `app.js`、`local-client.html`、`local-client.css`、相关 Node 回归与共享静态资源版本；仅启动隔离静态验收夹具，未启动或重启业务服务。

## 关联文档

- [本机客户端页清单](../checklists/2026-11-03-local-client-page-checklist.md)
- [本机客户端页设计](../specs/2026-11-03-local-client-page-design.md)
- [本机客户端页计划](../plans/2026-11-03-local-client-page.md)
- [本机客户端页提示词](../prompts/2026-11-03-local-client-page-prompt.md)
- [本机客户端页实现说明](../implementation/2026-11-03-local-client-page-implementation-design.md)


## R2 首次连接指引（2026-11-04）

| 任务 | 状态 | 实际结果 |
|---|---|---|
| T15 | 完成 | 页面加入客户端源码获取与构建、pair/run 命令、目标设备核对、落盘与单轮授权说明；更正跨页标题和成功提示 |
| T16 | 完成 | Node 12/12；JDK 21 `ConsoleResourceTest` 16/16；Windows 客户端构建及 `help` 入口通过；1280×720 与 390×844 静态模拟页无整体横向溢出 |
| T17 | 待做 | 未执行真实设备配对或实际授权；Linux/macOS 命令与设备实机、远程 HTTPS、登录后真实页面仍未验收 |

本次运行：`mvn -q -pl cm-agent-local-client -am -DskipTests install`，随后 `mvn -q -pl cm-agent-local-client dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib`；在 Windows JDK 21 下使用页面同一类路径调用 `LocalClientApplication help` 成功。`install` 将相关构件写入本机 Maven 仓库；没有启动 pair 或 run。`node --test cm-agent-console/src/test/js/local-client-page.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs` 12/12；`mvn -q -pl cm-agent-console -am -Dtest=ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false test` 16/16。临时预览服务已停止，静态夹具不等于登录后真实业务验证。未提交、未推送、未部署，未重启 8080 服务。

## 修订 R3（2026-11-05）

用户要求客户端构建后可直接用 `java -jar` 运行，并同步本工作包。R3 采用“保留薄 JAR、附加 `all` fat JAR”的方案，避免改变普通模块依赖语义；页面分发说明同步改为单文件。

| 差异 | 类别 | R2 → R3 | 采用状态 |
|---|---|---|---|
| C-R3-1 | 新增 | 代码路径运行 → Maven Shade 附加可执行 fat JAR | 已采用；新增 T18 |
| C-R3-2 | 调整 | 源码、`target/classes`、`target/lib` → 目标电脑只复制 `-all.jar` 并安装 JDK 21 | 已采用；新增 T19 |
| C-R3-3 | 澄清 | 平台类路径分隔符不同 → 统一 `java -jar` 命令 | 已采用；T19、T20 |

### R3 任务证据

| 任务 | 状态 | 实际结果 |
|---|---|---|
| T18 | 完成 | `cm-agent-local-client/pom.xml` 已接入 Maven Shade Plugin 3.6.0；普通薄 JAR 和 `all` fat JAR 同时生成 |
| T19 | 完成 | `local-client.html` 首次连接步骤已改为单文件分发；Node 回归已同步并禁止旧类路径命令 |
| T20 | 完成 | JDK 21 下构建、22 项客户端测试、fat JAR `help`、清单/依赖检查和 16 项控制台资源测试均通过 |

### R3 实际验证（2026-11-05）

- 环境：`JAVA_HOME=F:\java21`，`java -version` 为 OpenJDK 21.0.11，Maven 3.9.4。
- `mvn -q -pl cm-agent-local-client -am clean test package`：退出 0；客户端测试报告合计 22 项通过，0 失败、0 错误。
- 生成 `cm-agent-local-client/target/cm-agent-local-client-0.1.0-SNAPSHOT-all.jar`（4,733,500 字节，SHA-256 `246406EF21B7621236ECA97A295C01EFAA00B8408FA829F974CAA9FA7CD18C5E`）。
- 普通 `cm-agent-local-client-0.1.0-SNAPSHOT.jar` 继续生成（90,963 字节，SHA-256 `3FBE561255388554889126255C09E33737AD86247E08E06F5A62A45F319787E0`），清单无 `Main-Class`。
- fat JAR 清单包含 `Main-Class: com.cmagent.localclient.LocalClientApplication`；内嵌 `com/cmagent/core/*`、`com/fasterxml/jackson/*` 和 `com/sun/jna/*`，未包含 `META-INF/*.SF`、`*.DSA` 或 `*.RSA`。
- `java -jar cm-agent-local-client/target/cm-agent-local-client-0.1.0-SNAPSHOT-all.jar help`：退出 0，输出 `pair`、`run`、`status` 可用命令。
- `node --test cm-agent-console/src/test/js/local-client-page.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs`：12/12 通过。
- `mvn -q -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：16/16 通过。
- 首次控制台测试调用因 PowerShell 拆分 `-Dsurefire.failIfNoSpecifiedTests=false` 参数而被 Maven 拒绝；该次不计为测试结果，参数整体引用后重跑通过。
- 未启动 `pair` 或 `run`，未访问真实服务端，未创建或修改设备身份，未测试原生安装包、代码签名、Linux/macOS 实机、远程 TLS 或生产分发。

### R3 遗留与适用边界

- fat JAR 已解决单文件运行和跨平台类路径分隔符问题，但不等于已签名、可自动升级的安装包。
- 本轮 Windows JDK 21 `help` 只能证明主类、内嵌依赖和启动链路；真实配对、设备落盘、远程 HTTPS 和实际命令执行仍由 T7/T9/T17 跟踪。
- 页面示例固定使用当前 `0.1.0-SNAPSHOT` 文件名并提示版本变化时替换，不新增会失效的 glob 或平台脚本。
