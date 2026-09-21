package com.cmagent.core.runtime;

import java.util.function.Supplier;

/**
 * 技能读取治理扩展点，负责在调用原生加载器前后校验授权并提交记录与审计。
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
}
