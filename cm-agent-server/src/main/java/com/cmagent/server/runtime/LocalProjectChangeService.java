package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 本机变更的受保护意图及单次批准消费服务，不接受客户端根路径或批准布尔值。
 * 文件I/O在事务外；批准后的PREPARED条件版本只能赢得一次RUNNING派发权。
 * 任何已派发但未完整记录的结果转UNKNOWN，不自动重放。仅在受控本机配置通过门禁后装配。
 */
public final class LocalProjectChangeService implements AutoCloseable {
    /** 已核实私有项目元数据。 */ private final LocalProjectRepository projects;
    /** 持久操作意图及条件状态。 */ private final LocalProjectOperationRepository operations;
    /** 受信文件原语。 */ private final LocalProjectFiles files;
    /** 受保护清单加密器，不持有或输出原始密钥。 */ private final ModelCredentialCipher cipher;
    /** 只序列化当前受信信封，不使用HTTP对象覆盖上下文。 */ private final ObjectMapper mapper;
    /** 严格审计，基础设施错误不得降级成普通文件失败。 */ private final AuditAppender audit;
    /** 与仓储共用同一数据源的短事务，不包含文件I/O。 */ private final TransactionTemplate transaction;
    /** 服务端固定时间来源。 */ private final Clock clock;
    /** 固定本机执行节点。 */ private final String nodeId;
    /** 私有持久日志区，不写进用户项目。 */ private final Path state;
    /** 节点变更区独占锁，防止同状态目录的两服务并发写入。 */
    private final java.nio.channels.FileChannel lockChannel;
    /** 持有至服务关闭，不删除恢复记录。 */
    private final java.nio.channels.FileLock stateLock;
    /** GC跨批次保留目录游标，最多根/租户/项目三层；避免每轮重扫固定首批造成后续记录饥饿。 */
    private final Deque<GcDirectory> gcDirectories=new ArrayDeque<>();
    /** 仅记录固定分类和可信编号，不复制异常动态消息。 */
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LocalProjectChangeService.class);

    public LocalProjectChangeService(LocalProjectRepository projects, LocalProjectOperationRepository operations,
            ModelCredentialCipher cipher, ObjectMapper mapper, AuditAppender audit, TransactionTemplate transaction,
            Clock clock, String nodeId, Path stateDirectory) throws IOException {
        this(projects,operations,cipher,mapper,audit,transaction,clock,nodeId,stateDirectory,new LocalProjectFiles(List.of(stateDirectory.toAbsolutePath().normalize())));
    }

    /** 宿主复用完整私有目录排除策略，不能只保护changes子目录而让父项目访问其他状态文件。 */
    LocalProjectChangeService(LocalProjectRepository projects, LocalProjectOperationRepository operations,
            ModelCredentialCipher cipher, ObjectMapper mapper, AuditAppender audit, TransactionTemplate transaction,
            Clock clock, String nodeId, Path stateDirectory, LocalProjectFiles files) throws IOException {
        this.projects=Objects.requireNonNull(projects); this.operations=Objects.requireNonNull(operations);
        this.cipher=Objects.requireNonNull(cipher); this.mapper=Objects.requireNonNull(mapper);
        this.audit=Objects.requireNonNull(audit); this.transaction=Objects.requireNonNull(transaction);
        this.clock=Objects.requireNonNull(clock); this.nodeId=Objects.requireNonNull(nodeId);
        this.state=stateDirectory.toAbsolutePath().normalize();
        this.files=Objects.requireNonNull(files);
        Files.createDirectories(this.state);
        privatePath(this.state);
        this.lockChannel=java.nio.channels.FileChannel.open(this.state.resolve(".changes.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        privatePath(this.state.resolve(".changes.lock"));
        java.nio.channels.FileLock held=null;
        try {
            held=lockChannel.tryLock();
            if(held==null)throw new IOException("本机变更恢复区已经被另一服务占用");
        } catch(IOException|RuntimeException failure){lockChannel.close();throw new IOException("本机变更恢复区不能独占使用");}
        this.stateLock=held;
    }

    /**
     * 生成不可变意图，正文只进入密文和受控preview，不进入View或审计。
     * @param principal 认证主体
     * @param binding 可信固定Run来源
     * @param root 主机解密并核验后的根，不从HTTP构造
     * @param requests 有界动作，未批准时绝不修改项目
     * @return 不含正文的安全意图视图
     */
    public synchronized View propose(PrincipalRef principal, LocalProjectBinding binding, LocalProjectDirectoryTickets.Root root,
            List<Change> requests) throws IOException {
        return proposeTrusted(principal,binding,root,requests,Map.of());
    }
    private View proposeTrusted(PrincipalRef principal, LocalProjectBinding binding, LocalProjectDirectoryTickets.Root root,
            List<Change> requests,Map<String,WindowsLocalProjectSecurity.Snapshot> restore) throws IOException {
        authorize(principal,binding,root);
        if(operations.hasUncertainOperation(binding.tenantId(),binding.ownerId(),binding.projectId(),null))
            throw new IOException("项目存在未核对的执行结果，请先核对原操作");
        var changes=requests.stream().map(value->new LocalProjectChangePlanner.Change(
                LocalProjectChangePlanner.Kind.valueOf(value.kind()),value.path(),value.target(),value.content(),value.expectedSha256())).toList();
        var plan=new LocalProjectChangePlanner(files).plan(root,changes);
        var envelope=new Envelope(binding,plan,restore);
        String json=mapper.writeValueAsString(envelope);
        String encrypted=cipher.encrypt(json);
        Instant now=clock.instant();
        var operation=new LocalProjectOperation(UUID.randomUUID(),binding.tenantId(),binding.ownerId(),binding.projectId(),
                binding.agentId(),binding.conversationId(),binding.runId(),LocalProjectOperation.Kind.FILE_CHANGE,
                hash(json),encrypted,LocalProjectOperation.State.PROPOSED,1,now,now);
        // 密文预算计费，比明文额度更保守；先检查再持久化，GC只处理本服务已建立的日志。
        if(retainedBytes(binding)>100L*1024*1024-encrypted.getBytes(StandardCharsets.UTF_8).length*2L)
            throw new IOException("变更恢复区容量不足，请先核对并清理到期记录");
        transaction.executeWithoutResult(status->{
            authorize(principal,binding,root);
            if(!operations.insertOperation(operation))throw new IllegalStateException("变更意图未能新增");
            audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_PROPOSE","PROJECT_OPERATION",
                    operation.id().toString(),"SUCCEEDED","runId="+binding.runId()+" digest="+operation.digest()+" count="+plan.changes().size());
        });
        try {journal(binding,operation,"PROPOSED",0);}
        catch(IOException failure){
            operations.advance(binding.tenantId(),binding.ownerId(),operation.id(),1,LocalProjectOperation.State.CANCELLED,clock.instant());
            throw new IOException("变更恢复记录未能持久化，提案已取消");
        }
        return view(operation,plan.changes().size());
    }

    /** 只允许原主体读取受保护差异；调用方仍必须在HTTP交付前做凭据字段脱敏。 */
    public Preview preview(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,UUID id) throws IOException {
        authorize(principal,binding,root);
        var operation=owned(binding,id);
        var envelope=decode(binding,operation);
        return new Preview(view(operation,envelope.plan().changes().size()),envelope.plan().changes().stream()
                .map(value->new Diff(value.kind().name(),value.path(),value.target(),value.beforeVersion(),value.before(),value.after())).toList());
    }

    /**
     * 只消费由可信审批链推进到PREPARED的精确意图；不接受HTTP approved字段。
     * @param expectedVersion 审批后持久版本，不自动读取最新版本绕过竞争
     */
    public synchronized View apply(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,UUID id,long expectedVersion) throws IOException {
        authorize(principal,binding,root);
        var current=owned(binding,id);
        if(operations.hasUncertainOperation(binding.tenantId(),binding.ownerId(),binding.projectId(),id))
            throw new IOException("项目存在未核对的执行结果，请先核对原操作");
        var envelope=decode(binding,current);
        if(!current.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant()))throw new IOException("恢复期限已到，请重新生成提案");
        if(current.state()!=LocalProjectOperation.State.PREPARED || current.version()!=expectedVersion)
            throw new IOException("变更未获精确批准或状态已经变化，请核对操作");
        // 不支持的原生动作在取得派发权之前拒绝，不能对外谎报完整应用。
        for(var delta:envelope.plan().changes())
            if((delta.kind()==LocalProjectChangePlanner.Kind.DELETE_EMPTY_DIRECTORY
                    ||delta.kind()==LocalProjectChangePlanner.Kind.DELETE_FILE)&&(delta.security()==null||!delta.security().restorable()))
                throw new IOException("原对象的权限归属无法安全恢复，拒绝删除，请人工核对权限");
        var running=transaction.execute(status->{
            authorize(principal,binding,root);
            var value=operations.claimPrepared(binding.tenantId(),binding.ownerId(),id,expectedVersion,clock.instant())
                    .orElseThrow(()->new IllegalStateException("变更派发条件已失效"));
            audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_APPLY","PROJECT_OPERATION",id.toString(),
                    "STARTED","runId="+binding.runId()+" digest="+current.digest());
            return value;
        });
        int completed=0;
        boolean actionMayHaveChanged=false;
        var observed=new LinkedHashMap<String,Observed>();
        try {
            journal(binding,running,"RUNNING",completed,observed);
            var writer=new WindowsLocalProjectFileMutator(files);
            for(var delta:envelope.plan().changes()) {
                authorize(principal,binding,root);
                WindowsLocalProjectFileMutator.Result result=switch(delta.kind()){
                    case CREATE_FILE -> {
                        var permissions=envelope.restore().get(delta.path());
                        if(permissions==null)yield writer.create(root,delta.path(),delta.after().getBytes(StandardCharsets.UTF_8));
                        else{
                            writer.create(root,delta.path(),new byte[0]);
                            // 恢复ACL和正文是同一动作内的后续步骤；不能把已创建对象当成零副作用。
                            actionMayHaveChanged=true;
                            WindowsLocalProjectSecurity.restore(root.directory().resolve(delta.path()),permissions);
                            yield writer.replace(root,delta.path(),hash(""),delta.after().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    case REPLACE_FILE -> writer.replace(root,delta.path(),delta.beforeVersion(),delta.after().getBytes(StandardCharsets.UTF_8),delta.security());
                    case MOVE_FILE -> writer.move(root,delta.path(),delta.target(),delta.beforeVersion(),delta.security());
                    case DELETE_FILE -> {writer.deleteFile(root,delta.path(),delta.beforeVersion(),delta.security());yield null;}
                    case CREATE_DIRECTORY -> {
                        var created=writer.createDirectory(root,delta.path());
                        var permissions=envelope.restore().get(delta.path());
                        if(permissions!=null){WindowsLocalProjectSecurity.restore(root.directory().resolve(delta.path()),permissions);created=WindowsLocalProjectSecurity.capture(root.directory().resolve(delta.path()));}
                        observed.put(delta.path(),new Observed(created.identity(),created.fingerprint()));
                        yield null;
                    }
                    case DELETE_EMPTY_DIRECTORY -> {writer.deleteEmptyDirectory(root,delta.path(),delta.beforeVersion(),delta.security());yield null;}
                };
                if(result!=null)observed.put(delta.kind()==LocalProjectChangePlanner.Kind.MOVE_FILE?delta.target():delta.path(),new Observed(result.fileIdentity(),result.securityFingerprint()));
                completed++;
                journal(binding,running,"RUNNING",completed,observed);
            }
            final int count=completed;
            var success=transaction.execute(status->{
                var value=operations.advance(binding.tenantId(),binding.ownerId(),id,running.version(),LocalProjectOperation.State.SUCCEEDED,clock.instant())
                        .orElseThrow(()->new IllegalStateException("变更完成状态未能保存"));
                audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_APPLY","PROJECT_OPERATION",id.toString(),
                        "SUCCEEDED","runId="+binding.runId()+" digest="+current.digest()+" count="+count);
                return value;
            });
            journal(binding,success,"SUCCEEDED",completed,observed);
            return view(success,completed);
        } catch(IOException|RuntimeException failure) {
            if(completed==0&&!actionMayHaveChanged&&failure instanceof LocalProjectMutationException mutation
                    &&!mutation.possibleSideEffects()&&(mutation.reason()==LocalProjectFileException.Reason.FILE_CHANGED
                    ||mutation.reason()==LocalProjectFileException.Reason.NOT_FOUND)){
                // 原生写入器在独占句柄及实际版本检查期间确认尚未写入；只对此明确证据收口FAILED。
                // 操作状态与严格审计共用短事务，失败时不能向模型伪称已完成冲突收口。
                var rejected=transaction.execute(status->{
                    var value=operations.advance(binding.tenantId(),binding.ownerId(),id,running.version(),LocalProjectOperation.State.FAILED,clock.instant())
                            .orElseThrow(()->new IllegalStateException("变更冲突状态未能保存"));
                    audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_APPLY","PROJECT_OPERATION",id.toString(),
                            "FAILED","runId="+binding.runId()+" digest="+current.digest()+" reason=CONFLICT_BEFORE_WRITE");
                    return value;
                });
                journal(binding,rejected,"FAILED",0,observed);
                throw new LocalProjectChangeException(LocalProjectChangeException.Reason.CONFLICT);
            }
            // 已派发后结果持久化可能失败，不能用FAILED假装没有副作用，更不能自动重新执行。
            try{operations.advance(binding.tenantId(),binding.ownerId(),id,running.version(),LocalProjectOperation.State.UNKNOWN,clock.instant());}
            catch(RuntimeException recordingFailure){
                log.error("errorId={} operation=LOCAL_PROJECT_CHANGE errorCode=PROJECT_CHANGE_STATE_UNAVAILABLE tenantId={} principalId={} runId={}",id,binding.tenantId(),binding.ownerId(),binding.runId());
                throw recordingFailure;
            }
            log.error("errorId={} operation=LOCAL_PROJECT_CHANGE errorCode=PROJECT_CHANGE_RESULT_UNKNOWN tenantId={} principalId={} runId={} completed={}",id,binding.tenantId(),binding.ownerId(),binding.runId(),completed);
            if(failure instanceof com.cmagent.server.audit.AuditPersistenceException||failure instanceof org.springframework.dao.DataAccessException)
                throw (RuntimeException)failure;
            throw new IOException("变更结果需要核对，请保留操作编号并查看恢复记录");
        }
    }

    /** 失败/未知操作不会在重启时重放；只提供原主体的安全状态核对。 */
    public View status(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,UUID id) throws IOException {
        authorize(principal,binding,root);
        var operation=owned(binding,id);
        return view(operation,operation.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant())?decode(binding,operation).plan().changes().size():0);
    }

    /** 条件撤销生成全新的提案和批准周期，不直接回放旧操作，也不覆盖当前已变化的文件。 */
    public View undoProposal(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,UUID id) throws IOException{
        authorize(principal,binding,root);
        var original=operations.operation(binding.tenantId(),binding.ownerId(),id).orElseThrow(()->new IOException("原变更不存在"));
        if(original.kind()!=LocalProjectOperation.Kind.FILE_CHANGE||!original.projectId().equals(binding.projectId())
                ||original.state()!=LocalProjectOperation.State.SUCCEEDED)throw new IOException("原变更尚未成功或需要先核对结果");
        if(!original.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant()))throw new IOException("原变更恢复期限已到，不能自动撤销");
        Envelope envelope;
        try{
            String json=cipher.decrypt(original.encryptedPayload());
            if(!hash(json).equals(original.digest()))throw new IOException();
            envelope=mapper.readValue(json,Envelope.class);
            var source=envelope.binding();
            if(!source.tenantId().equals(binding.tenantId())||!source.ownerId().equals(binding.ownerId())
                    ||!source.projectId().equals(binding.projectId())||!source.rootBindingId().equals(binding.rootBindingId())
                    ||!source.rootIdentity().equals(binding.rootIdentity())||!source.nodeId().equals(binding.nodeId())
                    ||!source.runId().equals(original.runId()))throw new IOException();
        }catch(IOException|RuntimeException failure){throw new IOException("原变更意图不可核实");}
        var receipt=readJournal(envelope.binding(),original);
        var changes=new ArrayList<Change>();
        var restore=new HashMap<String,WindowsLocalProjectSecurity.Snapshot>();
        for(int i=envelope.plan().changes().size()-1;i>=0;i--){
            var delta=envelope.plan().changes().get(i);
            // 原生对象/ACL也属于条件撤销的版本；同正文重建文件不能借用旧操作的撤销权限。
            if(delta.kind()==LocalProjectChangePlanner.Kind.CREATE_FILE||delta.kind()==LocalProjectChangePlanner.Kind.REPLACE_FILE
                    ||delta.kind()==LocalProjectChangePlanner.Kind.MOVE_FILE||delta.kind()==LocalProjectChangePlanner.Kind.CREATE_DIRECTORY){
                String path=delta.kind()==LocalProjectChangePlanner.Kind.MOVE_FILE?delta.target():delta.path();
                var expected=receipt.observed().get(path);
                var actual=WindowsLocalProjectSecurity.capture(root.directory().resolve(path));
                if(expected==null||!expected.identity().equals(actual.identity())||!expected.securityFingerprint().equals(actual.fingerprint()))
                    throw new IOException("撤销目标对象或权限已变化，拒绝覆盖当前对象");
            }
            switch(delta.kind()){
                case REPLACE_FILE -> changes.add(new Change("REPLACE_FILE",delta.path(),null,delta.before(),hash(delta.after())));
                case CREATE_FILE -> changes.add(new Change("DELETE_FILE",delta.path(),null,null,hash(delta.after())));
                case MOVE_FILE -> changes.add(new Change("MOVE_FILE",delta.target(),delta.path(),null,hash(delta.before())));
                case DELETE_FILE -> {
                    if(delta.security()==null||!delta.security().restorable())throw new IOException("原权限快照不支持安全恢复");
                    restore.put(delta.path(),delta.security());
                    changes.add(new Change("CREATE_FILE",delta.path(),null,delta.before(),null));
                }
                case CREATE_DIRECTORY -> changes.add(new Change("DELETE_EMPTY_DIRECTORY",delta.path(),null,null,null));
                case DELETE_EMPTY_DIRECTORY -> {
                    if(delta.security()==null||!delta.security().restorable())throw new IOException("原目录权限快照不支持安全恢复");
                    restore.put(delta.path(),delta.security());
                    changes.add(new Change("CREATE_DIRECTORY",delta.path(),null,null,null));
                }
            }
        }
        return proposeTrusted(principal,binding,root,changes,restore);
    }

    /**
     * 核对文件意图的实际现状，只允许全部匹配批准后状态或全部匹配原状时收口。
     * 混合/不可读取保留UNKNOWN，不自动覆盖、撤销或再次派发。
     */
    public synchronized View reconcile(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,UUID id) throws IOException{
        authorize(principal,binding,root);
        var current=owned(binding,id);
        if(current.state()!=LocalProjectOperation.State.RUNNING&&current.state()!=LocalProjectOperation.State.UNKNOWN)
            return view(current,0);
        var envelope=decode(binding,current);
        Journal receipt;
        try{receipt=readJournal(binding,current);}
        catch(IOException unavailable){return view(current,envelope.plan().changes().size());}
        var observed=new LinkedHashMap<>(receipt.observed());
        boolean before=true,after=true;
        for(var delta:envelope.plan().changes()){
            try{
                boolean absent=files.absent(root,delta.path());
                boolean actionBefore=false,actionAfter=false;
                var original=delta.security()==null?null:new Observed(delta.security().identity(),delta.security().fingerprint());
                switch(delta.kind()){
                    case CREATE_FILE ->{
                        actionBefore=absent;
                        // 新对象无法由旧计划推导身份；缺写后回执时不能凭同正文补造撤销凭据。
                        actionAfter=!absent&&matchesObservedFile(root,delta.path(),hash(delta.after()),observed.get(delta.path()));
                    }
                    case REPLACE_FILE ->{
                        actionBefore=!absent&&matchesObservedFile(root,delta.path(),delta.beforeVersion(),original);
                        actionAfter=!absent&&compatibleReceipt(observed,delta.path(),original)
                                &&matchesObservedFile(root,delta.path(),hash(delta.after()),original);
                        if(actionAfter)observed.put(delta.path(),original);
                    }
                    case MOVE_FILE ->{
                        boolean targetAbsent=files.absent(root,delta.target());
                        actionBefore=!absent&&targetAbsent&&matchesObservedFile(root,delta.path(),delta.beforeVersion(),original);
                        actionAfter=absent&&!targetAbsent&&compatibleReceipt(observed,delta.target(),original)
                                &&matchesObservedFile(root,delta.target(),delta.beforeVersion(),original);
                        // 原生重命名保留对象及ACL，因此可从批准前快照核实移动后的身份，而非猜测新对象。
                        if(actionAfter)observed.put(delta.target(),original);
                    }
                    case DELETE_FILE ->{
                        actionBefore=!absent&&matchesObservedFile(root,delta.path(),delta.beforeVersion(),original);
                        actionAfter=absent;
                    }
                    case CREATE_DIRECTORY ->{
                        actionBefore=absent;
                        actionAfter=!absent&&matchesObservedDirectory(root,delta.path(),null,observed.get(delta.path()));
                    }
                    case DELETE_EMPTY_DIRECTORY ->{
                        actionBefore=!absent&&matchesObservedDirectory(root,delta.path(),delta.beforeVersion(),original);
                        actionAfter=absent;
                    }
                }
                before &= actionBefore;after &= actionAfter;
            }catch(IOException failure){before=false;after=false;}
        }
        if(!before&&!after)return view(current,envelope.plan().changes().size());
        var next=after?LocalProjectOperation.State.SUCCEEDED:LocalProjectOperation.State.FAILED;
        var updated=transaction.execute(status->{
            var value=operations.advance(binding.tenantId(),binding.ownerId(),id,current.version(),next,clock.instant())
                    .orElseThrow(()->new IllegalStateException("核对状态发生竞争"));
            audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_RECONCILE","PROJECT_OPERATION",
                    id.toString(),next.name(),"runId="+binding.runId()+" observed="+(next==LocalProjectOperation.State.SUCCEEDED?"OBSERVED_AFTER":"OBSERVED_BEFORE"));
            return value;
        });
        journal(binding,updated,next.name(),after?envelope.plan().changes().size():0,observed);
        return view(updated,envelope.plan().changes().size());
    }

    /** 旧对象操作允许从批准快照推导身份；已存在回执必须一致，不能覆盖矛盾的执行证据。 */
    private static boolean compatibleReceipt(Map<String,Observed> observed,String path,Observed expected){
        return expected!=null&&(!observed.containsKey(path)||expected.equals(observed.get(path)));
    }

    /** 正文读取前后复验对象/ACL并固定父目录，避免用同正文重建文件冒充批准对象。 */
    private boolean matchesObservedFile(LocalProjectDirectoryTickets.Root root,String path,String version,Observed expected)throws IOException{
        if(expected==null)return false;
        Path file=root.directory().resolve(path);
        try(var lease=WindowsLocalProjectDirectoryLease.acquire(root,file.getParent(),System.nanoTime()+Duration.ofSeconds(5).toNanos())){
            if(!matchesObservedSecurity(file,expected))return false;
            // UTF-8首码点最多4字节；只取1字节会把中文开头的合法文件误报为不可核实。
            if(!files.read(root,path,0,4).sha256().equals(version))return false;
            boolean matched=matchesObservedSecurity(file,expected);lease.verify();return matched;
        }
    }

    /** 目录核对同时要求仍为空、原生身份和权限匹配；不把空的新目录当作原目录。 */
    private boolean matchesObservedDirectory(LocalProjectDirectoryTickets.Root root,String path,String version,Observed expected)throws IOException{
        if(expected==null)return false;
        Path directory=root.directory().resolve(path);
        try(var lease=WindowsLocalProjectDirectoryLease.acquire(root,directory.getParent(),System.nanoTime()+Duration.ofSeconds(5).toNanos())){
            if(!matchesObservedSecurity(directory,expected))return false;
            String actual=files.emptyDirectoryVersion(root,path);
            if(version!=null&&!version.equals(actual))return false;
            boolean matched=matchesObservedSecurity(directory,expected);lease.verify();return matched;
        }
    }

    private static boolean matchesObservedSecurity(Path path,Observed expected)throws IOException{
        var actual=WindowsLocalProjectSecurity.capture(path);
        return expected.identity().equals(actual.identity())&&expected.securityFingerprint().equals(actual.fingerprint());
    }

    /** 到期未派发意图以原版本取消后擦除；RUNNING保留待核对，绝不把GC当作停止或重放执行。 */
    public synchronized int cleanupExpired() throws IOException{
        if(!stateLock.isValid())throw new IllegalStateException("变更恢复区实例锁已关闭");
        int removed=0;
        try{
            if(gcDirectories.isEmpty())openGcDirectory(state,0);
            // 枚举预算包含无效目录项，既限制单轮读取，也会保留下一项位置而非每次从头开始。
            for(int scanned=0;scanned<100&&!gcDirectories.isEmpty();scanned++){
                var frame=gcDirectories.peek();
                if(!frame.entries().hasNext()){gcDirectories.pop().stream().close();scanned--;continue;}
                Path path=frame.entries().next();
                if(!safeGcPath(path))continue;
                String name=path.getFileName().toString();
                if(frame.depth()<2){
                    if(uuidName(name)&&Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))openGcDirectory(path,frame.depth()+1);
                    continue;
                }
                if(!name.endsWith(".gcm")||!uuidName(name.substring(0,name.length()-4))
                        ||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>12_000_000)continue;
                Journal journal;
                try{journal=mapper.readValue(cipher.decrypt(Files.readString(path,StandardCharsets.UTF_8)),Journal.class);}
                catch(IOException|RuntimeException malformed){continue;}
                var binding=journal.binding();
                if(binding==null||!binding.nodeId().equals(nodeId)||!path.getParent().getFileName().toString().equals(binding.projectId().toString())
                        ||!path.getParent().getParent().getFileName().toString().equals(binding.tenantId().toString())
                        ||!name.equals(journal.operationId()+".gcm"))continue;
                var operation=operations.operation(binding.tenantId(),binding.ownerId(),journal.operationId()).orElse(null);
                if(operation==null||operation.kind()!=LocalProjectOperation.Kind.FILE_CHANGE||!operation.digest().equals(journal.digest())
                        ||!operation.projectId().equals(binding.projectId())||!operation.agentId().equals(binding.agentId())
                        ||!operation.conversationId().equals(binding.conversationId())||!operation.runId().equals(binding.runId())
                        ||operation.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant()))continue;
                String expired=cipher.encrypt("{\"expired\":true,\"operationId\":\""+operation.id()+"\"}");
                Boolean erased=transaction.execute(status->{
                    var candidate=operation;
                    if(Set.of(LocalProjectOperation.State.PROPOSED,LocalProjectOperation.State.WAITING_APPROVAL,LocalProjectOperation.State.PREPARED).contains(operation.state())){
                        // expectedVersion取自本轮读取；claim若已赢得RUNNING，CAS失败，不读取最新版本强行取消执行。
                        candidate=operations.advance(binding.tenantId(),binding.ownerId(),operation.id(),operation.version(),LocalProjectOperation.State.CANCELLED,clock.instant()).orElse(null);
                        if(candidate==null)return false;
                        audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_EXPIRE_CANCEL","PROJECT_OPERATION",
                                operation.id().toString(),"CANCELLED","runId="+binding.runId());
                    }
                    if(!operations.erasePayload(binding.tenantId(),binding.ownerId(),candidate.id(),candidate.version(),expired,clock.instant()))return false;
                    audit.appendControlled(binding.tenantId(),binding.ownerId(),"LOCAL_PROJECT_CHANGE_EXPIRE","PROJECT_OPERATION",
                            operation.id().toString(),"SUCCEEDED","runId="+binding.runId());
                    return true;
                });
                if(Boolean.TRUE.equals(erased)){Files.deleteIfExists(path);removed++;}
            }
        }catch(DirectoryIteratorException failure){closeGcDirectories();throw new IOException("恢复区清理扫描暂不可用");}
        catch(IOException|RuntimeException failure){closeGcDirectories();throw failure;}
        return removed;
    }

    private void openGcDirectory(Path path,int depth)throws IOException{
        var stream=Files.newDirectoryStream(path);
        gcDirectories.push(new GcDirectory(path,stream,stream.iterator(),depth));
    }

    /** 每次访问均复验父链，DirectoryStream不跟随符号链接，拒绝UUID布局外的任意全局目录。 */
    private boolean safeGcPath(Path path)throws IOException{
        if(!path.toAbsolutePath().normalize().startsWith(state))return false;
        Path component=state;
        if(Files.isSymbolicLink(component)||!component.toRealPath().equals(component))return false;
        for(Path part:state.relativize(path)){
            component=component.resolve(part);
            if(Files.isSymbolicLink(component)||!component.toRealPath().equals(component.toAbsolutePath().normalize()))return false;
        }
        return true;
    }

    private static boolean uuidName(String name){
        try{return UUID.fromString(name).toString().equals(name);}catch(IllegalArgumentException invalid){return false;}
    }

    /** 服务关闭或扫描失败都释放至多三层游标；不删除文件，也不扩展到其他恢复目录。 */
    private void closeGcDirectories()throws IOException{
        IOException failure=null;
        while(!gcDirectories.isEmpty()){
            try{gcDirectories.pop().stream().close();}
            catch(IOException closing){if(failure==null)failure=closing;else failure.addSuppressed(closing);}
        }
        if(failure!=null)throw failure;
    }

    private LocalProjectOperation owned(LocalProjectBinding binding,UUID id) throws IOException {
        var operation=operations.operation(binding.tenantId(),binding.ownerId(),id).orElseThrow(()->new IOException("变更意图不存在"));
        if(operation.kind()!=LocalProjectOperation.Kind.FILE_CHANGE || !operation.projectId().equals(binding.projectId())
                ||!operation.agentId().equals(binding.agentId())||!operation.conversationId().equals(binding.conversationId())
                ||!operation.runId().equals(binding.runId()))throw new IOException("变更意图不存在");
        return operation;
    }

    private Envelope decode(LocalProjectBinding binding,LocalProjectOperation operation) throws IOException {
        if(!operation.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant()))throw new IOException("变更恢复期限已到");
        try {
            String json=cipher.decrypt(operation.encryptedPayload());
            if(!hash(json).equals(operation.digest()))throw new IOException("变更意图完整性核验失败");
            var value=mapper.readValue(json,Envelope.class);
            if(!value.binding().equals(binding))throw new IOException("变更意图来源不匹配");
            return value;
        } catch(IOException failure){throw new IOException("变更意图不可核实，请重新生成提案");}
        catch(RuntimeException failure){throw new IOException("变更意图不可核实，请重新生成提案");}
    }

    private void authorize(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root) {
        if(!stateLock.isValid())throw new IllegalStateException("变更恢复区实例锁已关闭");
        if(binding.runId()==null||!principal.tenantId().equals(binding.tenantId())||!principal.principalId().equals(binding.ownerId())
                ||!principal.permissions().containsAll(Set.of("project:read","project:write","agent:run"))
                ||!binding.nodeId().equals(nodeId)||!hash(root.identity()).equals(binding.rootIdentity()))
            throw new SecurityException("无权访问该本地项目变更");
        var project=projects.find(binding.tenantId(),binding.ownerId(),binding.projectId()).orElseThrow(()->new SecurityException("项目不可用"));
        if(project.state()!=LocalProject.State.ACTIVE||!project.rootBindingId().equals(binding.rootBindingId())
                ||!project.rootIdentity().equals(binding.rootIdentity())||!project.nodeId().equals(nodeId))
            throw new SecurityException("项目不可用");
    }

    /** 每次副作用后单独持久化受保护日志；失败按UNKNOWN处理，不对外输出部分正文。 */
    private void journal(LocalProjectBinding binding,LocalProjectOperation operation,String state,int completed) throws IOException {
        journal(binding,operation,state,completed,Map.of());
    }

    private void journal(LocalProjectBinding binding,LocalProjectOperation operation,String state,int completed,Map<String,Observed> observed) throws IOException {
        var value=new Journal(operation.id(),operation.digest(),binding,state,completed,clock.instant().toString(),
                operation.encryptedPayload(),operation.createdAt().plus(Duration.ofDays(7)).toString(),observed);
        byte[] data=cipher.encrypt(mapper.writeValueAsString(value)).getBytes(StandardCharsets.UTF_8);
        Path directory=this.state.resolve(binding.tenantId().toString()).resolve(binding.projectId().toString());
        Files.createDirectories(directory);privatePath(directory);
        Path target=directory.resolve(operation.id()+".gcm");
        Path staged=directory.resolve(operation.id()+"-"+UUID.randomUUID()+".tmp");
        try {
            Files.write(staged,data,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            privatePath(staged);
            try(var channel=java.nio.channels.FileChannel.open(staged,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Arrays.fill(data,(byte)0);Files.deleteIfExists(staged);}
    }

    /** 只读取本服务私有AEAD回执并与原意图完整关联；缺失回执不猜测自动撤销。 */
    private Journal readJournal(LocalProjectBinding binding,LocalProjectOperation operation)throws IOException {
        try {
            Path path=state.resolve(binding.tenantId().toString()).resolve(binding.projectId().toString()).resolve(operation.id()+".gcm");
            if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path.toAbsolutePath().normalize())||Files.size(path)>12_000_000)throw new IOException();
            var result=mapper.readValue(cipher.decrypt(Files.readString(path,StandardCharsets.UTF_8)),Journal.class);
            if(!result.operationId().equals(operation.id())||!result.digest().equals(operation.digest())||!result.binding().equals(binding))throw new IOException();
            return result;
        }catch(IOException|RuntimeException failure){throw new IOException("原变更执行回执不可核实，拒绝自动撤销");}
    }

    private long retainedBytes(LocalProjectBinding binding) throws IOException {
        long total=0;
        Path project=state.resolve(binding.tenantId().toString()).resolve(binding.projectId().toString());
        if(!Files.exists(project,LinkOption.NOFOLLOW_LINKS))return 0;
        if(Files.isSymbolicLink(project)||!project.toRealPath().equals(project.toAbsolutePath().normalize()))throw new IOException("恢复区身份不可信");
        try(var paths=Files.walk(project)){
            for(Path path:paths.filter(value->Files.isRegularFile(value,LinkOption.NOFOLLOW_LINKS)).toList()){
                total=Math.addExact(total,Files.size(path));
                if(total>100L*1024*1024)return total;
            }
        }
        return total;
    }

    /** 只释放本服务的节点锁，不删除用户项目或恢复日志。 */
    @Override public synchronized void close() throws IOException {
        try{try{closeGcDirectories();}finally{if(stateLock.isValid())stateLock.release();}}
        finally{lockChannel.close();}
    }

    /** 应用私有目录/文件不沿用项目的宽ACL；只修改本服务创建的状态区。 */
    private static void privatePath(Path path) throws IOException {
        if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException("恢复区不是受信私有路径");
        var posix=Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null)posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));
        else {
            var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(acl==null)throw new IOException("恢复区权限视图不可用");
            var permissions=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class));
            if(Files.isDirectory(path))permissions.setFlags(java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,java.nio.file.attribute.AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(permissions.build()));
        }
    }

    private static String hash(String value){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("变更摘要能力不可用");}
    }
    private static View view(LocalProjectOperation value,int count){return new View(value.id(),value.state(),value.version(),value.digest(),count,value.updatedAt());}

    /**
     * @param kind 动作枚举名称
     * @param path 根内路径
     * @param target 移动目标
     * @param content 受保护正文
     * @param expectedSha256 原版本
     */
    public record Change(String kind,String path,String target,String content,String expectedSha256){@Override public String toString(){return "Change[受保护正文]";}}
    /**
     * @param operationId 操作编号
     * @param state 持久状态
     * @param version 条件版本
     * @param digest 精确意图摘要
     * @param count 动作数
     * @param updatedAt 状态时间
     */
    public record View(UUID operationId,LocalProjectOperation.State state,long version,String digest,int count,Instant updatedAt){}
    /**
     * @param view 安全操作状态
     * @param changes 受保护差异，不能进入普通进度或日志
     */
    public record Preview(View view,List<Diff> changes){public Preview{changes=List.copyOf(changes);}@Override public String toString(){return "Preview[受保护差异]";}}
    /**
     * @param kind 动作
     * @param path 来源
     * @param target 目标
     * @param beforeVersion 原版本
     * @param before 原文
     * @param after 新文
     */
    public record Diff(String kind,String path,String target,String beforeVersion,String before,String after){@Override public String toString(){return "Diff[受保护正文]";}}
    /**
     * @param binding 原信任来源
     * @param plan 不可变受保护计划
     * @param restore 仅从已认证原操作生成的权限恢复快照，不能从HTTP接受
     */
    private record Envelope(LocalProjectBinding binding,LocalProjectChangePlanner.Plan plan,Map<String,WindowsLocalProjectSecurity.Snapshot> restore){
        Envelope{restore=restore==null?Map.of():Map.copyOf(restore);}
    }
    /**
     * @param operationId 操作
     * @param digest 精确意图
     * @param binding 来源
     * @param state 状态
     * @param completed 已核实动作数
     * @param time 可信时间
     * @param encryptedIntent 原清单的受保护密文，包含恢复内容
     * @param expiresAt 固定恢复期限，读取不续期
     * @param observed 实际写入句柄捕获的目标身份/ACL，用于条件撤销
     */
    private record Journal(UUID operationId,String digest,LocalProjectBinding binding,String state,int completed,String time,String encryptedIntent,String expiresAt,Map<String,Observed> observed){
        Journal{observed=observed==null?Map.of():Map.copyOf(observed);}
    }
    /** @param identity 实际执行对象的原生身份 @param securityFingerprint 写后权限语义版本 */
    private record Observed(String identity,String securityFingerprint){}
    /**
     * @param directory 当前仅用于GC的私有目录
     * @param stream 本实例持有并在遍历结束/服务关闭时关闭的目录流
     * @param entries 跨批次推进的位置，不每轮重建迭代器
     * @param depth 根为0、租户为1、项目为2，不进入更深层目录
     */
    private record GcDirectory(Path directory,DirectoryStream<Path> stream,Iterator<Path> entries,int depth){}
}

