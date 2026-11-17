# 文档导航清理：执行提示词

日期：2026-11-13；主题：`doc-navigation-cleanup`；修订：R4；模式：`mode=default`。

以下提示词记录本次实施范围；提示词本身不额外授权提交、推送或部署。

```text
仓库：F:/java/cm-agent
需求：只执行已确认的 P0+P1 文档整理。

先读取 AGENTS.md、本次清单、设计和计划，核对 Git 工作树。保护现有修改，尤其不得改动 2026-11-09 和 2026-11-12 工作包。

P0：删除 AGENTS.md 第 230 行开始的外来 changeDescription、建议代码和 userPrompt 尾段，恢复正常规则文件。
P1：复核已识别的 4 个本地 Markdown 链接候选，修复实际失效链接；fenced code sample 按其生成后文档的上下文判断，不机械套用当前文件相对路径；新增 docs/README.md 作为根文档入口，并让根 README 首先指向该入口。

不执行技术架构事实重写、工作包批量索引、历史文件移动或合并。完成后扫描全部可见 Markdown 的本地链接，运行 git diff --check，并核对受保护工作包没有被本次任务改写。按仓库要求维护本次 2026-11-13 六份中文工作包，明确未提交状态。
```

## 关联文档

- [清单](../checklists/2026-11-13-doc-navigation-cleanup-checklist.md)
- [设计](../specs/2026-11-13-doc-navigation-cleanup-design.md)
- [计划](../plans/2026-11-13-doc-navigation-cleanup.md)
- [实现说明](../implementation/2026-11-13-doc-navigation-cleanup-implementation-design.md)
- [进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)

## 修订 R1：P2 执行提示词

```text
仓库：F:/java/cm-agent
需求：执行 P2，建立当前事实来源矩阵并刷新技术架构、部署指南和路线图中的滞后能力描述。

先读取 docs/README.md、technical-architecture.md、deployment.md、roadmap.md、配置/运维说明、根 README、相关 Controller 和 Flyway 迁移目录。当前工作树有大量既有修改；不得覆盖用户改动，不修改 2026-11-09 和 2026-11-12 工作包，不修改业务代码。

在 docs/README.md 明确代码/配置/测试、专项生产文档、README、发布说明和工作包历史的权威顺序。刷新三份目标文档时，以当前代码证据为准，区分已交付核心能力、默认关闭或未公开发布能力、尚未交付能力。迁移说明使用当前 V31 头和稳定分组，部署前仍以迁移目录与 flyway_schema_history 为准。

完成后检查旧措辞、控制台 11 个入口、cancel/SSE 路由、默认配置和迁移版本；运行 Markdown 本地链接扫描、git diff --check 和受保护路径状态检查。同步更新本次六份工作包的 R1 记录，不提交、不推送、不部署。
```

## 修订 R2：P3 执行提示词

```text
仓库：F:/java/cm-agent
需求：执行 P3，为 docs/superpowers 下的全部工作包建立统一状态目录。

先读取 docs/superpowers/README.md、本次清单/设计/计划和 Git 工作树。不得修改、移动或删除已有工作包，尤其不得改写 2026-11-09 和 2026-11-12 的现有文件。

扫描六个工作包目录，按日期和规范化主题分组。目录展示日期、主题、文档集合数量、显式最高修订、最新账本、当前 Git 未提交状态和后续入口。未声明修订不得猜测；缺少账本必须保留为空。后续入口优先提示词，其次计划或账本，并说明提示词只是形成时输入，不构成新的执行授权。

新增 docs/superpowers/WORKPACK-STATUS.md，并从 docs/superpowers/README.md 与 docs/README.md 链接。确认基线 112 组与当前 113 组的差异来自本次 2026-11-13 维护工作包。完成后验证主题计数、文件覆盖、链接、空白和受保护路径；同步维护本次六份工作包的 R2 记录，不提交、不推送、不部署。
```

## 修订 R3：P4 执行提示词

```text
仓库：F:/java/cm-agent
需求：收紧六件套职责，让进度账本成为实时状态和验证证据的唯一入口，其他五份保留固定职责和账本链接。

先读取 AGENTS.md、docs/README.md、docs/superpowers/README.md、requirement-workpack 的 SKILL.md 与 artifact-contract.md，以及本次工作包当前账本。保护现有修改，不批量回写历史工作包，不修改 2026-11-09 和 2026-11-12 工作包。

将规则落实为：清单定义稳定任务、依赖和验收；提示词保存某一修订的执行输入；设计记录方案与决定；计划记录实施顺序和验证方法；实现说明记录实际改动及设计差异；账本唯一维护实时状态、命令结果、验证证据、阻塞、提交和发布信息。其他五份不得复制状态表，只在需要时链接账本。

选择迁移前跨文件重复度最高的完整、干净历史六件套 2026-10-01-remote-docker-password-auth，将其提升到自然下一修订 R2；保留 R0 归档，把 R1 的实时状态和验证证据集中到账本，其他五份改为固定职责和账本链接。把当前工作包提升为 R3，以自身作为新契约示例；R0–R2 历史状态保留。更新 WORKPACK-STATUS.md 中两个主题的当前修订。完成后重新计算迁移组相似度，检查元数据、任务编号、冲突措辞、链接、空白和受保护路径；不提交、不推送、不部署。
```

当前修订的执行状态、验证结果和提交信息只以[进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)为准。

## 修订 R4 当前执行输入（2026-11-13）

以下只用于本批文档迁移，不执行业务实现。

```text
仓库：F:/java/cm-agent
工作包：2026-11-13 / doc-navigation-cleanup / R4 / mode=default。

先读取本提示词、清单、设计、计划、实现说明、进度账本和工作树状态。补齐 2026-10-08-skill-import-description 的 R1 账本，再把 2026-10-27-workspace-directory-selection-implementation 从 R2 迁移到 R3、2026-09-30-skill-sandbox 从 R1 迁移到 R2。

非账本活动区只保留各自固定职责；任务状态、命令结果、测试证据、阻塞和提交信息只写账本；迁移前全文完整归档。不得重做历史业务，不得把未完成门禁改成完成，不得修改业务源码、配置、数据库或运行中的服务。

保护 2026-11-09 和 2026-11-12 工作包及所有无关修改。完成后更新 WORKPACK-STATUS.md，检查修订、任务编号、链接、代码围栏、空白、Git 范围和保护路径哈希。不提交、不推送、不部署。
```

关联另外五份文档和[进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)。
