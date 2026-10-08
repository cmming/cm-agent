package com.cmagent.server.diagnostic;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorDiagnosticLoggerTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ErrorDiagnosticLogger.class);
    private final ListAppender<ILoggingEvent> capture = new ListAppender<>();
    private final ErrorDiagnosticLogger diagnostics = new ErrorDiagnosticLogger(
            new SensitiveDataRedactor(), new ToolOutputSanitizer(new ObjectMapper()));

    @BeforeEach
    void attach() {
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(capture);
        capture.stop();
    }

    @ParameterizedTest
    @CsvSource({
            "22001,1406,字段长度超限", "22001,0,字段长度超限",
            "23505,0,唯一约束冲突", "23000,1062,唯一约束冲突",
            "23502,0,非空约束冲突", "23000,1048,非空约束冲突",
            "23503,0,外键约束冲突", "23000,1452,外键约束冲突",
            "08006,0,数据库连接失败", "28P01,0,数据库认证失败",
            "42000,1064,SQL 语法错误", "40P01,0,数据库死锁",
            "23000,1052,数据库操作失败，原始消息已脱敏",
            "HY000,9999,数据库操作失败，原始消息已脱敏"
    })
    void 数据库诊断按编号分类而不记录原消息(String state, int vendorCode, String reason) {
        var sql = new SQLException("Duplicate entry 'private-row-marker'; Detail: failing row private-row-marker", state, vendorCode);
        var failure = new DataIntegrityViolationException("SQL [INSERT INTO private_table VALUES ('private-sql-marker')]", sql);
        diagnostics.error(context(), failure, "SKILL", "skill-id");

        ILoggingEvent event = event();
        assertThat(event.getFormattedMessage()).contains("errorId=diagnostic-error-id", "tenantId=tenant-id",
                "resourceType=SKILL", "resourceId=skill-id", "sqlState=" + state, "vendorCode=" + vendorCode,
                "databaseExceptionType=java.sql.SQLException", "databaseReason=" + reason);
        assertThat(rendered()).contains("Caused by:", "java.sql.SQLException", reason)
                .doesNotContain("private-row-marker", "private-sql-marker", "private_table", "INSERT INTO");
        assertThat(sql.getMessage()).contains("private-row-marker");
        assertThat(failure.getCause()).isSameAs(sql);
    }

    @Test
    void 普通异常保留原因链与附加异常并逐层脱敏() {
        var cause = new IllegalArgumentException("原因 apiKey=unit-private-key http://internal.invalid/private");
        var failure = new IllegalStateException("操作 password=unit-private-password", cause);
        failure.addSuppressed(new IllegalStateException("Bearer unit-private-jwt SELECT private_sql FROM private_table"));
        diagnostics.error(context(), failure);

        assertThat(rendered()).contains("java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
                "Caused by:", "Suppressed:", "<已脱敏>", "<已脱敏SQL>")
                .doesNotContain("unit-private-key", "unit-private-password", "unit-private-jwt",
                        "internal.invalid", "private_sql", "private_table");
        assertThat(event().getFormattedMessage()).contains("sqlState=-", "vendorCode=-");
        assertThat(event().getThrowableProxy().getCause().getStackTraceElementProxyArray()[0].getStackTraceElement())
                .isEqualTo(cause.getStackTrace()[0]);
    }

    @Test
    void 未识别数据库错误和非法SQLState不会输出失败行和凭据() {
        var sql = new SQLException("Detail: failing row contains private-business-value jdbc:mysql://private-db/path", "password=private-state", 9000);
        sql.addSuppressed(new IllegalStateException("private-unlabelled-value"));
        diagnostics.error(context(), sql);

        assertThat(rendered()).contains("sqlState=-", "vendorCode=9000", "原始消息已脱敏", "Suppressed:")
                .doesNotContain("private-business-value", "private-db", "private-state", "private-unlabelled-value");
    }

    @Test
    void JDBC后续异常有独立编号和脱敏原因() {
        var sql = new SQLException("private-first", "HY000", 0);
        sql.setNextException(new SQLException("private-next", "22001", 1406));
        diagnostics.error(context(), sql);

        assertThat(rendered()).contains("JDBC nextException", "sqlState=22001", "vendorCode=1406", "字段长度超限")
                .doesNotContain("private-first", "private-next");
    }

    @Test
    void 预扫描预算耗尽时深处数据库分支仍拒绝原始附加消息() {
        var sql = new SQLException("private-sql-message", "22001", 1406);
        sql.addSuppressed(new IllegalStateException("private-unlabelled-row"));
        var failure = new IllegalStateException("顶层失败", new IllegalStateException("包装失败", sql));
        for (int index = 0; index < 32; index++) {
            failure.addSuppressed(new IllegalStateException("附加失败"));
        }
        diagnostics.error(context(), failure);
        assertThat(rendered()).contains("字段长度超限", "vendorCode=1406")
                .doesNotContain("private-sql-message", "private-unlabelled-row");
    }

    @Test
    void 循环与超深异常链受控截断() {
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);
        diagnostics.error(context(), first);
        assertThat(rendered()).contains("异常链已截断");
        capture.list.clear();
        Throwable deep = new IllegalStateException("private-tail-marker");
        for (int index = 0; index < 100; index++) {
            deep = new IllegalStateException("level", deep);
        }
        diagnostics.error(context(), deep);
        assertThat(rendered()).contains("异常链已截断").doesNotContain("private-tail-marker");
    }

    @Test
    void 大量附加异常与超长消息受控截断() {
        var failure = new IllegalStateException("a".repeat(10_000));
        for (int index = 0; index < 1000; index++) {
            failure.addSuppressed(new IllegalStateException("suppressed-" + index));
        }
        diagnostics.error(context(), failure);

        assertThat(rendered()).contains("消息已截断", "异常链已截断").doesNotContain("suppressed-999");
        assertThat(event().getThrowableProxy().getSuppressed()).hasSizeLessThanOrEqualTo(33);
    }

    @Test
    void 受控失败和上游正文继续使用现有脱敏规则() {
        diagnostics.error(context(), "password=unit-controlled-secret SELECT private_controlled_value");
        assertThat(event().getThrowableProxy()).isNull();
        assertThat(rendered()).contains("CONTROLLED_FAILURE").doesNotContain("unit-controlled-secret", "private_controlled_value");
        capture.list.clear();
        diagnostics.error(context(), new IllegalStateException("失败"), "Authorization: Bearer unit-upstream-token");
        assertThat(rendered()).doesNotContain("unit-upstream-token");
    }

    private ErrorDiagnosticLogger.DiagnosticContext context() {
        return new ErrorDiagnosticLogger.DiagnosticContext("diagnostic-error-id", "REST_API", "PERSISTENCE_UNAVAILABLE",
                "tenant-id", "principal-id", "-", "-", "-", "-", "test-operation");
    }

    private ILoggingEvent event() {
        assertThat(capture.list).hasSize(1);
        return capture.list.getFirst();
    }

    private String rendered() {
        ILoggingEvent event = event();
        return event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
    }
}
