# 技能试运行目标 Agent 下拉选择计划

对应设计：[需求设计](../specs/2026-09-28-skill-trial-agent-dropdown-design.md)。

## 任务拆分

| 顺序 | 任务 | 涉及文件 | 验证 |
| --- | --- | --- | --- |
| 1 | 检查现有技能试运行表单、Agent 列表接口和权限语义 | `skills.js`、现有 API/权限实现 | 确认复用现有接口，不新增后端能力 |
| 2 | 将 UUID 输入替换为加载态、空态、失败态明确的 Agent 下拉框 | `skills.js`、`multipage.css` | 检查无障碍标签和原请求体兼容 |
| 3 | 覆盖可选 Agent 过滤逻辑 | `skills.test.cjs` | Node 内置测试 |
| 4 | 浏览器验证选项加载/选择及窄屏尺寸 | 本地 test profile 浏览器 | 检查 390px 视口宽度与控件边界，不执行试运行 |
| 5 | 更新发布说明与四份任务记录，检查差异 | `docs/release-notes.md`、`docs/superpowers/{specs,plans,implementation,progress}` | 定向 `git diff --check` 与工作树核对 |

## 实现顺序与边界

先通过现有 `/api/agents` 获取当前主体可见的 Agent，再在前端只提供启用项；缺少读取或运行权限时不给出可提交的目标。提交仍将所选 ID 放入原试运行请求。所有安全判断继续由服务端执行。本次不更改服务端、数据库、配置或 API。

## 验收

按设计文档的四项标准完成。由于当前任务是纯控制台交互调整，不运行 Rocky 双库验证；没有数据库或容器相关改动。提交由用户决定，本次不创建提交。
