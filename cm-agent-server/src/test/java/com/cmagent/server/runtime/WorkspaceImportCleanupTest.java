package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.WorkspaceImportStorage;
import com.cmagent.server.store.InMemoryWorkspaceImportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.*;
import java.util.*;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceImportCleanupTest {
    @Test void 删除失败不归还配额且后续扫描可补偿() throws Exception {
        var rows = new InMemoryWorkspaceImportRepository(); var limits = WorkspaceImportLimits.defaults(); var now = Instant.now();
        var scope = new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID());
        var value = WorkspaceImport.draft(UUID.randomUUID(), scope, "合成目录", List.of(new WorkspaceImportFile(UUID.randomUUID(), "a.txt", 1, "a".repeat(64))), now, now.plusSeconds(60));
        rows.reserve(value, limits, now); rows.revoke(scope, value.id(), 1, now);
        var storage = mock(WorkspaceImportStorage.class); var beans = new StaticListableBeanFactory(); beans.addBean("storage", storage);
        var cleanup = new WorkspaceImportCleanup(rows, beans.getBeanProvider(WorkspaceImportStorage.class));
        doThrow(new IOException("合成内部路径和秘密，不得写入日志")).doNothing().when(storage).delete(any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WorkspaceImportCleanup.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            cleanup.sweep(); assertThat(rows.find(scope, value.id()).orElseThrow().reclaimed()).isFalse();
            var event = appender.list.getFirst(); assertThat(event.getFormattedMessage()).contains("errorId=", "WORKSPACE_IMPORT_CLEANUP_FAILED", value.id().toString());
            assertThat(event.getThrowableProxy().getMessage()).doesNotContain("合成内部路径", "秘密");
            cleanup.sweep(); assertThat(rows.find(scope, value.id()).orElseThrow().reclaimed()).isTrue();
        } finally { logger.detachAppender(appender); }
    }
    @Test void 缺失卷不能被当成删除成功() {
        var rows = new InMemoryWorkspaceImportRepository(); var now = Instant.now();
        var scope = new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID());
        var value = WorkspaceImport.draft(UUID.randomUUID(), scope, "合成目录", List.of(new WorkspaceImportFile(UUID.randomUUID(), "a.txt", 1, "a".repeat(64))), now, now.plusSeconds(60));
        rows.reserve(value, WorkspaceImportLimits.defaults(), now); rows.revoke(scope, value.id(), 1, now);
        new WorkspaceImportCleanup(rows, new StaticListableBeanFactory().getBeanProvider(WorkspaceImportStorage.class)).sweep();
        assertThat(rows.find(scope, value.id()).orElseThrow().reclaimed()).isFalse();
    }
}
