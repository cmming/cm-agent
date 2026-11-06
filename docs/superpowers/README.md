# Superpowers 工作包索引

本索引登记两个长期页面需求组，以及按页面和共享运行边界合并后的三组 2026-11-05 六件套工作包；`docs/superpowers` 中的其他历史工作包仍按各自目录保留。工作包遵守六件套职责：清单定义范围和验收，设计定义方案，计划定义方法，提示词提供后续执行输入，实现说明记录实际交付，账本记录证据和剩余项。

## 当前需求组

两个需求组均使用 `mode=default`。本地项目页当前为 `R1`（2026-11-04），本机客户端页当前为 `R3`（2026-11-05）。R0 是按页面重组后的基线，不代表功能未实施；后续修订分别补全授权模式、权限、配对生命周期、设备管理状态、首次连接指引和客户端可执行 fat JAR 分发。

### 本地项目页

页面：`/console/v2/projects.html`
主题：`2026-11-03-projects-page`

| 文档 | 入口 |
|---|---|
| 清单 | [本地项目页清单](checklists/2026-11-03-projects-page-checklist.md) |
| 设计 | [本地项目页设计](specs/2026-11-03-projects-page-design.md) |
| 计划 | [本地项目页计划](plans/2026-11-03-projects-page.md) |
| 提示词 | [本地项目页提示词](prompts/2026-11-03-projects-page-prompt.md) |
| 实现说明 | [本地项目页实现说明](implementation/2026-11-03-projects-page-implementation-design.md) |
| 账本 | [本地项目页账本](progress/2026-11-03-projects-page-ledger.md) |

范围：本地文件夹登记、项目列表/详情、文件树/搜索/预览、项目会话、固定 `project_*` 工具、手工工具目录登记兼容入口、三种项目内授权模式、审批/撤销/命令、异步未知状态和响应式页面。

### 本机客户端页

页面：`/console/v2/local-client.html`
主题：`2026-11-03-local-client-page`

| 文档 | 入口 |
|---|---|
| 清单 | [本机客户端页清单](checklists/2026-11-03-local-client-page-checklist.md) |
| 设计 | [本机客户端页设计](specs/2026-11-03-local-client-page-design.md) |
| 计划 | [本机客户端页计划](plans/2026-11-03-local-client-page.md) |
| 提示词 | [本机客户端页提示词](prompts/2026-11-03-local-client-page-prompt.md) |
| 实现说明 | [本机客户端页实现说明](implementation/2026-11-03-local-client-page-implementation-design.md) |
| 账本 | [本机客户端页账本](progress/2026-11-03-local-client-page-ledger.md) |

范围：签名配对包、本机确认、RBAC、设备密钥存储、客户端最终落盘、设备列表/详情、二次撤销、三种 Run 授权模式边界、可独立运行的 fat JAR 分发、Windows/Linux/macOS 客户端和远程 Server 后续门禁。页面本身不执行文件、网络或命令。

## 跨组边界

| 能力 | 本地项目页 | 本机客户端页 |
|---|---|---|
| 文件根 | Server 所在电脑上的已登记项目目录 | 浏览器所在电脑的用户账号可访问范围 |
| 执行位置 | Server 运行账号 | 配对设备上的用户账号 |
| `FULL_ACCESS` | 当前项目、Agent、Run 的固定 `project_*` 工具 | 当前 Run 内已授权设备类别，受设备身份和服务端约束 |
| 页面职责 | 项目登记、文件工作台、项目会话和审批 | 配对包、设备列表/详情、撤销和授权边界说明 |

两组可互相引用，但不能用一个页面的验收结果证明另一个页面的能力。

### 2026-11-05 合并工作包

当日五个过程主题已按页面和共享运行边界合并为三组，不再保留单文件总记录或原五个独立工作包。

| 范围 | 合并来源 | 六件套入口 |
|---|---|---|
| 本机客户端页 | `local-client-tool-input-fix` R1 | [清单](checklists/2026-11-05-local-client-page-checklist.md)、[设计](specs/2026-11-05-local-client-page-design.md)、[计划](plans/2026-11-05-local-client-page.md)、[提示词](prompts/2026-11-05-local-client-page-prompt.md)、[实现](implementation/2026-11-05-local-client-page-implementation-design.md)、[账本](progress/2026-11-05-local-client-page-ledger.md) |
| Projects 页 | `project-layout-refinement` R0、`local-project-non-loopback-switch` R1 | [清单](checklists/2026-11-05-projects-page-checklist.md)、[设计](specs/2026-11-05-projects-page-design.md)、[计划](plans/2026-11-05-projects-page.md)、[提示词](prompts/2026-11-05-projects-page-prompt.md)、[实现](implementation/2026-11-05-projects-page-implementation-design.md)、[账本](progress/2026-11-05-projects-page-ledger.md) |
| 共享运行环境 | `local-runtime-script` R3、`lan-console-listening` R0 | [清单](checklists/2026-11-05-shared-runtime-checklist.md)、[设计](specs/2026-11-05-shared-runtime-design.md)、[计划](plans/2026-11-05-shared-runtime.md)、[提示词](prompts/2026-11-05-shared-runtime-prompt.md)、[实现](implementation/2026-11-05-shared-runtime-implementation-design.md)、[账本](progress/2026-11-05-shared-runtime-ledger.md) |

## 历史来源

原 2026-10-29 本地项目工作区、2026-11-02 智能体操作授权和 2026-11-02 项目页样式工作包已按页面合并到上述长期工作包；原 2026-11-05 的五组过程文档和中间单文件记录已删除。历史来源名称、关键决定和证据适用范围保留在三个合并工作包中。
