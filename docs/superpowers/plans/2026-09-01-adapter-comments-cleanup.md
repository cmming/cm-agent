# 2026-09-01 agentscope-adapter 注释整改计划

## 任务拆分与实现顺序

1. **主代码补注释**
   - `AgentScopeReActExecutor#executeStructured` 补 JavaDoc：说明生产主流程定位、事件归并职责与脱敏边界。
   - `TIMEOUT_MESSAGE` / `FAILURE_MESSAGE` / `MODEL_TIMEOUT_PREFIX` 三个常量补注释：说明固定中文文案的脱敏目的与 `MODEL_TIMEOUT_PREFIX` 对 AgentScope 2.0.0 消息格式的版本依赖。
   - 涉及文件：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java`。
2. **新增包级说明**
   - 创建 `package-info.java`：说明适配层定位、`AgentScopeRuntimeAdapter` → `AgentScopeReActExecutor` → `AgentScopeToolBridge` 协作链、中间类型包内可见的设计意图，以及 AgentScope 扩展依赖为 optional 的边界。
3. **测试注释位置修正**
   - 用 PowerShell 正则把 `@Test` / `@Override` / `@BeforeEach` / `@AfterEach` 之后的多行 JavaDoc 块移动到注解之前。
   - 覆盖 `AgentScopeToolBridgeTest`、`AgentScopeRuntimeContractTest`、`AgentScopeRuntimeAdapterTest`、`AgentScopeModelFactoryTest` 共 74 处。
4. **测试模板注释清理**
   - 删除含“所描述的业务行为”“验证 `{@code X}` …”“`@param x` 测试辅助方法使用的…”标记的模板 JavaDoc。
   - 清理残留空 JavaDoc 块与多余空行。
   - 有实质信息的注释（如“本合同刻意模拟忽略中断的外部工具”）一律保留。
5. **验证**
   - `mvn -q "-DskipTests" compile -pl cm-agent-agentscope-adapter -am`：主代码编译通过。
   - `mvn test -pl cm-agent-agentscope-adapter -am`：全部测试通过。
   - grep 确认模板标记清零、损坏 import 已修复。

## 风险预案

- 正则跨块删除：限定 `/\*\*[\s\S]*?\*/` 非贪婪边界，逐次转换后用“`/\*\*` 与 `*/` 数量配对”脚本校验。
- 注解与方法签名被合并到同一行：转换后专门补换行，再统一恢复缩进。
- 单模块测试（无 `-am`）报 `NoClassDefFoundError: MessageContentBlock`：为本地仓库旧 core 快照问题，非本次改动导致；始终带 `-am` 联编验证。

## 验证方式

- 每步机械转换后用 PowerShell 校验 JavaDoc 配对数与“悬空结构”数量。
- 最终以 Maven 构建结果与测试统计为准：预期 adapter 模块 54 个测试全部通过，`BUILD SUCCESS`。

