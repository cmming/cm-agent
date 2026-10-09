package com.cmagent.persistence;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.SkillArtifactRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;
/** 元数据短事务；租户行锁串行化跨实例配额预留，任何文件 I/O 必须在此事务外。 */
public final class JdbcSkillArtifactRepository implements SkillArtifactRepository {
    /** 与项目现有事务管理器共享的 JDBC 客户端。 */
    private final JdbcClient jdbc;
    /** 预留使用独立短事务并参与已有运行完成事务。 */
    private final TransactionTemplate tx;
    public JdbcSkillArtifactRepository(JdbcClient jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    public boolean reserve(SkillArtifact a,long runLimit,long tenantLimit,int runFiles,int tenantFiles) {
        return Boolean.TRUE.equals(tx.execute(status->{
            // 租户来自可信 Run；锁同一租户固定行，使多个实例不能同时绕过 SUM 配额。
            if(jdbc.sql("SELECT id FROM tenants WHERE id=:tenant FOR UPDATE").param("tenant",a.tenantId().toString()).query(String.class).optional().isEmpty())return false;
            long tenantBytes=usage(a.tenantId(),null,"COALESCE(SUM(size_bytes),0)");
            long runBytes=usage(a.tenantId(),a.runId(),"COALESCE(SUM(size_bytes),0)");
            if(tenantBytes+a.sizeBytes()>tenantLimit||runBytes+a.sizeBytes()>runLimit
                ||usage(a.tenantId(),null,"COUNT(*)")>=tenantFiles||usage(a.tenantId(),a.runId(),"COUNT(*)")>=runFiles)return false;
            if(jdbc.sql("SELECT COUNT(*) FROM skill_artifacts WHERE tenant_id=:tenant AND run_id=:run AND call_id=:call AND filename=:name")
                .param("tenant",a.tenantId().toString()).param("run",a.runId().toString()).param("call",a.callId()).param("name",a.filename()).query(Long.class).single()>0)return false;
            jdbc.sql("""
                INSERT INTO skill_artifacts(id,tenant_id,principal_id,agent_id,run_id,run_kind,skill_id,version_id,call_id,
                    filename,media_type,size_bytes,sha256,status,created_at,expires_at)
                VALUES(:id,:tenant,:principal,:agent,:run,:kind,:skill,:version,:call,:name,:type,:size,:hash,:status,:created,:expires)
                """).param("id",a.id().toString()).param("tenant",a.tenantId().toString()).param("principal",a.principalId())
                .param("agent",a.agentId().toString()).param("run",a.runId().toString()).param("kind",a.runKind().name())
                .param("skill",a.skillId().toString()).param("version",a.versionId().toString()).param("call",a.callId())
                .param("name",a.filename()).param("type",a.mediaType()).param("size",a.sizeBytes()).param("hash",a.sha256())
                .param("status",a.status().name()).param("created",Timestamp.from(a.createdAt())).param("expires",Timestamp.from(a.expiresAt())).update();
            return true;
        }));
    }
    /** 表达式只来自本类固定常量，不能接受客户端 SQL。DELETING 仍占用直到实际删除确认。 */
    private long usage(UUID tenant,UUID run,String expression) {
        // 租户容量在实际删除后归还；Run 累计预算不归还，防止失败重试和审批恢复绕过。
        var spec=jdbc.sql("SELECT "+expression+" FROM skill_artifacts WHERE tenant_id=:tenant"+(run==null?" AND status<>'DELETED'":" AND run_id=:run"))
            .param("tenant",tenant.toString());
        if(run!=null)spec=spec.param("run",run.toString());
        return spec.query(Long.class).single();
    }
    public Optional<SkillArtifact> find(UUID tenant,UUID id){
        return jdbc.sql("SELECT * FROM skill_artifacts WHERE tenant_id=:tenant AND id=:id")
            .param("tenant",tenant.toString()).param("id",id.toString()).query(this::map).optional();
    }
    public List<SkillArtifact> list(UUID tenant,UUID run){
        return jdbc.sql("SELECT * FROM skill_artifacts WHERE tenant_id=:tenant AND run_id=:run ORDER BY created_at,id LIMIT 64")
            .param("tenant",tenant.toString()).param("run",run.toString()).query(this::map).list();
    }
    public boolean stored(UUID tenant,UUID id,String hash){
        return jdbc.sql("UPDATE skill_artifacts SET status='PENDING',sha256=:hash WHERE tenant_id=:tenant AND id=:id AND status='STAGING'")
            .param("hash",hash).param("tenant",tenant.toString()).param("id",id.toString()).update()==1;
    }
    public void publish(UUID tenant,UUID run,Instant expires){
        jdbc.sql("UPDATE skill_artifacts SET status='READY',expires_at=:expires WHERE tenant_id=:tenant AND run_id=:run AND status='PENDING'")
            .param("expires",Timestamp.from(expires)).param("tenant",tenant.toString()).param("run",run.toString()).update();
    }
    public void discard(UUID tenant,UUID run){
        jdbc.sql("UPDATE skill_artifacts SET status='DELETING' WHERE tenant_id=:tenant AND run_id=:run AND status<>'DELETED'")
            .param("tenant",tenant.toString()).param("run",run.toString()).update();
    }
    public void discardCall(UUID tenant,UUID run,String call){
        jdbc.sql("UPDATE skill_artifacts SET status='DELETING' WHERE tenant_id=:tenant AND run_id=:run AND call_id=:call AND status<>'DELETED'")
            .param("tenant",tenant.toString()).param("run",run.toString()).param("call",call).update();
    }
    public boolean lease(UUID tenant,UUID id,UUID token,Instant now,Instant until,boolean deleting){
        // 条件更新同时获取租约与删除状态；旧实例只能凭令牌释放自己的租约。
        return jdbc.sql("UPDATE skill_artifacts SET lease_token=:token,lease_until=:until"+(deleting?",status='DELETING'":"")
            +" WHERE tenant_id=:tenant AND id=:id AND status<>'DELETED' AND (lease_until IS NULL OR lease_until<=:now)"
            +(deleting?" AND (status='DELETING' OR expires_at<=:now)":" AND status='READY' AND expires_at>:now"))
            .param("token",token.toString()).param("until",Timestamp.from(until)).param("tenant",tenant.toString())
            .param("id",id.toString()).param("now",Timestamp.from(now)).update()==1;
    }
    public void release(UUID tenant,UUID id,UUID token,boolean deleted){
        jdbc.sql("UPDATE skill_artifacts SET lease_token=NULL,lease_until=NULL"+(deleted?",status='DELETED'":"")
            +" WHERE tenant_id=:tenant AND id=:id AND lease_token=:token")
            .param("tenant",tenant.toString()).param("id",id.toString()).param("token",token.toString()).update();
    }
    public List<SkillArtifact> cleanupCandidates(Instant now,Instant abandoned){
        // 仅服务端维护扫描允许跨租户；不向 Controller 暴露，也不返回文件内容。
        return jdbc.sql("""
            SELECT * FROM skill_artifacts WHERE status<>'DELETED'
                AND (status='DELETING' OR expires_at<=:now OR (status IN ('STAGING','PENDING') AND created_at<:abandoned))
                AND (lease_until IS NULL OR lease_until<=:now) ORDER BY created_at LIMIT 100
            """).param("now",Timestamp.from(now)).param("abandoned",Timestamp.from(abandoned)).query(this::map).list();
    }
    private SkillArtifact map(ResultSet r,int index)throws SQLException{
        String lease=r.getString("lease_token");Timestamp until=r.getTimestamp("lease_until");
        return new SkillArtifact(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("tenant_id")),r.getString("principal_id"),
            UUID.fromString(r.getString("agent_id")),UUID.fromString(r.getString("run_id")),RunKind.valueOf(r.getString("run_kind")),
            UUID.fromString(r.getString("skill_id")),UUID.fromString(r.getString("version_id")),r.getString("call_id"),
            r.getString("filename"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("sha256"),SkillArtifactStatus.valueOf(r.getString("status")),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),lease==null?null:UUID.fromString(lease),until==null?null:until.toInstant());
    }
}
