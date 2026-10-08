package com.cmagent.api;

/**
 * 对外 API 使用的稳定错误码。
 *
 * <p>错误码用于客户端分支判断；面向用户的具体说明由响应中的
 * {@code message} 提供。</p>
 */
public enum ApiErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    TENANT_NOT_FOUND,
    AGENT_NOT_FOUND,
    CONVERSATION_NOT_FOUND,
    /** 审批请求不存在或不属于当前可信资源边界。 */
    TOOL_APPROVAL_NOT_FOUND,
    /** 审批决定缺失、重复或包含不属于当前请求的明细。 */
    TOOL_APPROVAL_INVALID_DECISION,
    /** 审批版本或状态已被并发修改。 */
    TOOL_APPROVAL_CONFLICT,
    /** 审批请求已超过服务端权威有效期。 */
    TOOL_APPROVAL_EXPIRED,
    TOOL_NOT_FOUND,
    TOOL_NOT_GRANTED,
    VALIDATION_FAILED,
    /** 模型供应商拒绝目录请求或当前保存的模型凭据不可用。 */
    MODEL_DISCOVERY_AUTH_FAILED,
    /** 模型目录目标未通过协议、白名单或公网地址校验。 */
    MODEL_DISCOVERY_TARGET_REJECTED,
    /** 模型供应商未实现当前协议约定的目录接口。 */
    MODEL_DISCOVERY_UNSUPPORTED,
    /** 模型目录请求超过受控时间上限。 */
    MODEL_DISCOVERY_TIMEOUT,
    /** 模型供应商返回的目录结构或体积不符合受控边界。 */
    MODEL_DISCOVERY_RESPONSE_INVALID,
    /** 模型供应商网络不可用或返回了未分类错误。 */
    MODEL_DISCOVERY_UPSTREAM_ERROR,
    /** 技能包结构、frontmatter、名称或路径不符合约定。 */
    SKILL_PACKAGE_INVALID,
    /** 技能包包含不支持的脚本、二进制或其他资源类型。 */
    SKILL_RESOURCE_UNSUPPORTED,
    /** 技能包、解压内容、文件数或单文件大小超过限制。 */
    SKILL_PACKAGE_TOO_LARGE,
    /** 技能名称、版本、绑定或内部工具名称发生冲突。 */
    SKILL_CONFLICT,
    /** 技能候选指针已被并发修改，调用方应刷新后重试。 */
    SKILL_CANDIDATE_CONFLICT,
    /** 技能尚无正式发布版本。 */
    SKILL_NOT_PUBLISHED,
    /** 请求固定的技能版本从未形成发布事实。 */
    SKILL_VERSION_NOT_RELEASED,
    /** 技能包中的依赖声明结构或逻辑键无效。 */
    SKILL_DEPENDENCY_INVALID,
    /** 必需逻辑依赖尚未映射到租户工具。 */
    SKILL_DEPENDENCY_UNMAPPED,
    /** 已映射工具不存在、停用或运行时未就绪。 */
    SKILL_DEPENDENCY_UNAVAILABLE,
    /** 目标 Agent 未获得映射工具的现行授权。 */
    SKILL_AGENT_GRANT_MISSING,
    /** 预检采用的版本或映射修订已经过期。 */
    SKILL_PREFLIGHT_STALE,
    /** 当前候选尚无满足发布要求的试运行。 */
    SKILL_TRIAL_REQUIRED,
    /** 试运行未实际加载目标技能，不能作为发布依据。 */
    SKILL_TRIAL_NOT_TRIGGERED,
    /** 技能发布或回滚事务未能完成。 */
    SKILL_RELEASE_FAILED,
    /** 技能功能关闭，当前写操作不可执行。 */
    SKILL_FEATURE_DISABLED,
    /** 技能、版本或资源不存在，或不属于可信租户边界。 */
    SKILL_NOT_FOUND,
    /** 运行快照引用的技能已停用、解绑或纪元失效。 */
    SKILL_ACCESS_REVOKED,
    /** 运行技能快照缺失、版本缺失或无法一致恢复。 */
    SKILL_SNAPSHOT_UNAVAILABLE,
    /** 技能预载或按需读取次数、字节预算已耗尽。 */
    SKILL_LOAD_LIMIT_EXCEEDED,
    /** 技能原生加载或未分类运行链路失败。 */
    SKILL_LOAD_FAILED,
    /** 技能沙箱未被部署环境显式启用。 */
    SKILL_SANDBOX_DISABLED,
    /** 脚本路径、输入或固定资源不符合沙箱约束。 */
    SKILL_SANDBOX_INVALID,
    /** 同一模型执行调用已经准备过，禁止再次产生副作用。 */
    SKILL_SANDBOX_DUPLICATE,
    /** 沙箱并发、输入或输出超过受控限额。 */
    SKILL_SANDBOX_LIMIT_EXCEEDED,
    /** 沙箱执行超过部署环境时间限制。 */
    SKILL_SANDBOX_TIMEOUT,
    /** Docker、解释器或沙箱清理不可用。 */
    SKILL_SANDBOX_UNAVAILABLE,
    /** 脚本非零退出，原始诊断输出不对外泄露。 */
    SKILL_SANDBOX_FAILED,
    /** 端点不属于当前租户或已删除。 */ SKILL_SANDBOX_ENDPOINT_NOT_FOUND,
    /** 配置版本、默认选择或探测已过期。 */ SKILL_SANDBOX_ENDPOINT_CONFLICT,
    /** 主机或端口不在部署允许范围。 */ SKILL_SANDBOX_TARGET_DENIED,
    /** SSH或TLS身份校验失败。 */ SKILL_SANDBOX_AUTH_FAILED,
    /** 清理无法确认，实例保留并发占用。 */ SKILL_SANDBOX_CLEANUP_FAILED,
    /** 加密主密钥或认证材料不可用。 */ SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE,
    RUNTIME_ERROR,
    AUDIT_UNAVAILABLE,
    PERSISTENCE_UNAVAILABLE,
    INTERNAL_ERROR
}
