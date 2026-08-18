package com.cmagent.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 创建 CM Agent 使用的 Flyway 配置，并为当前数据库选择对应方言迁移。
 *
 * <p>公共迁移使用只匹配目录根部 SQL 文件的通配位置，避免 Flyway 递归扫描时同时加载
 * PostgreSQL 与 MySQL 的同版本方言脚本。调用方仍负责按需要调用 {@link FluentConfiguration#load()}
 * 以及执行迁移或测试清理。</p>
 */
public final class CmAgentFlyway {
    private static final String COMMON_MIGRATION_LOCATION = "classpath:db/migration/*.sql";
    private static final String DIALECT_MIGRATION_ROOT = "classpath:db/migration/";

    private CmAgentFlyway() {
    }

    /**
     * 基于数据源元数据创建 Flyway 配置。
     *
     * @param dataSource 待迁移数据库的数据源；该方法会临时获取并关闭一个连接以识别数据库类型
     * @return 已绑定数据源及公共、当前方言迁移位置的 Flyway 配置
     * @throws IllegalStateException 数据库连接失败或数据库类型不受支持时抛出
     */
    public static FluentConfiguration configure(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource 不能为空");
        String dialect = resolveDialect(dataSource);
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(COMMON_MIGRATION_LOCATION, DIALECT_MIGRATION_ROOT + dialect);
    }

    private static String resolveDialect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return switch (productName) {
                case "PostgreSQL" -> "postgresql";
                case "MySQL" -> "mysql";
                default -> throw new IllegalStateException("不支持为数据库 " + productName + " 选择 Flyway 方言迁移");
            };
        } catch (SQLException exception) {
            throw new IllegalStateException("无法识别数据库类型，不能选择 Flyway 方言迁移", exception);
        }
    }
}
