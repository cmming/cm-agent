package com.cmagent.server.runtime.local;

import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlLocalExampleCatalogTest {

    @Test
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
    void echo拒绝无效输入(String input) {
        var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("echo").orElseThrow();

        assertThat(example.executor().execute(new ToolExecutionRequest(example.definition().id(), input)).success())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"left\":1}", "{\"left\":\"1\",\"right\":2}", "not-json"
    })
    void add拒绝无效输入(String input) {
        var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("add").orElseThrow();

        assertThat(example.executor().execute(new ToolExecutionRequest(example.definition().id(), input)).success())
                .isFalse();
    }
}
