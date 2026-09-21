package com.cmagent.server.service;

import java.util.function.Supplier;

/**
 * 技能管理和运行读取的原子工作单元。
 *
 * <p>memory 实现通过暂存状态发布，JDBC 实现通过现有事务管理器提交。调用方必须把
 * 严格审计放在返回之前，使审计失败能够阻止状态发布或数据库提交。</p>
 */
@FunctionalInterface
public interface SkillUnitOfWork {
    /**
     * 在同一原子边界内执行操作。
     *
     * @param operation 需要原子提交的业务操作
     * @param <T> 返回值类型
     * @return 操作返回值
     */
    <T> T execute(Supplier<T> operation);
}
