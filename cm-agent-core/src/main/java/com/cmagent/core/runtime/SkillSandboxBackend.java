package com.cmagent.core.runtime;

import java.util.Map;

/**
 * 由服务端注册的沙箱执行扩展；实现不能信任模型提供的连接配置。
 * <p>连接参数只来自部署策略或已认证租户的加密端点；实现不得记录其中凭据，
 * 不得自动重放脚本。句柄由调用方关闭，关闭失败必须传播，不能伪造清理成功。</p>
 */
public interface SkillSandboxBackend {
    /** @return 注册标识，不能由客户端指定实现类 */
    String backendId();

    /**
     * 打开一次固定连接的执行句柄；默认实现兼容原来的无连接执行器。
     * @param request 可信运行上下文
     * @param connection 已授权的不可变连接参数，可能包含临时凭据，禁止记录
     * @return 调用方负责关闭的单次句柄
     */
    default Execution open(SkillReadRequest request, Map<String, String> connection) {
        if (!connection.isEmpty()) throw new IllegalArgumentException("后端不支持连接参数");
        return new Execution() {
            public String execute(Map<String, String> files, String stdin) {
                return SkillSandboxBackend.this.execute(request, files, stdin);
            }
            public void close() {}
        };
    }

    /**
     * 执行固定资源，旧扩展实现可直接实现本方法。
     * @param request 可信上下文
     * @param files 固定版本的资源副本
     * @param stdin 有界标准输入
     * @return 成功的文本输出；统一编排层再次限额与脱敏
     */
    String execute(SkillReadRequest request, Map<String, String> files, String stdin);

    /** 一次执行的资源所有权边界；连接切换不影响当前句柄。 */
    interface Execution extends AutoCloseable {
        /** @param files 固定资源 @param stdin 有界输入 @return 输出 */
        String execute(Map<String, String> files, String stdin);
        /**
         * 执行并同步交付有界文件流；旧后端默认只返回文本，保持扩展兼容。
         * @param files 固定技能资源
         * @param stdin 有界标准输入
         * @param sink 当前调用独占的文件接收器，不能保存二进制到文本结果
         * @return 脱敏文本结果
         */
        default String execute(Map<String, String> files, String stdin, SkillArtifactSink sink) {
            return execute(files, stdin);
        }
        /** 交付前检查端点停用/撤销，允许资源关闭后调用；默认扩展没有管理端点。 */
        default void verifyAccess() {}
        /** 幂等释放临时材料；必须终止正在运行的任务，资源无法确认清理时必须失败。 */
        @Override void close();
    }
}
