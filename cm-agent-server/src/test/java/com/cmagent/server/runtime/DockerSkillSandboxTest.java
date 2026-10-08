package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.server.config.SkillSandboxProperties;
import com.cmagent.core.runtime.SkillAccessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DockerSkillSandboxTest {
    @Test
    void 默认拒绝执行且没有宿主回退() {
        var sandbox = new DockerSkillSandbox(new SkillSandboxProperties());
        assertThatThrownBy(() -> sandbox.execute(DockerSkillSandboxIntegrationTest.request(), Map.of("scripts/main.py", "print(1)"), ""))
                .isInstanceOfSatisfying(SkillAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_DISABLED));
    }

    @Test
    void 固定参数禁止网络挂载提权和自动拉取() {
        var properties = new SkillSandboxProperties();
        properties.setRuntime("runsc");
        var command = new DockerSkillSandbox(properties).command("cm-agent-skill-test");
        assertThat(command).contains("--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user=65534:65534", "--pids-limit=32", "--memory=128m", "--memory-swap=128m", "--cpus=0.5",
                "--pull=never", "--runtime=runsc", "--log-driver=none", "--entrypoint=python",
                "--tmpfs=/workspace:rw,noexec,nosuid,nodev,size=16m,mode=1777,uid=65534,gid=65534")
                .doesNotContain("--privileged", "--volume", "-v", "sh", "bash", "/var/run/docker.sock");
    }

    @Test
    void 路径与输入非法时不启动容器() {
        var properties = new SkillSandboxProperties();
        properties.setEnabled(true);
        properties.setMaxInputBytes(3);
        var sandbox = new DockerSkillSandbox(properties);
        assertThatThrownBy(() -> sandbox.execute(DockerSkillSandboxIntegrationTest.request(), Map.of("../main.py", "print(1)"), ""))
                .isInstanceOfSatisfying(SkillAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_INVALID));
        assertThatThrownBy(() -> sandbox.execute(DockerSkillSandboxIntegrationTest.request(), Map.of("scripts/main.py", "print(1)"), "中文"))
                .isInstanceOfSatisfying(SkillAccessException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_INVALID));
        assertThat(DockerSkillSandbox.safePath("/tmp/main.py")).isFalse();
        assertThat(DockerSkillSandbox.safePath("a/../../main.py")).isFalse();
        assertThat(DockerSkillSandbox.safePath("C:\\main.py")).isFalse();
        assertThat(DockerSkillSandbox.safePath("scripts//main.py")).isFalse();
        assertThat(DockerSkillSandbox.safePath("scripts/main.py")).isTrue();
    }
}
