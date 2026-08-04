package com.cmagent.examples.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolExampleRunnerTest {
    private HttpToolExampleProperties properties;
    private MockRestServiceServer mockServer;
    private HttpToolExampleRunner runner;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        properties = new HttpToolExampleProperties();
        properties.setBaseUrl("http://localhost:8080");
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        CmAgentToolClient client = new CmAgentToolClient(builder, new ObjectMapper(), properties);
        runner = new HttpToolExampleRunner(properties, client);
    }

    @Test
    /**
     * 验证或支持 {@code shouldNotSendRequestWhenDisabled} 所描述的测试场景。
     */
    void shouldNotSendRequestWhenDisabled() throws Exception {
        properties.setEnabled(false);

        runner.run(new DefaultApplicationArguments());

        mockServer.verify();
    }

    @Test
    /**
     * 验证或支持 {@code shouldRejectMissingJwtBeforeSendingRequest} 所描述的测试场景。
     */
    void shouldRejectMissingJwtBeforeSendingRequest() {
        properties.setEnabled(true);
        properties.setTargetUrl("https://api.example.test/messages");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CM Agent JWT 不能为空");
        mockServer.verify();
    }

    @Test
    /**
     * 验证或支持 {@code shouldRejectMissingTargetUrlBeforeSendingRequest} 所描述的测试场景。
     */
    void shouldRejectMissingTargetUrlBeforeSendingRequest() {
        properties.setEnabled(true);
        properties.setJwt("test-jwt");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HTTP 目标 URL 不能为空");
        mockServer.verify();
    }

    @Test
    /**
     * 验证或支持 {@code shouldRejectIncompleteSecretConfiguration} 所描述的测试场景。
     */
    void shouldRejectIncompleteSecretConfiguration() {
        properties.setEnabled(true);
        properties.setJwt("test-jwt");
        properties.setTargetUrl("https://api.example.test/messages");
        properties.setSecretHeaderName("X-Api-Key");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Secret Header 名称和引用必须同时提供");
        mockServer.verify();
    }
}
