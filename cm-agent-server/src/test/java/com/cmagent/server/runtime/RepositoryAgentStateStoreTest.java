package com.cmagent.server.runtime;

import com.cmagent.server.store.InMemoryRuntimeCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryAgentStateStoreTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String USER_ID = TENANT_ID + ":principal";

    @Test
    void 状态以密文保存并可跨实例恢复() {
        InMemoryRuntimeCheckpointRepository repository = new InMemoryRuntimeCheckpointRepository();
        ModelCredentialCipher cipher = cipher();
        RepositoryAgentStateStore first = store(repository, cipher, Duration.ofMinutes(15));

        first.save(USER_ID, "run-1", "custom", new TestState("高风险参数"));

        String encrypted = repository.find(TENANT_ID, USER_ID, "run-1", "custom")
                .orElseThrow().encryptedPayload();
        assertThat(encrypted).startsWith("v1:").doesNotContain("高风险参数");
        RepositoryAgentStateStore second = store(repository, cipher, Duration.ofMinutes(15));
        assertThat(second.get(USER_ID, "run-1", "custom", TestState.class))
                .get().extracting(TestState::getValue).isEqualTo("高风险参数");
    }

    @Test
    void 支持状态列表并在过期读取时删除条目() {
        InMemoryRuntimeCheckpointRepository repository = new InMemoryRuntimeCheckpointRepository();
        RepositoryAgentStateStore store = store(repository, cipher(), Duration.ofSeconds(-1));
        store.save(USER_ID, "run-2", "items", List.of(new TestState("one"), new TestState("two")));

        assertThat(store.getList(USER_ID, "run-2", "items", TestState.class)).isEmpty();
        assertThat(repository.exists(TENANT_ID, USER_ID, "run-2")).isFalse();
    }

    @Test
    void 拒绝缺少可信租户前缀的状态槽() {
        RepositoryAgentStateStore store = store(
                new InMemoryRuntimeCheckpointRepository(), cipher(), Duration.ofMinutes(15));

        assertThatThrownBy(() -> store.exists("principal", "run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AgentScope userId 缺少可信租户前缀");
    }

    private static RepositoryAgentStateStore store(
            InMemoryRuntimeCheckpointRepository repository,
            ModelCredentialCipher cipher,
            Duration ttl
    ) {
        return new RepositoryAgentStateStore(repository, cipher, new ObjectMapper(), Clock.systemUTC(), ttl);
    }

    private static ModelCredentialCipher cipher() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        return new ModelCredentialCipher(new SecretKeySpec(key, "AES"));
    }

    /** Jackson 往返测试使用的最小状态对象。 */
    public static final class TestState implements State {
        private String value;

        public TestState() {
        }

        TestState(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
