package com.cmagent.server.runtime;

import com.cmagent.persistence.CmAgentFlyway;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仅Rocky运行两个实际Server二进制的排空、模式往返和旧包回退合同。
 *
 * <p>数据库由Testcontainers创建，模型是无外部副作用的回环协议桩。依次停止全部测试实例后
 * 才启动下一策略或旧包；旧包没有V21模式门禁，不能用并存旧进程证明滚动升级安全。
 * 数据库备份只保存在测试内存并核对摘要，真实凭据、模型正文不进入证据文件。</p>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CM_AGENT_TEST_BINARY_ROLLBACK", matches = "true")
class HarnessBinaryRollbackIntegrationTest {
    /** 每库仅供本类独占，不读取或修改部署数据库。 */
    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** MySQL与PostgreSQL各跑同一实际二进制流程，退出时由Testcontainers释放。 */
    @org.testcontainers.junit.jupiter.Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    /** 仅序列化合成请求和子进程环境，不输出认证请求或响应。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings = {"postgresql", "mysql"})
    void 排空后切换策略并启动旧二进制保留历史(String dialect) throws Exception {
        JdbcDatabaseContainer<?> database = "mysql".equals(dialect) ? mysql : postgres;
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        CmAgentFlyway.configure(source).load().migrate();
        var jdbc = JdbcClient.create(source);
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,'default','二进制验收',true,:now)")
                .param("id", "00000000-0000-0000-0000-000000000001")
                .param("now", Timestamp.from(Instant.now())).update();
        String password = UUID.randomUUID() + "Aa!", jwtSecret = randomBase64(64), encryptionKey = randomBase64(32);
        var loopback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        loopback.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"id\":\"receipt\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"二进制验收通过\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        loopback.start();
        Path logRoot = Path.of("target", "harness-t6", "binary-logs").toAbsolutePath();
        Files.createDirectories(logRoot);
        Path logs = Files.createTempDirectory(logRoot, "cm-binary-rollback-");
        boolean accepted = false;
        var settings = new HashMap<String, Object>();
        settings.put("cm-agent.config.jwt-secret", jwtSecret);
        settings.put("cm-agent.config.bootstrap-admin-password", password);
        settings.put("cm-agent.config.fake-runtime-enabled", false);
        settings.put("cm-agent.config.agentscope-enabled", true);
        settings.put("cm-agent.agentscope.enabled", true);
        settings.put("cm-agent.agentscope.studio.enabled", false);
        settings.put("cm-agent.agentscope.model-max-attempts", 1);
        settings.put("cm-agent.model-credentials.encryption-key", encryptionKey);
        settings.put("cm-agent.persistence.mode", "jdbc");
        settings.put("cm-agent.persistence.jdbc.url", database.getJdbcUrl());
        settings.put("cm-agent.persistence.jdbc.username", database.getUsername());
        settings.put("cm-agent.persistence.jdbc.password", database.getPassword());
        settings.put("cm-agent.persistence.jdbc.driver-class-name", database.getDriverClassName());
        try {
            UUID agent;
            // 所有HTTP操作完成并确认状态排空，才关闭实例；没有并发调用跨过模式切换点。
            try (var app = start("CM_AGENT_TEST_CURRENT_JAR", "react", settings, logs.resolve("react.log"))) {
                String token = login(app, password);
                String model = post(app, "/api/model-configs", token, Map.of("providerType", "OPENAI_COMPATIBLE",
                        "displayName", "回环二进制验收", "baseUrl", "http://127.0.0.1:" + loopback.getAddress().getPort() + "/v1",
                        "modelName", "fixture", "enabled", true, "apiKey", "invalid-loopback-key")).path("id").asText();
                agent = UUID.fromString(post(app, "/api/agents", token, Map.of("name", "二进制验收",
                        "systemPrompt", "只完成当前验收。", "modelConfigId", model, "enabled", true)).path("id").asText());
                run(app, token, agent);
                drained(jdbc);
            }
            try (var app = start("CM_AGENT_TEST_CURRENT_JAR", "harness", settings, logs.resolve("harness.log"))) {
                assertEquals("harness", jdbc.sql("SELECT execution_mode FROM runtime_execution_modes WHERE id=1").query(String.class).single());
                run(app, login(app, password), agent);
                drained(jdbc);
            }
            // 使用支持门禁的新包先恢复react部署元数据；确认它已退出后才能启动没有门禁的旧包。
            try (var app = start("CM_AGENT_TEST_CURRENT_JAR", "react", settings, logs.resolve("restore-mode.log"))) {
                drained(jdbc);
            }
            var backup = database.execInContainer("sh", "-c", "mysql".equals(dialect)
                    ? "MYSQL_PWD=\"$MYSQL_PASSWORD\" mysqldump --no-tablespaces -u\"$MYSQL_USER\" \"$MYSQL_DATABASE\""
                    : "pg_dump -U \"$POSTGRES_USER\" \"$POSTGRES_DB\"");
            assertEquals(0, backup.getExitCode(), "临时数据库备份失败");
            assertTrue(backup.getStdout().contains("runtime_execution_modes"), "备份缺少模式元数据");
            String backupHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(backup.getStdout().getBytes(StandardCharsets.UTF_8)));
            long history = count(jdbc, "SELECT COUNT(*) FROM runs");
            assertEquals(21L, count(jdbc, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=true"));
            try (var app = start("CM_AGENT_TEST_OLD_JAR", "react", settings, logs.resolve("old.log"))) {
                run(app, login(app, password), agent);
                drained(jdbc);
                assertEquals(history + 1, count(jdbc, "SELECT COUNT(*) FROM runs"), "旧包没有保留当前运行历史");
                assertEquals(21L, count(jdbc, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=true"), "回退擅自修改迁移历史");
            }
            System.out.println("BINARY_ROLLBACK database=" + dialect + " strategy=react-harness-react-old"
                    + " schema=21 history_preserved=true drained=true backup_sha256=" + backupHash);
            accepted = true;
        } finally {
            loopback.stop(0);
            // 失败日志保留在忽略目录便于定位；通过后只删除本类随机目录，不跟随链接。
            if (accepted) try (var paths = Files.walk(logs)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** 使用子进程环境注入临时认证信息，避免密码出现在CLI参数、固定文件或测试报告。 */
    private static App start(String jarVariable, String mode, Map<String, Object> settings, Path log) throws Exception {
        String jar = System.getenv(jarVariable);
        assertTrue(jar != null && Files.isRegularFile(Path.of(jar)), "缺少待验证二进制");
        // 本仓库package生成普通JAR，验收需显式spring-boot:repackage，不能把打包成功当可执行包。
        try (var archive = new java.util.jar.JarFile(jar)) {
            assertNotNull(archive.getManifest().getMainAttributes().getValue("Main-Class"), "待验收包尚未repackage");
        }
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        var config = new HashMap<>(settings);
        config.put("server.port", port);
        config.put("cm-agent.agentscope.execution-mode", mode);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar);
        builder.environment().put("SPRING_PROFILES_ACTIVE", "test");
        builder.environment().put("SPRING_APPLICATION_JSON", JSON.writeValueAsString(config));
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        var app = new App(builder.start(), URI.create("http://127.0.0.1:" + port));
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && app.process().isAlive()) {
            try {
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(app.base().resolve("/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return app;
            } catch (java.io.IOException ignored) { /* 启动中的端口尚未监听，有限等待而非请求重放。 */ }
            Thread.sleep(250);
        }
        app.close();
        fail("二进制未在启动预算内健康；检查临时子进程诊断");
        return app;
    }

    private static String login(App app, String password) throws Exception {
        String token = post(app, "/api/auth/login", "", Map.of("username", "admin", "password", password)).path("accessToken").asText();
        assertFalse(token.isBlank(), "登录没有返回访问令牌");
        return token;
    }

    private static void run(App app, String token, UUID agent) throws Exception {
        var result = post(app, "/api/agents/" + agent + "/runs", token, Map.of("input", "完成二进制验收"));
        assertEquals("SUCCEEDED", result.path("status").asText(), "实际Server运行未成功");
    }

    /** HTTP失败只断言状态，不把请求密钥、JWT或完整响应放入断言消息。 */
    private static JsonNode post(App app, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(app.base().resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        var response = HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "合成验收HTTP请求失败，状态=" + response.statusCode());
        return JSON.readTree(response.body());
    }

    private static void drained(JdbcClient jdbc) {
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM runs WHERE status NOT IN ('SUCCEEDED','FAILED','DENIED','CANCELLED')"));
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM skill_trials WHERE status NOT IN ('PASSED','NOT_TRIGGERED','FAILED')"));
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM runtime_checkpoints"));
    }

    private static long count(JdbcClient jdbc, String sql) { return jdbc.sql(sql).query(Long.class).single(); }
    private static String randomBase64(int size) { byte[] bytes = new byte[size]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }

    /**
     * 子进程只在独占测试数据集上持有资源，close等待实际退出后下一实例才能启动。
     * @param process 本轮实际Server进程
     * @param base 回环测试HTTP地址，不是部署入口
     */
    private record App(Process process, URI base) implements AutoCloseable {
        @Override public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                fail("二进制未按预算正常退出，不能视为排空回退成功");
            }
        }
    }
}
