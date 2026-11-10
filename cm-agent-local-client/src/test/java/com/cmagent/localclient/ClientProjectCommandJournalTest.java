package com.cmagent.localclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientProjectCommandJournalTest {
    @Test void 客户端重启把孤立运行命令收口为未知且禁止复用(@TempDir Path directory)
            throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        String digest = "a".repeat(64);
        var mapper = new ObjectMapper();
        var path = directory.resolve("commands");
        var journal = new ClientProjectCommandJournal(path, mapper);
        journal.running(commandId, rootId, digest);

        var restarted = new ClientProjectCommandJournal(path, mapper);
        assertThat(restarted.find(commandId).path("state").asText()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> restarted.running(commandId, rootId, digest))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("禁止重复执行");
    }
}
