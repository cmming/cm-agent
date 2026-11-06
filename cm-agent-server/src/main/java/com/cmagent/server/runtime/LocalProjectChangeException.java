package com.cmagent.server.runtime;

import java.io.IOException;

/** 变更计划的业务失败；不附带原文、请求内容或宿主路径，HTTP关联编号由最终服务边界提供。 */
final class LocalProjectChangeException extends IOException {
    /** 固定原因，便于前端选择刷新、拆分或重新预览动作。 */
    private final Reason reason;
    LocalProjectChangeException(Reason reason) { super(reason.message); this.reason = reason; }
    Reason reason() { return reason; }

    enum Reason {
        /** 捕获的旧版本或目标不存在条件已经失效。 */ CONFLICT("项目文件已变化或目标已存在，请刷新并重新预览"),
        /** 同批路径重复、大小写别名或父子依赖不能安全应用。 */ OVERLAP("本批变更路径重叠或相互依赖，请拆成独立操作"),
        /** 创建/移动目标不是当前支持的文本文件。 */ UNSUPPORTED("该变更目标不属于当前支持的项目文本类型"),
        /** 所请求的替换不会改变任何正文。 */ NO_CHANGE("本次变更没有实际内容修改，无需执行"),
        /** 动作数量、单文件或整批明文预算超限。 */ LIMIT("项目变更超过数量或字节预算，请拆分提案");
        /** 固定安全中文消息，不拼接外部异常。 */
        private final String message;
        Reason(String message) { this.message = message; }
    }
}

