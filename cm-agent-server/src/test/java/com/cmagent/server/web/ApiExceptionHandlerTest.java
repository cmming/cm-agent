package com.cmagent.server.web;

import com.cmagent.server.audit.AuditPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void validationFailureUsesControlledChineseResponse() throws Exception {
        mockMvc.perform(get("/test/resources/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    @Test
    void persistenceFailureDoesNotExposeJdbcCredentialsOrSql() throws Exception {
        mockMvc.perform(get("/test/persistence"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERSISTENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("数据服务暂不可用"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unit-user:unit-password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("select * from"))));
    }

    @Test
    void auditPersistenceFailureMapsToServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/audit-persistence"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("审计服务暂不可用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database unavailable"))));
    }

    @Test
    void runtimeFailureDoesNotExposeBearerTokenOrApiKey() throws Exception {
        mockMvc.perform(get("/test/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务内部错误"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unit-test-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unit-test-api-key"))));
    }

    @RestController
    static class FailingController {
        @GetMapping("/test/resources/{id}")
        UUID resource(@PathVariable UUID id) {
            return id;
        }

        @GetMapping("/test/persistence")
        void persistence() {
            throw new DataAccessResourceFailureException(
                    "select * from users where password='unit-password' jdbc:postgresql://unit-user:unit-password@db.example/cm_agent"
            );
        }

        @GetMapping("/test/runtime")
        void runtime() {
            throw new IllegalStateException("Bearer unit-test-token apiKey=unit-test-api-key");
        }

        @GetMapping("/test/audit-persistence")
        void auditPersistence() {
            throw new AuditPersistenceException(
                    "审计写入失败",
                    new IllegalStateException("database unavailable password=unit-test-password")
            );
        }
    }
}
