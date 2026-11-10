package com.cmagent.localclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientProjectOperationJournalTest {
    @Test void 新进程实例读取旧回执并拒绝同编号再次执行(@TempDir Path directory) throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        String digest = "a".repeat(64);
        Path journalDirectory = directory.resolve("operations");
        var mapper = new ObjectMapper();

        new ClientProjectOperationJournal(journalDirectory, mapper)
                .running(operationId, rootId, digest);
        var restarted = new ClientProjectOperationJournal(journalDirectory, mapper);

        assertThat(restarted.find(operationId).path("state").asText()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> restarted.running(operationId, rootId, digest))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("禁止重复执行");

        restarted.finish(operationId, "UNKNOWN");
        var restartedAgain = new ClientProjectOperationJournal(journalDirectory, mapper);
        assertThat(restartedAgain.find(operationId).path("state").asText()).isEqualTo("UNKNOWN");
        var receipt = Files.readString(journalDirectory.resolve(operationId + ".json"));
        assertThat(receipt).doesNotContain(directory.toString(), "fileBody", "absolutePath");
    }
}
