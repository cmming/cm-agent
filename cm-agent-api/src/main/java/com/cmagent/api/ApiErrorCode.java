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
    /** 同会话仍有活动运行，或停止与状态变化发生冲突。 */
    RUN_CONTROL_CONFLICT,
    /** 执行句柄不可达或当前 Runtime 未实现实际停止控制。 */
    RUN_CONTROL_UNAVAILABLE,
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
    /** 文件协议、路径或格式不合法。 */ SKILL_ARTIFACT_INVALID,
    /** 文件数量、字节或存储配额超限。 */ SKILL_ARTIFACT_LIMIT_EXCEEDED,
    /** 当前租户或主体不可见该产物。 */ SKILL_ARTIFACT_NOT_FOUND,
    /** 已授权产物已超过有效期。 */ SKILL_ARTIFACT_EXPIRED,
    /** 加密存储、文件完整性或读取租约暂不可用。 */ SKILL_ARTIFACT_UNAVAILABLE,
    /** 管理员工作区路径、文本编码或文件类型不符合只读边界。 */ WORKSPACE_CONTEXT_INVALID,
    /** 固定参考资料数量或 UTF-8 字节预算超限。 */ WORKSPACE_CONTEXT_LIMIT_EXCEEDED,
    /** 参考资料缺失、文件系统不可读或基础服务不可用。 */ WORKSPACE_CONTEXT_UNAVAILABLE,
    /** 工作区映射已停用、移除或变更，禁止继续旧运行。 */ WORKSPACE_CONTEXT_REVOKED,
    /** 原运行的加密参考快照缺失、过期或损坏，禁止重新读取最新资料。 */ WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,
    /** 目录选择模式或逻辑标识不合法。 */ WORKSPACE_SELECTION_INVALID,
    /** 会话已开始或选择版本过期。 */ WORKSPACE_SELECTION_CONFLICT,
    /** 登记目录不存在或不在当前可信租户与 Agent 的可见范围。 */ WORKSPACE_NOT_FOUND,
    /** 已选目录关闭、撤销或登记身份已改变。 */ WORKSPACE_UNAVAILABLE,
    /** 导入manifest、路径、请求关联标识或版本参数不合法。 */ WORKSPACE_IMPORT_INVALID,
    /** 导入或派生资源缺失，或当前tenant/owner/Agent不可见。 */ WORKSPACE_IMPORT_NOT_FOUND,
    /** 派生资源读取被隐藏；HTTP复用原路由的通用404，避免新错误码成为私有资源存在性探针。 */ WORKSPACE_RESOURCE_NOT_FOUND,
    /** 导入版本、状态或重复创建/上传发生冲突。 */ WORKSPACE_IMPORT_CONFLICT,
    /** 部署关闭新导入，仍允许原主体核对和撤销。 */ WORKSPACE_IMPORT_DISABLED,
    /** 原主体可见的导入已到服务端固定期限。 */ WORKSPACE_IMPORT_EXPIRED,
    /** HTTP正文超过受控读取上限。 */ WORKSPACE_IMPORT_TOO_LARGE,
    /** 文本、实际长度、摘要或清单预算不合格。 */ WORKSPACE_IMPORT_CONTENT_INVALID,
    /** 导入配额或请求/上传并发已经用尽。 */ WORKSPACE_IMPORT_LIMIT_EXCEEDED,
    /** 私有存储、完整性验证或其他导入依赖不可用。 */ WORKSPACE_IMPORT_UNAVAILABLE,
    /** 读取上传正文超过预算，结果须先查询核对。 */ WORKSPACE_IMPORT_UPLOAD_TIMEOUT,
    /** 本机项目元数据、私有卷、密钥或审计基础设施不可用。 */ PROJECT_UNAVAILABLE,
    /** 项目不存在或不属于当前精确owner。 */ PROJECT_NOT_FOUND,
    /** 当前主体缺少本机项目操作权限。 */ PROJECT_FORBIDDEN,
    /** 项目已归档，禁止新的文件/执行活动。 */ PROJECT_ARCHIVED,
    /** 项目所属执行节点与当前服务不一致。 */ PROJECT_NODE_UNAVAILABLE,
    /** 固定项目运行的根/节点等长期来源不一致。 */ PROJECT_BINDING_CONFLICT,
    /** 来源资源缺失或当前主体不可见。 */ PROJECT_RESOURCE_NOT_FOUND,
    /** 原始连接、精确Host/Origin或显式本机操作头不满足约束。 */ PROJECT_LOCAL_REQUEST_REQUIRED,
    /** 项目元数据条件版本已变化。 */ PROJECT_VERSION_CONFLICT,
    /** 展示名称无效，不作为目录路径解释。 */ PROJECT_NAME_INVALID,
    /** 项目关联Agent不存在或不可访问。 */ PROJECT_AGENT_NOT_FOUND,
    /** 系统选择窗口或有界请求槽正在占用。 */ PROJECT_SELECTION_BUSY,
    /** 原生选择票据过期、已消费或主体不匹配。 */ PROJECT_SELECTION_EXPIRED,
    /** 选择动作不存在或已超过固定保留期限。 */ PROJECT_SELECTION_NOT_FOUND,
    /** 脱敏UTF-8视图的页范围或续读位置无效。 */ PROJECT_READ_RANGE_INVALID,
    /** 项目存在执行中或结果未知的副作用，必须先核对。 */ PROJECT_OPERATION_UNCERTAIN,
    /** 原不可变提案的版本、摘要或状态不匹配。 */ PROJECT_PROPOSAL_CHANGED,
    /** 当前工具调用没有匹配的真实逐次HIGH批准。 */ PROJECT_EXACT_APPROVAL_REQUIRED,
    /** 项目工具参数不满足固定模式。 */ PROJECT_TOOL_INPUT_INVALID,
    /** 操作元数据列表容量不满足有界查询要求。 */ PROJECT_OPERATION_LIMIT_INVALID,
    /** 人工终结未知意图前尚未明确接受可能发生的副作用。 */ PROJECT_OPERATION_ACKNOWLEDGEMENT_REQUIRED,
    /** 操作不存在或不属于当前项目、运行和精确owner。 */ PROJECT_OPERATION_NOT_FOUND,
    /** 操作不在UNKNOWN状态或条件版本已经变化。 */ PROJECT_OPERATION_CONFLICT,
    /** 项目元数据版本不是可接受的正数。 */ PROJECT_VERSION_INVALID,
    /** 归档前仍有运行中、待审批或停止中的固定Run。 */ PROJECT_ACTIVE_RUNS,
    /** 归档前仍有执行中或结果未知的本机操作。 */ PROJECT_UNCERTAIN_OPERATIONS,
    /** 根内相对路径或读取范围无效。 */ PROJECT_FILE_INVALID_PATH,
    /** 项目文件或目录已不存在。 */ PROJECT_FILE_NOT_FOUND,
    /** 凭据、私有目录、生成目录或文本类型默认排除。 */ PROJECT_FILE_EXCLUDED,
    /** 已登记目录被移除或原生身份变化。 */ PROJECT_FILE_ROOT_CHANGED,
    /** 文件为链接、设备或其他非普通安全文本对象。 */ PROJECT_FILE_UNSAFE_FILE,
    /** 文件正文、最终路径或版本在访问期间变化。 */ PROJECT_FILE_FILE_CHANGED,
    /** 项目文件或单次请求超出读取硬界。 */ PROJECT_FILE_LIMIT_EXCEEDED,
    /** 项目文件正被其他写入者持有。 */ PROJECT_FILE_FILE_BUSY,
    /** 当前运行账号没有文件读取权限。 */ PROJECT_FILE_ACCESS_DENIED,
    /** 文件编码或控制字符不满足UTF-8纯文本约束。 */ PROJECT_FILE_INVALID_TEXT,
    /** 文件协作式读取预算已耗尽或已停止。 */ PROJECT_FILE_TIMEOUT,
    /** 项目文件原生组件或文件系统不可用。 */ PROJECT_FILE_UNAVAILABLE,
    /** 变更原版本或目标不存在条件已失效。 */ PROJECT_CHANGE_CONFLICT,
    /** 同批变更路径重叠或互相依赖。 */ PROJECT_CHANGE_OVERLAP,
    /** 目标类型不支持当前受控文本变更。 */ PROJECT_CHANGE_UNSUPPORTED,
    /** 提案替换没有实际内容变化。 */ PROJECT_CHANGE_NO_CHANGE,
    /** 变更动作或字节预算超限。 */ PROJECT_CHANGE_LIMIT,
    RUNTIME_ERROR,
    AUDIT_UNAVAILABLE,
    PERSISTENCE_UNAVAILABLE,
    INTERNAL_ERROR
}
