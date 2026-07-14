package com.cmagent.server.web;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void auditPageCopiesMutableItemsBeforeHoldingThem() {
        AuditEvent event = auditEvent("11111111-1111-1111-1111-111111111111");
        List<AuditEvent> mutableItems = new ArrayList<>(List.of(event));

        AuditController.AuditPage page = new AuditController.AuditPage(mutableItems, null);

        mutableItems.clear();

        assertThat(page.items()).containsExactly(event);
        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyRepositoryDoesNotAdvertiseCursorPagination() {
        assertThat(new LegacyAuditEventRepository(List.of()).supportsCursorPagination()).isFalse();
    }

    @Test
    void inMemoryRepositoryAdvertisesCursorPagination() {
        assertThat(new InMemoryPlatformStore().supportsCursorPagination()).isTrue();
    }

    @Test
    void legacyRepositoryReturnsFullFirstPageWithoutAttemptingCursorProbe() throws Exception {
        LegacyAuditEventRepository repository = new LegacyAuditEventRepository(
                List.of(auditEvent("11111111-1111-1111-1111-111111111111"))
        );
        AuditController controller = new AuditController(
                repository,
                new DefaultPermissionEvaluator(),
                new AuditAppender(repository)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new JwtService.JwtSession(TENANT_ID, "admin", "管理员", List.of("audit:read")),
                null,
                List.of()
        );

        mockMvc.perform(get("/api/audit-events")
                        .param("limit", "1")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void legacyRepositoryRejectsCursorBeforeRepositoryPageCall() throws Exception {
        LegacyAuditEventRepository repository = new LegacyAuditEventRepository(
                List.of(auditEvent("11111111-1111-1111-1111-111111111111"))
        );
        AuditController controller = new AuditController(
                repository,
                new DefaultPermissionEvaluator(),
                new AuditAppender(repository),
                new SensitiveDataRedactor()
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new JwtService.JwtSession(TENANT_ID, "admin", "管理员", List.of("audit:read")),
                null,
                List.of()
        );
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "2026-07-14T00:00:00Z|11111111-1111-1111-1111-111111111111".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(get("/api/audit-events")
                        .param("cursor", cursor)
                        .principal(authentication))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditResponseRedactsPersistedMessage() throws Exception {
        AuditEvent raw = new AuditEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), TENANT_ID, "admin", "LOGIN",
                "AUTH", "login", "SUCCEEDED", "password=secret jdbc:postgresql://db:5432/cm_agent",
                Instant.parse("2026-07-14T00:00:00Z")
        );
        AuditEventRepository repository = new LegacyAuditEventRepository(List.of(raw));
        AuditController controller = new AuditController(
                repository,
                new DefaultPermissionEvaluator(),
                new AuditAppender(repository),
                new SensitiveDataRedactor()
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new JwtService.JwtSession(TENANT_ID, "admin", "管理员", List.of("audit:read")),
                null,
                List.of()
        );

        mockMvc.perform(get("/api/audit-events").param("limit", "1").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].message").value("password=<已脱敏> <已脱敏>"));
    }

    private static AuditEvent auditEvent(String id) {
        return new AuditEvent(
                UUID.fromString(id),
                TENANT_ID,
                "admin",
                "LOGIN",
                "AUTH",
                "login",
                "SUCCEEDED",
                "ok",
                Instant.parse("2026-07-14T00:00:00Z")
        );
    }

    private static final class LegacyAuditEventRepository implements AuditEventRepository {
        private final List<AuditEvent> events;

        private LegacyAuditEventRepository(List<AuditEvent> events) {
            this.events = events;
        }

        @Override
        public void append(AuditEvent event) {
            events.add(event);
        }

        @Override
        public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
            return events.stream()
                    .filter(event -> event.tenantId().equals(tenantId))
                    .limit(limit)
                    .toList();
        }
    }
}
