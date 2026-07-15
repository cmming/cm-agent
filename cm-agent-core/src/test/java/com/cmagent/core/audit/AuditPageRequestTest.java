package com.cmagent.core.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditPageRequestTest {

    @Test
    void acceptsBoundedLimitAndCompleteCursor() {
        assertThatCode(() -> new AuditPageRequest(1, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> new AuditPageRequest(
                100,
                Instant.parse("2026-07-14T00:00:00Z"),
                UUID.randomUUID()
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsLimitOutsideOneToOneHundred() {
        assertThatThrownBy(() -> new AuditPageRequest(0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须在 1 到 100 之间");
        assertThatThrownBy(() -> new AuditPageRequest(101, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须在 1 到 100 之间");
    }

    @Test
    void rejectsPartialCursor() {
        assertThatThrownBy(() -> new AuditPageRequest(20, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("beforeCreatedAt 与 beforeId 必须同时为空或同时非空");
        assertThatThrownBy(() -> new AuditPageRequest(20, null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("beforeCreatedAt 与 beforeId 必须同时为空或同时非空");
    }
}
