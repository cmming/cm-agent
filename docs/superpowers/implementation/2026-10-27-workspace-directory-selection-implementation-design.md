# 工作区目录选择本次实现说明

日期：2026-10-27；主题：workspace-directory-selection；修订：R0；模式：mode=default。

## 实际交付

本次完成 T0：读取规范、技能与产物契约；检查 Git/暂存/工作树；先使用 CodeGraph 定位，再针对结果缺失复核配置、运行、接口、领域、持久化及控制台源码；生成本组六份中文文档。

文档明确了 DEFAULT/SELECTED/NONE、登记只读目录选择、会话持久绑定、Run 身份/快照、审批恢复、旧调用兼容、前端状态与验证门禁。目录位于服务端、首版只读、列表只展示登记项和首发后固定均为默认假设 A1–A6，可以通过后续需求修订调整。

## 当前已有实现与缺口

已有 `WorkspaceContextProperties → WorkspaceContextService/WorkspaceContextFiles → AgentRunRequest.workspaceContext → ReAct/Harness` 只读资料链，不是本次新增。当前资料映射按 tenant/Agent 唯一；前端没有选择入口，Conversation 没有绑定信息，接口没有选择参数。

本次没有增加 catalog、数据库表、API、运行参数、前端组件或自动化测试。T1–T7 全部待做；设计中的新对象、Repository、表、路由和行为均为拟定合同，不能当作可调用/已验证能力。

## 与方案关系及边界

没有业务实施差异可报告。需求概述中的“工作区目录”在首版按已有只读治理边界解释；通用项目读写/原生本机目录选择不在本版。后续若改变此假设，应先修订需求范围与安全模型，而不是在原方案下启用 Harness 原生文件工具。

本次仅新增六份文件，没有改动用户已有配置和发布说明。未更新 `docs/release-notes.md`：当前未改变发布行为，而且该文件已有用户修改；真正实施后由 T7 更新生产说明和发布记录。未提交。

## 验证适用范围

完成文件存在性、相对链接、日期/主题/修订/模式/任务编号一致性和空白检查；结果见账本。没有运行 Maven、数据库、真实 Provider 或浏览器验收，因为没有业务代码变更，本轮只生成需求文档。源码证据与文档检查不能代替 T6/T7 业务验收。

## 关联产物

- [清单](../checklists/2026-10-27-workspace-directory-selection-checklist.md)
- [提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)
- [设计](../specs/2026-10-27-workspace-directory-selection-design.md)
- [计划](../plans/2026-10-27-workspace-directory-selection.md)
- [账本](../progress/2026-10-27-workspace-directory-selection-ledger.md)
