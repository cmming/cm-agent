# T4 技能沙箱与产物清单

日期：2026-10-11；主题：harness-t4；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

来源：[T3 提示词](../prompts/2026-10-10-harness-t3-prompt.md)、[架构清单 T4](2026-10-10-harness-architecture-checklist.md)及用户明确的继续实施授权。独立工作包保留此前 30 份文档和既有脏文件。

| 编号 | 状态 | 目标及文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| S0 | 完成 | 当前 Skill/桥接/沙箱/产物链及环境核对 | 无 | CodeGraph 优先、POM/配置/README、JDK21、Rocky Docker 与固定 Python 镜像 |
| S1 | 完成 | 实际 Harness 固定快照、读取、脚本、预算与防重；HarnessSkillSandboxIntegrationTest | S0 | 跨审批发布新版本仍执行原版本；撤销、重复、超限拒绝，不开放宿主工具 |
| S2 | 完成 | 实际 Python、停止分类与严格失败清理；GovernedSkillAccessService 及对应单测 | S1 | 实际在途停止必须清理后 CANCELLED；超时、附加关闭失败、撤销及审计/状态读取故障保持严格失败及安全关联诊断 |
| S3 | 完成 | 加密产物、归属、TEST 与对象重建 | S1 | PENDING 在成功前不可见；成功 READY 下载可验证；主体/租户拒绝；TEST 不改正式绑定/发布/会话 |
| S4 | 完成 | 本地与 Rocky 两库验收 | S1–S3 | 本地非容器合同；Rocky PostgreSQL16/MySQL8.4 的生产链，当前源码哈希一致 |
| S5 | 完成 | 六文档与生产说明同步 | S4 | 真实证据、限制、链接与既有改动保护核对 |

沿用已有 CM Agent 治理，不引入新沙箱、Shell、原生 Skill 或文件交付通道。实际实验发现并修复“在途脚本用户停止被误判为致命超时”；其余生产链复用既有实现。本轮本地 81 项不同合同、两库各 16 项集成合同及独立 Docker 10 项通过，详见账本。真实 Provider、浏览器、强杀进程恢复、多实例接管、跨主机 TTL 不由协议桩与对象重建证明。T5/T6 未授权实施。

关联：[提示词](../prompts/2026-10-11-harness-t4-prompt.md)、[设计](../specs/2026-10-11-harness-t4-design.md)、[计划](../plans/2026-10-11-harness-t4.md)、[实现](../implementation/2026-10-11-harness-t4-implementation-design.md)、[账本](../progress/2026-10-11-harness-t4-ledger.md)。
