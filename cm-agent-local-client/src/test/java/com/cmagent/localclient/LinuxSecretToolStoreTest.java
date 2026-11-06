package com.cmagent.localclient;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinuxSecretToolStoreTest {
    @Test
    void 秘密只经标准输入且只能读取删除本应用随机引用() throws Exception {
        var fake = new FakeRunner();
        var store = new LinuxSecretToolStore(fake);
        byte[] secret = {1, 2, 3, 4};
        String reference = store.create(secret);

        assertThat(store.read(reference).orElseThrow()).containsExactly(1, 2, 3, 4);
        assertThat(fake.lastArguments).doesNotContain(new String(secret, StandardCharsets.UTF_8));
        assertThat(store.delete(reference)).isTrue();
        assertThat(store.read(reference)).isEmpty();
        assertThat(store.delete(reference)).isFalse();
        assertThatThrownBy(() -> store.read("other-application")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 系统工具报错不能误认为凭据不存在() {
        var store = new LinuxSecretToolStore((arguments, input) ->
                new LinuxSecretToolStore.Result(1, new byte[0], true));
        assertThatThrownBy(() -> store.read("00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(IOException.class).hasMessage("系统凭据读取失败");
    }

    /** 测试替身只保留模拟密文，不访问真实桌面密钥链。 */
    private static final class FakeRunner implements LinuxSecretToolStore.Runner {
        private final Map<String, byte[]> values = new HashMap<>();
        private List<String> lastArguments = List.of();

        @Override public LinuxSecretToolStore.Result run(List<String> arguments, byte[] input) {
            lastArguments = List.copyOf(arguments);
            String key = arguments.getLast();
            return switch (arguments.getFirst()) {
                case "store" -> {
                    values.put(key, input.clone());
                    yield new LinuxSecretToolStore.Result(0, new byte[0], false);
                }
                case "lookup" -> {
                    byte[] value = values.get(key);
                    yield new LinuxSecretToolStore.Result(value == null ? 1 : 0,
                            value == null ? new byte[0] : value.clone(), false);
                }
                case "clear" -> {
                    byte[] removed = values.remove(key);
                    if (removed != null) Arrays.fill(removed, (byte) 0);
                    yield new LinuxSecretToolStore.Result(0, new byte[0], false);
                }
                default -> throw new IllegalArgumentException();
            };
        }
    }
}
