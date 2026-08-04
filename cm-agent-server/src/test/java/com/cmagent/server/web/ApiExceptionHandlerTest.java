package com.cmagent.server.web;

import com.cmagent.server.audit.AuditPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private MockMvc mockMvc;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    /**
     * 验证或支持 {@code validationFailureUsesControlledChineseResponse} 所描述的测试场景。
     */
    void validationFailureUsesControlledChineseResponse() throws Exception {
        mockMvc.perform(get("/test/resources/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    @Test
    /**
     * 验证或支持 {@code persistenceFailureDoesNotExposeJdbcCredentialsOrSql} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code auditPersistenceFailureMapsToServiceUnavailable} 所描述的测试场景。
     */
    void auditPersistenceFailureMapsToServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/audit-persistence"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("审计服务暂不可用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database unavailable"))));
    }

    @Test
    /**
     * 验证或支持 {@code runtimeFailureDoesNotExposeBearerTokenOrApiKey} 所描述的测试场景。
     */
    void runtimeFailureDoesNotExposeBearerTokenOrApiKey() throws Exception {
        mockMvc.perform(get("/test/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务内部错误"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unit-test-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unit-test-api-key"))));
    }

    @Test
    /**
     * 验证或支持 {@code conflictUsesControlledBusinessReason} 所描述的测试场景。
     */
    void conflictUsesControlledBusinessReason() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("工具已有调用历史，为保留运行记录不能删除"));
    }

    @RestController
    static class FailingController {
        @GetMapping("/test/resources/{id}")
        /**
         * 验证或支持 {@code resource} 所描述的测试场景。
         *
         * @param id 测试辅助方法使用的 id 参数
         */
        UUID resource(@PathVariable UUID id) {
            return id;
        }

        @GetMapping("/test/persistence")
        /**
         * 验证或支持 {@code persistence} 所描述的测试场景。
         */
        void persistence() {
            throw new DataAccessResourceFailureException(
                    "select * from users where password='unit-password' jdbc:postgresql://unit-user:unit-password@db.example/cm_agent"
            );
        }

        @GetMapping("/test/runtime")
        /**
         * 验证或支持 {@code runtime} 所描述的测试场景。
         */
        void runtime() {
            throw new IllegalStateException("Bearer unit-test-token apiKey=unit-test-api-key");
        }

        @GetMapping("/test/audit-persistence")
        /**
         * 验证或支持 {@code auditPersistence} 所描述的测试场景。
         */
        void auditPersistence() {
            throw new AuditPersistenceException(
                    "审计写入失败",
                    new IllegalStateException("database unavailable password=unit-test-password")
            );
        }

        @GetMapping("/test/conflict")
        /**
         * 验证或支持 {@code conflict} 所描述的测试场景。
         */
        void conflict() {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "工具已有调用历史，为保留运行记录不能删除"
            );
        }
    }
}
