package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.WorkspaceImportRepository;
import com.cmagent.core.runtime.*;
import com.cmagent.server.config.WorkspaceContextProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * IMPORTED专用运行输入入口，永不把用户相对路径传给服务器目录读取器。
 * READ租约只覆盖实际解密/恢复完整性复核片段；模型与ASK引用独立加密Run快照，不持续引用导入文件。
 */
@Service
public final class WorkspaceImportRuntime {
    /** 父开关与预算仍是可信部署策略；关闭新上传不阻止已冻结Run排空。 */
    private final WorkspaceContextProperties policy;
    /** 原主体/Agent限定的不可变清单及跨实例租约。 */
    private final WorkspaceImportRepository rows;
    /** 独立私有密文存储，关闭新上传后仍应保留配置及密钥。 */
    private final ObjectProvider<WorkspaceImportStorage> storage;
    /** 仅服务端时钟决定期限与租约。 */
    private final Clock clock;

    /**
     * @param policy 部署策略
     * @param rows 持久导入仓储
     * @param storage 宿主持有的私有存储
     */
    @Autowired public WorkspaceImportRuntime(WorkspaceContextProperties policy,WorkspaceImportRepository rows,ObjectProvider<WorkspaceImportStorage> storage){
        this(policy,rows,storage,Clock.systemUTC());
    }
    /** 测试可固定时钟；生产不接受HTTP时间覆盖。 */
    WorkspaceImportRuntime(WorkspaceContextProperties policy,WorkspaceImportRepository rows,ObjectProvider<WorkspaceImportStorage> storage,Clock clock){
        this.policy=policy;this.rows=rows;this.storage=storage;this.clock=clock;
    }

    /**
     * 只校验元数据，不读取正文；原运行恢复独立于新上传开关。
     * @param run 可信原运行
     * @param selected 长期固定的IMPORTED元数据
     * @return 当前仍有效且身份不变的原批次
     */
    WorkspaceImport require(RunRecord run,WorkspaceBinding selected){
        if(selected==null || selected.selection().mode()!=WorkspaceSelection.Mode.IMPORTED
                || !selected.tenantId().equals(run.tenantId()) || !selected.agentId().equals(run.agentId())
                || !selected.principalId().equals(run.principalId()) || !selected.resourceId().equals(run.id()))
            throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原运行导入选择缺失或身份错配");
        if(!policy.isEnabled())throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_REVOKED,"工作区参考资料已由部署者关闭");
        var scope=new WorkspaceImportScope(run.tenantId(),run.principalId(),run.agentId());
        var batch=rows.find(scope,selected.selection().workspaceId()).orElseThrow(()->rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原导入批次元数据缺失"));
        if(!batch.usable(clock.instant()))throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_REVOKED,"原导入已撤销或过期，请重新导入");
        if(!batch.manifestDigest().equals(selected.mappingDigest()))throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_REVOKED,"原导入清单身份已变化");
        if(batch.files().size()>policy.getMaxFiles() || batch.totalBytes()>policy.getMaxTotalBytes()
                || batch.files().stream().anyMatch(file->file.sizeBytes()>policy.getMaxFileBytes()))
            throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_LIMIT_EXCEEDED,"原导入超出当前运行预算");
        return batch;
    }

    /**
     * @param run 可信原运行
     * @param selected 固定来源，不能取当前会话的新选择
     * @return 调用方负责关闭的短期读取引用，GC不能在该引用有效期间删除对象
     */
    Read open(RunRecord run,WorkspaceBinding selected){
        var batch=require(run,selected);var objects=storage.getIfAvailable();
        if(objects==null)throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE,"原导入私有存储不可用");
        var lease=rows.acquire(batch.scope(),batch.id(),WorkspaceImportLease.Kind.READ,Duration.ofMinutes(1),clock.instant())
                .orElseThrow(()->rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE,"导入正在回收或读取引用已满"));
        return new Read(run,selected,batch,objects,lease);
    }

    /** 仅比较不可变清单，不重读源文件，也不允许用有效加密的子集替换原Run正文。 */
    void match(RunRecord run,WorkspaceImport batch,WorkspaceContextSnapshot snapshot){
        if(snapshot.resources().size()!=batch.files().size())throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原快照文件集合与固定清单不一致");
        for(int i=0;i<batch.files().size();i++){
            var file=batch.files().get(i);var resource=snapshot.resources().get(i);
            if(!file.relativePath().equals(resource.path()) || file.sizeBytes()!=resource.sizeBytes() || !file.sha256().equals(resource.sha256()))
                throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原快照文件身份与固定清单不一致");
        }
    }

    /** 实际文件访问期间持有的引用，不跨模型调用、长事务或审批等待保存。 */
    final class Read implements AutoCloseable {
        /** 原Run用于同号诊断及身份复核。 */ private final RunRecord run;
        /** 固定来源，不能在读取中替换。 */ private final WorkspaceBinding selected;
        /** 获取租约时的不可变清单。 */ private final WorkspaceImport batch;
        /** 宿主创建的私有存储，此对象不负责关闭存储实例。 */ private final WorkspaceImportStorage objects;
        /** 只允许释放本令牌，不释放其他Run/读取者。 */ private final WorkspaceImportLease lease;
        /** 除字节硬界外也限制实际读取片段耗时；不把模型耗时算作文件读取。 */ private final long deadline;
        private Read(RunRecord run,WorkspaceBinding selected,WorkspaceImport batch,WorkspaceImportStorage objects,WorkspaceImportLease lease){
            this.run=run;this.selected=selected;this.batch=batch;this.objects=objects;this.lease=lease;this.deadline=System.nanoTime()+policy.getReadTimeout().toNanos();
        }
        /** @return 固定输入期限，不把Run快照续到原导入期限之后 */
        Instant expiresAt(){return batch.expiresAt();}
        /** @return 首发经真实GCM/长度/hash校验的资料，最多当前预算，不静默截断 */
        List<WorkspaceContextResource> resources() throws IOException {
            var result=new ArrayList<WorkspaceContextResource>();
            for(var file:batch.files()){
                budget();
                byte[] bytes=objects.readVerified(batch,file);
                try{budget();result.add(new WorkspaceContextResource(file.relativePath(),new String(bytes,StandardCharsets.UTF_8),bytes.length,file.sha256()));}
                finally{Arrays.fill(bytes,(byte)0);}
            }
            finish();return List.copyOf(result);
        }
        /**
         * 恢复只核对原对象完整性；返回模型的正文必须继续使用original，不能以读到的源对象替换。
         * @param original 已解密并核对可信身份的原Run快照
         */
        void verify(WorkspaceContextSnapshot original) throws IOException {
            match(run,batch,original);
            for(int i=0;i<batch.files().size();i++){
                budget();var file=batch.files().get(i);
                byte[] bytes=objects.readVerified(batch,file);try{budget();}finally{Arrays.fill(bytes,(byte)0);}
            }
            finish();
        }
        /** 读后重查撤销/期限及本令牌，封住读中撤销与租约过期后GC的窗口。 */
        void finish(){
            if(Thread.currentThread().isInterrupted())throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE,"导入读取已中断");
            require(run,selected);
            if(!rows.renew(batch.scope(),batch.id(),lease.token(),Duration.ofMinutes(1),clock.instant()))
                throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE,"原导入读取租约已失效");
        }
        private void budget(){if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline)
            throw rejected(run,ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE,"导入读取超时或已中断");}
        @Override public void close(){rows.release(batch.scope(),batch.id(),lease.token());}
    }
    private static WorkspaceContextException rejected(RunRecord run,ApiErrorCode code,String message){
        return new WorkspaceContextException(code,message,run.id().toString());
    }
}
