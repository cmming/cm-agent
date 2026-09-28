# 技能试运行目标 Agent 下拉选择实现说明

对应设计：[需求设计](../specs/2026-09-28-skill-trial-agent-dropdown-design.md)，执行拆分见[计划](../plans/2026-09-28-skill-trial-agent-dropdown.md)。

## 最终实现

- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js`：新增可用试运行 Agent 过滤函数；将目标 UUID 文本输入替换为必选下拉框；异步加载现有 Agent 列表，过滤停用项和无效 ID，显示 Agent 名称及模型；对加载中、无可用 Agent、权限缺失及请求失败提供中文状态提示。提交逻辑继续向原试运行接口传所选 ID。
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css`：令下拉控件沿用现有表单宽度和盒模型规则。
- `cm-agent-console/src/test/js/skills.test.cjs`：验证有效启用 Agent 保留、停用或无效项过滤及非数组输入返回空集合。
- `docs/release-notes.md`：记录页面行为变化及 API、数据库和授权语义不变。

## 调用链与安全边界

页面通过既有 Agent 列表接口取得当前用户可见的条目，选择值仍为 Agent ID；试运行仍调用原接口。前端过滤和权限提示只改善可操作性，不替代服务端的租户、权限和运行状态复核，也不授予工具权限。

## 与设计的差异

无后端或数据层范围扩展。真实浏览器仅验证已启用 Agent 的显示/选择与窄屏布局，没有提交试运行请求，避免产生 Run 或触发模型、工具副作用。
