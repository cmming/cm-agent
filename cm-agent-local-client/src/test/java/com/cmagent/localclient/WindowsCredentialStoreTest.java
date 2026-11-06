package com.cmagent.localclient;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsCredentialStoreTest {
    @Test
    void 仅创建读取并删除本次随机测试凭据() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var store = new WindowsCredentialStore();
        byte[] secret = {1, 2, 3, 4};
        String reference = store.create(secret);
        try {
            byte[] read = store.read(reference).orElseThrow();
            assertThat(read).containsExactly(1, 2, 3, 4);
            Arrays.fill(read, (byte) 0);
            assertThat(store.read(reference).orElseThrow()).containsExactly(1, 2, 3, 4);
            assertThat(store.delete(reference)).isTrue();
            assertThat(store.read(reference)).isEmpty();
            assertThat(store.delete(reference)).isFalse();
            assertThatThrownBy(() -> store.delete("unrelated-entry")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            store.delete(reference);
        }
    }
}
