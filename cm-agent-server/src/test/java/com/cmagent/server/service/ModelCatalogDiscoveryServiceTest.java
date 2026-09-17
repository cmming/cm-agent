package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ModelCatalogDiscoveryProperties;
import com.cmagent.server.runtime.http.HttpToolUrlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogDiscoveryServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONFIG_ID = UUID.fromString("00000000-0000-0000-0000-000000000411");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef(TENANT_ID, "model-admin", "模型管理员", Set.of("model:write"));

    @Test
    void 草稿目录按名称去重排序且不将密钥交给审计() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        AuditAppender auditAppender = mock(AuditAppender.class);
        ModelCatalogDiscoveryService.CatalogTransport transport = (uri, apiKey, timeout, maxBytes) -> {
            assertThat(uri).isEqualTo(URI.create("https://models.example.test/v1/models"));
            assertThat(apiKey).isEqualTo("draft-model-key");
            return response(200, """
                    {"data":[{"id":"z-model"},{"id":"a-model"},{"id":"z-model"},{"id":""}]}
                    """);
        };
        ModelCatalogDiscoveryService service = service(repository, auditAppender, transport, allowedPolicy());

        assertThat(service.discoverDraft(PRINCIPAL, ModelProviderType.OPENAI_COMPATIBLE,
                "https://models.example.test/v1", "draft-model-key", "catalog-001"))
                .containsExactly("a-model", "z-model");

        verify(auditAppender).append(TENANT_ID, "model-admin", "MODEL_CONFIG_MODEL_DISCOVERY", "MODEL_CONFIG", "draft",
                "SUCCEEDED", "已获取模型目录，共 2 项");
        verify(auditAppender, never()).append(any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.contains("draft-model-key"));
    }

    @Test
    void 已保存配置只使用仓储中的地址与密钥() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        AuditAppender auditAppender = mock(AuditAppender.class);
        ModelConfig config = new ModelConfig(CONFIG_ID, TENANT_ID, ModelProviderType.DASHSCOPE_NATIVE,
                "DashScope", "https://models.example.test/api/v1", "qwen-plus", true);
        when(repository.findByTenantAndId(TENANT_ID, CONFIG_ID)).thenReturn(Optional.of(config));
        ModelCatalogDiscoveryService.CatalogTransport transport = (uri, apiKey, timeout, maxBytes) -> {
            assertThat(uri).isEqualTo(URI.create("https://models.example.test/api/v1/models"));
            assertThat(apiKey).isEqualTo("saved-model-key");
            return response(200, "{" + "\"data\":[{\"id\":\"qwen-plus\"}]}" );
        };
        ModelCatalogDiscoveryService service = service(repository, (tenantId, modelConfigId) -> {
            assertThat(tenantId).isEqualTo(TENANT_ID);
            assertThat(modelConfigId).isEqualTo(CONFIG_ID);
            return new com.cmagent.core.runtime.ModelCredential("saved-model-key");
        }, auditAppender, transport, allowedPolicy());

        assertThat(service.discoverSaved(PRINCIPAL, CONFIG_ID, "catalog-002")).containsExactly("qwen-plus");
        verify(repository).findByTenantAndId(TENANT_ID, CONFIG_ID);
    }

    @Test
    void 不支持目录接口返回稳定错误码且不暴露供应商正文() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        AuditAppender auditAppender = mock(AuditAppender.class);
        ModelCatalogDiscoveryService service = service(repository, auditAppender,
                (uri, apiKey, timeout, maxBytes) -> response(404, "provider secret response"), allowedPolicy());

        assertThatThrownBy(() -> service.discoverDraft(PRINCIPAL, ModelProviderType.OPENAI_COMPATIBLE,
                "https://models.example.test/v1", "draft-model-key", "catalog-003"))
                .isInstanceOf(ModelCatalogDiscoveryException.class)
                .satisfies(exception -> {
                    ModelCatalogDiscoveryException failure = (ModelCatalogDiscoveryException) exception;
                    assertThat(failure.errorCode()).isEqualTo(ApiErrorCode.MODEL_DISCOVERY_UNSUPPORTED);
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.upstreamResponse()).isEqualTo("provider secret response");
                    assertThat(failure.getMessage()).doesNotContain("secret", "example.test", "draft-model-key");
                });
        verify(auditAppender).append(TENANT_ID, "model-admin", "MODEL_CONFIG_MODEL_DISCOVERY", "MODEL_CONFIG", "draft",
                "FAILED", "模型目录获取失败，错误码：MODEL_DISCOVERY_UNSUPPORTED");
    }

    @Test
    void 缺少已保存凭据提供者时返回受控失败且不访问供应商() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        AuditAppender auditAppender = mock(AuditAppender.class);
        ModelConfig config = new ModelConfig(CONFIG_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "OpenAI", "https://models.example.test/v1", "gpt-test", true);
        when(repository.findByTenantAndId(TENANT_ID, CONFIG_ID)).thenReturn(Optional.of(config));
        ModelCatalogDiscoveryService.CatalogTransport transport = mock(ModelCatalogDiscoveryService.CatalogTransport.class);
        ModelCatalogDiscoveryService service = service(repository, null, auditAppender, transport, allowedPolicy());

        assertThatThrownBy(() -> service.discoverSaved(PRINCIPAL, CONFIG_ID, "catalog-004"))
                .isInstanceOf(ModelCatalogDiscoveryException.class)
                .satisfies(exception -> assertThat(((ModelCatalogDiscoveryException) exception).errorCode())
                        .isEqualTo(ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED));

        verify(transport, never()).get(any(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(auditAppender).append(TENANT_ID, "model-admin", "MODEL_CONFIG_MODEL_DISCOVERY", "MODEL_CONFIG",
                CONFIG_ID.toString(), "FAILED", "模型目录获取失败，错误码：MODEL_DISCOVERY_AUTH_FAILED");
    }

    private static ModelCatalogDiscoveryService service(
            ModelConfigRepository repository,
            AuditAppender auditAppender,
            ModelCatalogDiscoveryService.CatalogTransport transport,
            HttpToolUrlPolicy urlPolicy
    ) {
        return service(repository, (tenantId, modelConfigId) -> new com.cmagent.core.runtime.ModelCredential("unused"),
                auditAppender, transport, urlPolicy);
    }

    private static ModelCatalogDiscoveryService service(
            ModelConfigRepository repository,
            ModelCredentialProvider credentials,
            AuditAppender auditAppender,
            ModelCatalogDiscoveryService.CatalogTransport transport,
            HttpToolUrlPolicy urlPolicy
    ) {
        return new ModelCatalogDiscoveryService(repository, credentials, auditAppender,
                new ModelCatalogDiscoveryProperties(), new ObjectMapper(), urlPolicy, transport);
    }

    private static HttpToolUrlPolicy allowedPolicy() {
        HttpToolUrlPolicy policy = mock(HttpToolUrlPolicy.class);
        when(policy.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return policy;
    }

    private static ModelCatalogDiscoveryService.CatalogHttpResponse response(int status, String body) {
        return new ModelCatalogDiscoveryService.CatalogHttpResponse(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
