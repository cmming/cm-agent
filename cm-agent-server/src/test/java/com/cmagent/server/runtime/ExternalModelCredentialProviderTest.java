package com.cmagent.server.runtime;

import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalModelCredentialProviderTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String TENANT_A_KEY = "invalid-test-key-tenant-a";
    private static final String TENANT_B_KEY = "invalid-test-key-tenant-b";

    @Test
    void exposesSafeDefaults() {
        AgentScopeRuntimeProperties properties = new AgentScopeRuntimeProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getModelTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getToolTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getModelMaxAttempts()).isEqualTo(2);
        assertThat(properties.getCredentials()).isEmpty();
    }

    @Test
    void resolvesCredentialByTenantAndModelConfig() {
        AgentScopeRuntimeProperties properties = properties(
                credential(TENANT_A, MODEL_ID, TENANT_A_KEY),
                credential(TENANT_B, MODEL_ID, TENANT_B_KEY));
        ExternalModelCredentialProvider provider = new ExternalModelCredentialProvider(properties);

        assertThat(provider.resolve(TENANT_A, MODEL_ID).apiKey()).isEqualTo(TENANT_A_KEY);
        assertThat(provider.resolve(TENANT_B, MODEL_ID).apiKey()).isEqualTo(TENANT_B_KEY);
    }

    @Test
    void rejectsCredentialLookupAcrossTenants() {
        ExternalModelCredentialProvider provider = new ExternalModelCredentialProvider(
                properties(credential(TENANT_A, MODEL_ID, TENANT_A_KEY)));

        assertThatThrownBy(() -> provider.resolve(TENANT_B, MODEL_ID))
                .isInstanceOf(ModelCredentialUnavailableException.class)
                .hasMessage("模型凭据不可用")
                .hasMessageNotContaining(TENANT_A_KEY);
    }

    @Test
    void rejectsDuplicateTenantAndModelConfigKeyWithoutLeakingSecret() {
        AgentScopeRuntimeProperties properties = properties(
                credential(TENANT_A, MODEL_ID, TENANT_A_KEY),
                credential(TENANT_A, MODEL_ID, TENANT_B_KEY));

        assertThatThrownBy(() -> new ExternalModelCredentialProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型凭据配置重复")
                .hasMessageNotContaining(TENANT_A_KEY)
                .hasMessageNotContaining(TENANT_B_KEY);
    }

    @Test
    void rejectsBlankApiKey() {
        AgentScopeRuntimeProperties properties = properties(credential(TENANT_A, MODEL_ID, "  "));

        assertThatThrownBy(() -> properties.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型凭据 API Key 不能为空");
    }

    @Test
    void rejectsMissingCredentialIdentifiers() {
        AgentScopeRuntimeProperties missingTenant = properties(credential(null, MODEL_ID, TENANT_A_KEY));
        AgentScopeRuntimeProperties missingModel = properties(credential(TENANT_A, null, TENANT_A_KEY));

        assertThatThrownBy(() -> missingTenant.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型凭据 tenantId 不能为空")
                .hasMessageNotContaining(TENANT_A_KEY);
        assertThatThrownBy(() -> missingModel.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型凭据 modelConfigId 不能为空")
                .hasMessageNotContaining(TENANT_A_KEY);
    }

    @Test
    void rejectsNullCredentialEntry() {
        AgentScopeRuntimeProperties properties = new AgentScopeRuntimeProperties();
        List<AgentScopeRuntimeProperties.CredentialProperties> source = new ArrayList<>();
        source.add(null);

        assertThatThrownBy(() -> properties.setCredentials(source))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonPositiveOrMissingTimeouts() {
        AgentScopeRuntimeProperties missingModelTimeout = properties();
        missingModelTimeout.setModelTimeout(null);
        AgentScopeRuntimeProperties zeroModelTimeout = properties();
        zeroModelTimeout.setModelTimeout(Duration.ZERO);
        AgentScopeRuntimeProperties negativeToolTimeout = properties();
        negativeToolTimeout.setToolTimeout(Duration.ofSeconds(-1));

        assertThatThrownBy(() -> missingModelTimeout.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型超时时间必须为正数");
        assertThatThrownBy(() -> zeroModelTimeout.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型超时时间必须为正数");
        assertThatThrownBy(() -> negativeToolTimeout.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工具超时时间必须为正数");
    }

    @Test
    void rejectsModelMaxAttemptsOutsideSupportedRange() {
        AgentScopeRuntimeProperties tooFewAttempts = properties();
        tooFewAttempts.setModelMaxAttempts(0);
        AgentScopeRuntimeProperties tooManyAttempts = properties();
        tooManyAttempts.setModelMaxAttempts(6);

        assertThatThrownBy(() -> tooFewAttempts.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型最大尝试次数必须在 1 到 5 之间");
        assertThatThrownBy(() -> tooManyAttempts.validate(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型最大尝试次数必须在 1 到 5 之间");
    }

    @Test
    void rejectsEnablingRealAndFakeRuntimeTogether() {
        AgentScopeRuntimeProperties properties = properties();

        assertThatThrownBy(() -> properties.validate(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AgentScope 真实运行时与 fake runtime 不能同时启用");
    }

    @Test
    void allowsFakeRuntimeWhenAgentScopeIsDisabled() {
        AgentScopeRuntimeProperties properties = new AgentScopeRuntimeProperties();

        properties.validate(true);
    }

    @Test
    void copiesCredentialListDefensively() {
        AgentScopeRuntimeProperties.CredentialProperties configuredCredential =
                credential(TENANT_A, MODEL_ID, TENANT_A_KEY);
        List<AgentScopeRuntimeProperties.CredentialProperties> source = new ArrayList<>();
        source.add(configuredCredential);
        AgentScopeRuntimeProperties properties = new AgentScopeRuntimeProperties();

        properties.setCredentials(source);
        source.clear();

        assertThat(properties.getCredentials()).containsExactly(configuredCredential);
        assertThatThrownBy(() -> properties.getCredentials().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotExposeApiKeyThroughToString() {
        AgentScopeRuntimeProperties properties = properties(credential(TENANT_A, MODEL_ID, TENANT_A_KEY));
        ExternalModelCredentialProvider provider = new ExternalModelCredentialProvider(properties);

        assertThat(properties.toString()).doesNotContain(TENANT_A_KEY);
        assertThat(properties.getCredentials().getFirst().toString()).doesNotContain(TENANT_A_KEY);
        assertThat(provider.toString()).doesNotContain(TENANT_A_KEY);
        assertThat(provider.resolve(TENANT_A, MODEL_ID).toString()).doesNotContain(TENANT_A_KEY);
    }

    private static AgentScopeRuntimeProperties properties(
            AgentScopeRuntimeProperties.CredentialProperties... credentials) {
        AgentScopeRuntimeProperties properties = new AgentScopeRuntimeProperties();
        properties.setEnabled(true);
        properties.setCredentials(List.of(credentials));
        return properties;
    }

    private static AgentScopeRuntimeProperties.CredentialProperties credential(
            UUID tenantId, UUID modelConfigId, String apiKey) {
        AgentScopeRuntimeProperties.CredentialProperties credential =
                new AgentScopeRuntimeProperties.CredentialProperties();
        credential.setTenantId(tenantId);
        credential.setModelConfigId(modelConfigId);
        credential.setApiKey(apiKey);
        return credential;
    }
}
