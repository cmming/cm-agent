package com.cmagent.server.runtime;

import java.io.IOException;

/**
 * 本机原生能力的固定失败分类，不附带原生消息、命令、环境或宿主路径。
 *
 * <p>仅供内部适配器使用；宿主边界关联可信请求的errorId并记录同号脱敏诊断，
 * 不能将原生异常或底层动态消息直接序列化为响应。</p>
 */
final class LocalProjectNativeException extends IOException {
    /** 可稳定检索的本机失败分类，不包含系统动态错误文本。 */
    private final Reason reason;

    LocalProjectNativeException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    /** @return 固定安全分类，不泄露底层系统错误 */
    Reason reason() { return reason; }

    /** 原生边界的失败原因；后续Web错误映射需显式处理，不降级为成功。 */
    enum Reason {
        /** 当前OS、位数或交互桌面不支持本机原生能力。 */
        DESKTOP_UNAVAILABLE("当前环境没有可用的本机Windows交互桌面"),
        /** 原生库或COM线程初始化不可用。 */
        NATIVE_UNAVAILABLE("本机原生组件暂不可用"),
        /** 用户确认后未能取得有效目录；取消另以空结果表示。 */
        SELECTION_FAILED("未能取得有效的本地项目目录"),
        /** 原生选择票据不属于当前主体、已消费、过期或目录身份漂移。 */
        TICKET_UNAVAILABLE("目录选择授权不存在或已失效，请重新选择"),
        /** 尚未消费的选择记录已达到当前实例上限。 */
        SELECTION_CAPACITY("本机目录选择暂时繁忙，请稍后重试"),
        /** 子进程尚未安全纳入管理，不能继续执行。 */
        PROCESS_START_FAILED("本机进程未能安全启动"),
        /** 无法确认受管进程组已退出，不能报告停止成功。 */
        PROCESS_STOP_FAILED("暂不能确认本机进程组已全部退出"),
        /** 进程或Job状态读取失败，不能按已结束处理。 */
        PROCESS_QUERY_FAILED("本机进程状态暂不可核实"),
        /** 原生资源释放失败，不掩盖为正常成功。 */
        RESOURCE_RELEASE_FAILED("本机资源释放失败，请核对执行状态");

        /** 可对外使用的固定中文说明；禁止拼接原生错误消息。 */
        private final String message;

        Reason(String message) { this.message = message; }
    }
}

