package com.cmagent.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class LocalProjectPathPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"../a.java", "src/../../a", "/etc/passwd", "C:/project/file", "src\\file.java",
            "src//a.java", "src/./a.java", "src/a.java.", "src/a.java ", "src/CON", "src/nul.txt",
            "src/com1.txt", "src/CONOUT$", "src/\u202Ea.java", "src/Ａ.java", "src/a\u0000.java",
            "src/a?.java", "src/a*.java", "src/a<b.java", "src/a>b.java", "src/a|b.java", "src/a\"b.java"})
    void 拒绝路径越界设备流别名与不可见字符且不回显输入(String path) {
        assertThatThrownBy(() -> LocalProjectPathPolicy.requireRelative(path, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("项目路径必须是无歧义的根内相对路径");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java/Main.java", "docs/需求.md", "pom.xml", "src/app.tsx", ".gitignore",
            "Dockerfile", ".mvn/settings.xml", "scripts/build.ps1"})
    void 普通开发文件保持原样且脚本只作为文本候选(String path) {
        assertThat(LocalProjectPathPolicy.requireRelative(path, false)).isEqualTo(path);
        assertThat(LocalProjectPathPolicy.textCandidate(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", ".env.example", "src/.ssh/config", ".git/config", "node_modules/a.js",
            "target/classes/app.xml", "secrets.yml", "cert/private.key", "config/store.p12", ".idea/workspace.xml"})
    void 私有凭据及生成目录不能因父目录获授权而开放(String path) {
        assertThat(LocalProjectPathPolicy.excluded(path)).isTrue();
        assertThat(LocalProjectPathPolicy.textCandidate(path)).isFalse();
    }

    @Test void 根标记只在明确允许时接受且层级和字符预算有界() {
        assertThat(LocalProjectPathPolicy.requireRelative("", true)).isEmpty();
        assertThatThrownBy(() -> LocalProjectPathPolicy.requireRelative("", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalProjectPathPolicy.requireRelative("a/".repeat(32) + "file.java", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalProjectPathPolicy.requireRelative("a".repeat(256), false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LocalProjectPathPolicy.textCandidate("assets/image.png")).isFalse();
        assertThat(LocalProjectPathPolicy.textCandidate("src/PasswordService.java")).isTrue();
    }
}

