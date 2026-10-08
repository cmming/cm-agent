package com.cmagent.server.diagnostic;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 仅连接 Testcontainers 临时数据库，以真实驱动消息验证脱敏边界，不接入运行中的服务或数据库。 */
@Testcontainers
class DiagnosticJdbcLoggingTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    static Stream<JdbcDatabaseContainer<?>> databases() {
        return Stream.of(postgres, mysql);
    }

    @ParameterizedTest
    @MethodSource("databases")
    void 真实双库长度和唯一约束错误保留原因及编号且不泄露数据(JdbcDatabaseContainer<?> database) {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE diagnostic_probe (id INTEGER PRIMARY KEY, sample VARCHAR(5) NOT NULL)");
        jdbc.update("INSERT INTO diagnostic_probe (id, sample) VALUES (?, ?)", 1, "short");
        var length = catchThrowableOfType(DataAccessException.class,
                () -> jdbc.update("INSERT INTO diagnostic_probe (id, sample) VALUES (?, ?)", 2, "private-row-marker"));
        var duplicate = catchThrowableOfType(DataAccessException.class,
                () -> jdbc.update("INSERT INTO diagnostic_probe (id, sample) VALUES (?, ?)", 1, "other"));

        var logger = (Logger) LoggerFactory.getLogger(ErrorDiagnosticLogger.class);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            var diagnostics = new ErrorDiagnosticLogger(new SensitiveDataRedactor(), new ToolOutputSanitizer(new ObjectMapper()));
            var context = ErrorDiagnosticLogger.DiagnosticContext.api("real-jdbc-error-id", "PERSISTENCE_UNAVAILABLE", "test-jdbc");
            diagnostics.error(context, length);
            diagnostics.error(context, duplicate);
            assertThat(capture.list).hasSize(2);
            assertThat(capture.list.get(0).getFormattedMessage()).contains("sqlState=22001", "databaseReason=字段长度超限");
            assertThat(capture.list.get(1).getFormattedMessage()).contains("databaseReason=唯一约束冲突");
            if (database instanceof MySQLContainer<?>) {
                assertThat(capture.list.get(0).getFormattedMessage()).contains("vendorCode=1406");
                assertThat(capture.list.get(1).getFormattedMessage()).contains("sqlState=23000", "vendorCode=1062");
            } else {
                assertThat(capture.list.get(1).getFormattedMessage()).contains("sqlState=23505");
            }
            for (ILoggingEvent event : capture.list) {
                assertThat(event.getFormattedMessage() + ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .contains("errorId=real-jdbc-error-id", "Caused by:")
                        .doesNotContain("private-row-marker", "diagnostic_probe", database.getJdbcUrl(), "INSERT INTO");
            }
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
