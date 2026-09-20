# Tool 工作区分阶段进度账本

关联计划：[Tool 工作区分阶段实施计划](../plans/2026-09-20-tool-workspace-stages.md)。

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| 左右布局和默认详情 | 完成 | 左侧为 Tool 与内置示例，右侧默认显示选中 Tool 详情。 |
| 单一右侧工作区 | 完成 | 定义、授权、调试与详情互斥展示。 |
| 内置示例入口 | 完成 | 所有内置工具始终显示且按 ID 去重；已存在的查看详情，尚未添加的提供添加入口。 |
| 内置示例重绘回归 | 完成 | 将示例恢复逻辑收敛到 `renderTools`，选择普通 Tool 后不会丢失示例卡片。 |
| 静态资源与测试 | 完成 | `node --check`、`mvn -q -pl cm-agent-console -am test` 和 `git diff --check` 均通过。 |

## 验证记录

- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`：通过。
- `mvn -q -pl cm-agent-console -am test`：通过，Maven 使用 Java 21。
- `git diff --check`：通过；仅输出 Git 的既有换行符提示。
- 初次浏览器检查：本地 8080 服务将未认证访问重定向至登录页；未绕过认证执行交互。
- 登录后回归：使用用户授权的本地调试账号进入工具治理页，选择 `echo` 后确认 `add（内置示例）`仍保留在左侧；未修改任何 Tool 数据。

## 提交信息

## 二次修复验证

- 根因补充：过滤 `installed=true` 隐藏了 echo；修改后的 add 虽已存在，但 `installed=false` 被误解为可重复添加。
- 已通过真实本地登录验证：3 张工具卡片中包含 echo、add 两张内置工具卡片，每张只有一个按钮；查看详情、编辑取消、选择普通工具及刷新后两张均保留，右侧可见功能面板为 1 个。
- 浏览器模拟验证：工具列表为空时，内置目录先返回或后返回均显示两个添加入口；未发起安装或业务写入。
- 390px 移动端宽度检查通过，无横向溢出。
- Java 21 下控制台 Maven 测试和 JavaScript 语法检查通过。同步缓存版本至 2.0.24。
- 本次为既有界面缺陷修复，未修改发布流程，因此未单独更新发布说明。

## 提交状态

## LOCAL MCP 发布入口修复

- 根因：普通更新接口不允许未发布 LOCAL 工具通过 `mcpPublished=true` 首次发布，而表单提供了可编辑复选框且内置工具详情缺少专用入口。
- 修复：详情增加 MCP 发布/取消发布按钮；LOCAL 表单只读回显；普通更新载荷保留工具当前发布状态。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`：70 项通过，新增未发布和已发布两种状态的更新载荷回归。
- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js` 和 Java 21 下 `mvn -q -pl cm-agent-console -am test`：通过。
- 浏览器模拟验证：保存未发布工具、专用 PUT 发布、保存已发布工具、专用 DELETE 取消发布、400 错误码及编号显示均通过；左侧内置工具仍只有一个按钮。
- 初次浏览器模拟的路径匹配未覆盖发布子路径，实际调用了一次本地 echo 发布接口；随后修正匹配并重新完成全部模拟验证。真实 API 回读确认 echo 当前已发布，已向用户说明。
- 无后端、数据库或权限规则修改；静态资源版本同步提升。本次未更新发布说明，属于该界面任务的连续修复。

## 当前提交状态

未提交。
