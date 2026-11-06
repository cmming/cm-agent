package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalProjectCiphertextTest {
    @Test void 明文路径及伪信封不能写入项目根() {
        for (String invalid : new String[]{"F:/private/project", "/srv/private", "v1:bad:payload", "v1::", "v2:aa:bb"}) {
            assertThatThrownBy(() -> new LocalProjectRoot(UUID.randomUUID(), UUID.randomUUID(), "owner", "node",
                    invalid, "a".repeat(64), Instant.now())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("项目受保护字段必须为有效的有界密文信封");
        }
    }

    @Test void 先限制大小并拒绝缺少认证标签的载荷() {
        assertThatThrownBy(() -> LocalProjectCiphertext.require("x".repeat(1025), 1024)).isInstanceOf(IllegalArgumentException.class);
        String iv = Base64.getEncoder().encodeToString(new byte[12]);
        assertThatThrownBy(() -> LocalProjectCiphertext.require("v1:" + iv + ":" + Base64.getEncoder().encodeToString(new byte[16]), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> LocalProjectCiphertext.require("v1:" + iv + ":" + Base64.getEncoder().encodeToString(new byte[17]), 1024))
                .doesNotThrowAnyException();
    }
}

