package com.cmagent.core.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cmagent.core.repository.RunRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunPageRequestTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-14T00:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void requestRejectsInvalidLimitsAndIncompleteCursor() {
        assertThatThrownBy(() -> new RunPageRequest(0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须在 1 到 100 之间");
        assertThatThrownBy(() -> new RunPageRequest(101, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须在 1 到 100 之间");
        assertThatThrownBy(() -> new RunPageRequest(1, STARTED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("beforeStartedAt 与 beforeId 必须同时为空或同时非空");
        assertThatThrownBy(() -> new RunPageRequest(1, null, ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("beforeStartedAt 与 beforeId 必须同时为空或同时非空");
    }

    @Test
    void repositoryListRequiresValidatedPageRequest() {
        assertThatCode(() -> RunRepository.class.getMethod(
                "listByTenantAndAgent", UUID.class, UUID.class, RunPageRequest.class))
                .doesNotThrowAnyException();
    }
}
