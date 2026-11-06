package com.cmagent.server.runtime;

import com.cmagent.core.repository.*;
import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.KeyGenerator;
import javax.sql.DataSource;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 显式启用的纯Spring装配与Windows私有句柄生命周期测试。
 * JdbcClient只返回版本字符串和固定冷启动发现的空候选，没有DataSource、不执行真实SQL；不构成JDBC或迁移验收。
 * 本类不引入EnableScheduling，冷启动只核验空候选流程；不触发选择窗口、后台定时清理、Provider、Job或用户项目文件访问。
 */
@EnabledOnOs(OS.WINDOWS)
class LocalProjectEnabledWiringTest {
    @TempDir Path temporary;

    @Test void 完整本机门禁下所有项目组件装配且关闭后释放两级锁() throws Exception {
        Path state=privateState();
        Path preserved=Files.writeString(state.resolve("retained-marker.txt"),"合成保留状态");
        var jdbc=startupOnlyJdbc();
        var runtime=mock(AgentRuntime.class);
        when(runtime.supportsLocalProjects()).thenReturn(true);
        runner(state,jdbc,runtime).run(context->{
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LocalProjectStateLock.class)
                    .hasSingleBean(LocalProjectService.class).hasSingleBean(LocalProjectTools.class)
                    .hasSingleBean(LocalProjectReadSnapshots.class).hasSingleBean(LocalProjectChangeService.class)
                    .hasSingleBean(LocalProjectCommandService.class).hasSingleBean(LocalProjectResourceAccess.class)
                    .hasSingleBean(LocalProjectRunService.class).hasSingleBean(LocalProjectOperationMaintenance.class)
                    .hasSingleBean(LocalProjectRetentionService.class).hasSingleBean(LocalProjectRecoveryService.class)
                    .hasSingleBean(LocalProjectSelections.class)
                    .doesNotHaveBean(DataSource.class);
            context.getBean(LocalProjectStateLock.class).requireHeld();
            assertHeld(state.resolve(".instance-lock"));
            assertHeld(state.resolve("changes/.changes.lock"));
            assertThat(Files.readString(preserved)).isEqualTo("合成保留状态");
        });
        assertAvailable(state.resolve(".instance-lock"));
        assertAvailable(state.resolve("changes/.changes.lock"));
        assertThat(Files.readString(preserved)).isEqualTo("合成保留状态");
        verify(jdbc,atLeastOnce()).sql("SELECT version()");
        verify(jdbc).sql(argThat(LocalProjectEnabledWiringTest::isRecoveryDiscovery));
        verifyNoMoreInteractions(jdbc);
        verify(runtime).supportsLocalProjects();
        verifyNoMoreInteractions(runtime);
    }

    @Test void 运行时不支持本地项目时启动失败且已创建实例锁被关闭() throws Exception {
        Path state=privateState();
        var runtime=mock(AgentRuntime.class);
        when(runtime.supportsLocalProjects()).thenReturn(false);
        runner(state,startupOnlyJdbc(),runtime).run(context->{
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("当前运行时未声明本地项目数据保护与审批支持");
        });
        // 配置先建立实例状态卷，再校验真实运行时能力；失败刷新必须销毁此前创建的资源。
        assertThat(state.resolve(".instance-lock")).exists();
        assertAvailable(state.resolve(".instance-lock"));
        if(Files.exists(state.resolve("changes/.changes.lock")))assertAvailable(state.resolve("changes/.changes.lock"));
        verify(runtime).supportsLocalProjects();
        verifyNoMoreInteractions(runtime);
    }

    private ApplicationContextRunner runner(Path state,JdbcClient jdbc,AgentRuntime runtime)throws Exception{
        var policy=new AgentScopeRuntimeProperties();policy.setEnabled(true);policy.setPermissionEnabled(true);policy.setExecutionMode("react");
        var generator=KeyGenerator.getInstance("AES");generator.init(256);
        var cipher=new ModelCredentialCipher(generator.generateKey());
        return new ApplicationContextRunner().withUserConfiguration(LocalProjectConfiguration.class)
                .withInitializer(context->context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("cm-agent.local-projects.enabled=true","cm-agent.local-projects.node-id=wiring-node",
                        "cm-agent.local-projects.state-directory="+state,"cm-agent.local-projects.allowed-origins[0]=http://127.0.0.1:19080",
                        "cm-agent.persistence.mode=jdbc","server.address=127.0.0.1","server.forward-headers-strategy=none",
                        "cm-agent.agentscope.enabled=true","cm-agent.agentscope.permission-enabled=true",
                        "cm-agent.agentscope.execution-mode=react","cm-agent.agentscope.studio.enabled=false")
                .withBean(JdbcClient.class,()->jdbc)
                .withBean(TransactionTemplate.class,()->new TransactionTemplate(mock(PlatformTransactionManager.class)))
                .withBean(AgentRuntime.class,()->runtime)
                .withBean(AgentScopeRuntimeProperties.class,()->policy)
                .withBean(WorkspaceContextProperties.class,WorkspaceContextProperties::new)
                .withBean(ModelCredentialCipher.class,()->cipher)
                .withBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules())
                .withBean(PermissionEvaluator.class,()->mock(PermissionEvaluator.class))
                .withBean(AuditAppender.class,()->mock(AuditAppender.class))
                .withBean(ErrorDiagnosticLogger.class,()->mock(ErrorDiagnosticLogger.class))
                .withBean(SensitiveDataRedactor.class,SensitiveDataRedactor::new)
                .withBean(ConversationRepository.class,()->mock(ConversationRepository.class))
                .withBean(RunRepository.class,()->mock(RunRepository.class))
                .withBean(AgentDefinitionRepository.class,()->mock(AgentDefinitionRepository.class))
                .withBean(ToolDefinitionRepository.class,()->mock(ToolDefinitionRepository.class))
                .withBean(ToolGrantRepository.class,()->mock(ToolGrantRepository.class))
                .withBean(ToolApprovalRepository.class,()->mock(ToolApprovalRepository.class))
                .withBean(RuntimeCheckpointRepository.class,()->mock(RuntimeCheckpointRepository.class));
    }

    @SuppressWarnings("unchecked")
    private static JdbcClient startupOnlyJdbc(){
        // 只支持构造器方言识别及固定冷启动发现的空结果；SQL语义由Rocky双方言测试验收。
        var jdbc=mock(JdbcClient.class);
        var statement=mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query=mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql("SELECT version()")).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.single()).thenReturn("PostgreSQL 16");
        var recovery=mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<LocalProjectBinding> empty=mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(argThat(LocalProjectEnabledWiringTest::isRecoveryDiscovery))).thenReturn(recovery);
        when(recovery.param("node","wiring-node")).thenReturn(recovery);
        when(recovery.param("limit",100)).thenReturn(recovery);
        when(recovery.query(any(RowMapper.class))).thenReturn(empty);
        when(empty.list()).thenReturn(List.of());
        return jdbc;
    }

    /** 固定发现表、归属WHERE及活动态后缀必须同时命中；不能用任意SQL通配掩盖新的启动查询。 */
    private static boolean isRecoveryDiscovery(String sql){
        return sql!=null&&sql.startsWith("SELECT b.* FROM local_project_runs b JOIN local_projects p ON p.tenant_id=b.tenant_id AND p.id=b.project_id")
                &&sql.contains(" WHERE b.owner_id COLLATE \"C\" = p.owner_id AND r.principal_id COLLATE \"C\" = b.owner_id")
                &&sql.contains("p.node_id COLLATE \"C\" = :node AND b.node_id COLLATE \"C\" = :node AND cb.node_id COLLATE \"C\" = :node")
                &&sql.endsWith("AND r.workspace_protocol=4 AND c.workspace_protocol=4 AND r.run_kind='NORMAL' AND r.status IN ('RUNNING','CANCELLING') ORDER BY b.tenant_id,b.run_id LIMIT :limit");
    }

    private Path privateState()throws Exception{
        Path directory=Files.createDirectory(temporary.resolve("private-state")).toRealPath();
        var view=Files.getFileAttributeView(directory,AclFileAttributeView.class);
        assertThat(view).isNotNull();
        // 只收紧本测试新建的状态卷，子文件继承当前owner完整许可，不修改真实用户目录ACL。
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build()));
        return directory;
    }

    private static void assertHeld(Path path)throws Exception{
        assertThat(path).exists();
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){
            assertThatThrownBy(channel::tryLock).isInstanceOf(OverlappingFileLockException.class);
        }
    }

    private static void assertAvailable(Path path)throws Exception{
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE);var acquired=channel.tryLock()){
            assertThat(acquired).isNotNull();
        }
    }
}
