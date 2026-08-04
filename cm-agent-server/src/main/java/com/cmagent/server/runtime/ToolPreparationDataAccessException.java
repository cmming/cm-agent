package com.cmagent.server.runtime;

import org.springframework.dao.DataAccessException;

import java.util.Objects;

/**
 * 工具准备阶段的数据访问异常，用于区分执行失败与基础设施失败。
 */
public class ToolPreparationDataAccessException extends RuntimeException {
    private final DataAccessException dataAccessException;
    /**
     * 表示 {@code ToolPreparationDataAccessException} 对应失败场景的受控异常。
     *
     * @param dataAccessException 准备工具阶段捕获的原始数据访问异常
     */
    public ToolPreparationDataAccessException(DataAccessException dataAccessException) {
        super("工具准备数据访问失败", Objects.requireNonNull(dataAccessException, "dataAccessException 不能为空"));
        this.dataAccessException = dataAccessException;
    }
    /**
     * 返回准备工具阶段捕获的原始数据访问异常。
     */
    public DataAccessException dataAccessException() {
        return dataAccessException;
    }
}
