package com.cmagent.server.diagnostic;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 将异常图复制成仅供日志使用的脱敏快照，不持有原异常作为 cause 或 suppressed。
 *
 * <p>数据库驱动消息可能包含失败行、重复键值和 SQL 参数，不能仅靠密码正则清理；
 * 数据库异常只按 SQLState 和厂商编号生成固定原因。普通异常仍使用现有文本脱敏器。
 * 本组件不连接数据库、不修改原异常；每次调用独立计数，可并发使用。</p>
 */
final class DiagnosticExceptionSanitizer {
    /** 限制异常图规模，避免循环或超大批处理异常阻塞错误日志边界。 */
    private static final int MAX_NODES = 32;
    /** 限制递归深度，防止异常链导致栈溢出。 */
    private static final int MAX_DEPTH = 16;
    /** 每个异常仅保留有限原始堆栈位置，控制日志体积。 */
    private static final int MAX_FRAMES = 128;
    /** 普通异常脱敏后仍需限制消息长度，防止大响应占满日志。 */
    private static final int MAX_MESSAGE_LENGTH = 2048;

    private DiagnosticExceptionSanitizer() {
    }

    /**
     * 复制 cause、suppressed 和 JDBC nextException，返回首个 JDBC 异常的结构化诊断。
     * SQLState 只接受五位大写字母或数字，异常原文、SQL 和参数不会进入 JDBC 摘要。
     *
     * @param failure 待记录的原异常，不能为 {@code null}，不会被修改
     * @param textSanitizer 普通异常使用的文本脱敏器，不能为 {@code null}
     * @return 有限大小的独立异常快照与 JDBC 诊断字段
     */
    static Snapshot sanitize(Throwable failure, UnaryOperator<String> textSanitizer) {
        var visited = identitySet();
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        SQLException firstSql = null;
        boolean databaseFailure = false;
        while (!pending.isEmpty() && visited.size() < MAX_NODES) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            databaseFailure |= current instanceof DataAccessException || current instanceof SQLException;
            if (current instanceof SQLException sql) {
                if (firstSql == null) {
                    firstSql = sql;
                }
                if (sql.getNextException() != null) {
                    pending.addLast(sql.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            // 最多入队剩余预算的附加异常，不能让一个异常带来的巨大数组放大遍历成本。
            for (Throwable suppressed : current.getSuppressed()) {
                if (pending.size() >= MAX_NODES) {
                    break;
                }
                pending.addLast(suppressed);
            }
        }
        Throwable safeFailure = copy(failure, textSanitizer, databaseFailure, identitySet(), 0);
        return new Snapshot(safeFailure, firstSql == null ? "-" : sqlState(firstSql),
                firstSql == null ? "-" : Integer.toString(firstSql.getErrorCode()),
                firstSql == null ? "-" : firstSql.getClass().getName(),
                firstSql == null ? "-" : databaseReason(firstSql));
    }

    private static Throwable copy(Throwable original, UnaryOperator<String> textSanitizer,
                                  boolean databaseFailure, Set<Throwable> visited, int depth) {
        if (depth >= MAX_DEPTH || visited.size() >= MAX_NODES || !visited.add(original)) {
            var truncated = new RuntimeException("异常链已截断（循环、深度或数量限制）");
            truncated.setStackTrace(new StackTraceElement[0]);
            return truncated;
        }
        // 前置遍历也有预算；深处分支即使未被预扫描发现，仍必须建立数据库消息的拒绝边界。
        databaseFailure |= original instanceof DataAccessException || original instanceof SQLException;
        String message;
        if (original instanceof SQLException sql) {
            message = databaseReason(sql) + "；sqlState=" + sqlState(sql) + "; vendorCode=" + sql.getErrorCode();
        } else if (databaseFailure) {
            // Spring 包装消息与 JDBC 附加异常也可能复制原始 SQL/行数据，统一禁止原文透传。
            message = "数据库操作失败，详情见脱敏原因链及 JDBC 错误编号";
        } else {
            message = textSanitizer.apply(original.getMessage());
            message = message.isBlank() ? "未提供异常消息" : message;
            if (message.length() > MAX_MESSAGE_LENGTH) {
                message = message.substring(0, MAX_MESSAGE_LENGTH) + "<消息已截断>";
            }
        }
        // 不按反射实例化原异常类型，避免驱动构造器或 toString 再次输出敏感扩展字段。
        var safe = new RuntimeException(original.getClass().getName() + ": " + message);
        StackTraceElement[] frames = original.getStackTrace();
        safe.setStackTrace(java.util.Arrays.copyOf(frames, Math.min(frames.length, MAX_FRAMES)));
        if (original.getCause() != null) {
            safe.initCause(copy(original.getCause(), textSanitizer, databaseFailure, visited, depth + 1));
        }
        int suppressedCount = 0;
        for (Throwable suppressed : original.getSuppressed()) {
            if (++suppressedCount > MAX_NODES) {
                safe.addSuppressed(new RuntimeException("附加异常数量已截断"));
                break;
            }
            if (visited.size() >= MAX_NODES) {
                safe.addSuppressed(copy(suppressed, textSanitizer, databaseFailure, visited, depth + 1));
                break;
            }
            safe.addSuppressed(copy(suppressed, textSanitizer, databaseFailure, visited, depth + 1));
        }
        if (original instanceof SQLException sql && sql.getNextException() != null) {
            // Throwable 没有 JDBC nextException 槽位，以带标签的 suppressed 快照保留独立后续错误。
            var next = new RuntimeException("JDBC nextException（后续数据库异常）");
            next.setStackTrace(new StackTraceElement[0]);
            next.initCause(copy(sql.getNextException(), textSanitizer, databaseFailure, visited, depth + 1));
            safe.addSuppressed(next);
        }
        return safe;
    }

    private static Set<Throwable> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static String sqlState(SQLException sql) {
        String state = sql.getSQLState();
        return state != null && state.length() == 5 && state.matches("[A-Z0-9]{5}") ? state : "-";
    }

    /** 按 PostgreSQL 16/MySQL 8.4 文档中的编号分类，不解析本地化错误文本或回显行值。 */
    private static String databaseReason(SQLException sql) {
        String state = sqlState(sql);
        // MySQL 约束错误共用 23000，必须结合厂商编号才能区分具体原因。
        if (state.equals("23000")) {
            return switch (sql.getErrorCode()) {
                case 1062 -> "唯一约束冲突";
                case 1048 -> "非空约束冲突";
                case 1451, 1452 -> "外键约束冲突";
                // MySQL 也把部分非约束错误放在 23000，未知厂商编号不能误判为约束冲突。
                default -> "数据库操作失败，原始消息已脱敏";
            };
        }
        if (state.equals("42000")) {
            return switch (sql.getErrorCode()) {
                case 1064 -> "SQL 语法错误";
                case 1142, 1143 -> "数据库权限不足";
                default -> "SQL 语法或访问规则错误";
            };
        }
        return switch (state) {
            case "22001" -> "字段长度超限";
            case "23505" -> "唯一约束冲突";
            case "23502" -> "非空约束冲突";
            case "23503" -> "外键约束冲突";
            case "23514" -> "检查约束冲突";
            case "40001" -> "事务序列化失败或死锁";
            case "40P01" -> "数据库死锁";
            case "42601" -> "SQL 语法错误";
            case "42P01", "42S02" -> "数据库表不存在";
            case "42703", "42S22" -> "数据库列不存在";
            case "42501" -> "数据库权限不足";
            default -> state.startsWith("08") ? "数据库连接失败"
                    : state.startsWith("28") ? "数据库认证失败"
                    : "数据库操作失败，原始消息已脱敏";
        };
    }

    /**
     * 一次日志调用的独立快照。
     *
     * @param failure 已复制并脱敏的异常图，不包含原异常引用
     * @param sqlState 首个 JDBC 异常的校验后 SQLState；无有效值时为横线
     * @param vendorCode 首个 JDBC 异常的厂商整数编号；无 JDBC 异常时为横线
     * @param databaseExceptionType 首个 JDBC 异常的原始 Java 类型名称；无 JDBC 异常时为横线
     * @param databaseReason 按编号生成的固定中文分类；无 JDBC 异常时为横线
     */
    record Snapshot(Throwable failure, String sqlState, String vendorCode,
                    String databaseExceptionType, String databaseReason) {
    }
}
