package com.cmagent.server.store;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.SkillArtifactRepository;
import java.time.*;
import java.util.*;
/** 仅 local/test 使用；同步预留与租约模拟数据库条件更新，重启不保留元数据。 */
public final class InMemorySkillArtifactRepository implements SkillArtifactRepository {
    /** 所有访问由实例监视器串行化，不能将此实现作为跨实例配额。 */
    private final Map<UUID,SkillArtifact> rows=new LinkedHashMap<>();
    public synchronized boolean reserve(SkillArtifact a,long runLimit,long tenantLimit,int runFiles,int tenantFiles) {
        var active=rows.values().stream().filter(x->x.tenantId().equals(a.tenantId())&&x.status()!=SkillArtifactStatus.DELETED).toList();
        // Run 预算累计计算，补偿删除不退还，以免审批恢复反复生成绕过总预算。
        var run=rows.values().stream().filter(x->x.tenantId().equals(a.tenantId())&&x.runId().equals(a.runId())).toList();
        if(active.size()>=tenantFiles||run.size()>=runFiles
            ||active.stream().mapToLong(SkillArtifact::sizeBytes).sum()+a.sizeBytes()>tenantLimit
            ||run.stream().mapToLong(SkillArtifact::sizeBytes).sum()+a.sizeBytes()>runLimit
            ||rows.containsKey(a.id())||rows.values().stream().anyMatch(x->x.tenantId().equals(a.tenantId())
                &&x.runId().equals(a.runId())&&x.callId().equals(a.callId())&&x.filename().equals(a.filename()))) return false;
        rows.put(a.id(),a);return true;
    }
    public synchronized Optional<SkillArtifact> find(UUID tenant,UUID id) {
        return Optional.ofNullable(rows.get(id)).filter(a->a.tenantId().equals(tenant));
    }
    public synchronized List<SkillArtifact> list(UUID tenant,UUID run) {
        return rows.values().stream().filter(a->a.tenantId().equals(tenant)&&a.runId().equals(run)).limit(64).toList();
    }
    public synchronized boolean stored(UUID tenant,UUID id,String hash) {
        var a=find(tenant,id).orElse(null);if(a==null||a.status()!=SkillArtifactStatus.STAGING)return false;
        rows.put(id,a.change(SkillArtifactStatus.PENDING,hash,a.expiresAt(),null,null));return true;
    }
    public synchronized void publish(UUID tenant,UUID run,Instant expiry) {
        for(var a:list(tenant,run))if(a.status()==SkillArtifactStatus.PENDING)
            rows.put(a.id(),a.change(SkillArtifactStatus.READY,a.sha256(),expiry,null,null));
    }
    public synchronized void discard(UUID tenant,UUID run) {
        for(var a:list(tenant,run))if(a.status()!=SkillArtifactStatus.DELETED)
            rows.put(a.id(),a.change(SkillArtifactStatus.DELETING,a.sha256(),a.expiresAt(),a.leaseToken(),a.leaseUntil()));
    }
    public synchronized void discardCall(UUID tenant,UUID run,String call) {
        for(var a:list(tenant,run))if(a.callId().equals(call)&&a.status()!=SkillArtifactStatus.DELETED)
            rows.put(a.id(),a.change(SkillArtifactStatus.DELETING,a.sha256(),a.expiresAt(),a.leaseToken(),a.leaseUntil()));
    }
    public synchronized boolean lease(UUID tenant,UUID id,UUID token,Instant now,Instant until,boolean deleting) {
        var a=find(tenant,id).orElse(null);
        if(a==null||a.status()==SkillArtifactStatus.DELETED || a.leaseUntil()!=null&&a.leaseUntil().isAfter(now))return false;
        if(!deleting && (a.status()!=SkillArtifactStatus.READY || !a.expiresAt().isAfter(now)))return false;
        if(deleting && a.status()!=SkillArtifactStatus.DELETING && a.expiresAt().isAfter(now))return false;
        rows.put(id,a.change(deleting?SkillArtifactStatus.DELETING:a.status(),a.sha256(),a.expiresAt(),token,until));return true;
    }
    public synchronized void release(UUID tenant,UUID id,UUID token,boolean deleted) {
        var a=find(tenant,id).orElse(null);
        if(a!=null&&token.equals(a.leaseToken()))rows.put(id,a.change(deleted?SkillArtifactStatus.DELETED:a.status(),a.sha256(),a.expiresAt(),null,null));
    }
    public synchronized List<SkillArtifact> cleanupCandidates(Instant now,Instant abandoned) {
        return rows.values().stream().filter(a->a.status()!=SkillArtifactStatus.DELETED &&
            (a.status()==SkillArtifactStatus.DELETING||!a.expiresAt().isAfter(now)||a.createdAt().isBefore(abandoned)&&a.status()!=SkillArtifactStatus.READY))
            .limit(100).toList();
    }
}
