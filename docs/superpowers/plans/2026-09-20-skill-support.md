# Skill 支持实施计划

> **面向执行代理：** 用户已于 2026-09-21 选择 A，由主代理使用 `superpowers:executing-plans` 在隔离工作树中按任务执行。所有步骤使用复选框记录实际进度。

**目标：** 一次性交付租户隔离的指令与资源型 Skill 管理、Agent 绑定、自动按需加载，以及前端完整操作和排查闭环。

**架构：** Core 定义平台契约，Server 管理技能、授权、事务与审计，Persistence 提供 JDBC 实现，Adapter 通过受控入口复用 AgentScope 的原生技能提示与加载。每次 Run 固定不可变技能版本，读取与审批恢复复核撤销状态；Console 保留现有中文多页面架构。

**技术栈：** Java 21、Spring Boot 3.5.0、AgentScope 2.0.2、现有 JDBC/Flyway、PostgreSQL 16/MySQL 8.4、原生 HTML/CSS/JavaScript、JUnit/MockMvc、Node 内置测试。

**设计依据：** [已确认设计](../specs/2026-09-20-skill-support-design.md)。同时阅读[实现说明](../implementation/2026-09-20-skill-support-implementation-design.md)和[进度账本](../progress/2026-09-20-skill-support-ledger.md)。主题日期保持 2026-09-20，计划编写日期为 2026-09-21。

## 全局约束

- Java 21；AgentScope 2.0.2；本需求不升级 AgentScope 或 MCP，不引入 JPA、MyBatis、新前端框架、对象存储或向量数据库。
- “第一版只接收明确支持的文本资源”；不执行脚本、不自动联网、不授予业务 ToolGrant。
- “一个 ZIP 只包含一个技能”；“初次上传默认停用”；“同一技能上传更新形成新版本”。
- ZIP 2 MiB，解压文本 4 MiB，64 个普通文件，SKILL.md 32 KiB，单资源 64 KiB，路径 240 字符，绑定 20 个技能；单 Run 准备 8 MiB，读取 32 次/256 KiB，恢复不重置预算。
- “同一次运行固定版本”；“停用后重新启用”“解绑后重新绑定”不复活旧快照。
- 所有数据库访问保持 tenant 条件，权限来自认证主体；错误响应沿用 `code/message/timestamp/errorId`。
- “只有在原生读取成功且最终状态复核、读取记录及严格审计成功提交后，才能将正文交给模型”。
- 新增/修改中文 JavaDoc 按 AGENTS.md 触发清单落地，record 的全部组件使用 `@param` 说明，安全和资源生命周期解释原因。
- “前端必须和后端在本次需求中一并完成”；保留七个现有业务页，新增技能页，v1 不加载技能组件。
- Docker、Testcontainers、JDBC、Flyway 测试仅在 `ssh rocky` 的 `maven:3.9.9-eclipse-temurin-21` 容器中执行；验证提交必须与本地一致。
- 保留现有配置及 `.codex/`、`.impeccable/critique/`、`.workbuddy/` 改动。禁止 `git add -A`、提交测试凭据或生成物。
- 未执行的测试标记为未执行；原生接口已存在不等于 Skill 功能已通过验证。

## 评审重点

1. **旧 Run 与空技能集合：** 空快照有效，快照缺失不能回退最新内容；T4 回填迁移、T6 恢复测试覆盖。
2. **撤销再启用/再绑定与并发读取：** 纪元和绑定身份变化永久使旧快照失效，事务提交决定可见边界；T3/T4/T6 覆盖。
3. **原生错误被框架吞掉：** 审计、持久化、撤销故障必须阻止后续业务工具；T6/T7 覆盖共享中止门控。
4. **前端旧会话和慢响应：** A 资源的迟到结果不覆盖 B，旧 401 不注销新会话；T8/T9/T10 覆盖。
5. **包内异常条目与文本注入：** 中央目录链接、大小写冲突、YAML 别名、HTML 文本、未知 multipart 错误不能绕过校验；T2/T5/T9/T11 覆盖。

## 文件与接口总图

下列前缀仅为本节便于阅读；每个任务的文件清单给出完整仓库相对路径。

| 所在目录 | 类型/文件 | 职责 |
| --- | --- | --- |
| Core domain | `SkillDefinition`、`SkillVersion`、`SkillResource`、`AgentSkillBinding` | 稳定身份、不可变内容与绑定 |
| Core domain | `SkillVersionView`、`SkillSnapshotRef`、`RunSkillSnapshot`、`SkillRuntimeBundle`、`SkillLoadRecord`、`SkillLoadStatus` | 版本资源聚合、Run 快照、实际读取记录 |
| Core repository | 六个 `Skill*Repository`/`AgentSkillBindingRepository`/`RunSkillSnapshotRepository` 接口 | 租户隔离存取与锁定 |
| Core runtime | `SkillAccessGateway`、`SkillReadRequest`、`SkillReadResult`、`SkillAccessException` | 受控读取契约，不出现原生框架类型 |
| Server service | `SkillPackageParser`、`ParsedSkillPackage`、`SkillPackageLimits`、`SkillManagementService`、`SkillQueryService` | 导入校验、管理事务、查询 DTO |
| Server runtime | `SkillRuntimeService`、`GovernedSkillAccessService` | 快照创建/恢复、读时撤销与预算复核 |
| Server store/config | `InMemorySkillStore`、`SkillUnitOfWork`、`SkillConfiguration`、`SkillProperties` | memory 工作单元、JDBC 事务装配与配置 |
| Adapter | `AgentScopeSkillRepository`、`AgentScopeSkillSession`、`AgentScopeSkillLoadBridge` | 框架映射、原生读取委托、受控包装 |
| Console | `console/v2/skills.html`、`console/v2/assets/skills.js` | 技能工作区、绑定和读取记录组件 |

### 契约字典

所有 record 字段按下列签名保持一致；正式源码补齐中文 JavaDoc、非空/长度/范围校验及集合防御性复制。正文类型均为 UTF-8 文本，摘要为 SHA-256 十六进制字符串。

```java
record SkillDefinition(UUID id, UUID tenantId, String name, UUID currentVersionId,
        boolean enabled, long accessEpoch, String createdBy, String updatedBy,
        Instant createdAt, Instant updatedAt) {}
record SkillVersion(UUID id, UUID tenantId, UUID skillId, int versionNo,
        String description, Map<String, Object> metadata, String content,
        String sha256, String createdBy, Instant createdAt) {}
record SkillResource(UUID tenantId, UUID skillId, UUID versionId, String path,
        String mediaType, String content, int byteLength, String sha256) {}
record AgentSkillBinding(UUID id, UUID tenantId, UUID agentId, UUID skillId,
        String boundBy, Instant createdAt) {}
record SkillVersionView(SkillDefinition definition, SkillVersion version,
        List<SkillResource> resources) {}
record SkillSnapshotRef(UUID skillId, UUID versionId, UUID bindingId, long accessEpoch) {}
record RunSkillSnapshot(UUID tenantId, UUID runId, UUID agentId, int formatVersion,
        List<SkillSnapshotRef> skills, Instant createdAt) {}
record SkillRuntimeBundle(RunSkillSnapshot snapshot, List<SkillVersionView> versions) {}
enum SkillLoadStatus { SUCCEEDED, FAILED, DENIED }
record SkillLoadRecord(UUID id, UUID tenantId, UUID runId, String modelCallId,
        int attemptNo, UUID skillId, UUID versionId, String path,
        SkillLoadStatus status, int deliveredBytes, long durationMillis,
        ApiErrorCode errorCode, String errorId, Instant createdAt) {}
record SkillReadRequest(PrincipalRef principal, UUID agentId, UUID runId,
        String modelCallId, UUID attemptId, UUID skillId, UUID versionId, String path) {}
record SkillReadResult(String content, UUID recordId) {}
interface SkillAccessGateway {
    SkillReadResult load(SkillReadRequest request, Supplier<String> nativeLoader);
}
```

`SkillAccessException` 为 Core 中受控运行异常，构造签名为 `(ApiErrorCode code, String safeMessage, String errorId, boolean fatal)`，提供同名访问器；不持有 HTTP 状态，Server 映射为 HTTP/SSE，Adapter 根据 fatal 控制是否继续。原始异常另交最终诊断边界，不拼入 safeMessage。

`SkillReadRequest` 的上下文全部来自当前 `AgentRunRequest` 和已解析的快照映射；模型仅提供原生 ID、路径。无法解析的 ID 使用空技能字段记录拒绝，不能替换成其他租户的对象。

运行请求在原有组件后追加 `List<SkillVersionView> skills`，保持七参数和八参数构造器并默认 `List.of()`；快照归属由 Server 校验，Core 构造器再次核对全部版本/资源 tenant、skillId、versionId 及名称唯一性。

### 任务顺序

| 任务 | 依赖 | 可评审结果 |
| --- | --- | --- |
| T1 | 无 | 平台领域与运行契约 |
| T2 | T1 | 有界、无落盘技能包解析 |
| T3 | T1 | memory 存取与受控工作单元 |
| T4 | T1、T3 | 双数据库迁移和相同 Repository 合同 |
| T5 | T2～T4 | 完整管理 API、权限与诊断 |
| T6 | T3～T5 | Run 快照、撤销、读取记录和审批恢复 |
| T7 | T1、T6 | 真实 AgentScope 自动加载 |
| T8 | T5 接口定义 | 兼容 multipart 的前端公共能力 |
| T9 | T5、T8 | 技能页和导航 |
| T10 | T6～T9 | Agent 绑定、聊天错误和运行记录 |
| T11 | T1～T10 | 前后端闭环、回归与正式文档 |

顺序执行即可；此依赖图不要求创建子代理。每个任务最后只提交自己的明确文件。以 T1 为例，`git add -- <本任务逐一列出的路径>` 后检查 `git diff --cached --check`、`git diff --cached --name-only`，再使用该任务给出的中文提交说明；不得用目录通配符把无关文件一并暂存。

## 验证环境与远程传递约定

本地快速测试先设置并确认 JDK 21：

```powershell
$env:JAVA_HOME = 'F:\java\temurin21\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -v
```

每项测试先增加会失败的测试，再实现，再执行相同测试得到通过结果。上游模块没有同名测试时统一加 `-Dsurefire.failIfNoSpecifiedTests=false`；这不能用于隐藏目标测试缺失，须同时核对目标测试的实际执行数量。

需要远程数据库验证时，先保存仅本任务路径的可验证提交。可用测试提交承载预期失败用例，再提交实现；每次只传递实际待验证的 HEAD：

```powershell
$skillRevision = (git rev-parse HEAD).Trim()
if ($skillRevision -notmatch '^[0-9a-f]{40}$') { throw '提交编号格式异常' }
$skillBundle = Join-Path $env:TEMP "cm-agent-skills-$skillRevision.bundle"
git bundle create $skillBundle HEAD
scp $skillBundle "rocky:/tmp/cm-agent-skills-$skillRevision.bundle"
ssh rocky "git clone '/tmp/cm-agent-skills-$skillRevision.bundle' '/tmp/cm-agent-skills-$skillRevision'"
ssh rocky "git -C '/tmp/cm-agent-skills-$skillRevision' rev-parse HEAD"
ssh rocky docker info --format '{{.ServerVersion}}'
```

若同名目录已存在，先核对 HEAD 和工作区干净状态，确认完全一致才复用；不覆盖、不删除已有远程目录。将前一步实际生成的 40 位提交号传给下列脚本的第一个参数，在 Rocky 会话内执行；脚本本身不使用猜测的远程项目路径：

```sh
set -eu
expected="$1"
workspace="/tmp/cm-agent-skills-$expected"
test "$(git -C "$workspace" rev-parse HEAD)" = "$expected"
test -z "$(git -C "$workspace" status --porcelain)"
docker run --rm \
  -v "$workspace:$workspace" -w "$workspace" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-21 mvn -v
docker run --rm \
  -v "$workspace:$workspace" -w "$workspace" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B -pl cm-agent-persistence -am \
  -Dtest=MigrationTest,JdbcSkillRepositoriesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最后一条是 T4 的测试集合；T5/T6/T11 的服务器测试使用各自指定的 `-pl` 和 `-Dtest` 替换这两个参数。仅运行项目测试所需容器，不做 Docker 全局清理；失败输出进入本项目测试结果，不回传生产配置、凭据或完整生产连接串。无法 SSH/拉取镜像/确认提交时记录具体阻碍，不将未执行写成通过。

---

## Task 1：定义技能领域、错误与运行契约

**文件：**

- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/` 下契约字典列出的 10 个 Skill 领域类型，每个类型独立文件。
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/runtime/SkillAccessGateway.java`、`SkillReadRequest.java`、`SkillReadResult.java`、`SkillAccessException.java`。
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java`、`cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java`。
- 新增 `cm-agent-core/src/test/java/com/cmagent/core/domain/SkillDomainTest.java`；修改同目录 `AgentRunRequestTest.java`。

**输入：** 现有 `PrincipalRef`、`ApiErrorCode`、`AgentRunRequest`。

**输出：** 契约字典中的完整签名；`SkillAccessException` 和十个技能错误码：`SKILL_PACKAGE_INVALID`、`SKILL_RESOURCE_UNSUPPORTED`、`SKILL_PACKAGE_TOO_LARGE`、`SKILL_CONFLICT`、`SKILL_FEATURE_DISABLED`、`SKILL_NOT_FOUND`、`SKILL_ACCESS_REVOKED`、`SKILL_SNAPSHOT_UNAVAILABLE`、`SKILL_LOAD_LIMIT_EXCEEDED`、`SKILL_LOAD_FAILED`。

- [x] **1. 写失败测试：** 在 `SkillDomainTest` 中增加空快照与集合不可变测试，再在既有请求测试增加混租户技能拒绝和旧构造器空列表断言。

```java
@Test
void 空快照有效且构造后不能被外部集合修改() {
    UUID tenant = UUID.randomUUID();
    List<SkillSnapshotRef> refs = new ArrayList<>();
    RunSkillSnapshot snapshot = new RunSkillSnapshot(
            tenant, UUID.randomUUID(), UUID.randomUUID(), 1, refs, Instant.EPOCH);
    refs.add(new SkillSnapshotRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0));
    assertThat(snapshot.skills()).isEmpty();
    assertThatThrownBy(() -> snapshot.skills().add(refs.getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
}
```

- [x] **2. 执行红灯：** `mvn -pl cm-agent-core -am -Dtest=SkillDomainTest,AgentRunRequestTest -Dsurefire.failIfNoSpecifiedTests=false test`。预期新增类型缺失或断言失败，核对不是 JDK 版本错误。
- [x] **3. 实现领域校验：** 严格使用契约字典签名。版本号从 1 开始，纪元非负；资源 byteLength 必须等于 UTF-8 实际长度；快照只接受格式版本 1；嵌套 metadata 必须递归冻结，不能仅复制最外层 Map。

```java
public RunSkillSnapshot {
    Objects.requireNonNull(tenantId, "tenantId 不能为空");
    Objects.requireNonNull(runId, "runId 不能为空");
    Objects.requireNonNull(agentId, "agentId 不能为空");
    Objects.requireNonNull(createdAt, "createdAt 不能为空");
    if (formatVersion != 1) throw new IllegalArgumentException("不支持的技能快照格式");
    skills = List.copyOf(skills);
    if (skills.stream().map(SkillSnapshotRef::skillId).distinct().count() != skills.size()) {
        throw new IllegalArgumentException("运行技能不能重复");
    }
}
```

保留旧请求构造器，八参数入口使用以下完整委托，七参数入口先补空 conversationId；每个新增字段与枚举常量写中文说明。

```java
this(runId, tenantId, agent, modelConfig, principal, input, tools, conversationId, List.of());
```

- [x] **4. 同命令执行绿灯：** 增加空值、负纪元、混版本资源、重复技能、metadata 嵌套修改以及旧构造器测试后一次性执行。
- [x] **5. 检查本任务差异并提交：** `git commit -m "feat: 定义技能领域与运行契约"`。

## Task 2：实现无落盘的技能包校验与配置

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillPackageParser.java`、`ParsedSkillPackage.java`、`SkillPackageLimits.java`。
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/config/SkillProperties.java`、`SkillConfiguration.java`。
- 修改 `cm-agent-server/pom.xml`；确需统一版本时修改父 `pom.xml` 的本次新增依赖管理项。
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/service/SkillPackageParserTest.java`、`cm-agent-server/src/test/java/com/cmagent/server/config/SkillPropertiesTest.java`。

**接口：**

```java
record SkillPackageLimits(int zipBytes, int expandedBytes, int fileCount,
        int instructionBytes, int resourceBytes, int pathLength) {
    static SkillPackageLimits defaults() {
        return new SkillPackageLimits(2 * 1024 * 1024, 4 * 1024 * 1024,
                64, 32 * 1024, 64 * 1024, 240);
    }
}
record ParsedSkillPackage(String name, String description, Map<String, Object> metadata,
        String content, Map<String, String> resources, String sha256) {}
// SkillPackageParser 的公开入口。
ParsedSkillPackage parse(InputStream input, SkillPackageLimits limits);
```

- [ ] **1. 写红灯测试：** 测试类增加以下 ZIP 辅助函数（仅用于测试，不输出凭据），参数化校验逃逸、重复路径、脚本、二进制及限额。

```java
private static byte[] zip(Map<String, String> files) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            out.putNextEntry(new ZipEntry(entry.getKey()));
            out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
    return bytes.toByteArray();
}

@Test
void 拒绝压缩包中的父目录路径() throws Exception {
    byte[] bytes = zip(Map.of("SKILL.md", "---\nname: test\ndescription: 测试\n---\n正文",
            "../outside.txt", "不可读取"));
    assertThatThrownBy(() -> new SkillPackageParser().parse(
            new ByteArrayInputStream(bytes), SkillPackageLimits.defaults()))
            .isInstanceOf(SkillAccessException.class)
            .extracting("code").isEqualTo(ApiErrorCode.SKILL_PACKAGE_INVALID);
}
```

- [ ] **2. 执行：** `mvn -pl cm-agent-server -am -Dtest=SkillPackageParserTest,SkillPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **3. 实现：** 将输入限制为 zipBytes + 1 后拒绝超限；使用 Commons Compress 中央目录读取链接属性，使用内存可寻址通道，不用 `Files.createTempDirectory`。当前本地依赖中 Commons Compress 为 1.27.1，显式声明相同版本以固定接口，不借本任务升级；SnakeYAML 2.4 由 Spring Boot BOM 管理，作为直接依赖声明。

```java
LoaderOptions options = new LoaderOptions();
options.setAllowDuplicateKeys(false);
options.setMaxAliasesForCollections(0);
options.setNestingDepthLimit(8);
options.setCodePointLimit(limits.instructionBytes());
Yaml yaml = new Yaml(new SafeConstructor(options));
```

读取后仅接受普通 Map/List/String/Number/Boolean/null，显式拒绝文档中的任何 YAML alias/anchor 事件（仅设置集合 alias 数不覆盖标量 alias）；使用 UTF-8 解码器 `CodingErrorAction.REPORT`。路径先验证再保存，不能 normalize 后掩盖 `..`。最多扫描 256 个归档条目（包含目录），普通文件不超过 64，且所有上限随配置降低而收紧。

先确定唯一 SKILL.md 根，再把所有资源转换为无包装目录的相对路径；校验 name、description、正文、类型和资源总字节。摘要使用排序路径、长度前缀及规范化元数据的 UTF-8 字节，避免简单字符串拼接歧义；同一包 ZIP 时间戳变化不改变摘要。

配置使用 `cm-agent.skills.enabled=false`，其余属性为 `max-zip-bytes`、`max-expanded-bytes`、`max-files`、`max-instruction-bytes`、`max-resource-bytes`、`max-path-length`、`max-bound-skills`、`max-run-bytes`、`max-load-attempts`、`max-loaded-bytes`。只允许 1 到设计默认值的范围，若未来需要放大限制须重新评估而非默许无上限配置；properties.validate 在启动时执行。

- [ ] **4. 验证：** 同命令绿灯，增加真正中央目录 symlink、加密标志、未知长度、截断 ZIP、两个 SKILL.md、非法 UTF-8、大小写冲突、标量 alias、禁用对象 tag 和“相同内容不同 ZIP 时间戳摘要相同”测试。
- [ ] **5. 提交：** `git commit -m "feat: 增加受限技能包解析与配置"`。

## Task 3：实现 Repository 契约和 memory 工作单元

**文件：**

- 新增 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDefinitionRepository.java`、`SkillVersionRepository.java`、`SkillResourceRepository.java`、`AgentSkillBindingRepository.java`、`RunSkillSnapshotRepository.java`、`SkillLoadRecordRepository.java`。
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/store/InMemorySkillStore.java`、`cm-agent-server/src/main/java/com/cmagent/server/service/SkillUnitOfWork.java`。
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`、T2 的 `SkillConfiguration.java`。
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/store/InMemorySkillStoreTest.java`。

**Repository 产出签名（每个接口独立文件）：**

```java
// SkillDefinitionRepository
Optional<SkillDefinition> find(UUID tenantId, UUID skillId);
Optional<SkillDefinition> findByName(UUID tenantId, String name);
ApiPageResponse<SkillDefinition> list(UUID tenantId, String query, Boolean enabled, ApiPageRequest page);
SkillDefinition insert(SkillDefinition definition);
SkillDefinition lock(UUID tenantId, UUID skillId);
boolean updateCurrent(SkillDefinition next, UUID expectedVersionId);
void updateEnabled(SkillDefinition next);
// SkillVersionRepository
void insert(SkillVersion version);
Optional<SkillVersion> find(UUID tenantId, UUID skillId, UUID versionId);
// SkillResourceRepository
void insertAll(List<SkillResource> resources);
List<SkillResource> list(UUID tenantId, UUID skillId, UUID versionId);
// AgentSkillBindingRepository
List<AgentSkillBinding> list(UUID tenantId, UUID agentId);
Optional<AgentSkillBinding> find(UUID tenantId, UUID agentId, UUID skillId);
void insert(AgentSkillBinding binding);
boolean delete(UUID tenantId, UUID agentId, UUID skillId);
long countBySkill(UUID tenantId, UUID skillId);
void lockAgent(UUID tenantId, UUID agentId);
// RunSkillSnapshotRepository
void insert(RunSkillSnapshot snapshot);
Optional<RunSkillSnapshot> find(UUID tenantId, UUID runId);
RunSkillSnapshot lock(UUID tenantId, UUID runId);
// SkillLoadRecordRepository
void insert(SkillLoadRecord record);
Optional<SkillLoadRecord> findByCall(UUID tenantId, UUID runId, String modelCallId);
List<SkillLoadRecord> listForBudget(UUID tenantId, UUID runId);
ApiPageResponse<SkillLoadRecord> list(UUID tenantId, UUID runId, ApiPageRequest page);
// SkillUnitOfWork：仅位于 Server。
<T> T execute(Supplier<T> operation);
```

`lock*` 只能在工作单元内调用，找不到抛对应受控异常；分页、更新冲突与重复插入不以空成功表示。

- [ ] **1. 写失败测试：** 相同名字不同租户隔离、同租户重名拒绝、解绑重绑 ID 变化、空快照存在、工作单元异常不发布写入。

```java
@Test
void 工作单元失败不能发布快照() {
    InMemorySkillStore store = new InMemorySkillStore();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    RunSkillSnapshot snapshot = new RunSkillSnapshot(
            tenant, run, UUID.randomUUID(), 1, List.of(), Instant.EPOCH);
    assertThatThrownBy(() -> store.execute(() -> {
        store.snapshots().insert(snapshot);
        throw new IllegalStateException("模拟严格审计失败");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(store.snapshots().find(tenant, run)).isEmpty();
}
```

- [ ] **2. 执行：** `mvn -pl cm-agent-server -am -Dtest=InMemorySkillStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **3. 实现：** Store 暴露 `definitions()/versions()/resources()/bindings()/snapshots()/loads()` 六个接口视图及 `execute`。使用一把可重入锁和当前事务的暂存数据副本；正常返回时发布，异常时丢弃，禁止每个仓储独立提交。

```java
// execute 的事务边界；copyState/currentState 为 Store 内部状态，审计由调用服务在最后写入。
lock.lock();
try {
    State staged = currentState.copy();
    transactionState.set(staged);
    T value = operation.get();
    currentState = staged;
    return value;
} finally {
    transactionState.remove();
    lock.unlock();
}
```

`State` 是 InMemorySkillStore 的私有类，持有六个按 tenant 复合键索引的 Map，`copy()` 复制容器而共享不可变领域值。嵌套 execute 复用现有事务，不能覆盖 ThreadLocal；读操作在同一锁下读取当前事务视图。memory 审计不宣称数据库级回滚，服务必须把审计放在所有可失败业务准备之后、状态发布之前。

- [ ] **4. 同命令验证绿灯：** 加入并发重复绑定、撤销与读取顺序、嵌套事务、分页稳定性和异常后 ThreadLocal 清理测试。
- [ ] **5. 提交：** `git commit -m "feat: 增加技能仓储与内存工作单元"`。

## Task 4：实现双数据库结构、迁移和 JDBC 合同

**文件：**

- 新增 `cm-agent-persistence/src/main/resources/db/migration/postgresql/V12__add_skills.sql` 和 `cm-agent-persistence/src/main/resources/db/migration/mysql/V12__add_skills.sql`；执行前若 V12 已占用，两个方言同时改用下一个版本并同步本文。
- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDefinitionRepository.java`、`JdbcSkillVersionRepository.java`、`JdbcSkillResourceRepository.java`、`JdbcAgentSkillBindingRepository.java`、`JdbcRunSkillSnapshotRepository.java`、`JdbcSkillLoadRecordRepository.java`。
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`、T2 的 `SkillConfiguration.java`。
- 修改 `cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java`；新增同目录 `JdbcSkillRepositoriesTest.java`。

**输入/输出：** 完整实现 T3 六个接口；所有锁方法使用当前事务的带 tenant 条件的行锁查询。JDBC 工作单元使用现有 `TransactionTemplate`，和 AuditAppender 的 Repository 共用数据源及事务管理器。

```sql
SELECT id, tenant_id, name, current_version_id, enabled, access_epoch,
       created_by, updated_by, created_at, updated_at
FROM skill_definitions
WHERE tenant_id = :tenantId AND id = :skillId FOR UPDATE;
```

- [ ] **1. 编写迁移和 Repository 红灯：** 将六表加入现有 `REQUIRED_TABLES`，迁移数量从 11 改为 12；保留现有逐表逐字段注释断言。增加 PostgreSQL 和 MySQL 两个入口，委托同一个 `verifyContracts(DataSource)`。

```java
@Test
void postgres技能表满足合同() {
    verifyContracts(new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
}
@Test
void mysql技能表满足合同() {
    verifyContracts(new DriverManagerDataSource(
            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
}
```

测试类分别使用现有 `PostgreSQLContainer<>("postgres:16-alpine")` 和 `MySQLContainer<>("mysql:8.4")`。`verifyContracts` 迁移后创建两个测试租户和相应 Agent/Run，再按 T3 接口断言跨租户找不到、同名隔离、版本只插入、CAS 更新冲突、重复绑定拒绝、读取记录同调用幂等和事务回滚。连接串只由容器生成，不输出。

- [ ] **2. 保存红灯测试提交并远程运行：** 使用前述提交传递流程运行 `MigrationTest,JdbcSkillRepositoriesTest`，预期新表/实现缺失，不接受因 SSH 或 Docker 不可用产生的失败作为红灯证据。
- [ ] **3. 编写迁移：** 完整字段采用设计第 6 节和契约字典，UUID 统一既有 `varchar(36)`；PG 正文/JSON 使用 text，MySQL 正文/JSON 使用 longtext，时间和布尔类型沿用相邻迁移。为每张表及每个字段添加原生中文注释。

关键键与索引必须逐项落地：

```sql
-- 以下为两种方言都必须保持的约束语义；建表时写入完整 CREATE TABLE。
UNIQUE (tenant_id, name)
UNIQUE (tenant_id, skill_id, version_no)
UNIQUE (tenant_id, version_id, path)
UNIQUE (tenant_id, agent_id, skill_id)
UNIQUE (tenant_id, run_id)
UNIQUE (tenant_id, run_id, model_call_id)
```

定义 `(tenant_id,id)` 唯一键，版本提供 `(tenant_id,skill_id,id)` 唯一键，资源复合外键指向版本，绑定外键指向 Agent 与技能，运行快照/读取记录外键指向 Run。读取记录非法技能可以为空，不设置使拒绝记录无法保存的技能外键。current_version_id 是服务端验证的非空软指针，创建时预分配初版 UUID，再在同一事务依次插入定义、版本、资源；不对该指针建立造成循环插入的即时外键，也不让对外领域对象出现空当前版本。

MySQL 的 name/path 用大小写明确的二进制排序规则；版本 description、正文仍保存 UTF-8。同一技能的更新必须先锁定义行，后插新版本和资源，最后 CAS 指针；解绑/绑定先锁 Agent，批量定义按 UUID 稳定排序加锁。运行读锁顺序为 Run 快照 → Agent → 排序后的技能定义，其他流程不得反向获取快照锁。

```sql
UPDATE skill_definitions
SET current_version_id = :newVersionId, updated_by = :principalId, updated_at = :now
WHERE tenant_id = :tenantId AND id = :skillId
  AND current_version_id = :expectedVersionId;

INSERT INTO run_skill_snapshots
    (tenant_id, run_id, agent_id, format_version, skills_json, created_at)
SELECT tenant_id, id, agent_id, 1, '[]', started_at FROM runs;
```

旧 Run 空快照回填发生在停写迁移窗口，不采用“缺失时动态使用最新技能”。新增索引支持租户列表、Agent 绑定、Run 读取时间/ID 排序。硬删除带绑定 Agent 前在现有 Agent 删除命令同事务删除其绑定，不能留下悬挂关联或仅依赖外键报 500。

- [ ] **4. 实现 JDBC 并双库验证：** 构造器统一采用 `JdbcClient`、需要 JSON 的仓储加 `ObjectMapper`；mapper 必须保持领域校验。新增测试覆盖 V11 已有运行升级、资源路径 utf8、绑定上限并发竞争、严格审计回滚和唯一键冲突映射。
- [ ] **5. 提交与远程绿灯记录：** `git commit -m "feat: 持久化技能版本与运行读取记录"`，传递该提交后再次运行双库测试，账本记录实际 SHA 和结果。

## Task 5：交付管理 API、权限、事务与中文诊断

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillManagementService.java`、`SkillQueryService.java`。
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillController.java`、`AgentSkillController.java`、`SkillResponses.java`、`SkillRequests.java`。
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/ApiExceptionHandler.java`、`cm-agent-server/src/main/java/com/cmagent/server/web/AuthController.java`、`cm-agent-server/src/main/java/com/cmagent/server/service/AgentDefinitionCommandService.java`。
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java`、`AgentSkillControllerTest.java`、`SkillManagementJdbcPersistenceTest.java`；修改同目录 `ApiExceptionHandlerTest.java`、`AuthControllerTest.java`。

**服务接口：**

```java
// SkillManagementService：统一从 principal 取得 tenant。
SkillVersionView create(PrincipalRef principal, ParsedSkillPackage parsed);
SkillVersionView update(PrincipalRef principal, UUID skillId, UUID expectedVersionId, ParsedSkillPackage parsed);
SkillDefinition setEnabled(PrincipalRef principal, UUID skillId, boolean enabled);
AgentSkillBinding bind(PrincipalRef principal, UUID agentId, UUID skillId);
void unbind(PrincipalRef principal, UUID agentId, UUID skillId);
// SkillQueryService
ApiPageResponse<SkillResponses.Summary> list(PrincipalRef principal, String q, Boolean enabled, ApiPageRequest page);
SkillResponses.Detail detail(PrincipalRef principal, UUID skillId);
SkillResponses.Resource resource(PrincipalRef principal, UUID skillId, UUID versionId, String path);
List<SkillResponses.Binding> bindings(PrincipalRef principal, UUID agentId);
```

`SkillResponses` 定义嵌套 record：`Summary(id,name,description,currentVersionId,versionNo,enabled,updatedAt)`、`ResourceEntry(path,mediaType,byteLength)`、`Detail(summary,metadata,content,resources,boundAgentCount)`、`Resource(skillId,versionId,path,mediaType,content)`、`Binding(bindingId,skillId,name,description,versionNo,enabled)`、`Capabilities(enabled,allowedExtensions,maxZipBytes,maxExpandedBytes,maxFiles,maxInstructionBytes,maxResourceBytes,maxPathLength,maxBoundSkills)`；字段类型直接对应 Core 字段，resources 为 `List<ResourceEntry>`，allowedExtensions 为 `List<String>`。

为 T6 定义 `Load(id,skillId,name,versionId,versionNo,path,status,deliveredBytes,durationMillis,errorCode,errorId,createdAt)`；name 从稳定技能身份取得，versionNo 由记录中的历史 versionId 解析，未知/拒绝标识允许为空，禁止查询当前版本来补历史版本号。

`SkillRequests` 定义 `EnabledRequest(boolean enabled)`；multipart 的 file 与 expectedVersionId 使用 `@RequestPart`/`@RequestParam` 显式绑定。读取和管理接口与设计第 8 节完全一致。

- [ ] **1. 写 MockMvc 红灯：** 使用 `@SpringBootTest(properties={"cm-agent.skills.enabled=true", "cm-agent.persistence.mode=memory"})`、`@AutoConfigureMockMvc`、`@ActiveProfiles("test")` 和 `JwtService.createToken`，不以 `@WithMockUser` 替代真实认证主体结构。独立 JDBC 测试类通过容器动态属性覆盖为 jdbc，不套用 memory 属性。

```java
@Test
void 只有运行权限不能上传技能() throws Exception {
    String token = jwtService.createToken(UUID.randomUUID(), "reader", "测试读取者",
            List.of("agent:run", "agent:read"));
    MockMultipartFile file = new MockMultipartFile("file", "invalid.zip",
            "application/zip", new byte[]{1, 2, 3});
    mockMvc.perform(multipart("/api/skills").file(file)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
}
```

新增完整权限用户测试：非法包 400、合法初次上传 201 且 enabled=false、更新冲突 409、跨租户 404、关闭功能仍能读取 capabilities 和历史内容、停用/解绑允许。测试所需 ZIP 函数复制 T2 的完整辅助实现到本测试类，避免跨包依赖另一个测试类的 private 方法。

- [ ] **2. 本地执行：** `mvn -pl cm-agent-server -am -Dtest=SkillControllerTest,AgentSkillControllerTest,ApiExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **3. 实现服务与 Controller：** 先权限后解析包；解析不持有事务，保存开始后锁相关对象并在同一工作单元内写审计。名称不可变；更新先比对 expectedVersionId，再判摘要相同，无变化返回现有版本。相同内容不应先写入再删除。

```java
// setEnabled 的核心：重复停用不增加纪元；从启用切到停用才撤销旧运行。
long nextEpoch = current.enabled() && !enabled
        ? Math.addExact(current.accessEpoch(), 1) : current.accessEpoch();
SkillDefinition next = new SkillDefinition(current.id(), current.tenantId(), current.name(),
        current.currentVersionId(), enabled, nextEpoch, current.createdBy(),
        principal.principalId(), current.createdAt(), clock.instant());
```

绑定数量检查和插入必须在同一 Agent 锁内；同一绑定重复请求返回原 bindingId，解绑后重绑生成新 ID。Agent 删除时在其现有工作单元内清理技能绑定；memory 使用同一技能 Store 锁与既有 Agent 删除锁固定顺序，不能先删 Agent 再尝试清理失败。

权限拒绝沿用 PermissionEvaluator/AuditAppender；bootstrap 权限清单只增加两项技能管理权限。功能关闭的写操作返回 `SKILL_FEATURE_DISABLED`，但停用/解绑仍通过正常权限检查。

在 ApiExceptionHandler 新增 SkillAccessException 映射；multipart 超限、缺失 file 和不支持内容类型进入技能专用错误处理，不能让容器默认 HTML 413 或通用 500 绕过错误编号。用 RequestCorrelationFilter 的既有编号，诊断和响应必须一致；运行内部生成的 attemptId 不重新替换。

- [ ] **4. 绿灯与双库事务检查：** 同本地命令；远程 `mvn -pl cm-agent-server -am -Dtest=SkillManagementJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test`，验证审计失败不产生新版本、资源或绑定。用测试日志捕获器注入包含模拟 JWT/内部 URL 的异常，断言响应与受控日志均不泄露且能通过 errorId 关联。
- [ ] **5. 提交：** `git commit -m "feat: 提供技能管理与 Agent 绑定接口"`。

## Task 6：固定 Run 技能快照并治理每次读取

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeService.java`、`GovernedSkillAccessService.java`。
- 修改同目录 `RunExecutionService.java`、`ToolApprovalService.java`；修改 `cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java`、`ConversationController.java`。
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/config/SkillConfiguration.java`。
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillRuntimeServiceTest.java`、`GovernedSkillAccessServiceTest.java`、`SkillRuntimeJdbcPersistenceTest.java` 和 `cm-agent-server/src/test/java/com/cmagent/server/web/ConversationControllerTest.java`；修改 runtime 下 `ToolApprovalServiceTest.java` 和 web 下 `RunControllerTest.java`。

**接口：**

```java
// SkillRuntimeService
SkillRuntimeBundle prepare(PrincipalRef principal, RunRecord run);
SkillRuntimeBundle restore(PrincipalRef principal, RunRecord run);
// GovernedSkillAccessService implements SkillAccessGateway
SkillReadResult load(SkillReadRequest request, Supplier<String> nativeLoader);
// RunController 新增技能读取分页，保持原有 Agent/Run 父子路由。
ApiPageResponse<SkillResponses.Load> skillLoads(UUID agentId, UUID runId, int page, int size,
        Authentication authentication);
```

- [ ] **1. 写失败测试：** prepare 空集合也持久化，restore 缺记录时拒绝；新绑定不进入旧快照，更新不替换旧版本；停用再启用或解绑重绑后旧快照拒绝。使用 Mockito 验证 `nativeLoader` 在授权失败时从未执行。

```java
@Test
void 缺失快照不能自动重建为最新版本() {
    RunSkillSnapshotRepository snapshots = mock(RunSkillSnapshotRepository.class);
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(snapshots.find(tenant, runId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> SkillRuntimeService.requireSnapshot(snapshots, tenant, runId))
            .isInstanceOf(SkillAccessException.class)
            .extracting("code").isEqualTo(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE);
    verify(snapshots, never()).insert(any());
}
```

`requireSnapshot(RunSkillSnapshotRepository, UUID tenantId, UUID runId)` 为该服务包内静态辅助函数，抛 fatal=true 的 SkillAccessException；服务 restore 使用它，不只在测试中增加空方法。

- [ ] **2. 执行红灯：** `mvn -pl cm-agent-server -am -Dtest=SkillRuntimeServiceTest,GovernedSkillAccessServiceTest,ToolApprovalServiceTest,RunControllerTest,ConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **3. 实现准备与恢复：** 在运行进入模型前创建快照；已有快照不能被 prepare 覆盖。restore 必须先校验 Run 的原主体，再验证全部引用的绑定 ID、纪元和 enabled，最后读取原版本。功能关闭仅允许空技能快照恢复。

```java
boolean allowed = current.enabled()
        && current.accessEpoch() == reference.accessEpoch()
        && binding.id().equals(reference.bindingId())
        && binding.skillId().equals(reference.skillId())
        && binding.agentId().equals(run.agentId());
if (!allowed) {
    throw new SkillAccessException(ApiErrorCode.SKILL_ACCESS_REVOKED,
            "本轮使用的技能已停用或解绑，请重新发起对话", run.id().toString(), true);
}
```

上述 current、reference、binding、run 分别是加载后的 SkillDefinition、SkillSnapshotRef、AgentSkillBinding、RunRecord。不是以模型字段构造这些对象。快照创建、审批恢复与所有异常收口均保留原有 Run 终态及检查点清理规则，恢复拒绝不得留下新的 RUNNING 僵尸记录。

`RunExecutionService` 在泛化 RuntimeException 捕获前单独捕获 SkillAccessException，完成失败收口后保留原异常；`RunController.streamError`、`ConversationController.streamError` 和非流式异常处理均识别此类型并使用同一个 code/errorId。已记录诊断的异常不在每个边界重复打印；若基础设施失败优先于技能失败，保留原有 AUDIT_UNAVAILABLE/PERSISTENCE_UNAVAILABLE 分类。增加普通运行、聊天流、审批恢复流三个出口的编号一致性断言。

- [ ] **4. 实现读取工作单元：** 锁 Run 快照与相关对象；查询已有 modelCallId，只有相同技能/版本/路径的完成重试可复用记录，不可重复扣预算；不同参数复用 ID 拒绝。由持久记录计算本轮次数和字节，新模型调用使用新 ID，重复读取仍计费。

```java
int attempts = records.size();
int delivered = records.stream().mapToInt(SkillLoadRecord::deliveredBytes).sum();
if (attempts >= limits.getMaxLoadAttempts()
        || resource.byteLength() > limits.getMaxLoadedBytes() - delivered) {
    // 落地拒绝记录和审计后返回受控结果，不能在同一事务里抛异常导致记录回滚。
    return ReadOutcome.denied(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED,
            "本轮技能读取已达上限", request.attemptId().toString(), false);
}
```

`limits` 为 SkillProperties，采用 JavaBean getter（`getMaxLoadAttempts()`、`getMaxLoadedBytes()` 均返回 int）；`resource` 为已经验证归属的 SKILL.md 或 SkillResource 内容视图；SKILL.md 按版本正文计算 byteLength。`ReadOutcome` 为 GovernedSkillAccessService 私有 record，字段为 `(SkillReadResult result, SkillAccessException failure)`，提供 `success` 与 `denied` 工厂；工作单元返回后再抛 failure，保证受控拒绝记录提交。基础设施异常直接抛出并回滚，不伪造拒绝记录。

原生 loader 仅做本次内存集合读取；它返回正文后，在同一短事务内复核状态并持久化记录与审计，提交后才返回 SkillReadResult。统计 deliveredBytes 使用技能原文字节，不含原生响应包装标签。底层异常和 supplier 抛出的异常交最终日志边界，未知异常 fatal=true。

对授权失败、资源不存在、原生失败也使用 ReadOutcome 保存允许保存的失败记录；非法 path 不记录原始值。第 33 次及之后的请求保持拒绝，不向模型交付内容。审批恢复从数据库预算继续，不能仅依赖内存计数。

- [ ] **5. 验证绿灯与竞态：** 同命令；远程执行 `SkillRuntimeJdbcPersistenceTest`，用屏障控制读取与停用的事务先后，验证提交界限、读取记录去重、预算竞争、回滚不交付正文；两种数据库均执行。
- [ ] **6. 提交：** `git commit -m "feat: 固定运行技能版本并治理读取与恢复"`。

## Task 7：接入原生技能加载并防止框架吞掉致命失败

**文件：**

- 新增 `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillRepository.java`、`AgentScopeSkillSession.java`、`AgentScopeSkillLoadBridge.java`。
- 修改同目录 `AgentScopeReActExecutor.java`、`AgentScopeRuntimeAdapter.java`、`AgentScopeRunGate.java`；修改 `cm-agent-server/src/main/java/com/cmagent/server/config/AgentScopeRuntimeConfiguration.java`。
- 新增 `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillSessionTest.java`、`AgentScopeSkillContractTest.java`、`AgentScopeRunGateTest.java`；修改同目录 `AgentScopeRuntimeContractTest.java`。

**接口：**

```java
// AgentScopeSkillRepository implements AgentSkillRepository；每次执行独立只读实例。
AgentScopeSkillRepository(List<SkillVersionView> versions);
// AgentScopeSkillSession implements AutoCloseable：包内生命周期组件。
AgentScopeSkillSession(AgentRunRequest request, SkillAccessGateway gateway, AgentScopeRunGate gate);
String directoryPrompt();
AgentTool loadTool();
void close();
// 在 AgentScopeRuntimeAdapter 增加六参数工厂，原四/五参数入口保留。
static AgentScopeRuntimeAdapter create(ModelCredentialProvider credentials,
        ToolInvocationGateway tools, AgentScopeRuntimeOptions options, Clock clock,
        AgentStateStore stateStore, SkillAccessGateway skills);
```

只读仓库的 save/delete/setWriteable(true) 明确拒绝；close 清理运行内引用，不持有共享可变注册表。原工厂以拒绝型技能网关委托新工厂：无技能仍可运行，有技能时报明确缺失网关错误。

- [ ] **1. 写红灯：** 单元测试验证只有已提供技能进入仓库，setWriteable(true) 失败；契约测试使用本地 OpenAI 兼容 Stub 返回 `load_skill_through_path` 调用，检查首次模型请求只含名称/描述，后续请求才含正文与资源。

```java
@Test
void 空技能仓库不可写() {
    AgentScopeSkillRepository repository = new AgentScopeSkillRepository(List.of());
    assertThat(repository.getAllSkills()).isEmpty();
    assertThat(repository.isWriteable()).isFalse();
    assertThatThrownBy(() -> repository.setWriteable(true))
            .isInstanceOf(UnsupportedOperationException.class);
}
```

契约测试在同一类内建立完整测试 Provider fixture：第一次响应 tool call 读取 SKILL.md，第二次读取 `references/guide.md`，第三次给出文本；保存收到的三份请求供断言。使用已有 RuntimeContractTest 的本地 HttpServer 方式实现，不能访问真实模型或写入 Key。

- [ ] **2. 执行：** `mvn -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeSkillSessionTest,AgentScopeSkillContractTest,AgentScopeRunGateTest,AgentScopeRuntimeContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **3. 构建原生会话：** 将平台 UUID+版本映射为运行内原生 ID，source 使用固定非路径说明，不传宿主 originDir。使用 SkillBox 的原生注册、提示和读取工具；不安装默认动态中间件。

```java
Toolkit nativeToolkit = new Toolkit();
SkillBox box = new SkillBox(nativeToolkit);
box.setAutoUploadSkill(false);
box.setExposeAllSkillMetadata(false);
box.getSkillPromptProvider().setCodeExecutionEnable(false);
repository.getAllSkills().forEach(box::registerSkill);
box.registerSkillLoadTool();
AgentTool delegate = Objects.requireNonNull(nativeToolkit.getTool("load_skill_through_path"));
```

在真实 Toolkit 注册包装器而非 delegate；包装器参数先校验字符串类型、已知 ID 与资源存在性，再经 SkillAccessGateway.load 调用 delegate。原生结果为错误块时转换为受控异常，不把原生错误 body 透传。框架需Mono的接口使用 `Mono.fromCallable`，本地委托只读内存，在限时边界内同步完成，不能让回调持有事务等待模型或网络。

ReActAgent 的 sysPrompt 为 Agent 原提示词加原生目录摘要，不含全量正文。禁止未经编码的 name/description 破坏原生目录标记；发现原生 prompt 对这些字段不转义时在映射前进行纯文本 XML 字符编码，测试覆盖 `</skill>` 等输入。业务工具重名在构建前失败。

- [ ] **4. 扩展共享中止门控：** 现有门控会保留业务工具基础设施异常；技能的 fatal 异常也必须被保留，并与业务工具使用同一调用锁。新增包内方法 `SkillReadResult invokeSkill(SkillAccessGateway, SkillReadRequest, Supplier<String>)`、`void throwIfSkillFailure()`；普通 invoke 调用前后也检查技能 fatal 状态，执行器在事件边界与最终结果前检查它。

```java
try {
    return gateway.load(request, nativeLoader);
} catch (SkillAccessException failure) {
    if (failure.fatal()) skillFailure.compareAndSet(null, failure);
    throw failure;
} catch (RuntimeException infrastructureFailure) {
    // 原因交既有最终日志边界；门控保存故障，防止框架把它转成普通工具返回后继续。
    skillInfrastructureFailure.compareAndSet(null, infrastructureFailure);
    throw infrastructureFailure;
}
```

以上两类原子字段分别持有 SkillAccessException 和 RuntimeException；`throwIfSkillFailure` 先抛基础设施失败，再抛受控 fatal。执行器将受控技能失败原样抛到 T6 的 Server 失败边界；不能放入只有 errorMessage 的 AgentRunResult 后丢失 code/errorId。业务工具的失败、审批、资源关闭优先级保持既有规则。

- [ ] **5. 绿灯和真实链验证：** 增加审计失败后模型尝试调用业务工具但网关调用次数为 0、资源不存在可恢复、跨租户/撤销致命、无目录写入/进程/额外联网、每次运行注册表独立、审批恢复再次读取原版本、内部名称冲突、无技能旧工厂回归。
- [ ] **6. 提交：** `git commit -m "feat: 接入受治理的 AgentScope 原生技能加载"`。

## Task 8：让前端请求支持 multipart 并保持会话隔离

**文件：**

- 修改 `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`。
- 修改 `cm-agent-console/src/test/js/console-core.test.cjs`。

**接口：** 保持现有 `createApiClient` 配置参数及其 `request(path, options)` 方法；增加错误对象 `code/errorId/status` 字段，保持 `message` 的现有中文格式。`stream()` 不改变协议或请求格式。

- [ ] **1. 增加可执行红灯用例：**

```javascript
test("multipart 由浏览器设置 Content-Type 并保留同源凭据", async () => {
    let received;
    const client = core.createApiClient({
        fetchImpl: async (url, options) => {
            received = options;
            return new Response(JSON.stringify({id: "skill-1"}), {status: 201});
        },
        getToken: () => "test-token",
        onUnauthorized: () => assert.fail("不能触发退出")
    });
    const body = new FormData();
    body.append("file", new Blob(["test"], {type: "application/zip"}), "test.zip");
    await client.request("/api/skills", {method: "POST", body});
    assert.equal(received.headers.has("Content-Type"), false);
    assert.equal(received.headers.get("Authorization"), "Bearer test-token");
    assert.equal(received.credentials, "same-origin");
    assert.equal(received.body, body);
});
```

- [ ] **2. 运行：** `node --test cm-agent-console/src/test/js/console-core.test.cjs`，预期 Content-Type 断言失败。
- [ ] **3. 修改请求封装：**

```javascript
const headers = new Headers(options.headers || {});
const isMultipart = typeof FormData !== "undefined" && options.body instanceof FormData;
if (isMultipart) headers.delete("Content-Type");
else if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
```

结构化失败在已有 `new Error(formatError(response.status, body, rawBody))` 后附加受控的 body.code/body.errorId，不把整个 body 存到可随手打印的错误对象；非 JSON 413 映射为明确上传过大提示，编号缺失时不伪造服务端编号。

- [ ] **4. 绿灯回归：** 追加 JSON 仍为 application/json、调用方传错 multipart 头也由封装移除、旧会话 401 不退出新会话、503 保留错误编号、SSE 流读取仍正常的测试，再执行同命令。
- [ ] **5. 提交：** `git commit -m "feat: 支持技能上传的 multipart 请求"`。

## Task 9：交付技能工作区与完整导航生命周期

**文件：**

- 新增 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/skills.html`、同目录 `assets/skills.js`。
- 修改 `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`、`cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css`。
- 修改 v2 下 `overview.html`、`model-configs.html`、`agents.html`、`tools.html`、`chat.html`、`runs.html`、`audit.html` 导航/脚本装载；`login.html` 只同步共享资源版本。
- 新增 `cm-agent-console/src/test/js/console-skills.test.cjs`；修改 `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`。

**组件接口（普通脚本导出 `window.CmAgentSkills`，Node 使用 module.exports）：**

```javascript
createRequestScope(getSessionEpoch); // 返回 issue(key)、isCurrent(ticket)、invalidate()。
createSkillPage({api, getSessionEpoch, getPermissions, navigate, document});
// 页面实例返回 mount(root)、reload()、dispose()。
```

本任务产出目录、导入、更新、资源查看和启停；T10 继续增加同一模块的绑定/记录组件，不创建第二套 API 客户端。

- [ ] **1. 编写状态与资源红灯：**

```javascript
const skills = require("../../main/resources/META-INF/resources/console/v2/assets/skills.js");
test("切换技能后旧详情结果不能覆盖当前详情", () => {
    let session = 1;
    const scope = skills.createRequestScope(() => session);
    const first = scope.issue("skill-A");
    const second = scope.issue("skill-B");
    assert.equal(scope.isCurrent(first), false);
    assert.equal(scope.isCurrent(second), true);
    session = 2;
    assert.equal(scope.isCurrent(second), false);
});
```

ConsoleResourceTest 验证 skills.html 的独立 data-page、列表/详情/上传表单区域、八个业务页相同导航顺序、三脚本顺序和无 token 持久化；不能以这些静态断言代替 DOM 行为验证。

- [ ] **2. 执行：** `node --test cm-agent-console/src/test/js/console-skills.test.cjs`；`mvn -pl cm-agent-console -am -Dtest=ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **3. 实现新组件与页面：** 按设计第 9 节布局，DOM ID 使用 `skillList`、`skillDetail`、`skillUploadForm`、`skillFile`、`skillPageStatus`、`skillResourceContent`、`skillPagination`。目录按 page/size/q/enabled 请求，资源 URL 的 path 使用 URLSearchParams 编码。

```javascript
function createRequestScope(getSessionEpoch) {
    let revision = 0;
    let selected = "";
    return {
        issue(key) {
            selected = key;
            return {key, revision: ++revision, session: getSessionEpoch()};
        },
        isCurrent(ticket) {
            return ticket.key === selected && ticket.revision === revision
                && ticket.session === getSessionEpoch();
        },
        invalidate() { revision += 1; selected = ""; }
    };
}
```

列表、详情、资源各持有独立 scope，避免刷新列表误取消并行的资源读取；每次 DOM 更新前复核 ticket。dispose 撤销事件监听、使 scope 失效并释放文件引用。异步成功/失败/finally 都复核，不能只在成功分支防旧响应。

技能工作区在详情/上传/更新之间切换。上传先检查扩展名与服务端限制，FormData 交 API 客户端；成功返回详情，明确尚未启用；更新使用用户开始编辑时的 expectedVersionId，409 后只提供刷新确认，不自动重传。文本全部通过 textContent。

在 app.js 的 multiPagePaths、pageInfo、loadCurrentPage、loadMultiPage 清理和 logout 处接入实例。所有 v2 业务页首次加载 skills.js；局部页面替换不重复装载脚本，mount 前先 dispose。同步共享 CSS/JS 版本号和资源测试预期，版本号以实际修改时递增值为准并保证全部引用一致。

- [ ] **4. 验证：** 运行两个测试命令，增加功能关闭、只读权限、同名/版本冲突、提交中双击、网络不确定、分页重置、纯文本 XSS 和重复 mount/dispose 测试。使用真实浏览器检查 1440×900/390×844 的页面骨架和导航；按 Impeccable 要求读取 craft-floor 后才开始本任务 UI 实现。
- [ ] **5. 提交：** `git commit -m "feat: 新增技能管理工作区与导航"`。

## Task 10：接通 Agent 绑定、聊天失败与运行读取详情

**文件：**

- 修改 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/agents.html`、`chat.html`、`runs.html`。
- 修改 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js`、`multipage.css`、`cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`。
- 修改 `cm-agent-console/src/test/js/console-skills.test.cjs`、`console-core.test.cjs` 和 `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`。

**接口：**

```javascript
createAgentSkills({api, getSessionEpoch, getPermissions, navigate, document});
// 返回 mount(root, agentId)、reload()、dispose()。
createRunSkillLoads({api, getSessionEpoch, document});
// 返回 mount(root, agentId, runId)、reload()、dispose()。
formatSkillLoadState({loading, error, items}); // 返回状态文案，不含资源正文。
```

- [ ] **1. 写红灯：**

```javascript
test("读取记录查询失败不能伪装为未读取技能", () => {
    assert.equal(skills.formatSkillLoadState({loading: false, error: "数据服务暂不可用", items: []}),
            "技能读取记录加载失败：数据服务暂不可用");
    assert.equal(skills.formatSkillLoadState({loading: false, error: "", items: []}),
            "本轮未读取技能");
});
```

新增两个 Agent/Run 切换延迟响应测试，绑定重复点击只发送一次 PUT，成功后才更新，停用条目仍存在、历史版本不同于目录当前版本，以及具备 agent:read 但没有 skill:read 时仍能查看读取摘要的测试。

- [ ] **2. 执行：** `node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/console-skills.test.cjs`。
- [ ] **3. 实现 Agent 与运行组件：** Agent 详情增加 `agentSkillsRegion`，独立载入绑定；候选列表必须有 skill:read，绑定同时检查 agent:write；取消绑定操作不修改模型/提示词表单。访问控制以真实服务端结果为准。

```javascript
function formatSkillLoadState({loading, error, items}) {
    if (loading) return "正在加载技能读取记录…";
    if (error) return `技能读取记录加载失败：${error}`;
    return items.length ? `已加载 ${items.length} 条技能读取记录` : "本轮未读取技能";
}
```

renderRunDetail 先显示已有运行主体，再挂载 `runSkillLoadsRegion`；加载失败只更新该区域。使用实际 versionId/versionNo 展示历史，禁止用当前技能详情覆盖历史名称版本。请求路径严格包含 Agent/Run 归属，分页使用 API page/size。

聊天沿用现有 SSE 错误处理器，显示后端 code/errorId；SKILL_ACCESS_REVOKED 和 SKILL_SNAPSHOT_UNAVAILABLE 提示重新发起，不自动重发消息、不重放审批。没有实际加载证据时不显示“正在使用某技能”，不新增手动选择或技能专用 SSE 类型。

- [ ] **4. 绿灯并操作验证：** 执行 JS 测试和 ConsoleResourceTest；浏览器分别以管理、只读、仅可运行三种权限验证可见操作。覆盖快速切换 Agent/Run、网络查询失败恢复、长路径窄屏以及无技能 Agent/普通审批卡片回归。
- [ ] **5. 提交：** `git commit -m "feat: 接通技能绑定与运行读取详情"`。

## Task 11：完成前后端闭环、回归与正式说明

**文件：**

- 新增 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillFlowIntegrationTest.java`、`cm-agent-server/src/test/resources/skills/support-guide/SKILL.md`、同目录 `references/guide.md`。
- 更新 T4～T10 的测试文件和资源版本断言。
- 修改 `README.md`、`docs/configuration.md`、`docs/operations.md`、`docs/release-notes.md`、`cm-agent-agentscope-adapter/README.md` 和本任务四份文档。

**输入：** 全部管理/绑定/运行 API 与 Console 页面；**输出：** 可复现闭环、实际验证结果、正式启用与运维说明。

- [ ] **1. 创建可复现示例与集成测试：**

```markdown
---
name: support-guide
description: 用户要求生成服务故障排查清单时使用。
---

读取 references/guide.md，按资料中的检查顺序整理清单。
用户没有提供的信息标记为需要补充，不编造运行结果。
```

`references/guide.md` 固定内容为“先确认影响范围，再核对最近变更，最后给出验证恢复的方法。”，不包含地址、凭据或脚本。集成测试用 ZIP 测试辅助函数生成上传包，通过真实 HTTP/MockMvc 管理流程创建、启用、绑定，使用本地 Provider Stub 驱动真实 AgentScope；断言读取记录至少包含 SKILL.md 与 guide.md，普通业务工具未被自动授权。

- [ ] **2. 快速回归：**

```powershell
node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/console-skills.test.cjs
mvn -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test
mvn -pl cm-agent-server -am -Dtest=SkillControllerTest,AgentSkillControllerTest,SkillRuntimeServiceTest,GovernedSkillAccessServiceTest,SkillFlowIntegrationTest,ToolApprovalServiceTest,ApiExceptionHandlerTest,RunControllerTest,ConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

先通过针对性检查，再在 Rocky 的相同提交执行 `mvn -B test` 完整回归。全量测试包含数据库/示例容器时必须留在远端，不能把本地 Docker 不可用写成容器测试已通过。

- [ ] **3. 浏览器闭环：** 显式 test profile 启动受控测试服务，以临时测试身份登录。按设计第 9.9 节十项逐一执行；同时拍摄桌面/移动视口的技能详情、绑定、读取记录和关键错误状态。截图存测试输出目录，不提交原始会话凭据或未经核对的页面内容。

浏览器闭环顺序：上传 → 查看文本 → 启用 → 绑定 → 聊天 → Run 读取记录 → 上传 v2 → 新 Run 使用 v2 → 旧 Run 保留 v1 → 等待审批时停用 → 恢复明确拒绝。真实模型如需要另行提供验证凭据，仅作补充，不写入静态资源或文档；无凭据可用确定性 Provider Stub 完成自动化验收。

- [ ] **4. 同步正式文档：** 仅在前述功能确实实现后更新已交付清单。配置示例使用安全占位符，说明能力开关、multipart 限制一致性、权限换发、停写迁移、版本保留、文本格式和上下文成本。Adapter README 更正实际 2.0.2 版本并解释窄适配与契约测试。release-notes 记录新增前端入口和旧 Run 迁移，不宣称支持脚本。
- [ ] **5. 整体验收与提交：** 检查 JavaDoc、租户条件、日志脱敏、原生委托无旁路、版本边界、四文档一致、暂存路径；提交 `git commit -m "feat: 完成技能前后端闭环与验收说明"`。账本列出实际命令、结果、远端 SHA、浏览器证据与未执行项；遇到环境阻碍明确保留任务未完成。

## 覆盖核对与执行交接

| 设计要求 | 实施任务 |
| --- | --- |
| 领域、SDK 兼容、错误码 | T1、T7 |
| 格式、容量、无脚本、无落盘 | T2、T7 |
| 六表、不可变版本、旧 Run 回填、中文注释 | T3、T4 |
| 管理接口、权限、并发更新、关闭功能 | T5 |
| 快照、撤销、审批恢复、预算、严格日志审计 | T6、T7 |
| 原生自动选择和按需读取 | T7、T11 |
| 前端 F1/F2/F6 | T9 |
| 前端 F3 | T8、T9 |
| 前端 F4/F5 | T10 |
| 前端 F7、全部端到端验收 | T8～T11 |
| 配置、部署、兼容与正式发布说明 | T11 |

计划已将前后端作为一个完整需求拆成 11 个交付任务。当前复选框全部未执行；当前完成的是设计与计划文档，不是业务功能。用户已选择由主代理按依赖顺序执行。

主代理在当前任务按依赖顺序执行，并在最后进行独立整体验证/评审：运行快照、事务、审批恢复和前端接口共享较多契约，串行推进能减少接口漂移。不创建逐任务实现子代理。
