package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.server.config.SkillSandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 仅在 ssh rocky 的 Maven 21 容器内显式启用，真实验证服务调用的 Docker 隔离参数。 */
@EnabledIfEnvironmentVariable(named = "CM_AGENT_TEST_SANDBOX", matches = "true")
class DockerSkillSandboxIntegrationTest {
    @Test
    void 实际执行固定脚本并通过标准输入读取同版本资源() {
        String output = sandbox().execute(request(), Map.of(
                "scripts/main.py", "import sys,pathlib\nprint(sys.stdin.read() + pathlib.Path('data.txt').read_text())",
                "data.txt", "资源"), "输入");
        assertThat(output).isEqualTo("输入资源\n");
    }

    @Test
    void 实际阻止外网宿主写入并以非root运行() {
        String script = """
                import os,socket,pathlib
                assert os.getuid() == 65534
                assert not pathlib.Path('/var/run/docker.sock').exists()
                assert not pathlib.Path('/host').exists()
                assert 'CM_AGENT_TEST_PRIVATE_VALUE' not in os.environ
                cgroup = pathlib.Path('/sys/fs/cgroup')
                if (cgroup / 'memory.max').exists():
                    assert (cgroup / 'memory.max').read_text().strip() == '134217728'
                    assert (cgroup / 'pids.max').read_text().strip() == '32'
                    quota, period = (cgroup / 'cpu.max').read_text().split()
                    assert int(quota) / int(period) == 0.5
                try:
                    pathlib.Path('/escape.txt').write_text('escape')
                    raise AssertionError('root writable')
                except OSError:
                    pass
                try:
                    socket.create_connection(('1.1.1.1', 80), timeout=0.5)
                    raise AssertionError('network enabled')
                except OSError:
                    pass
                print('isolated')
                """;
        assertThat(sandbox().execute(request(), Map.of("scripts/main.py", script), "")).isEqualTo("isolated\n");
    }

    @Test
    void 实际超时销毁容器及子进程() {
        var properties = properties();
        properties.setTimeout(Duration.ofSeconds(2));
        var sandbox = new DockerSkillSandbox(properties);
        assertCode(() -> sandbox.execute(request(), Map.of("scripts/main.py",
                "import subprocess,time\nsubprocess.Popen(['python','-c','import time; time.sleep(120)'])\ntime.sleep(120)"), ""),
                ApiErrorCode.SKILL_SANDBOX_TIMEOUT);
    }

    @Test
    void 实际输出超限立即失败() {
        var properties = properties();
        properties.setMaxOutputBytes(1024);
        assertCode(() -> new DockerSkillSandbox(properties).execute(request(), Map.of("scripts/main.py",
                "while True: print('x' * 4096, flush=True)"), ""), ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED);
    }

    @Test
    void 脚本失败不泄露原始堆栈凭据和内部地址() {
        assertThatThrownBy(() -> sandbox().execute(request(), Map.of("scripts/main.py",
                "raise RuntimeError('api_key=verification-only-value https://internal.example.local/secret')"), ""))
                .isInstanceOfSatisfying(SkillAccessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_FAILED);
                    assertThat(failure.safeMessage()).doesNotContain("verification-only-value", "internal.example", "Traceback");
                });
    }

    @Test
    void 成功输出也会脱敏() {
        assertThat(sandbox().execute(request(), Map.of("scripts/main.py",
                "print('api_key=verification-only-value https://internal.example.local/private')"), ""))
                .doesNotContain("verification-only-value", "internal.example");
    }

    @Test
    void 镜像缺失不会回退宿主解释器() {
        var properties = properties();
        properties.setImage("cm-agent-skill-image-unavailable:test-only");
        assertCode(() -> new DockerSkillSandbox(properties).execute(request(), Map.of("scripts/main.py", "print(1)"), ""),
                ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ApiErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SkillAccessException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static DockerSkillSandbox sandbox() { return new DockerSkillSandbox(properties()); }
    private static SkillSandboxProperties properties() {
        var properties = new SkillSandboxProperties();
        properties.setEnabled(true);
        return properties;
    }

    static SkillReadRequest request() {
        return new SkillReadRequest(new PrincipalRef(UUID.randomUUID(), "sandbox-tester", "沙箱测试", Set.of("agent:run")),
                UUID.randomUUID(), UUID.randomUUID(), "call-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "scripts/main.py");
    }
}
