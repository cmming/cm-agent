package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@ConfigurationProperties(prefix = "cm-agent.persistence")
/** 服务端持久化模式和数据库连接相关配置属性。 */
public class CmAgentPersistenceProperties {

    private Mode mode = Mode.MEMORY;
    private Jdbc jdbc = new Jdbc();

    /**
     * @return 当前持久化模式；未配置时为内存模式。
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * @param mode 持久化模式；传入 {@code null} 时回退到内存模式。
     */
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.MEMORY : mode;
    }

    /**
     * @return JDBC 连接配置对象。
     */
    public Jdbc getJdbc() {
        return jdbc;
    }

    /**
     * @param jdbc JDBC 连接配置；传入 {@code null} 时使用空配置对象。
     */
    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc == null ? new Jdbc() : jdbc;
    }

    /**
     * 校验当前 profile 下的持久化模式和 JDBC 地址。
     *
     * @param environment Spring 环境，用于读取激活的 profile
     * @throws IllegalStateException 生产类 profile 未使用 JDBC 或 JDBC 地址为空时抛出
     */
    public void validate(Environment environment) {
        boolean strictPersistenceProfileActive = hasStrictPersistenceProfile(environment);
        if (strictPersistenceProfileActive && mode != Mode.JDBC) {
            String profileLabel = hasSupabaseProfile(environment)
                    ? "production/prod/supabase profile"
                    : "production/prod profile";
            throw new IllegalStateException(profileLabel + " 必须使用 jdbc 持久化模式");
        }
        if (mode == Mode.JDBC && (jdbc == null || isBlank(jdbc.getUrl()))) {
            throw new IllegalStateException("启用 jdbc 持久化模式时必须配置 cm-agent.persistence.jdbc.url");
        }
    }

    /**
     * hasStrictPersistenceProfile：判断当前条件是否成立。
     */
    private boolean hasStrictPersistenceProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "production".equalsIgnoreCase(profile)
                        || "prod".equalsIgnoreCase(profile)
                        || "supabase".equalsIgnoreCase(profile));
    }

    /**
     * hasSupabaseProfile：判断当前条件是否成立。
     */
    private boolean hasSupabaseProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "supabase".equalsIgnoreCase(profile));
    }

    /**
     * isBlank：判断当前条件是否成立。
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Mode：枚举本模块使用的有限状态或类型。
     */
    public enum Mode {
        /** 使用仅供本地开发和测试的内存存储。 */
        MEMORY,
        /** 使用由 Flyway 管理的 JDBC 持久化存储。 */
        JDBC
    }

    /**
     * Jdbc：封装本模块的相关实现逻辑。
     */
    public static class Jdbc {
        private String url;
        private String username;
        private String password;
        private String driverClassName;

        /**
         * @return JDBC URL；未配置时返回空字符串。
         */
        public String getUrl() {
            return url == null ? "" : url;
        }

        /**
         * @param url JDBC 连接地址。
         */
        public void setUrl(String url) {
            this.url = url;
        }

        /**
         * @return JDBC 用户名；未配置时返回空字符串。
         */
        public String getUsername() {
            return username == null ? "" : username;
        }

        /**
         * @param username JDBC 用户名。
         */
        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * @return JDBC 密码；未配置时返回空字符串，调用方不得记录该值。
         */
        public String getPassword() {
            return password == null ? "" : password;
        }

        /**
         * @param password JDBC 密码，仅用于连接配置，不得写入日志。
         */
        public void setPassword(String password) {
            this.password = password;
        }

        /**
         * @return JDBC 驱动类名；未配置时返回空字符串。
         */
        public String getDriverClassName() {
            return driverClassName == null ? "" : driverClassName;
        }

        /**
         * @param driverClassName JDBC 驱动类名。
         */
        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }
}
