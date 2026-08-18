-- PostgreSQL 使用 COMMENT ON 写入原生表注释和字段注释，不改变既有表结构。
COMMENT ON TABLE tenants IS '租户基础信息';
COMMENT ON COLUMN tenants.id IS '租户唯一标识';
COMMENT ON COLUMN tenants.code IS '租户代码';
COMMENT ON COLUMN tenants.name IS '租户名称';
COMMENT ON COLUMN tenants.enabled IS '是否启用';
COMMENT ON COLUMN tenants.created_at IS '创建时间';

COMMENT ON TABLE users IS '租户用户账号';
COMMENT ON COLUMN users.id IS '用户唯一标识';
COMMENT ON COLUMN users.tenant_id IS '所属租户标识';
COMMENT ON COLUMN users.username IS '登录用户名';
COMMENT ON COLUMN users.password_hash IS '密码哈希值';
COMMENT ON COLUMN users.display_name IS '用户显示名称';
COMMENT ON COLUMN users.enabled IS '是否启用';
COMMENT ON COLUMN users.created_at IS '创建时间';

COMMENT ON TABLE roles IS '租户角色定义';
COMMENT ON COLUMN roles.id IS '角色唯一标识';
COMMENT ON COLUMN roles.tenant_id IS '所属租户标识';
COMMENT ON COLUMN roles.code IS '角色代码';
COMMENT ON COLUMN roles.name IS '角色名称';

COMMENT ON TABLE permissions IS '系统权限定义';
COMMENT ON COLUMN permissions.code IS '权限代码';
COMMENT ON COLUMN permissions.description IS '权限说明';

COMMENT ON TABLE user_roles IS '用户与角色关联';
COMMENT ON COLUMN user_roles.tenant_id IS '所属租户标识';
COMMENT ON COLUMN user_roles.user_id IS '用户标识';
COMMENT ON COLUMN user_roles.role_id IS '角色标识';

COMMENT ON TABLE role_permissions IS '角色与权限关联';
COMMENT ON COLUMN role_permissions.role_id IS '角色标识';
COMMENT ON COLUMN role_permissions.permission_code IS '权限代码';

COMMENT ON TABLE api_keys IS '租户 API Key 元数据';
COMMENT ON COLUMN api_keys.id IS 'API Key 唯一标识';
COMMENT ON COLUMN api_keys.tenant_id IS '所属租户标识';
COMMENT ON COLUMN api_keys.name IS 'API Key 名称';
COMMENT ON COLUMN api_keys.key_hash IS 'API Key 哈希值';
COMMENT ON COLUMN api_keys.permissions_json IS '权限集合 JSON';
COMMENT ON COLUMN api_keys.enabled IS '是否启用';
COMMENT ON COLUMN api_keys.created_at IS '创建时间';
COMMENT ON COLUMN api_keys.rotated_at IS '最近轮换时间';

COMMENT ON TABLE model_configs IS '租户模型配置';
COMMENT ON COLUMN model_configs.id IS '模型配置唯一标识';
COMMENT ON COLUMN model_configs.tenant_id IS '所属租户标识';
COMMENT ON COLUMN model_configs.provider_type IS '模型服务提供方类型';
COMMENT ON COLUMN model_configs.display_name IS '模型配置显示名称';
COMMENT ON COLUMN model_configs.base_url IS '模型服务基础地址';
COMMENT ON COLUMN model_configs.model_name IS '模型名称';
COMMENT ON COLUMN model_configs.encrypted_api_key IS '历史兼容密钥占位字段，禁止存储明文 API Key';
COMMENT ON COLUMN model_configs.enabled IS '是否启用';
COMMENT ON COLUMN model_configs.created_at IS '创建时间';

COMMENT ON TABLE agent_definitions IS 'Agent 定义';
COMMENT ON COLUMN agent_definitions.id IS 'Agent 唯一标识';
COMMENT ON COLUMN agent_definitions.tenant_id IS '所属租户标识';
COMMENT ON COLUMN agent_definitions.name IS 'Agent 名称';
COMMENT ON COLUMN agent_definitions.description IS 'Agent 说明';
COMMENT ON COLUMN agent_definitions.system_prompt IS '系统提示词';
COMMENT ON COLUMN agent_definitions.model_provider_id IS '关联模型配置标识';
COMMENT ON COLUMN agent_definitions.model_name IS '运行使用的模型名称';
COMMENT ON COLUMN agent_definitions.temperature IS '模型采样温度';
COMMENT ON COLUMN agent_definitions.max_iterations IS '最大迭代次数';
COMMENT ON COLUMN agent_definitions.enabled IS '是否启用';
COMMENT ON COLUMN agent_definitions.tool_ids_json IS '关联工具标识集合 JSON';
COMMENT ON COLUMN agent_definitions.created_by IS '创建主体标识';
COMMENT ON COLUMN agent_definitions.updated_by IS '最后更新主体标识';
COMMENT ON COLUMN agent_definitions.created_at IS '创建时间';
COMMENT ON COLUMN agent_definitions.updated_at IS '最后更新时间';

COMMENT ON TABLE tool_definitions IS '工具定义';
COMMENT ON COLUMN tool_definitions.id IS '工具唯一标识';
COMMENT ON COLUMN tool_definitions.tenant_id IS '所属租户标识';
COMMENT ON COLUMN tool_definitions.name IS '工具名称';
COMMENT ON COLUMN tool_definitions.description IS '工具说明';
COMMENT ON COLUMN tool_definitions.type IS '工具类型';
COMMENT ON COLUMN tool_definitions.input_schema IS '工具输入 JSON Schema';
COMMENT ON COLUMN tool_definitions.risk_level IS '工具风险等级';
COMMENT ON COLUMN tool_definitions.enabled IS '是否启用';
COMMENT ON COLUMN tool_definitions.endpoint IS '工具端点元数据，不作为自动执行地址';
COMMENT ON COLUMN tool_definitions.created_by IS '创建主体标识';
COMMENT ON COLUMN tool_definitions.updated_by IS '最后更新主体标识';
COMMENT ON COLUMN tool_definitions.created_at IS '创建时间';
COMMENT ON COLUMN tool_definitions.updated_at IS '最后更新时间';
COMMENT ON COLUMN tool_definitions.deleted_at IS '软删除时间，为空表示活动工具';
COMMENT ON COLUMN tool_definitions.deleted_name IS '软删除前的原始工具名称';

COMMENT ON TABLE tool_http_configs IS 'HTTP 工具运行配置';
COMMENT ON COLUMN tool_http_configs.tenant_id IS '所属租户标识';
COMMENT ON COLUMN tool_http_configs.tool_id IS '工具标识';
COMMENT ON COLUMN tool_http_configs.method IS 'HTTP 请求方法';
COMMENT ON COLUMN tool_http_configs.url_template IS 'HTTP 请求地址模板';
COMMENT ON COLUMN tool_http_configs.secret_headers IS '仅包含 Secret 引用的请求头配置 JSON';
COMMENT ON COLUMN tool_http_configs.timeout_ms IS '请求超时时间，单位毫秒';
COMMENT ON COLUMN tool_http_configs.created_at IS '创建时间';
COMMENT ON COLUMN tool_http_configs.updated_at IS '最后更新时间';
COMMENT ON COLUMN tool_http_configs.parameter_definitions IS '扁平 HTTP 参数定义 JSON';

COMMENT ON TABLE tool_mcp_publications IS '工具 MCP 发布状态';
COMMENT ON COLUMN tool_mcp_publications.tenant_id IS '所属租户标识';
COMMENT ON COLUMN tool_mcp_publications.tool_id IS '工具标识';
COMMENT ON COLUMN tool_mcp_publications.enabled IS '是否发布到 MCP';
COMMENT ON COLUMN tool_mcp_publications.published_by IS '发布主体标识';
COMMENT ON COLUMN tool_mcp_publications.created_at IS '创建时间';
COMMENT ON COLUMN tool_mcp_publications.updated_at IS '最后更新时间';

COMMENT ON TABLE tool_grants IS 'Agent 工具授权';
COMMENT ON COLUMN tool_grants.id IS '授权记录唯一标识';
COMMENT ON COLUMN tool_grants.tenant_id IS '所属租户标识';
COMMENT ON COLUMN tool_grants.tool_id IS '工具标识';
COMMENT ON COLUMN tool_grants.agent_id IS 'Agent 标识';
COMMENT ON COLUMN tool_grants.role_code IS '可选角色代码';
COMMENT ON COLUMN tool_grants.granted IS '是否授予调用权限';
COMMENT ON COLUMN tool_grants.created_at IS '创建时间';

COMMENT ON TABLE conversations IS 'Agent 会话';
COMMENT ON COLUMN conversations.id IS '会话唯一标识';
COMMENT ON COLUMN conversations.tenant_id IS '所属租户标识';
COMMENT ON COLUMN conversations.agent_id IS 'Agent 标识';
COMMENT ON COLUMN conversations.title IS '会话标题';
COMMENT ON COLUMN conversations.created_by IS '创建主体标识';
COMMENT ON COLUMN conversations.created_at IS '创建时间';

COMMENT ON TABLE messages IS '会话消息';
COMMENT ON COLUMN messages.id IS '消息唯一标识';
COMMENT ON COLUMN messages.tenant_id IS '所属租户标识';
COMMENT ON COLUMN messages.conversation_id IS '会话标识';
COMMENT ON COLUMN messages.role IS '消息角色';
COMMENT ON COLUMN messages.content IS '消息内容';
COMMENT ON COLUMN messages.created_at IS '创建时间';

COMMENT ON TABLE runs IS 'Agent 运行记录';
COMMENT ON COLUMN runs.id IS '运行唯一标识';
COMMENT ON COLUMN runs.tenant_id IS '所属租户标识';
COMMENT ON COLUMN runs.agent_id IS 'Agent 标识';
COMMENT ON COLUMN runs.principal_id IS '发起运行的主体标识';
COMMENT ON COLUMN runs.status IS '运行状态';
COMMENT ON COLUMN runs.input_text IS '运行输入文本';
COMMENT ON COLUMN runs.output_text IS '运行输出文本';
COMMENT ON COLUMN runs.error_message IS '脱敏后的运行错误信息';
COMMENT ON COLUMN runs.started_at IS '开始时间';
COMMENT ON COLUMN runs.finished_at IS '结束时间';

COMMENT ON TABLE tool_calls IS '工具调用记录';
COMMENT ON COLUMN tool_calls.id IS '工具调用唯一标识';
COMMENT ON COLUMN tool_calls.tenant_id IS '所属租户标识';
COMMENT ON COLUMN tool_calls.run_id IS '所属运行标识';
COMMENT ON COLUMN tool_calls.tool_id IS '工具标识';
COMMENT ON COLUMN tool_calls.tool_name IS '调用时的工具名称快照';
COMMENT ON COLUMN tool_calls.input_summary IS '脱敏后的输入摘要';
COMMENT ON COLUMN tool_calls.output_summary IS '脱敏后的输出摘要';
COMMENT ON COLUMN tool_calls.status IS '调用状态';
COMMENT ON COLUMN tool_calls.authorized IS '调用时是否通过授权';
COMMENT ON COLUMN tool_calls.duration_ms IS '调用耗时，单位毫秒';
COMMENT ON COLUMN tool_calls.error_message IS '脱敏后的调用错误信息';
COMMENT ON COLUMN tool_calls.created_at IS '创建时间';

COMMENT ON TABLE audit_events IS '安全审计事件';
COMMENT ON COLUMN audit_events.id IS '审计事件唯一标识';
COMMENT ON COLUMN audit_events.tenant_id IS '所属租户标识';
COMMENT ON COLUMN audit_events.principal_id IS '操作主体标识，使用软引用';
COMMENT ON COLUMN audit_events.event_type IS '审计事件类型';
COMMENT ON COLUMN audit_events.resource_type IS '资源类型';
COMMENT ON COLUMN audit_events.resource_id IS '资源标识，使用软引用';
COMMENT ON COLUMN audit_events.status IS '操作结果状态';
COMMENT ON COLUMN audit_events.message IS '脱敏后的审计消息';
COMMENT ON COLUMN audit_events.created_at IS '创建时间';
