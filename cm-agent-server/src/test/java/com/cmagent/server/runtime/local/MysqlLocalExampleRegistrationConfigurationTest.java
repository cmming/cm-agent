package com.cmagent.server.runtime.local;

import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlLocalExampleRegistrationConfigurationTest {

    @Test
    void mysql非生产Profile注册两个执行器但不触碰Repository() {
        try (AnnotationConfigApplicationContext context = context("mysql")) {
            ToolRegistry registry = context.getBean(ToolRegistry.class);

            assertThat(registry.snapshot(MysqlLocalExampleCatalog.ECHO_TOOL_ID)).isPresent();
            assertThat(registry.snapshot(MysqlLocalExampleCatalog.ADD_TOOL_ID)).isPresent();
            assertThat(context.getBeansOfType(ToolDefinitionRepository.class)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "mysql,prod", "mysql,production", "mysql,supabase"})
    void 其他或混合生产Profile不注册内置执行器(String profiles) {
        try (AnnotationConfigApplicationContext context = context(profiles.split(","))) {
            ToolRegistry registry = context.getBean(ToolRegistry.class);

            assertThat(registry.snapshot(MysqlLocalExampleCatalog.ECHO_TOOL_ID)).isEmpty();
            assertThat(registry.snapshot(MysqlLocalExampleCatalog.ADD_TOOL_ID)).isEmpty();
        }
    }

    private AnnotationConfigApplicationContext context(String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.registerBean(ObjectMapper.class);
        context.registerBean(ToolRegistry.class, InMemoryToolRegistry::new);
        context.register(MysqlLocalExampleCatalog.class, MysqlLocalExampleRegistrationConfiguration.class);
        context.refresh();
        return context;
    }
}
