package com.cmagent.core.repository;

/**
 * 仅供可信部署流程使用的全局执行模式门禁，不是租户业务数据仓储。
 *
 * <p>同一数据集只允许一种策略；切换必须在短事务内核对全部非终态 Run/TEST 和所有检查点。
 * 这里只返回模式，不返回跨租户资源或计数；不得暴露为普通租户管理接口。</p>
 */
public interface RuntimeExecutionModeRepository {
    /**
     * 初始化或切换为目标模式；非空闲数据集的切换明确失败，同模式重启允许保留审批。
     * @param mode 可信部署流程选择的小写 {@code react} 或 {@code harness}，不能为空
     * @throws IllegalArgumentException 模式不受支持时拒绝
     * @throws IllegalStateException 切换时存在阻塞状态或无法核实排空时拒绝
     */
    void activate(String mode);
    /**
     * 每次已登记 Run 的模型执行前核对当前模式；读取失败必须抛出，不能用缓存继续运行。
     * @return 已持久化的稳定小写部署模式，不返回租户业务信息
     */
    String currentMode();
}

