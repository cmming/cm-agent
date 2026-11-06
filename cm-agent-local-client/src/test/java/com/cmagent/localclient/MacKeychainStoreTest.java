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

class MacKeychainStoreTest {
    @Test
    void 秘密只经标准输入且只能读取删除本应用随机条目() throws Exception {
        var fake = new FakeRunner();
        var store = new MacKeychainStore(fake);
        byte[] secret = {1, 2, 3, 4};
        String reference = store.create(secret);

        assertThat(store.read(reference).orElseThrow()).containsExactly(1, 2, 3, 4);
        assertThat(fake.lastArguments).doesNotContain(new String(secret, StandardCharsets.UTF_8));
        assertThat(store.delete(reference)).isTrue();
        assertThat(store.read(reference)).isEmpty();
        assertThatThrownBy(() -> store.read("other-entry")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 系统工具报错不能误认为条目不存在() {
        var store = new MacKeychainStore((arguments, input) ->
                new MacKeychainStore.Result(1, new byte[0], true));
        assertThatThrownBy(() -> store.read("device-00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(IOException.class).hasMessage("系统钥匙串读取失败");
    }

    private static final class FakeRunner implements MacKeychainStore.Runner {
        private final Map<String, byte[]> values = new HashMap<>();
        private List<String> lastArguments = List.of();

        @Override public MacKeychainStore.Result run(List<String> arguments, byte[] input) {
            lastArguments = List.copyOf(arguments);
            String key = arguments.get(arguments.indexOf("-a") + 1);
            return switch (arguments.getFirst()) {
                case "add-generic-password" -> {
                    int end = input.length;
                    while (end > 0 && (input[end - 1] == '\n' || input[end - 1] == '\r')) end--;
                    values.put(key, Arrays.copyOf(input, end));
                    yield new MacKeychainStore.Result(0, new byte[0], false);
                }
                case "find-generic-password" -> {
                    byte[] value = values.get(key);
                    yield new MacKeychainStore.Result(value == null ? 1 : 0,
                            value == null ? new byte[0] : value.clone(), false);
                }
                case "delete-generic-password" -> {
                    byte[] removed = values.remove(key);
                    if (removed != null) Arrays.fill(removed, (byte) 0);
                    yield new MacKeychainStore.Result(removed == null ? 1 : 0, new byte[0], false);
                }
                default -> throw new IllegalArgumentException();
            };
        }
    }
}
