package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ToolApprovalHistoryPageRequestTest {
    @Test
    void 分页大小与复合游标必须有效() {
        assertThat(new ToolApprovalHistoryPageRequest(20, null, null).limit()).isEqualTo(20);
        assertThatThrownBy(() -> new ToolApprovalHistoryPageRequest(0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolApprovalHistoryPageRequest(101, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolApprovalHistoryPageRequest(20, Instant.now(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolApprovalHistoryPageRequest(20, null, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
