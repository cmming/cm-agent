package com.cmagent.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleResourceTest {

    @Test
    void 控制台包含独立登录页和全部管理模块() throws IOException {
        String html = resource("META-INF/resources/index.html");

        assertThat(html)
                .contains("id=\"loginView\"", "id=\"consoleView\"", "id=\"loginForm\"")
                .contains("id=\"overviewPage\"", "id=\"agentsPage\"", "id=\"toolsPage\"")
                .contains("id=\"runsPage\"", "id=\"auditPage\"", "id=\"sidebarNav\"")
                .contains("/assets/console-core.js", "/assets/app.js")
                .doesNotContain("localStorage", "sessionStorage");
    }

    @Test
    void 控制台样式包含响应式和键盘焦点规则() throws IOException {
        String css = resource("META-INF/resources/assets/styles.css");

        assertThat(css)
                .contains(".sidebar", ".page-view", ".empty-state", ":focus-visible")
                .contains("@media (max-width: 900px)");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("资源应存在：%s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
