# 工具人工确认 UI 交互优化计划

后续状态：本文件保留卡片优化原计划；历史回显已按独立[审批历史计划](2026-09-03-tool-approval-history.md)落地，下面的缺口说明表示当时边界，不再代表最新实现。

## 关联文档

- [设计说明](../specs/2026-09-03-tool-approval-ui-ux-design.md)
- [实现说明](../implementation/2026-09-03-tool-approval-ui-ux-implementation-design.md)
- [进度账本](../progress/2026-09-03-tool-approval-ui-ux-ledger.md)

## 实现顺序

1. 核对 `cm-agent-console/pom.xml`、原审批设计、`app.js` 卡片/提交/查询函数以及 `chat.html` 和样式，保留现有未提交实现。
2. 在 `console-core.js` 增加可独立测试的选择汇总、UI 状态映射与内存草稿组件，不改变请求载荷合同。
3. 在 `app.js` 改善卡片层级、批量选择说明、提交结果文案、草稿回填和焦点恢复；按服务端事件区分提交与恢复。
4. 为未知结果和只读卡片增加只查询的刷新入口；保留会话代次、重复提交锁、权威查询和错误编号。
5. 更新 `styles.css` 的卡片说明、选择汇总、终态折叠与窄屏交互；同步 v2 八个页面的脚本和 CSS 缓存版本。
6. 扩充 `console-core.test.cjs`：纯函数测试、静态资源合同和执行真实提交/刷新函数的隔离编排测试。隔离测试只替换网络与 DOM 边界，不调用外部模型或工具。
7. 运行脚本语法检查、Node 测试、JDK 21 下 Console 测试和 Server 依赖打包，检查差异格式。
8. 更新生产说明、发布说明、本主题四份文档和原审批文档中的最新 UI 合同指引。

## 验证命令

- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`
- `mvn -q -pl cm-agent-console -am test`
- `mvn -q -pl cm-agent-server -am "-DskipTests" package`
- `git -c core.safecrlf=false diff --check`

Maven 前设置当前进程 `JAVA_HOME=F:\java21` 并将其 bin 加入 PATH 前部；不改系统环境和用户已有应用配置。本次不涉及数据库修改，无需重复执行 Rocky 数据库测试。

## 完成边界

实现与上述自动化验证完成后交付，提交状态记录为“未提交”。真实浏览器视效、键盘焦点流与全后端联调留作明确的待验收项，不表述为已通过。

用户反馈“只显示最后一次、无法回显”已确认：目前没有按会话读取终态审批历史并回放的完整链路。后续应把当前待确认区与只读历史区分离，增加租户/会话隔离的历史查询后按 Run/调用展示；该项需要前后端联动，不属于本轮已完成的卡片优化。
