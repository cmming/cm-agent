package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import com.cmagent.core.repository.LocalProjectOperationRepository.CommandRetentionCursor;
import com.cmagent.server.audit.AuditAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 受控本机后台隐私清理：复用文件恢复区GC，按受信节点发现并擦除到期命令清单。
 * 不接受HTTP参数、不读取命令输出、不停止Job，也不会将未知结果重新派发。
 * 宿主仅在本机项目门禁通过后装配本Bean；所有扫描调用在同一实例串行执行。
 */
public final class LocalProjectRetentionService {
    /** 日志仅包含固定分类和数据库确认的元数据，原异常动态消息/原因链不交给调度器。 */
    private static final Logger log=LoggerFactory.getLogger(LocalProjectRetentionService.class);
    /** 与文件变更完全一致的固定保留期，读取/审批不续期。 */
    private static final Duration RETENTION=Duration.ofDays(7);
    /** 每次定时最多一页；失败项也推进，末尾重置后下轮可重试。 */
    private static final int BATCH_SIZE=100;
    /** 节点只来自部署配置，不能由用户指定以扩大跨租户发现范围。 */
    private final String nodeId;
    /** 测试可替换的服务端时钟。 */
    private final Clock clock;
    /** 系统发现与精确tenant/owner条件更新使用同一持久仓储。 */
    private final LocalProjectOperationRepository operations;
    /** 与取消/擦除同事务的严格元数据审计。 */
    private final AuditAppender audit;
    /** 不含本机文件或进程操作的短事务。 */
    private final TransactionTemplate transaction;
    /** 与原命令清单相同的持久AEAD主密钥。 */
    private final ModelCredentialCipher cipher;
    /** 只清理其私有状态区，生命周期仍由宿主负责关闭。 */
    private final LocalProjectChangeService changes;
    /** 一轮遍历固定截止时间，持续出现新记录不会饿死失败项。 */
    private Instant sweepCutoff;
    /** 仅是发现位置，不是执行许可；重启清空后从头识别墓碑。 */
    private CommandRetentionCursor after;

    /**
     * @param nodeId 受信部署节点标识
     * @param clock 服务端时钟
     * @param operations 节点发现及条件状态仓储
     * @param audit 严格审计入口
     * @param transaction 与仓储/审计共用数据源的短事务
     * @param cipher 持久清单AEAD加密器
     * @param changes 本实例文件私有区GC，不由本服务创建或关闭
     */
    public LocalProjectRetentionService(String nodeId,Clock clock,LocalProjectOperationRepository operations,AuditAppender audit,
            TransactionTemplate transaction,ModelCredentialCipher cipher,LocalProjectChangeService changes){
        if(nodeId==null||nodeId.isBlank())throw new IllegalArgumentException("保留期节点不能为空");
        this.nodeId=nodeId;this.clock=Objects.requireNonNull(clock);this.operations=Objects.requireNonNull(operations);
        this.audit=Objects.requireNonNull(audit);this.transaction=Objects.requireNonNull(transaction);
        this.cipher=Objects.requireNonNull(cipher);this.changes=Objects.requireNonNull(changes);
    }

    /** Spring fixedDelay从上一轮结束计时；安全异常保留固定errorId且继续上抛，不能伪装清理成功。 */
    @Scheduled(fixedDelayString="PT5M")
    public void scheduledCleanup(){cleanup();}

    /**
     * 先推进一批文件GC，再处理一批到期命令；不释放宿主持有的文件锁或当前Job。
     * @return 本轮实际擦除的文件日志与命令清单数，识别已有墓碑不重复计数
     * @throws RetentionFailure 任何基础设施、审计或不可核实清单失败，已记录相同errorId的安全诊断
     */
    public synchronized int cleanup(){
        int removed;
        try{removed=changes.cleanupExpired();}
        catch(Exception failure){throw diagnose(null,"FILES",failure);}
        if(sweepCutoff==null)sweepCutoff=clock.instant().minus(RETENTION);
        java.util.List<LocalProjectOperation> candidates;
        try{candidates=operations.findCommandRetentionCandidates(nodeId,sweepCutoff,after,BATCH_SIZE);}
        catch(RuntimeException failure){throw diagnose(null,"DISCOVER",failure);}
        if(candidates.isEmpty()){after=null;sweepCutoff=null;return removed;}
        RetentionFailure firstFailure=null;
        for(var candidate:candidates){
            try{
                if(candidate.kind()!=LocalProjectOperation.Kind.COMMAND||candidate.createdAt().isAfter(sweepCutoff)
                        ||candidate.state()==LocalProjectOperation.State.RUNNING)continue;
                if(erase(candidate))removed++;
            }catch(RuntimeException failure){
                var diagnosed=diagnose(candidate,"COMMAND",failure);
                if(firstFailure==null)firstFailure=diagnosed;
            }finally{
                after=new CommandRetentionCursor(candidate.createdAt(),candidate.id(),candidate.tenantId());
            }
        }
        if(firstFailure!=null)throw firstFailure;
        return removed;
    }

    private boolean erase(LocalProjectOperation discovered){
        return Boolean.TRUE.equals(transaction.execute(status->{
            // 归属取自节点专用数据库发现；条件写入仍逐项绑定tenant/精确owner及原version。
            var current=operations.operation(discovered.tenantId(),discovered.ownerId(),discovered.id()).orElse(null);
            if(current==null||current.version()!=discovered.version()||current.kind()!=LocalProjectOperation.Kind.COMMAND
                    ||!current.projectId().equals(discovered.projectId())||!current.agentId().equals(discovered.agentId())
                    ||!current.conversationId().equals(discovered.conversationId())||!current.runId().equals(discovered.runId())
                    ||!current.digest().equals(discovered.digest())||current.createdAt().plus(RETENTION).isAfter(clock.instant())
                    ||current.state()==LocalProjectOperation.State.RUNNING)return false;
            String marker=expiredMarker(current.id());
            if(cipher.decrypt(current.encryptedPayload()).equals(marker))return false;
            if(Set.of(LocalProjectOperation.State.PROPOSED,LocalProjectOperation.State.WAITING_APPROVAL,LocalProjectOperation.State.PREPARED).contains(current.state())){
                // claim赢得RUNNING会改变version，原版本取消CAS失败后不追读新版本取消真正运行。
                current=operations.advance(current.tenantId(),current.ownerId(),current.id(),current.version(),
                        LocalProjectOperation.State.CANCELLED,clock.instant()).orElse(null);
                if(current==null)return false;
                audit.appendControlled(current.tenantId(),current.ownerId(),"LOCAL_PROJECT_COMMAND_EXPIRE_CANCEL","PROJECT_OPERATION",
                        current.id().toString(),"CANCELLED","runId="+current.runId());
            }
            if(!operations.erasePayload(current.tenantId(),current.ownerId(),current.id(),current.version(),cipher.encrypt(marker),clock.instant()))return false;
            audit.appendControlled(current.tenantId(),current.ownerId(),"LOCAL_PROJECT_COMMAND_EXPIRE","PROJECT_OPERATION",
                    current.id().toString(),"SUCCEEDED","runId="+current.runId()+" digest="+current.digest());
            return true;
        }));
    }

    private static String expiredMarker(UUID id){return "{\"expired\":true,\"operationId\":\""+id+"\"}";}

    /** 保留原始堆栈位置供诊断，移除动态消息/原因链，避免Spring调度器再次打印清单或SQL正文。 */
    private RetentionFailure diagnose(LocalProjectOperation operation,String stage,Exception failure){
        String errorId=UUID.randomUUID().toString();
        var safe=new IllegalStateException("保留期清理未完成，原始动态消息已省略");safe.setStackTrace(failure.getStackTrace());
        log.error("errorId={} operation=LOCAL_PROJECT_RETENTION errorCode=PROJECT_RETENTION_FAILED stage={} tenantId={} principalId={} runId={} resourceType=PROJECT_OPERATION resourceId={} exceptionType={}",
                errorId,stage,operation==null?"-":operation.tenantId(),operation==null?"system:local-project-retention":operation.ownerId(),
                operation==null?"-":operation.runId(),operation==null?"-":operation.id(),failure.getClass().getName(),safe);
        return new RetentionFailure(errorId);
    }

    /** 定时失败只向宿主提供固定分类与可检索编号，不透传密文、路径、SQL或原异常原因链。 */
    public static final class RetentionFailure extends IllegalStateException{
        /** 与安全后台诊断相同的随机关联编号。 */
        private final String errorId;
        private RetentionFailure(String errorId){super("本地项目保留期清理未完成 errorCode=PROJECT_RETENTION_FAILED errorId="+errorId);this.errorId=errorId;}
        /** @return 与本轮失败诊断一致的编号 */
        public String errorId(){return errorId;}
    }
}
