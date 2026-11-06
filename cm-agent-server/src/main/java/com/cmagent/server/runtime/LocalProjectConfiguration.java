package com.cmagent.server.runtime;

import com.cmagent.core.repository.*;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.persistence.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.*;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Platform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** 本机项目装配和启动拒绝策略；默认关闭时不加载原生DLL、不创建状态卷。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LocalProjectProperties.class, LocalProjectCommandProperties.class})
public class LocalProjectConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LocalProjectConfiguration.class);

    /** 必需的恢复/治理能力尚不齐全时拒绝开启，不能自动降低为memory或演示运行时。 */
    static void validateDeployment(LocalProjectProperties properties, Environment environment) {
        properties.validate();
        var profiles = Set.of(environment.getActiveProfiles());
        String serverAddress = environment.getProperty("server.address", "");
        boolean addressAllowed = properties.isAllowNonLoopback()
                ? serverAddress != null && !serverAddress.isBlank()
                : Set.of("127.0.0.1", "::1", "[::1]").contains(serverAddress);
        if (!Platform.isWindows() || !(profiles.contains("mysql") || profiles.contains("local") || profiles.contains("test"))
                || profiles.stream().anyMatch(value -> Set.of("prod", "production", "supabase").contains(value))
                || !"jdbc".equals(environment.getProperty("cm-agent.persistence.mode", "memory"))
                || !addressAllowed
                || !"none".equals(environment.getProperty("server.forward-headers-strategy", "none"))
                || !environment.getProperty("cm-agent.agentscope.enabled", Boolean.class, false)
                || !environment.getProperty("cm-agent.agentscope.permission-enabled", Boolean.class, false)
                || !Set.of("react","harness").contains(environment.getProperty("cm-agent.agentscope.execution-mode", "react"))
                || environment.getProperty("cm-agent.agentscope.studio.enabled", Boolean.class, false))
            throw new IllegalStateException("本地项目仅支持Windows本机local/test、显式回环监听或allow-non-loopback、JDBC与真实审批运行时，须关闭转发头和Studio");
        if (properties.isAllowNonLoopback()) {
            // 这是高风险的显式部署选择；日志只记录节点身份，不输出项目根或请求正文。
            log.warn("本机项目已允许非回环访问。nodeId={} serverAddress={}", properties.getNodeId(), serverAddress);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cm-agent.local-projects", name = "enabled", havingValue = "true")
    static class Enabled {
        /** @return 先校验部署，再取得状态卷独占；容器创建失败时由Spring负责关闭已装配资源 */
        @Bean LocalProjectStateLock localProjectStateLock(LocalProjectProperties properties, Environment environment) {
            validateDeployment(properties, environment);
            return new LocalProjectStateLock(Path.of(properties.getStateDirectory()));
        }
        /** @return 在服务接受请求前检查真实执行策略能力，避免状态卷→Runtime→Skill产物访问闸门的装配循环 */
        @Bean org.springframework.beans.factory.InitializingBean localProjectRuntimeValidator(com.cmagent.core.runtime.AgentRuntime runtime) {
            return ()->{if(!runtime.supportsLocalProjects())throw new IllegalStateException("当前运行时未声明本地项目数据保护与审批支持");};
        }
        /** @return 只在完整本机部署开启后建立长期私有项目仓储 */
        @Bean JdbcLocalProjectRepository localProjectRepository(JdbcClient jdbc, TransactionTemplate transaction, LocalProjectStateLock lock) {
            lock.requireHeld(); return new JdbcLocalProjectRepository(jdbc, transaction);
        }
        /** @return 固定来源绑定，依赖同一数据源和短事务 */
        @Bean LocalProjectBindingRepository localProjectBindings(JdbcClient jdbc, TransactionTemplate transaction, LocalProjectStateLock lock) {
            lock.requireHeld(); return new JdbcLocalProjectBindingRepository(jdbc, transaction);
        }
        /** @return 不可变意图与至多一次派发CAS */
        @Bean LocalProjectOperationRepository localProjectOperations(JdbcClient jdbc, TransactionTemplate transaction, LocalProjectStateLock lock) {
            lock.requireHeld(); return new JdbcLocalProjectOperationRepository(jdbc, transaction);
        }
        /** @return 根信封使用已配置的持久主密钥，不生成临时密钥 */
        @Bean LocalProjectRootCipher localProjectRootCipher(ModelCredentialCipher cipher, ObjectMapper json, LocalProjectProperties policy) {
            return new LocalProjectRootCipher(cipher, json, policy.getNodeId());
        }
        /** @return 私有卷和应用工作目录都不允许作为项目资料来源 */
        @Bean LocalProjectFiles localProjectFiles(LocalProjectStateLock lock, WorkspaceContextProperties workspace) {
            var excluded = new ArrayList<Path>(); excluded.add(lock.directory()); excluded.add(Path.of("").toAbsolutePath());
            if (workspace.getRootDirectory() != null && !workspace.getRootDirectory().isBlank()) excluded.add(Path.of(workspace.getRootDirectory()));
            if (!workspace.getImports().getRootDirectory().isBlank()) excluded.add(Path.of(workspace.getImports().getRootDirectory()));
            return new LocalProjectFiles(excluded);
        }
        /** @return 同租户管理员也不能旁路的私有访问边界 */
        @Bean LocalProjectAccessService localProjectAccess(JdbcLocalProjectRepository repository, LocalProjectBindingRepository bindings,
                PermissionEvaluator permissions, AuditAppender audit, ErrorDiagnosticLogger diagnostics, LocalProjectRootCipher cipher,
                LocalProjectFiles files, LocalProjectProperties properties) {
            return new LocalProjectAccessService(repository, repository, bindings, permissions, audit, diagnostics, cipher, files, properties.getNodeId());
        }
        /** @return 每次原生窗口结果最多使用一次，关闭后不持久恢复短时票据 */
        @Bean LocalProjectSelections localProjectSelections(LocalProjectAccessService access, LocalProjectProperties properties) {
            var clock = Clock.systemUTC();
            return new LocalProjectSelections(() -> new WindowsLocalProjectDirectoryPicker().choose(),
                    new LocalProjectDirectoryTickets(clock, properties.getSelectionTtl(), 32), access, clock, properties.getSelectionTtl());
        }
        /** @return 不接受浏览器宿主路径的项目编排服务 */
        @Bean LocalProjectService localProjectService(JdbcLocalProjectRepository repository, LocalProjectBindingRepository bindings,
                ConversationRepository conversations, AgentDefinitionRepository agents, LocalProjectToolCatalog toolCatalog,
                ToolGrantRepository toolGrants, LocalProjectAccessService access,
                LocalProjectSelections selections, LocalProjectRootCipher cipher, LocalProjectFiles files,
                SensitiveDataRedactor redactor, AuditAppender audit, TransactionTemplate transaction, LocalProjectProperties properties) {
            return new LocalProjectService(repository, repository, bindings, conversations, agents, toolCatalog, toolGrants,
                    access, selections, cipher, files,
                    redactor, audit, transaction, Clock.systemUTC(), properties.getNodeId());
        }
        /** @return 精确本机来源边界，不提供跨源放行 */
        @Bean LocalProjectRequestBoundary localProjectRequestBoundary(LocalProjectProperties properties) { return new LocalProjectRequestBoundary(properties); }
        /** @return 启用后历史资源也逐入口核对原项目来源，不打开本机目录 */
        @Bean LocalProjectResourceAccess localProjectResourceAccess(LocalProjectBindingRepository bindings, ConversationRepository conversations,
                RunRepository runs, LocalProjectAccessService access, LocalProjectRequestBoundary boundary) {
            return new LocalProjectResourceAccess(bindings, conversations, runs, access, boundary);
        }
        /** @return 固定项目工具目录，登记与Agent授权分开 */
        @Bean LocalProjectToolCatalog localProjectToolCatalog(ToolDefinitionRepository tools, LocalProjectAccessService access,
                TransactionTemplate transaction, AuditAppender audit, ObjectMapper json) {
            return new LocalProjectToolCatalog(tools, access, transaction, audit, json);
        }
        /** @return 本机项目首发及审批恢复，固定目录/节点/工具集合，不将文件I/O置于事务 */
        @Bean LocalProjectRunService localProjectRunService(RunRepository runs, LocalProjectBindingRepository bindings, JdbcLocalProjectRepository projects,
                LocalProjectResourceAccess resources, LocalProjectAccessService access, LocalProjectOperationRepository operations,
                LocalProjectToolCatalog catalog, ModelCredentialCipher cipher, ObjectMapper json, RuntimeCheckpointRepository checkpoints,
                AgentScopeRuntimeProperties runtime,LocalProjectReadSnapshots reads) {
            return new LocalProjectRunService(runs, bindings, projects, resources, access, operations, catalog, cipher, json,
                    checkpoints, Clock.systemUTC(), runtime.getApprovalTtl(),reads);
        }
        /** @return 本轮首次读取的有界加密全文，恢复不重新加载外部新正文 */
        @Bean LocalProjectReadSnapshots localProjectReadSnapshots(LocalProjectFiles files,ModelCredentialCipher cipher,ObjectMapper json,RuntimeCheckpointRepository checkpoints,
                SensitiveDataRedactor redactor,LocalProjectAccessService access,AgentScopeRuntimeProperties runtime) {
            return new LocalProjectReadSnapshots(files,cipher,json,checkpoints,redactor,access,Clock.systemUTC(),runtime.getApprovalTtl());
        }
        /** @return 复用所有私有目录排除策略的变更/恢复服务；只创建本服务状态子目录 */
        @Bean LocalProjectChangeService localProjectChanges(JdbcLocalProjectRepository projects, LocalProjectOperationRepository operations,
                ModelCredentialCipher cipher,ObjectMapper json,AuditAppender audit,TransactionTemplate transaction,LocalProjectProperties policy,
                LocalProjectStateLock state,LocalProjectFiles files) throws java.io.IOException {
            return new LocalProjectChangeService(projects,operations,cipher,json,audit,transaction,Clock.systemUTC(),policy.getNodeId(),state.privateSubdirectory("changes"),files);
        }
        /** @return 空工具链配置仍不宣称命令可用，环境只来自显式固定部署配置 */
        @Bean LocalProjectCommandService localProjectCommands(LocalProjectOperationRepository operations,ModelCredentialCipher cipher,ObjectMapper json,
                LocalProjectCommandProperties properties,LocalProjectAccessService access,LocalProjectStateLock state,AuditAppender audit,TransactionTemplate transaction) {
            return new LocalProjectCommandService(operations,cipher,json,Clock.systemUTC(),properties.resolve(state.privateSubdirectory("temp")),
                    (principal,binding)->{
                        state.requireHeld();var scope=access.run(principal,binding.runId(),"project:execute");
                        if(!scope.binding().equals(binding))throw new java.io.IOException("命令来源已变化");
                    },(binding,id,action,digest)->audit.appendControlled(binding.tenantId(),binding.ownerId(),action,"PROJECT_OPERATION",id.toString(),"RECORDED","runId="+binding.runId()+" digest="+digest),transaction);
        }
        /** @return 只有真实项目Run可用的专用工具治理入口，普通调试不注册原生执行器 */
        @Bean LocalProjectTools localProjectTools(RunRepository runs,LocalProjectOperationRepository operations,ToolApprovalRepository approvals,
                LocalProjectToolCatalog catalog,LocalProjectRunService runtime,LocalProjectAccessService access,LocalProjectService projects,
                LocalProjectChangeService changes,LocalProjectCommandService commands,LocalProjectStateLock state,ObjectMapper json,
                SensitiveDataRedactor redactor,TransactionTemplate transaction,org.springframework.beans.factory.ObjectProvider<RunExecutionService> executions,LocalProjectReadSnapshots reads) {
            return new LocalProjectTools(runs,operations,approvals,catalog,runtime,access,projects,changes,commands,state,json,redactor,transaction,Clock.systemUTC(),executions,reads);
        }
        /** @return 无正文解密和本机副作用能力的操作历史与人工未知意图终结入口 */
        @Bean LocalProjectOperationMaintenance localProjectOperationMaintenance(LocalProjectOperationRepository operations,LocalProjectBindingRepository bindings,
                RunRepository runs,LocalProjectAccessService access,LocalProjectResourceAccess resources,AuditAppender audit,TransactionTemplate transaction) {
            return new LocalProjectOperationMaintenance(operations,bindings,runs,access,resources,audit,transaction,Clock.systemUTC());
        }
        /** @return 只扫描原节点长期来源的冷启动元数据恢复，不执行或重放本机副作用 */
        @Bean LocalProjectRecoveryService localProjectRecoveryService(LocalProjectProperties properties,LocalProjectStateLock state,
                LocalProjectBindingRepository bindings,LocalProjectOperationRepository operations,RunRepository runs,AuditAppender audit,TransactionTemplate transaction) {
            return new LocalProjectRecoveryService(properties.getNodeId(),state,Clock.systemUTC(),bindings,operations,runs,audit,transaction);
        }
        /** @return 所有单例完成且Web服务接受请求之前收口失联Run；失败阻止启动，不让恢复与新派发竞态 */
        @Bean org.springframework.beans.factory.SmartInitializingSingleton localProjectRecoveryBeforeRequests(LocalProjectRecoveryService recovery) {
            return ()->{recovery.recover();};
        }
        /** @return 仅清理本机受保护恢复区/过期命令清单，生产调度由既有Spring调度生命周期管理 */
        @Bean LocalProjectRetentionService localProjectRetentionService(LocalProjectProperties properties,LocalProjectOperationRepository operations,
                AuditAppender audit,TransactionTemplate transaction,ModelCredentialCipher cipher,LocalProjectChangeService changes) {
            return new LocalProjectRetentionService(properties.getNodeId(),Clock.systemUTC(),operations,audit,transaction,cipher,changes);
        }
        /** @return 仅变更登记元数据的归档服务，不持有文件删除或进程停止能力 */
        @Bean LocalProjectArchiveService localProjectArchiveService(LocalProjectAccessService access,JdbcLocalProjectRepository projects,
                LocalProjectBindingRepository bindings,LocalProjectOperationRepository operations,AuditAppender audit,TransactionTemplate transaction) {
            return new LocalProjectArchiveService(access,projects,bindings,operations,audit,transaction,Clock.systemUTC());
        }
    }
}
