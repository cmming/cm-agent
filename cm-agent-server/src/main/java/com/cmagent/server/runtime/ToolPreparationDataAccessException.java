package com.cmagent.server.runtime;

import org.springframework.dao.DataAccessException;

import java.util.Objects;

/**
 * 工具准备阶段的数据访问异常，用于区分执行失败与基础设施失败。
 */
public class ToolPreparationDataAccessException extends RuntimeException {
    private final DataAccessException dataAccessException;
    /**
     * ToolPreparationDataAccessException：转换内部数据为目标表示。
     */
    public ToolPreparationDataAccessException(DataAccessException dataAccessException) {
        super("工具准备数据访问失败", Objects.requireNonNull(dataAccessException, "dataAccessException 不能为空"));
        this.dataAccessException = dataAccessException;
    }
    /**
     * dataAccessException：处理该类内部的业务逻辑或辅助计算。
     */
    public DataAccessException dataAccessException() {
        return dataAccessException;
    }
}
