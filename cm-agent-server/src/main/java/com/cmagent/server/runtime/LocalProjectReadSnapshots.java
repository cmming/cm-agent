package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import com.cmagent.core.runtime.LocalProjectPathPolicy;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/**
 * 模型按首次实际读取固定文件版本；浏览器预览仍读实时磁盘，不能把二者混为整目录原子快照。
 * 同一Run串行更新有界加密槽，审批恢复只复用原正文；批准写入后仅从精确意图更新受影响文件。
 */
final class LocalProjectReadSnapshots {
    /** 与工具/AgentState分开的宿主状态键及格式标识。 */
    private static final String KEY="local_project_reads_v1",TYPE="cm-agent-local-project-reads-v1";
    /** 累计明文预算同时约束磁盘原字节和脱敏视图，避免编码/重复引用放大。 */
    private static final int MAX_FILES=32,MAX_BYTES=2_097_152;
    /** 搜索元数据与结果独立限额；五秒为同步文件I/O之间检查的协作期限，不宣称可中断内核调用。 */
    private static final int MAX_SEARCH_ENTRIES=10_000,MAX_SEARCH_MATCHES=100;
    /** 唯一实际文件读取原语，包含私有目录排除及根身份复核。 */ private final LocalProjectFiles files;
    /** 认证加密先于仓储写入，不能用JSON明文落检查点。 */ private final ModelCredentialCipher cipher;
    /** 固定记录结构，无请求驱动多态。 */ private final ObjectMapper json;
    /** 每Run隔离的短时加密状态，恢复使用原持久主密钥。 */ private final RuntimeCheckpointRepository checkpoints;
    /** 固定敏感字段策略，不把原文件正文写入普通轨迹。 */ private final SensitiveDataRedactor redactor;
    /** 统一安全错误、严格审计和同号诊断。 */ private final LocalProjectAccessService access;
    /** 服务端时钟和与审批Store相同的期限，不由客户端续期。 */ private final Clock clock;
    private final Duration ttl;

    LocalProjectReadSnapshots(LocalProjectFiles files,ModelCredentialCipher cipher,ObjectMapper json,RuntimeCheckpointRepository checkpoints,
            SensitiveDataRedactor redactor,LocalProjectAccessService access,Clock clock,Duration ttl) {
        this.files=files;this.cipher=cipher;this.json=json;this.checkpoints=checkpoints;this.redactor=redactor;this.access=access;this.clock=clock;this.ttl=ttl;
    }

    /** 首发即创建空槽，使恢复时能区分“尚未读取”与“已丢失读取快照”，不能按最新目录补造。 */
    synchronized void initialize(PrincipalRef principal,LocalProjectBinding binding) {
        try {
            if(checkpoints.find(binding.tenantId(),user(binding),binding.runId().toString(),KEY).isPresent())throw new IllegalStateException("读取快照已存在");
            var now=clock.instant();save(new Snapshot(binding,List.of()),null,now.plus(ttl),now);
        }catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_READ_SNAPSHOT_CREATE",failure);}
    }

    /** 审批恢复必须先验证空槽/非空槽的完整性，缺失或过期不能读取外部新正文。 */
    synchronized void verify(PrincipalRef principal,LocalProjectBinding binding) { load(principal,binding); }

    /**
     * 只第一次访问文件时读磁盘；后续分页/恢复均来自原加密全文，并验证可选的用户引用版本。
     * @param principal 认证主体
     * @param scope 当前根/owner已复核的可信Run
     * @param path 项目根内相对路径
     * @param offset 脱敏UTF-8视图位置
     * @param maxBytes 单页最多64KiB
     * @param expectedSha256 用户明确引用时的原文件版本，空值表示当前首次版本
     * @return 固定版本的有界脱敏文本
     */
    synchronized LocalProjectService.Text read(PrincipalRef principal,LocalProjectAccessService.Scope scope,String path,int offset,int maxBytes,String expectedSha256) {
        var binding=scope.binding();
        try {
            LocalProjectPathPolicy.requireRelative(path,false);
            if(offset<0||maxBytes<4||maxBytes>65536||expectedSha256!=null&&!expectedSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            var loaded=load(principal,binding);
            var entries=new ArrayList<>(loaded.snapshot().files());
            Frozen file=entries.stream().filter(value->samePath(value.path(),path)).findFirst().orElse(null);
            if(file==null)file=freeze(principal,scope,path,expectedSha256,loaded,entries);
            if(file.deleted())throw access.reject(principal,binding.runId(),"PROJECT_FILE_NOT_FOUND",404,"该文件已在本轮批准的变更中移除");
            if(expectedSha256!=null&&!expectedSha256.equals(file.sha256()))throw access.reject(principal,binding.runId(),"PROJECT_FILE_FILE_CHANGED",409,"引用的文件版本与本轮读取版本不一致，请刷新引用或新建下一轮");
            byte[] safe=file.content().getBytes(StandardCharsets.UTF_8);
            try {
                if(offset>safe.length||offset<safe.length&&(safe[offset]&0xc0)==0x80)throw new IllegalArgumentException();
                int end=Math.min(safe.length,offset+maxBytes);while(end<safe.length&&(safe[end]&0xc0)==0x80)end--;
                return new LocalProjectService.Text(file.path(),file.sha256(),safe.length,offset,end,end<safe.length,file.redacted(),new String(safe,offset,end-offset,StandardCharsets.UTF_8));
            }finally{Arrays.fill(safe,(byte)0);}
        }catch(LocalProjectAccessException failure){throw failure;}
        catch(LocalProjectFileException failure){throw access.fileFailure(principal,binding.runId(),failure);}
        catch(BudgetExceeded failure){throw access.reject(principal,binding.runId(),"PROJECT_FILE_LIMIT_EXCEEDED",422,"本轮文件读取预算已满，请在下一轮拆分任务");}
        catch(IllegalArgumentException failure){throw access.reject(principal,binding.runId(),"PROJECT_READ_RANGE_INVALID",400,"本轮文件引用或读取范围无效");}
        catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_RUN_FILE_READ",failure);}
    }

    /**
     * 模型搜索复用同一首次版本；浏览器的实时搜索保留在{@link LocalProjectService}，不能共享正文通道。
     * 已冻结文件即使被外部移除也仍参与搜索，批准删除的墓碑禁止读取后来重建的同名正文。
     * @param principal 认证主体
     * @param scope 已核验owner、根和Run的可信来源
     * @param query 一至256字符的字面查询
     * @param content 是否检索脱敏正文；每个首次检索的文件完整计入本Run的32文件/2MiB预算
     * @return 最多100命中；预算、不安全文件、不可读目录或协作期限未覆盖的部分明确标记partial
     */
    synchronized LocalProjectService.Search search(PrincipalRef principal,LocalProjectAccessService.Scope scope,String query,boolean content) {
        var binding=scope.binding();
        try {
            if(query==null||query.isBlank()||query.length()>256)
                throw access.reject(principal,binding.runId(),"PROJECT_SEARCH_QUERY_INVALID",400,"搜索内容须为1至256个字符");
            var loaded=load(principal,binding);
            access.record(principal,binding.runId(),"PROJECT_RUN_SEARCH","STARTED");
            if(!content){
                var result=files.search(scope.root(),query,false);
                access.record(principal,binding.runId(),"PROJECT_RUN_SEARCH",result.partial()?"PARTIAL":"SUCCEEDED");
                return new LocalProjectService.Search(result.matches().stream().map(value->new LocalProjectService.Match(value.relativePath(),false,"" )).toList(),
                        result.partial(),result.scanned(),result.skipped());
            }
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            var entries=new ArrayList<>(loaded.snapshot().files());
            var seen=new HashSet<Path>();var matches=new ArrayList<LocalProjectService.Match>();
            int scanned=0,skipped=0;boolean partial=false;
            // 必须先查原快照；实时目录可能已经没有旧路径，不能让外部删除隐式改变本Run正文。
            for(var file:entries){
                seen.add(Path.of(file.path()));scanned++;
                if(!file.deleted())match(matches,file.path(),file.content(),query);
            }
            var pending=new ArrayDeque<String>();pending.add("");
            outer:while(!pending.isEmpty()){
                String directory=pending.removeFirst(),cursor=null;
                do {
                    if(expired(deadline)){partial=true;break outer;}
                    LocalProjectFiles.Page page;
                    try{page=files.list(scope.root(),directory,cursor,200);}
                    catch(LocalProjectFileException failure){
                        if(fatalSearchFailure(failure))throw failure;
                        partial=true;skipped++;
                        if(failure.reason()==LocalProjectFileException.Reason.TIMEOUT)break outer;
                        continue outer;
                    }
                    for(var entry:page.items()){
                        if(expired(deadline)||scanned>=MAX_SEARCH_ENTRIES){partial=true;break outer;}
                        scanned++;
                        if(entry.kind()==LocalProjectFiles.Kind.BLOCKED){partial=true;skipped++;continue;}
                        if(!seen.add(Path.of(entry.path())))continue;
                        if(entry.kind()==LocalProjectFiles.Kind.DIRECTORY){
                            if(entry.path().split("/").length<32)pending.addLast(entry.path());
                            else{partial=true;skipped++;}
                        }
                        if(entry.path().contains(query))matches.add(new LocalProjectService.Match(entry.path(),false,""));
                        else if(entry.readable()){
                            // 满额后不再打开新正文；仍可继续有界路径检索，不能回退实时片段或悄悄挤掉旧版本。
                            if(entries.size()>=MAX_FILES||entry.sizeBytes()>1_048_576||bytes(entries)+entry.sizeBytes()>MAX_BYTES){partial=true;skipped++;continue;}
                            try{var file=freeze(principal,scope,entry.path(),null,loaded,entries);match(matches,file.path(),file.content(),query);}
                            catch(BudgetExceeded failure){partial=true;skipped++;}
                            catch(LocalProjectFileException failure){
                                if(fatalSearchFailure(failure))throw failure;
                                partial=true;skipped++;
                                if(failure.reason()==LocalProjectFileException.Reason.TIMEOUT)break outer;
                            }
                        }
                        if(matches.size()>=MAX_SEARCH_MATCHES){partial=true;break outer;}
                    }
                    cursor=page.nextCursor();
                }while(cursor!=null);
            }
            access.record(principal,binding.runId(),"PROJECT_RUN_SEARCH",partial?"PARTIAL":"SUCCEEDED");
            return new LocalProjectService.Search(matches,partial,scanned,skipped);
        }catch(LocalProjectAccessException failure){throw failure;}
        catch(LocalProjectFileException failure){throw access.fileFailure(principal,binding.runId(),failure);}
        catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_RUN_SEARCH",failure);}
    }

    /** 搜索和显式读取共用“先认证加密持久化、再交付”的首次正文入口，失败不污染原有条目。 */
    private Frozen freeze(PrincipalRef principal,LocalProjectAccessService.Scope scope,String path,String expectedSha256,Loaded loaded,List<Frozen> entries)throws Exception {
        var binding=scope.binding();byte[] raw=null;
        try {
            access.record(principal,binding.runId(),"PROJECT_RUN_FILE_READ","STARTED");
            raw=files.mutationSnapshot(scope.root(),path);
            String original=new String(raw,StandardCharsets.UTF_8),safe=original.isBlank()?original:redactor.redact(original);
            String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
            if(expectedSha256!=null&&!expectedSha256.equals(sha))throw access.reject(principal,binding.runId(),"PROJECT_FILE_FILE_CHANGED",409,"引用的文件版本已变化，请刷新引用或新建下一轮");
            var file=new Frozen(path,sha,safe,raw.length,!safe.equals(original),false);
            var updated=new ArrayList<>(entries);updated.add(file);requireBudget(updated);
            save(new Snapshot(binding,updated),loaded.row(),loaded.row().expiresAt(),clock.instant());
            entries.add(file);
            access.record(principal,binding.runId(),"PROJECT_RUN_FILE_READ","SUCCEEDED");
            return file;
        }finally{if(raw!=null)Arrays.fill(raw,(byte)0);}
    }
    private static boolean expired(long deadline){return System.nanoTime()>=deadline||Thread.currentThread().isInterrupted();}
    private static boolean fatalSearchFailure(LocalProjectFileException failure){
        return failure.reason()==LocalProjectFileException.Reason.ROOT_CHANGED||failure.reason()==LocalProjectFileException.Reason.UNAVAILABLE;
    }
    private static void match(List<LocalProjectService.Match> matches,String path,String body,String query){
        if(path.contains(query)){matches.add(new LocalProjectService.Match(path,false,""));return;}
        int index=body.indexOf(query);
        if(index>=0)matches.add(new LocalProjectService.Match(path,true,body.substring(Math.max(0,index-40),Math.min(body.length(),index+query.length()+40))));
    }

    /** 在文件副作用之前核验写后快照预算；预算不足不能等文件已改后才作为普通参数错误返回。 */
    synchronized void validateApply(PrincipalRef principal,LocalProjectBinding binding,LocalProjectChangeService.Preview intent) {
        updated(principal,load(principal,binding).snapshot(),intent);
    }

    /** 写入成功后只从精确意图更新受影响文件，包括本轮尚未读取的新文件，不重新打开最新磁盘。 */
    synchronized void afterApply(PrincipalRef principal,LocalProjectBinding binding,LocalProjectChangeService.Preview intent) {
        var loaded=load(principal,binding);
        try{save(updated(principal,loaded.snapshot(),intent),loaded.row(),loaded.row().expiresAt(),clock.instant());}
        catch(LocalProjectAccessException failure){throw failure;}
        catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_READ_SNAPSHOT_UPDATE",failure);}
    }
    private Snapshot updated(PrincipalRef principal,Snapshot source,LocalProjectChangeService.Preview intent) {
        var binding=source.binding();var entries=new ArrayList<>(source.files());
        try {
            for(var change:intent.changes()){
                entries.removeIf(value->samePath(value.path(),change.path())||change.target()!=null&&samePath(value.path(),change.target()));
                switch(change.kind()){
                    case "CREATE_FILE","REPLACE_FILE" -> entries.add(frozen(change.path(),change.after()));
                    case "MOVE_FILE" -> {entries.add(new Frozen(change.path(),change.beforeVersion(),"",0,false,true));entries.add(frozen(change.target(),change.after()));}
                    case "DELETE_FILE" -> entries.add(new Frozen(change.path(),change.beforeVersion(),"",0,false,true));
                    default -> { /* 目录动作不构造文件正文，后续目录元数据仍由显式浏览获取。 */ }
                }
            }
            requireBudget(entries);return new Snapshot(binding,entries);
        }catch(BudgetExceeded failure){throw access.reject(principal,binding.runId(),"PROJECT_FILE_LIMIT_EXCEEDED",422,"本轮文件版本缓存预算已满，请在下一轮拆分变更");
        }catch(LocalProjectAccessException failure){throw failure;}
        catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_READ_SNAPSHOT_UPDATE",failure);}
    }

    /** 每次新的ASK沿用相同正文，只更新到本次审批的固定期限；普通轮询不能续期。 */
    synchronized void retain(PrincipalRef principal,LocalProjectBinding binding,Instant expires) {
        var loaded=load(principal,binding);
        try{save(loaded.snapshot(),loaded.row(),expires,clock.instant());}
        catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_READ_SNAPSHOT_RETAIN",failure);}
    }
    /** 终态/取消清理短时缓存，保留项目/操作的长期元数据和受控恢复日志。 */
    synchronized void cleanup(LocalProjectBinding binding){checkpoints.delete(binding.tenantId(),user(binding),binding.runId().toString(),KEY);}
    /** 准备阶段即使长期来源损坏也能清理本主体的短时槽，不依赖可被删除的绑定。 */
    synchronized void cleanup(PrincipalRef principal,RunRecord run){
        if(!principal.tenantId().equals(run.tenantId())||!principal.principalId().equals(run.principalId()))throw new SecurityException("运行清理归属不匹配");
        checkpoints.delete(run.tenantId(),run.tenantId()+":"+run.principalId(),run.id().toString(),KEY);
    }

    private Loaded load(PrincipalRef principal,LocalProjectBinding binding) {
        try {
            var row=checkpoints.find(binding.tenantId(),user(binding),binding.runId().toString(),KEY).orElseThrow();
            if(row.listPayload()||!TYPE.equals(row.stateType())||!row.expiresAt().isAfter(clock.instant())||row.encryptedPayload().length()>12_000_000)throw new IllegalStateException();
            Snapshot snapshot=json.readValue(cipher.decrypt(row.encryptedPayload()),Snapshot.class);
            if(!snapshot.binding().equals(binding))throw new IllegalStateException();requireBudget(snapshot.files());return new Loaded(row,snapshot);
        }catch(Exception failure){throw access.infrastructure(principal,binding.runId(),"PROJECT_READ_SNAPSHOT_LOAD",failure);}
    }
    private void save(Snapshot snapshot,RuntimeCheckpoint prior,Instant expires,Instant now)throws Exception {
        checkpoints.save(new RuntimeCheckpoint(prior==null?UUID.randomUUID():prior.id(),snapshot.binding().tenantId(),user(snapshot.binding()),snapshot.binding().runId().toString(),
                KEY,TYPE,false,cipher.encrypt(json.writeValueAsString(snapshot)),expires,prior==null?now:prior.createdAt(),now));
    }
    private Frozen frozen(String path,String body)throws Exception {
        String safe=body.isBlank()?body:redactor.redact(body);byte[] raw=body.getBytes(StandardCharsets.UTF_8);
        try{return new Frozen(path,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)),safe,raw.length,!safe.equals(body),false);}
        finally{Arrays.fill(raw,(byte)0);}
    }
    private static void requireBudget(List<Frozen> files) {
        if(files.size()>MAX_FILES||bytes(files)>MAX_BYTES)throw new BudgetExceeded();
    }
    private static long bytes(List<Frozen> files){long bytes=0;for(var file:files)bytes+=Math.max(file.sourceBytes(),file.content().getBytes(StandardCharsets.UTF_8).length);return bytes;}
    private static boolean samePath(String left,String right){return Path.of(left).equals(Path.of(right));}
    private static String user(LocalProjectBinding binding){return binding.tenantId()+":"+binding.ownerId();}
    /** @param binding 固定Run/主体/根来源 @param files 首次读取及已批准写后形成的版本，原文只入加密槽 */
    private record Snapshot(LocalProjectBinding binding,List<Frozen> files){Snapshot{files=List.copyOf(files);}@Override public String toString(){return "Snapshot[受保护项目读取]";}}
    /** @param path 原根内相对路径 @param sha256 原字节版本 @param content 脱敏全文 @param sourceBytes 原物理字节预算 @param redacted 是否已脱敏 @param deleted 本轮批准动作形成的移除见证 */
    private record Frozen(String path,String sha256,String content,int sourceBytes,boolean redacted,boolean deleted){@Override public String toString(){return "Frozen[受保护项目文件]";}}
    /** @param row 未解密检查点元数据 @param snapshot 已认证且核对绑定的内容 */
    private record Loaded(RuntimeCheckpoint row,Snapshot snapshot){}
    /** 与输入范围错误区分，超预算要求拆分任务，不能伪装成文件不存在。 */
    private static final class BudgetExceeded extends IllegalArgumentException{}
}
