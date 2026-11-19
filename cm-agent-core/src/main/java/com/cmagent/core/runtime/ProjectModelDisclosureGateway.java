package com.cmagent.core.runtime;

/**
 * 运行宿主对项目数据发往模型的控制。实例只属于创建它的可信 Run。
 * Adapter 每次真正发送前调用，宿主确认授权仍有效并写审计；调用失败必须阻止请求。
 */
public interface ProjectModelDisclosureGateway {
    /** @param value 待发送的文本 @return 可用于模型调用及审批预览的同一份脱敏文本 */
    String redact(String value);

    /** @param automatic 是否由完全访问模式放行；审批模式传 {@code false} */
    void beforeSend(boolean automatic);
}
