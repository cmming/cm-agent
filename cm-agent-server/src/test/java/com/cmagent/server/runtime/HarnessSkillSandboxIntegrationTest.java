package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SkillUnitOfWork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 仅在 Rocky 验证实际 Harness 的固定 Skill、Python 沙箱、审批和加密产物合同。
 *
 * <p>模型使用回环 SSE 协议桩，其余路径由 Spring 装配生产实现；Testcontainers 管理专属
 * PostgreSQL/MySQL，文件卷和密钥只属于本测试。对象重建验证持久化恢复，不代表强杀进程
 * 或真实 Provider 验收。容器测试不得在本机 Docker Desktop 执行。</p>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CM_AGENT_TEST_SANDBOX", matches = "true")
@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.fake-runtime-enabled=false", "cm-agent.agentscope.enabled=true",
        "cm-agent.agentscope.execution-mode=harness", "cm-agent.agentscope.studio.enabled=false",
        "cm-agent.agentscope.permission-enabled=true", "cm-agent.approval-expiry.enabled=false",
        "cm-agent.agentscope.model-max-attempts=1", "cm-agent.agentscope.model-timeout=10s",
        "cm-agent.agentscope.tool-timeout=20s", "cm-agent.skills.enabled=true",
        "cm-agent.skills.sandbox.enabled=true", "cm-agent.skills.sandbox.artifacts.enabled=true",
        "cm-agent.skills.allowed-resource-types=.md,.txt,.json,.yaml,.yml,.csv,.py"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HarnessSkillSandboxIntegrationTest.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class HarnessSkillSandboxIntegrationTest {
    /** 每类独占临时库，不读取部署连接参数；由 Testcontainers 关闭。 */
    @Container
    static final JdbcDatabaseContainer<?> database = "mysql".equals(System.getProperty("cm-agent.test.database"))
            ? new MySQLContainer<>("mysql:8.4") : new PostgreSQLContainer<>("postgres:16-alpine");
    /** 测试文件卷仅持有合成密文，禁止使用部署者的产物目录。 */
    private static final Path volume = temporaryVolume();
    /** 随机测试密钥仅驻留当前 JVM，重建对象时复用以检验原密文。 */
    private static final String encryptionKey = Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("cm-agent.persistence.mode", () -> "jdbc");
        properties.add("cm-agent.persistence.jdbc.url", database::getJdbcUrl);
        properties.add("cm-agent.persistence.jdbc.username", database::getUsername);
        properties.add("cm-agent.persistence.jdbc.password", database::getPassword);
        properties.add("cm-agent.persistence.jdbc.driver-class-name", database::getDriverClassName);
        properties.add("cm-agent.skills.sandbox.artifacts.root-directory", volume::toString);
        properties.add("cm-agent.skills.sandbox.artifacts.encryption-key", () -> encryptionKey);
        // 本类主动调用补偿验证，避免定时清理与故障注入断言竞争。
        properties.add("cm-agent.skills.sandbox.artifacts.cleanup-interval", () -> "1h");
    }

    @Autowired ConversationService conversations;
    @Autowired ToolApprovalService approvals;
    @Autowired SkillTrialService trialService;
    @Autowired SkillArtifactService artifacts;
    @Autowired SkillArtifactStorage storage;
    @Autowired SkillArtifactRepository artifactRows;
    @Autowired SkillDefinitionRepository definitions;
    @Autowired SkillVersionRepository versions;
    @Autowired SkillResourceRepository resources;
    @Autowired SkillDependencyRepository dependencies;
    @Autowired SkillDependencyMappingRepository mappings;
    @Autowired AgentSkillBindingRepository bindings;
    @Autowired RunSkillSnapshotRepository snapshots;
    @Autowired SkillLoadRecordRepository loads;
    @Autowired SkillTrialRepository trials;
    @Autowired SkillUnitOfWork work;
    @Autowired RunRepository runs;
    @Autowired ConversationRepository conversationRows;
    @Autowired RuntimeCheckpointRepository checkpoints;
    @Autowired ModelConfigRepository models;
    @Autowired AgentDefinitionRepository agents;
    @Autowired ToolDefinitionRepository tools;
    @Autowired ToolGrantRepository grants;
    @Autowired com.cmagent.core.tool.ToolRegistry registry;
    @Autowired AgentRuntime runtime;
    @Autowired SkillProperties policy;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @SpyBean AuditAppender audit;
    /** 每个用例独占回环服务与线程池，结束时释放。 */
    private HttpServer server;
    private ExecutorService executor;
    /** 每次模型调用从队列领取一个响应，可精确在审批或撤销边界插入动作。 */
    private final Queue<Function<JsonNode, String>> responses = new ConcurrentLinkedQueue<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private PrincipalRef owner;
    private UUID agent;
    private UUID conversation;
    private UUID skill;
    private UUID version;
    private UUID echoTool;
    /** 停止测试只检查前后差集中发现的沙箱容器，不修改或删除任何容器。 */
    private volatile String activeContainer;

    @BeforeEach
    void prepare() throws Exception {
        reset(audit);
        activeContainer = null;
        policy.setMaxLoadAttempts(32);
        policy.setMaxLoadedBytes(16 * 1024 * 1024);
        policy.getSandbox().setEnabled(true);
        policy.getSandbox().setTimeout(Duration.ofSeconds(15));
        policy.getSandbox().setMaxOutputBytes(32768);
        owner = new PrincipalRef(UUID.randomUUID(), "skill-owner", "技能验收主体",
                Set.of("agent:read", "agent:run", "agent:approve", "skill:read", "skill:test"));
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,'技能验收租户',true,:now)")
                .param("id", owner.tenantId().toString()).param("code", owner.tenantId().toString())
                .param("now", Timestamp.from(Instant.now())).update();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            var response = responses.poll();
            String content = response == null ? "缺少回环响应" : response.apply(mapper.readTree(body));
            byte[] bytes = ("data: " + content + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(response == null ? 500 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var model = models.save(new ModelConfig(UUID.randomUUID(), owner.tenantId(), ModelProviderType.OPENAI_COMPATIBLE,
                "技能回环模型", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture", true));
        agent = agents.save(new AgentDefinition(UUID.randomUUID(), owner.tenantId(), "技能 Harness", "", "", model.id(),
                "fixture", 0.1, 10, true, List.of(), owner.principalId(), owner.principalId())).id();
        var tool = tools.save(new ToolDefinition(UUID.randomUUID(), owner.tenantId(), "echo", "审批验收工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", ToolRiskLevel.HIGH, true,
                "", owner.principalId(), owner.principalId()));
        registry.register(tool, ignored -> com.cmagent.core.tool.ToolExecutionResult.succeeded("工具完成", null));
        grants.save(new ToolGrant(owner.tenantId(), tool.id(), agent, "", true));
        echoTool = tool.id();
        conversation = conversations.create(owner, agent).id();
        skill = UUID.randomUUID();
        version = UUID.randomUUID();
        work.execute(() -> {
            definitions.insert(new SkillDefinition(skill, owner.tenantId(), "harness-files", version, true, 0,
                    owner.principalId(), owner.principalId(), Instant.now(), Instant.now()));
            insertVersion(version, 1, fileScript("原始固定版本"));
            bindings.insert(new AgentSkillBinding(UUID.randomUUID(), owner.tenantId(), agent, skill, owner.principalId(), Instant.now()));
            return null;
        });
        assertThat(runtime).isInstanceOf(AgentScopeRuntimeAdapter.class);
    }

    @AfterEach
    void close() {
        reset(audit);
        policy.getSandbox().setEnabled(true);
        server.stop(0);
        executor.shutdownNow();
    }

    @AfterAll
    static void deleteTestVolume() throws Exception {
        // 仅清理本类随机创建的测试目录，walk 默认不跟随符号链接，不扫描其他产物根。
        try (var paths = Files.walk(volume)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void 审批恢复仍执行原版本且产物重建后只在成功终态加密下载() throws Exception {
        load(version, "load-old"); script(version, "script-before"); echo();
        var pending = waiting();
        assertThat(artifactRows.list(owner.tenantId(), pending.runId())).singleElement()
                .extracting(SkillArtifact::status).isEqualTo(SkillArtifactStatus.PENDING);
        assertThat(artifacts.list(owner, agent, pending.runId(), null).files()).isEmpty();
        UUID next = replaceScript(fileScript("新发布版本"));
        script(version, "script-after"); finish();
        var result = approve(pending);
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(snapshots.find(owner.tenantId(), pending.runId()).orElseThrow().skills().getFirst().versionId()).isEqualTo(version);
        assertThat(definitions.find(owner.tenantId(), skill).orElseThrow().publishedVersionId()).isEqualTo(next);
        assertThat(artifacts.list(owner, agent, pending.runId(), null).files()).hasSize(2);
        assertThat(checkpoints.exists(owner.tenantId(), user(), pending.runId().toString())).isFalse();
        // 重建文件服务和存储对象，保留同一 JDBC 元数据与测试密钥，不依赖原 JVM 对象缓存。
        var beans = new StaticListableBeanFactory();
        beans.addBean("storage", new FileSystemSkillArtifactStorage(policy.getSandbox().getArtifacts()));
        var rebuilt = new SkillArtifactService(artifactRows, beans.getBeanProvider(SkillArtifactStorage.class), runs, trials,
                conversationRows, (p, permission) -> p.permissions().contains(permission)
                ? com.cmagent.core.security.AuthorizationDecision.allow() : com.cmagent.core.security.AuthorizationDecision.deny("权限不足"), audit, policy);
        for (var row : artifactRows.list(owner.tenantId(), pending.runId())) {
            assertThat(row.versionId()).isEqualTo(version);
            assertThat(download(rebuilt, row)).isEqualTo("原始固定版本");
            byte[] ciphertext = Files.readAllBytes(volume.resolve(owner.tenantId().toString()).resolve(row.id() + ".gcm"));
            assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain("原始固定版本");
            mvc.perform(get("/api/skill-artifacts/" + row.id() + "/content").header("Authorization", token(owner)))
                    .andExpect(status().isOk()).andExpect(content().bytes("原始固定版本".getBytes(StandardCharsets.UTF_8)));
            var other = new PrincipalRef(owner.tenantId(), "other", "其他主体", owner.permissions());
            mvc.perform(get("/api/skill-artifacts/" + row.id() + "/content").header("Authorization", token(other)))
                    .andExpect(status().isNotFound());
            var foreign = new PrincipalRef(UUID.randomUUID(), owner.principalId(), "其他租户", owner.permissions());
            // 跨租户主体也必须来自真实存在的测试租户，否则严格审计的租户外键会正确阻断响应。
            jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,'其他验收租户',true,:now)")
                    .param("id", foreign.tenantId().toString()).param("code", foreign.tenantId().toString())
                    .param("now", Timestamp.from(Instant.now())).update();
            mvc.perform(get("/api/skill-artifacts/" + row.id() + "/content").header("Authorization", token(foreign)))
                    .andExpect(status().isNotFound());
        }
        assertThat(requests.getFirst()).doesNotContain("run_skill_code", "wait_async_results", "import pathlib", volume.toString());
        assertThat(requests.getLast()).doesNotContain("新发布版本", encryptionKey, volume.toString());
    }

    @Test
    void 实际Harness复用同一调用标识不会重复沙箱副作用() throws Exception {
        script(version, "same-call"); script(version, "same-call"); finish();
        var result = conversations.send(owner, agent, conversation, "验证重复调用");
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(loads.listForBudget(owner.tenantId(), result.run().runId())).hasSize(1);
        assertThat(artifactRows.list(owner.tenantId(), result.run().runId())).hasSize(1);
        assertThat(requests).hasSize(3);
    }

    @Test
    void 读取与脚本共用累计预算且超限不能新增副作用(CapturedOutput output) {
        policy.setMaxLoadAttempts(2);
        load(version, "load-budget"); script(version, "script-budget"); script(version, "over-budget");
        var failure = failedSend(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED);
        assertThat(loads.listForBudget(owner.tenantId(), currentRun().id())).hasSize(2);
        assertFailureCleaned(failure, output);
    }

    @Test
    void 沙箱执行期间撤销技能必须丢弃实际生成的文件(CapturedOutput output) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            revoke();
            return null;
        }).when(audit).append(eq(owner.tenantId()), any(), eq("SKILL_EXECUTE"), any(), any(), eq("STARTED"), any());
        script(version, "revoke-in-flight");
        var failure = failedSend(ApiErrorCode.SKILL_ACCESS_REVOKED);
        assertThat(artifactRows.list(owner.tenantId(), currentRun().id())).hasSize(1);
        assertFailureCleaned(failure, output);
    }

    @Test
    void 成功审计不可用不能把实际文件发布为可下载(CapturedOutput output) {
        doThrow(new AuditPersistenceException("合成审计失败", new IllegalStateException("secret=不应泄露 http://internal.invalid/private")))
                .when(audit).append(eq(owner.tenantId()), any(), eq("SKILL_EXECUTE"), any(), any(), eq("SUCCEEDED"), any());
        script(version, "audit-failure");
        var failure = failedSend(ApiErrorCode.AUDIT_UNAVAILABLE);
        assertFailureCleaned(failure, output);
        assertThat(output).doesNotContain("不应泄露", "internal.invalid/private");
    }

    @Test
    void 审批等待停止时未发布文件进入补偿且不能下载() throws Exception {
        script(version, "before-stop"); echo();
        var pending = waiting();
        conversations.cancel(owner, agent, conversation, pending.runId());
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status()).isEqualTo(RunStatus.CANCELLED);
        cleaned(pending.runId());
        assertThat(checkpoints.exists(owner.tenantId(), user(), pending.runId().toString())).isFalse();
    }

    @Test
    void 实际脚本在途停止等待容器退出后才交付取消终态() throws Exception {
        UUID slow = replaceScript("import time; time.sleep(120)");
        Set<String> before = sandboxContainers();
        script(slow, "active-stop");
        var future = executor.submit(() -> conversations.send(owner, agent, conversation, "停止实际脚本"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline && activeContainer == null) {
            activeContainer = sandboxContainers().stream().filter(name -> !before.contains(name)).findFirst().orElse(null);
            Thread.sleep(50);
        }
        assertThat(containerRunning()).isTrue();
        UUID run = currentRun().id();
        var accepted = conversations.cancel(owner, agent, conversation, run);
        assertThat(accepted.status()).isEqualTo(RunStatus.CANCELLING);
        var result = future.get(15, TimeUnit.SECONDS);
        assertThat(result.run().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(containerRunning()).isFalse();
        assertThat(checkpoints.exists(owner.tenantId(), user(), run.toString())).isFalse();
        cleaned(run);
    }

    private boolean containerRunning() throws Exception {
        if (activeContainer == null) return false;
        // 命令参数完全来自生产沙箱创建时的随机名称，不拼接 Shell，也不修改容器。
        var process = new ProcessBuilder("docker", "inspect", "--format", "{{.State.Running}}", activeContainer)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
        return process.exitValue() == 0 && new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().equals("true");
    }

    private Set<String> sandboxContainers() throws Exception {
        var process = new ProcessBuilder("docker", "ps", "--filter", "name=cm-agent-skill-", "--format", "{{.Names}}")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(3, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return new HashSet<>(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList());
    }

    @Test
    void 审批恢复前撤销快照拒绝继续且删除暂存文件() {
        script(version, "before-revoke"); echo();
        var pending = waiting();
        revoke();
        assertThatThrownBy(() -> approve(pending)).isInstanceOfSatisfying(SkillAccessException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        assertThat(requests).hasSize(2);
        cleaned(pending.runId());
        assertThat(checkpoints.exists(owner.tenantId(), user(), pending.runId().toString())).isFalse();
    }

    @Test
    void TEST只执行显式候选且不改正式绑定发布指针或会话() throws Exception {
        UUID candidate = UUID.randomUUID();
        work.execute(() -> { insertVersion(candidate, 2, fileScript("临时候选版本")); return null; });
        var before = definitions.find(owner.tenantId(), skill).orElseThrow();
        load(candidate, "trial-load"); script(candidate, "trial-script"); finish();
        var trial = trialService.start(owner, skill, candidate, agent, "验证候选文件");
        var result = trialService.execute(owner, trial, "验证候选文件", ignored -> {}, ignored -> {});
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(trialService.get(owner, trial.runId()).status()).isEqualTo(SkillTrialStatus.PASSED);
        assertThat(trialService.get(owner, trial.runId()).qualifiesRelease()).isTrue();
        assertThat(definitions.find(owner.tenantId(), skill)).contains(before);
        assertThat(bindings.list(owner.tenantId(), agent)).hasSize(1);
        assertThat(conversationRows.findByRun(owner.tenantId(), trial.runId())).isEmpty();
        var snapshot = snapshots.find(owner.tenantId(), trial.runId()).orElseThrow().skills().getFirst();
        assertThat(snapshot.origin()).isEqualTo(SkillSnapshotOrigin.TRIAL);
        assertThat(snapshot.versionId()).isEqualTo(candidate);
        assertThat(artifacts.listTrial(owner, skill, trial.runId()).files()).hasSize(1);
        assertThat(download(artifacts, artifactRows.list(owner.tenantId(), trial.runId()).getFirst())).isEqualTo("临时候选版本");
    }

    @Test
    void 脚本输出超限按稳定错误关闭运行并禁止下载(CapturedOutput output) {
        policy.getSandbox().setMaxOutputBytes(1024);
        UUID noisy = replaceScript("while True: print('x' * 4096, flush=True)");
        script(noisy, "output-limit");
        assertFailureCleaned(failedSend(ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED), output);
    }

    @Test
    void 沙箱超时必须清理后关闭运行并保留关联诊断(CapturedOutput output) {
        policy.getSandbox().setTimeout(Duration.ofSeconds(2));
        UUID slow = replaceScript("import time; time.sleep(120)");
        script(slow, "timeout");
        assertFailureCleaned(failedSend(ApiErrorCode.SKILL_SANDBOX_TIMEOUT), output);
    }

    @Test
    void 文件符号链接被实际协议拒绝且不交付模型(CapturedOutput output) {
        UUID unsafe = replaceScript("import os; os.symlink('/etc/passwd', '/workspace/output/leak.txt')");
        script(unsafe, "symlink");
        assertFailureCleaned(failedSend(ApiErrorCode.SKILL_ARTIFACT_INVALID), output);
        assertThat(requests).hasSize(1);
    }

    @Test
    void 模型不能指定宿主路径或未登记脚本() throws Exception {
        respond("run_skill_script", "host-path", Map.of("skillId", nativeId(version), "path", "/etc/passwd", "stdin", ""));
        respond("run_skill_script", "unknown-path", Map.of("skillId", nativeId(version), "path", "scripts/unknown.py", "stdin", ""));
        finish();
        var result = conversations.send(owner, agent, conversation, "禁止任意路径");
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(loads.listForBudget(owner.tenantId(), result.run().runId())).isEmpty();
        assertThat(artifactRows.list(owner.tenantId(), result.run().runId())).isEmpty();
        assertThat(requests.getLast()).contains("请求的技能脚本不存在或输入不合法").doesNotContain("root:");
    }

    @Test
    void 必需依赖授权撤销不能经技能快照恢复工具() {
        requiredDependency();
        script(version, "dependency-file"); echo();
        var pending = waiting();
        var reference = snapshots.find(owner.tenantId(), pending.runId()).orElseThrow().skills().getFirst();
        assertThat(reference.dependencies()).singleElement().extracting(SkillDependencyResolution::toolId).isEqualTo(echoTool);
        grants.delete(owner.tenantId(), agent, echoTool);
        assertThatThrownBy(() -> approve(pending)).isInstanceOfSatisfying(SkillAccessException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED));
        assertThat(requests).hasSize(2);
        cleaned(pending.runId());
    }

    @Test
    void 沙箱准备字节持续计入累计预算而不能因零交付记录绕过(CapturedOutput output) {
        var fixed = versions.find(owner.tenantId(), skill, version).orElseThrow();
        int bytes = fixed.content().getBytes(StandardCharsets.UTF_8).length
                + resources.list(owner.tenantId(), skill, version).stream().mapToInt(SkillResource::byteLength).sum();
        policy.setMaxLoadedBytes(bytes);
        script(version, "bytes-first"); script(version, "bytes-second");
        assertFailureCleaned(failedSend(ApiErrorCode.SKILL_LOAD_LIMIT_EXCEEDED), output);
        assertThat(loads.listForBudget(owner.tenantId(), currentRun().id())).singleElement()
                .extracting(SkillLoadRecord::status).isEqualTo(SkillLoadStatus.SANDBOX_PREPARED);
    }

    @Test
    void 实际密文损坏在HTTP响应提交前拒绝并给出关联错误(CapturedOutput output) throws Exception {
        script(version, "tamper-file"); finish();
        UUID run = conversations.send(owner, agent, conversation, "生成完整性验收文件").run().runId();
        var row = artifactRows.list(owner.tenantId(), run).getFirst();
        Path object = volume.resolve(owner.tenantId().toString()).resolve(row.id() + ".gcm");
        byte[] bytes = Files.readAllBytes(object);
        bytes[bytes.length - 1] ^= 1;
        Files.write(object, bytes);
        var response = mvc.perform(get("/api/skill-artifacts/" + row.id() + "/content").header("Authorization", token(owner)))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        var error = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(error.path("code").asText()).isEqualTo("SKILL_ARTIFACT_UNAVAILABLE");
        assertThat(error.path("errorId").asText()).isNotBlank();
        assertThat(output).contains("errorId=" + error.path("errorId").asText());
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("原始固定版本", volume.toString(), encryptionKey, "Exception");
    }

    private void requiredDependency() {
        work.execute(() -> {
            var current = definitions.lock(owner.tenantId(), skill);
            dependencies.insertAll(List.of(new SkillDependency(owner.tenantId(), skill, version, "approval", true, "审批工具", 0)));
            mappings.save(new SkillDependencyMapping(owner.tenantId(), skill, "approval", echoTool, owner.principalId(), Instant.now()));
            assertThat(definitions.updateDependencyMappingRevision(new SkillDefinition(current.id(), current.tenantId(), current.name(),
                    current.candidateVersionId(), current.publishedVersionId(), current.enabled(), current.accessEpoch(),
                    current.dependencyMappingRevision() + 1, current.createdBy(), owner.principalId(), current.createdAt(), Instant.now()),
                    current.dependencyMappingRevision())).isTrue();
            return null;
        });
    }

    private void load(UUID fixed, String call) { respond("load_skill_through_path", call, Map.of("skillId", nativeId(fixed), "path", "SKILL.md")); }
    private void script(UUID fixed, String call) { respond("run_skill_script", call, Map.of("skillId", nativeId(fixed), "path", "scripts/main.py", "stdin", "输入")); }
    private void echo() { respond("echo", "approval-call", Map.of()); }
    private void respond(String name, String call, Map<String, ?> args) {
        responses.add(ignored -> json(Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture",
                "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", call,
                "type", "function", "function", Map.of("name", name, "arguments", json(args))))), "finish_reason", "tool_calls")))));
    }
    private void finish() { responses.add(ignored -> json(Map.of("id", "fixture-final", "object", "chat.completion", "created", 2, "model", "fixture",
            "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", "文件任务完成"), "finish_reason", "stop"))))); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("回环响应序列化失败", e); } }
    private String nativeId(UUID fixed) {
        var view = versions.find(owner.tenantId(), skill, fixed).orElseThrow();
        var files = new LinkedHashMap<String, String>();
        resources.list(owner.tenantId(), skill, fixed).forEach(resource -> files.put(resource.path(), resource.content()));
        // 使用与生产仓储相同的固定正文和来源构造 SDK 标识；AgentScope 2.0.2 拒绝空正文。
        return new AgentSkill("harness-files", view.description(), view.content(), files,
                "cm-agent-run-snapshot:" + skill + ":" + fixed).getSkillId();
    }
    private ToolApprovalService.ToolApprovalView waiting() {
        assertThat(conversations.send(owner, agent, conversation, "生成固定文件并审批").run().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        return approvals.listPending(owner, agent, conversation).getFirst();
    }
    private ToolApprovalService.ApprovalResumeOutcome approve(ToolApprovalService.ToolApprovalView pending) {
        return approvals.decideAndResume(owner, agent, conversation, pending.approvalId(), pending.version(),
                pending.items().stream().map(item -> new ToolApprovalService.ItemDecision(item.itemId(), ToolApprovalDecision.APPROVE)).toList(),
                ignored -> {}, ignored -> {}, ignored -> {});
    }
    private SkillAccessException failedSend(ApiErrorCode code) {
        Throwable failure = catchThrowable(() -> conversations.send(owner, agent, conversation, "验证拒绝边界"));
        assertThat(failure).isInstanceOfSatisfying(SkillAccessException.class, e -> assertThat(e.code()).isEqualTo(code));
        return (SkillAccessException) failure;
    }
    private RunRecord currentRun() {
        // 仅用于断言当前随机测试租户和 Agent 的运行事实，不引入生产查询入口。
        UUID run = jdbc.sql("SELECT id FROM runs WHERE tenant_id=:tenant AND agent_id=:agent ORDER BY started_at DESC")
                .param("tenant", owner.tenantId().toString()).param("agent", agent.toString()).query(String.class).list().stream().map(UUID::fromString).findFirst().orElseThrow();
        return runs.findByTenantAndAgentAndId(owner.tenantId(), agent, run).orElseThrow();
    }
    private void assertFailureCleaned(SkillAccessException failure, CapturedOutput output) {
        var run = currentRun();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failure.errorId()).isNotBlank();
        assertThat(output).contains("errorId=" + failure.errorId(), "errorCode=" + failure.code(), "runId=" + run.id());
        assertThat(failure.safeMessage()).doesNotContain(encryptionKey, volume.toString(), "Exception", "http://");
        assertThat(checkpoints.exists(owner.tenantId(), user(), run.id().toString())).isFalse();
        cleaned(run.id());
    }
    private void cleaned(UUID run) {
        assertThat(artifacts.list(owner, agent, run, null).files()).isEmpty();
        var rows = artifactRows.list(owner.tenantId(), run);
        for (var row : rows) assertThatThrownBy(() -> artifacts.download(owner, row.id())).isInstanceOf(SkillAccessException.class);
        artifacts.cleanup();
        for (var row : artifactRows.list(owner.tenantId(), run)) {
            assertThat(row.status()).isEqualTo(SkillArtifactStatus.DELETED);
            assertThatThrownBy(() -> storage.readVerified(row)).isInstanceOf(java.io.IOException.class);
        }
    }
    private String download(SkillArtifactService service, SkillArtifact row) throws Exception {
        try (var download = service.download(owner, row.id())) {
            var output = new ByteArrayOutputStream(); download.write(output);
            assertThat((long) output.size()).isEqualTo(row.sizeBytes());
            return output.toString(StandardCharsets.UTF_8);
        }
    }
    private UUID replaceScript(String script) {
        return work.execute(() -> {
            var current = definitions.lock(owner.tenantId(), skill);
            UUID next = UUID.randomUUID(); insertVersion(next, 2, script);
            assertThat(definitions.updatePointers(new SkillDefinition(skill, owner.tenantId(), current.name(), next, next,
                    current.enabled(), current.accessEpoch(), current.dependencyMappingRevision(), current.createdBy(), owner.principalId(),
                    current.createdAt(), Instant.now()), current.candidateVersionId(), current.publishedVersionId())).isTrue();
            return next;
        });
    }
    private void revoke() {
        work.execute(() -> {
            var d = definitions.lock(owner.tenantId(), skill);
            definitions.updateEnabled(new SkillDefinition(d.id(), d.tenantId(), d.name(), d.candidateVersionId(), d.publishedVersionId(),
                    false, d.accessEpoch() + 1, d.dependencyMappingRevision(), d.createdBy(), owner.principalId(), d.createdAt(), Instant.now()));
            return null;
        });
    }
    private void insertVersion(UUID id, int number, String script) {
        String instructions = "读取本技能后执行 scripts/main.py，将文本写入 CM_AGENT_ARTIFACT_DIR。";
        versions.insert(new SkillVersion(id, owner.tenantId(), skill, number, "固定文件验收", Map.of(), instructions,
                sha(instructions), owner.principalId(), Instant.now()));
        resources.insertAll(List.of(new SkillResource(owner.tenantId(), skill, id, "scripts/main.py", "text/x-python", script,
                script.getBytes(StandardCharsets.UTF_8).length, sha(script))));
    }
    private static String sha(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("摘要算法不可用", e); } }
    private static String fileScript(String content) {
        return "import os, pathlib, sys\nassert os.getuid() != 0\nassert not pathlib.Path('/var/run/docker.sock').exists()\n"
                + "assert sys.stdin.read() == '输入'\npathlib.Path(os.environ['CM_AGENT_ARTIFACT_DIR'], '测试.txt')"
                + ".write_text('" + content + "', encoding='utf-8')\nprint('已生成文件')\n";
    }
    private String user() { return owner.tenantId() + ":" + owner.principalId(); }
    private String token(PrincipalRef p) { return "Bearer " + jwt.createToken(p.tenantId(), p.principalId(), p.displayName(), List.copyOf(p.permissions())); }
    private static Path temporaryVolume() { try { return Files.createTempDirectory("harness-skill-contract-"); } catch (java.io.IOException e) { throw new IllegalStateException("无法创建测试文件卷", e); } }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        /** 占位凭据只发送到当前回环服务，不读取真实模型认证材料。 */
        @Bean ModelCredentialProvider fixtureCredentials() { return (tenant, model) -> new ModelCredential("invalid-loopback-key"); }
    }
}
