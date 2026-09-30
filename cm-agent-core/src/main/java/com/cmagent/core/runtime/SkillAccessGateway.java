package com.cmagent.core.runtime;

import java.util.function.Supplier;

/**
 * 技能读取与可选脚本执行的治理扩展点，负责授权、记录与严格审计。
 *
 * <p>实现方只能在最终状态复核、读取记录和严格审计成功提交后返回正文；
 * {@code nativeLoader} 只读取本次运行持有的内存文本，不得在事务内访问网络或宿主文件系统。</p>
 */
@FunctionalInterface
public interface SkillAccessGateway {

    /**
     * 执行一次受治理读取。
     *
     * @param request 由当前 Run 和固定快照构造的可信请求
     * @param nativeLoader AgentScope 原生只读加载器
     * @return 已提交读取记录的正文结果
     * @throws SkillAccessException 授权、撤销、预算或原生读取失败时抛出
     */
    SkillReadResult load(SkillReadRequest request, Supplier<String> nativeLoader);

    /**
     * 返回部署环境是否开放受治理脚本执行；旧实现默认保持只读。
     *
     * @return 允许注册脚本执行工具时返回 true，实际调用仍须重新授权
     */
    default boolean executionEnabled() { return false; }

    /**
     * 执行固定快照中的脚本。不得信任模型提供的租户、命令、镜像或宿主路径。
     *
     * <p>实现方负责执行前后授权复核、严格审计、超时和资源清理；同一模型调用标识不可重复执行。
     * 默认实现拒绝执行，保证已有嵌入式调用方不会因升级自动获得宿主代码执行能力。</p>
     *
     * @param request 从当前运行与固定技能版本构造的可信资源定位
     * @param stdin 有界输入文本，只传递给沙箱标准输入，不拼接命令
     * @return 执行成功后的有界、脱敏输出
     * @throws SkillAccessException 关闭、拒绝、超时或执行失败时抛出稳定错误
     */
    default String execute(SkillReadRequest request, String stdin) {
        throw new SkillAccessException(com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_DISABLED,
                "技能沙箱未启用", request.attemptId().toString(), false);
    }
}
