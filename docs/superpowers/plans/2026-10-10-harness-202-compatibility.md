# Harness 2.0.2 运行兼容实验计划

日期：2026-10-10；主题：harness-202-compatibility；修订：R0；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

## 顺序与文件

来源：[原分析 R1 提示词](../prompts/2026-10-10-harness-architecture-prompt.md)。用户最初授权 2.0.4 运行实验，随后要求全部 AgentScope 升级 2.0.4，最终明确改为统一保持 2.0.2；以最后决定为准。正式 POM 未发生升级，旧分析包保留历史。本包独立记录已授权运行实验，继续原 T1，不扩大到生产接入。

“一致 2.0.2”适用于 io.agentscope 构件，不能把其他厂商依赖也改成此版本。Server MCP core/json 保留 2.0.0；独立 MCP 示例客户端保留 0.17.0。Jackson 2.19.0、SQLite 3.49.1.0 沿用项目 BOM 覆盖。

| 编号 | 状态 | 目标 | 依赖 | 验收与证据 |
| --- | --- | --- | --- | --- |
| C0 | 完成 | 核对授权、规范、基线和保护已有修改 | 无 | 正式 POM/源码/配置及原分析六文档 SHA256 不变，HEAD/暂存区未变 |
| C1 | 完成 | AgentScope 同版 2.0.2 依赖核查 | C0 | 十二项目 reactor dependency:tree 成功；Core/两种 Provider/Studio/Redis 2.0.2；实验 Harness 同版 |
| C2 | 完成 | 隔离编译与模型 Stub 运行合同 | C1 | clean test：104 项，103 通过、1 条件跳过；其中 Harness 新增 13 项通过 |
| C3 | 完成 | 记录准入失败与宿主生命周期边界 | C2 | G1/G2 已复现并保留特征测试；不宣称生产准入通过 |
| C4 | 完成 | 实验工程、六份工作包与一致性检查 | C3 | 实验不加入根 reactor，原分析包不覆盖，文档链接/差异检查通过 |

按 C0→C1→C2→C3→C4 执行：先记录原文件哈希，再核对发布 2.0.2 源码和 javap；编译当前源码和现有合同；新增 Harness 运行测试；保留失败验收与缺陷特征；独立文档记录实际结果。

实验文件：`experiments/harness-compatibility-2.0.2/pom.xml`、`README.md`、`src/test/java/com/cmagent/agentscope/Harness202ContractTest.java`。正式模块 POM 不变；build-helper 添加源目录，不复制或改写原执行器。

## 验证方法

~~~powershell
$env:JAVA_HOME='F:/java21'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
java -version
mvn -v
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml clean test
mvn -B dependency:tree "-Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:*" -Dverbose
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml dependency:tree -Dverbose
~~~

原始发布源码/JAR 与 2.0.4 中止草稿只作为忽略目录内证据。对正式 POM/源码/配置、原脏文件、原分析包比较哈希；检查 target 被忽略、无提交/暂存。文档状态只按当前结果更新。

首轮期望“Builder 后只有白名单”“DONT_ASK 不出现确认”的断言失败时，不降低生产约束；过滤只留在实验，DONT_ASK 保留缺陷特征并标明准入失败。

## 关联产物

- [清单](../checklists/2026-10-10-harness-202-compatibility-checklist.md)
- [提示词](../prompts/2026-10-10-harness-202-compatibility-prompt.md)
- [设计](../specs/2026-10-10-harness-202-compatibility-design.md)
- [实现说明](../implementation/2026-10-10-harness-202-compatibility-implementation-design.md)
- [账本](../progress/2026-10-10-harness-202-compatibility-ledger.md)
