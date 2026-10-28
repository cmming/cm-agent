package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceImportTest {
    @ParameterizedTest
    @ValueSource(strings={"../a.txt","C:/a.txt","/a.txt","a\\b.txt","a//b.txt","a/./b.txt",".ssh/a.txt",".env.txt",
            "node_modules/a.txt","credential.json","secret.txt","NUL.txt","a. /b.txt","a\u0000.txt","a\u202E.txt","Ａ.txt","a.exe"})
    void 非法路径与敏感名称不允许进入清单(String path) {
        assertThatThrownBy(() -> new WorkspaceImportFile(UUID.randomUUID(), path, 1, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void 导入标识按来源隔离且必须显式提供() {
        UUID id = UUID.randomUUID();
        assertThat(new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED, id).workspaceId()).isEqualTo(id);
        assertThatThrownBy(() -> new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED, null)).isInstanceOf(IllegalArgumentException.class);
        for (var mode : List.of(WorkspaceSelection.Mode.DEFAULT, WorkspaceSelection.Mode.NONE))
            assertThatThrownBy(() -> new WorkspaceSelection(mode, id)).isInstanceOf(IllegalArgumentException.class);
        var now = Instant.now();
        assertThatThrownBy(() -> new WorkspaceBinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED, id), "a".repeat(64), "本机", "local", 1, "owner", now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void 大小写碰撞和未完整上传的就绪状态拒绝() {
        var scope = new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID()); var now = Instant.now();
        var a = new WorkspaceImportFile(UUID.randomUUID(), "A.txt", 1, "a".repeat(64));
        var b = new WorkspaceImportFile(UUID.randomUUID(), "a.txt", 1, "a".repeat(64));
        assertThatThrownBy(() -> WorkspaceImport.draft(UUID.randomUUID(), scope, "本机", List.of(a, b), now, now.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        var draft = WorkspaceImport.draft(UUID.randomUUID(), scope, "本机", List.of(a), now, now.plusSeconds(60));
        assertThatThrownBy(() -> new WorkspaceImport(draft.id(), scope, "本机", draft.files(), draft.manifestDigest(), WorkspaceImport.State.READY,
                Set.of(), 2, now, now.plusSeconds(60), false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void 清单不可变且主体与文件身份参与摘要() {
        var scope = new WorkspaceImportScope(UUID.randomUUID(), "owner", UUID.randomUUID()); var now = Instant.now(); var id = UUID.randomUUID();
        var file = new WorkspaceImportFile(UUID.randomUUID(), "资料/a.md", 1, "a".repeat(64));
        var files = new ArrayList<>(List.of(file)); var draft = WorkspaceImport.draft(id, scope, "本机", files, now, now.plusSeconds(60));
        files.clear(); assertThat(draft.files()).containsExactly(file);
        assertThatThrownBy(() -> draft.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(WorkspaceImport.digest(id, new WorkspaceImportScope(scope.tenantId(), "other", scope.agentId()), draft.files()))
                .isNotEqualTo(draft.manifestDigest());
        assertThatThrownBy(() -> new WorkspaceImportLimits(9,16384,32768,8,64,262144,2097152,Duration.ofHours(1),Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
