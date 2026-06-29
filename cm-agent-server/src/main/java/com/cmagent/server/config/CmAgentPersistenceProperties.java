package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@ConfigurationProperties(prefix = "cm-agent.persistence")
public class CmAgentPersistenceProperties {

    private Mode mode = Mode.MEMORY;
    private Jdbc jdbc = new Jdbc();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(Environment environment) {
        boolean productionProfileActive = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "production".equals(profile) || "prod".equals(profile));
        if (productionProfileActive && mode != Mode.JDBC) {
            throw new IllegalStateException("production/prod profile 必须使用 jdbc 持久化模式");
        }
        if (mode == Mode.JDBC && (jdbc == null || isBlank(jdbc.getUrl()))) {
            throw new IllegalStateException("启用 jdbc 持久化模式时必须配置 cm-agent.persistence.jdbc.url");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Mode {
        MEMORY,
        JDBC
    }

    public static class Jdbc {
        private String url;
        private String username;
        private String password;
        private String driverClassName;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }
}
