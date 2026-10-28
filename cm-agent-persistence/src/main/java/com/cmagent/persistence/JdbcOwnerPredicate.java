package com.cmagent.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 私有来源owner必须按认证主体逐字匹配，不能沿用MySQL默认不区分大小写的比较。 */
final class JdbcOwnerPredicate {
    private JdbcOwnerPredicate() { }
    /** column/parameter只能来自仓储固定SQL，不能由HTTP输入拼接；两库测试覆盖大小写主体负例。 */
    static String equal(JdbcClient jdbc, String column, String parameter) {
        String version = jdbc.sql("SELECT version()").query(String.class).single();
        return version.startsWith("PostgreSQL") ? column + " COLLATE \"C\" = " + parameter : "BINARY " + column + " = BINARY " + parameter;
    }
}
