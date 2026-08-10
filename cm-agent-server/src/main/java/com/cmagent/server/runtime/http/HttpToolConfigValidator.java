package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InvalidSchemaRefException;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
/** 校验最新版本的动态 HTTP 工具参数定义及其生成 Schema。 */
public class HttpToolConfigValidator {
    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;
    private final Schema metaSchema;
    private final HttpParameterDefinitionCompiler parameterDefinitionCompiler;

    /**
     * 创建 HTTP 工具配置校验器。
     *
     * @param objectMapper JSON 映射器
     */
    public HttpToolConfigValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.metaSchema = schemaRegistry.getSchema(SchemaLocation.of(
                SpecificationVersion.DRAFT_2020_12.getDialectId()
        ));
        this.parameterDefinitionCompiler = new HttpParameterDefinitionCompiler(objectMapper);
    }

    /**
     * 校验动态 HTTP 工具配置是否满足运行时和安全约束。
     *
     * @param config 待校验的 HTTP 工具配置
     */
    public void validate(HttpToolConfig config) {
        compile(parseAndValidateSchema(config));
    }

    /**
     * 校验扁平参数定义并生成标准 JSON Schema。
     *
     * @param parameters 扁平参数定义
     * @param method HTTP 方法
     * @param urlTemplate URL 模板
     * @return JSON Schema 2020-12 文本
     */
    public String compileParameterDefinitions(
            List<HttpParameterDefinition> parameters,
            HttpToolMethod method,
            String urlTemplate
    ) {
        return parameterDefinitionCompiler.compileInputSchema(parameters, method, urlTemplate);
    }

    /**
     * 构建已完成关系校验的参数树，供运行时映射复用。
     */
    HttpParameterDefinitionCompiler.ParameterTree parameterTree(List<HttpParameterDefinition> parameters) {
        return parameterDefinitionCompiler.buildTree(parameters);
    }

    /**
     * 根据参数定义生成并校验工具输入 Schema。
     *
     * @param config HTTP 工具配置
     * @return 已校验的 Schema 节点
     */
    JsonNode parseAndValidateSchema(HttpToolConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        String generated = compileParameterDefinitions(config.parameters(), config.method(), config.urlTemplate());
        JsonNode rootSchema = parseJsonStrict(generated);
        validateSchemaDocument(rootSchema);
        return rootSchema;
    }

    /**
     * 编译 JSON Schema，供运行时输入校验复用。
     *
     * @param schemaNode 已校验的 Schema 节点
     */
    Schema compile(JsonNode schemaNode) {
        try {
            return schemaRegistry.getSchema(schemaNode);
        } catch (InvalidSchemaRefException exception) {
            throw exception;
        } catch (SchemaException exception) {
            throw new IllegalArgumentException("JSON Schema 无效", exception);
        }
    }

    private JsonNode parseJsonStrict(String value) {
        try {
            JsonNode parsed = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value);
            if (parsed == null) {
                throw new IllegalArgumentException("parameters 生成的 inputSchema 无效");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("parameters 生成的 inputSchema 无效", exception);
        }
    }

    private void validateSchemaDocument(JsonNode rootSchema) {
        if (!rootSchema.isObject()) {
            throw new IllegalArgumentException("parameters 生成的 inputSchema 根必须是 object Schema");
        }
        if (!metaSchema.validate(rootSchema).isEmpty()) {
            throw new IllegalArgumentException("parameters 生成的 JSON Schema 无效");
        }
        JsonNode type = rootSchema.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            throw new IllegalArgumentException("parameters 生成的 inputSchema 根必须声明为 object");
        }
    }
}
