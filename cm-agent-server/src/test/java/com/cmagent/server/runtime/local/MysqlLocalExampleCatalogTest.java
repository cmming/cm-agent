package com.cmagent.server.runtime.local;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlLocalExampleCatalogTest {

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 目录只暴露固定echo和add且执行结果受控() {
        MysqlLocalExampleCatalog catalog = new MysqlLocalExampleCatalog(new ObjectMapper());

        assertThat(catalog.list()).extracting(MysqlLocalExampleCatalog.LocalExample::key)
                .containsExactly("echo", "add");
        assertThat(catalog.find("missing")).isEmpty();

        var echo = catalog.find("echo").orElseThrow();
        ToolExecutionResult echoResult = echo.executor().execute(
                new ToolExecutionRequest(echo.definition().id(), "{\"message\":\"你好\"}")
        );
        assertThat(echoResult.success()).isTrue();
        assertThat(echoResult.outputSummary()).isEqualTo("{\"message\":\"你好\"}");

        var add = catalog.find("add").orElseThrow();
        ToolExecutionResult addResult = add.executor().execute(
                new ToolExecutionRequest(add.definition().id(), "{\"left\":0.1,\"right\":0.2}")
        );
        assertThat(addResult.success()).isTrue();
        assertThat(addResult.outputSummary()).isEqualTo("{\"sum\":0.3}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"message\":\"\"}", "{\"message\":1}", "not-json"
    })
    /**
     * 验证方法名称所描述的业务行为。
     *
     * @param input 测试输入
     */
    void echo拒绝无效输入(String input) {
        var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("echo").orElseThrow();

        assertThat(example.executor().execute(new ToolExecutionRequest(example.definition().id(), input)).success())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"left\":1}", "{\"left\":\"1\",\"right\":2}", "not-json"
    })
    /**
     * 验证方法名称所描述的业务行为。
     *
     * @param input 测试输入
     */
    void add拒绝无效输入(String input) {
        var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("add").orElseThrow();

        assertThat(example.executor().execute(new ToolExecutionRequest(example.definition().id(), input)).success())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"left\":1e999,\"right\":1}",
            "{\"left\":-1e999,\"right\":1}",
            "{\"left\":1,\"right\":1e999}",
            "{\"left\":1,\"right\":-1e999}"
    })
    /**
     * 验证方法名称所描述的业务行为。
     *
     * @param input 测试输入
     */
    void add将左右非有限数字转换为受控失败(String input) {
        var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("add").orElseThrow();

        ToolExecutionResult result = example.executor().execute(new ToolExecutionRequest(example.definition().id(), input));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("left 和 right 必须是数字");
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 目录锁定固定租户和工具标识() {
        MysqlLocalExampleCatalog catalog = new MysqlLocalExampleCatalog(new ObjectMapper());

        assertThat(MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID)
                .hasToString("00000000-0000-0000-0000-000000000001");
        assertThat(catalog.find("echo").orElseThrow().definition().id())
                .isEqualTo(MysqlLocalExampleCatalog.ECHO_TOOL_ID)
                .hasToString("00000000-0000-0000-0000-000000000101");
        assertThat(catalog.find("add").orElseThrow().definition().id())
                .isEqualTo(MysqlLocalExampleCatalog.ADD_TOOL_ID)
                .hasToString("00000000-0000-0000-0000-000000000102");
        assertThat(catalog.list()).allSatisfy(example ->
                assertThat(example.definition().tenantId()).isEqualTo(MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID)
        );
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 持久化定义仅替换审计主体() {
        MysqlLocalExampleCatalog catalog = new MysqlLocalExampleCatalog(new ObjectMapper());

        catalog.list().forEach(example -> {
            var definition = example.definition();
            var persistentDefinition = example.persistentDefinition("管理员");

            assertThat(persistentDefinition.id()).isEqualTo(definition.id());
            assertThat(persistentDefinition.tenantId()).isEqualTo(definition.tenantId());
            assertThat(persistentDefinition.name()).isEqualTo(definition.name());
            assertThat(persistentDefinition.description()).isEqualTo(definition.description());
            assertThat(persistentDefinition.type()).isEqualTo(definition.type());
            assertThat(persistentDefinition.inputSchema()).isEqualTo(definition.inputSchema());
            assertThat(persistentDefinition.riskLevel()).isEqualTo(definition.riskLevel());
            assertThat(persistentDefinition.enabled()).isTrue();
            assertThat(persistentDefinition.endpoint()).isEqualTo(definition.endpoint());
            assertThat(persistentDefinition.createdBy()).isEqualTo("管理员");
            assertThat(persistentDefinition.updatedBy()).isEqualTo("管理员");
        });
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void 示例输入返回防御性深拷贝() {
        MysqlLocalExampleCatalog.LocalExample echo = new MysqlLocalExampleCatalog(new ObjectMapper())
                .find("echo")
                .orElseThrow();
        ObjectNode returnedInput = (ObjectNode) echo.sampleInput();
        returnedInput.put("message", "已篡改");

        assertThat(echo.sampleInput().path("message").textValue()).isEqualTo("你好，CM Agent");
    }
}
