package com.cmagent.server;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsPilotLauncherTest {
    @TempDir
    Path pilotHome;

    @Test
    void 启动参数固定回环与审批且只引用外部配置路径() {
        var config = pilotHome.resolve("试装 目录").resolve("pilot.properties");
        var state = pilotHome.resolve("state");
        var origin = URI.create("http://127.0.0.1:18532");

        assertThat(WindowsPilotLauncher.launchOptions(config, state, origin))
                .contains("--spring.config.location=" + config.toUri().toASCIIString(),
                        "--spring.profiles.active=local",
                        "--server.address=127.0.0.1",
                        "--server.port=18532",
                        "--management.server.port=18532",
                        "--server.forward-headers-strategy=none",
                        "--cm-agent.persistence.mode=jdbc",
                        "--cm-agent.security.allow-dev-jwt-fallback=false",
                        "--cm-agent.agentscope.permission-enabled=true",
                        "--cm-agent.local-projects.allow-non-loopback=false",
                        "--cm-agent.local-projects.allowed-origins[0]=" + origin,
                        "--cm-agent.local-projects.state-directory=" + state,
                        "--cm-agent.local-projects.commands.fixed-arguments-only=true")
                .noneMatch(option -> option.startsWith("--management.server.address=")
                        || option.contains("secret=") || option.contains("password=") || option.contains("jdbc.url="));
    }

    @Test
    void 启动错误日志的诊断编号与窗口一致() throws Exception {
        var errorId = WindowsPilotLauncher.recordFailure(pilotHome, "PILOT_JDBC_UNAVAILABLE");

        assertThat(Files.readString(pilotHome.resolve("launcher.log")))
                .contains("operation=PILOT_LAUNCH errorCode=PILOT_JDBC_UNAVAILABLE errorId=" + errorId)
                .doesNotContain("jdbc:", "password=", "secret=", "SQLException", pilotHome.toString());
    }
}
