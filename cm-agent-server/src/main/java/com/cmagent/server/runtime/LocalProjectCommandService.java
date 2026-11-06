package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 本机命令的提案、单次派发与状态管理；仅在受控本机门禁后装配，不以外部boolean证明批准。
 *
 * <p>调用者在同源本机入口取得受信binding/root后调用。派发只消费持久PREPARED版本并CAS为RUNNING；
 * 本实例失联的RUNNING一律UNKNOWN，不能重放。输出仅在有界实例内存，不写普通日志/审计，
 * 状态返回完整脱敏快照，客户端应替换而非根据不稳定的脱敏字符偏移追加。</p>
 */
public final class LocalProjectCommandService implements AutoCloseable {
    /** 固定诊断与可信标识，原始命令、环境及输出不进入日志。 */
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(LocalProjectCommandService.class);
    /** 持久意图与条件状态，不承担进程副作用事务。 */
    private final LocalProjectOperationRepository operations;
    /** 宿主持有的认证加密器，清单包含根/主体/Run上下文。 */
    private final ModelCredentialCipher cipher;
    /** 仅序列化已验证的命令清单，不记录原始JSON。 */
    private final ObjectMapper mapper;
    /** 固定服务端期限来源。 */
    private final Clock clock;
    /** 服务端固定工具链，外部只能选择id，不能提供EXE路径。 */
    private final Map<String, Toolchain> tools;
    /** 上层必须执行当前项目/节点/权限撤销复核；失败不得静默忽略。 */
    private final Authorizer authorizer;
    /** 严格元数据审计，不接收清单/输出正文。 */
    private final Auditor auditor;
    /** 正式JDBC装配必须与仓储/审计使用同一DataSource，只包元数据短操作。 */
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    /** 单实例只允许一个本机命令组，拒绝排队避免资源无限增长。 */
    private final Semaphore slot = new Semaphore(1);
    /** 只监视本次创建的Job，宿主关闭时精确停止。 */
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    /** 当前实例进程监控者，线程有界、daemon，不控制其他实例进程。 */
    private final ExecutorService workers = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("local-project-command").factory());
    /** 全部输出统一使用现有固定秘密/URL清理规则。 */
    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    /**
     * @param operations 与宿主审计使用同一可信数据集的操作仓储
     * @param cipher 宿主加密器，调用者负责密钥生命周期
     * @param mapper 支持本模块record的宿主JSON映射器
     * @param clock 服务端时钟
     * @param tools 服务端允许的工具链快照
     * @param authorizer 每次交付/派发/取消的当前授权复核
     * @param auditor 严格元数据审计，失败继续抛出
     */
    public LocalProjectCommandService(LocalProjectOperationRepository operations, ModelCredentialCipher cipher,
            ObjectMapper mapper, Clock clock, Map<String, Toolchain> tools, Authorizer authorizer, Auditor auditor) {
        this(operations,cipher,mapper,clock,tools,authorizer,auditor,null);
    }
    /** 与兼容构造器相同，transaction必须来自宿主共享事务管理器；null仅用于内存合同测试。 */
    public LocalProjectCommandService(LocalProjectOperationRepository operations, ModelCredentialCipher cipher,
            ObjectMapper mapper, Clock clock, Map<String, Toolchain> tools, Authorizer authorizer, Auditor auditor,
            org.springframework.transaction.support.TransactionTemplate transaction) {
        this.operations=Objects.requireNonNull(operations); this.cipher=Objects.requireNonNull(cipher);
        this.mapper=Objects.requireNonNull(mapper); this.clock=Objects.requireNonNull(clock);
        this.tools=Map.copyOf(tools); this.authorizer=Objects.requireNonNull(authorizer); this.auditor=Objects.requireNonNull(auditor);
        this.transaction=transaction;
    }

    /** 只建立加密提案，不启动程序；不会自动进入PREPARED或替用户接受批准。 */
    public Proposal propose(PrincipalRef principal, LocalProjectBinding binding, LocalProjectDirectoryTickets.Root root, CommandRequest request) throws IOException {
        authorize(principal,binding,root);
        prune();
        if(operations.hasUncertainOperation(binding.tenantId(),binding.ownerId(),binding.projectId(),null))
            throw new IllegalStateException("项目存在运行中或结果未知的操作，请先核对");
        Toolchain tool=tools.get(request.toolchainId());
        if(tool==null)throw new IllegalArgumentException("命令工具链未获允许");
        Path directory=directory(root,request.relativeDirectory());
        var environment=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
        environment.putAll(tool.environment());
        request.environment().forEach((key,value)->{
            if(!tool.allowedEnvironment().contains(key) || sensitive(key) || value.indexOf('\0')>=0)
                throw new IllegalArgumentException("命令环境字段未获允许");
            environment.put(key,value);
        });
        var arguments=new ArrayList<>(tool.prefixArguments());arguments.addAll(request.arguments());
        if(arguments.stream().anyMatch(value->value==null||value.indexOf('\0')>=0||sensitiveAssignment(value)))
            throw new IllegalArgumentException("命令参数不允许包含秘密赋值或非法字符");
        new WindowsLocalProjectProcess.Launch(tool.executable(),arguments,directory,environment);
        var manifest=new Manifest(binding,request.toolchainId(),tool.executable().toString(),fileHash(tool.executable()),
                List.copyOf(arguments),request.relativeDirectory(),Map.copyOf(environment),request.timeoutSeconds(),request.maxOutputBytes(),
                buildHashes(root));
        String json=json(manifest), digest=hash(json.getBytes(StandardCharsets.UTF_8));
        Instant now=clock.instant();
        var operation=new LocalProjectOperation(UUID.randomUUID(),binding.tenantId(),binding.ownerId(),binding.projectId(),
                binding.agentId(),binding.conversationId(),binding.runId(),LocalProjectOperation.Kind.COMMAND,digest,cipher.encrypt(json),
                LocalProjectOperation.State.PROPOSED,1,now,now);
        metadata(()->{
            if(!operations.insertOperation(operation))throw new IllegalStateException("命令提案无法保存");
            try{auditor.append(binding,operation.id(),"COMMAND_PROPOSED",digest);}
            catch(RuntimeException failure){
                if(transaction==null)operations.advance(binding.tenantId(),binding.ownerId(),operation.id(),1,LocalProjectOperation.State.CANCELLED,clock.instant());
                throw failure;
            }
            return operation;
        });
        return new Proposal(operation.id(),1,digest,request.toolchainId(),List.copyOf(arguments),request.relativeDirectory(),
                request.timeoutSeconds(),request.maxOutputBytes());
    }

    /** 只接收持久PREPARED；调用者需先在严格批准事务中推进该状态，不得从模型输入直接设置。 */
    public Status dispatchPrepared(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,
            UUID id,long expectedVersion)throws IOException {
        return dispatchPrepared(principal,binding,root,id,expectedVersion,null);
    }

    /**
     * 与Run协作停止联动；专属调用许可持有到本Job实际退出，不能把发布停止信号当作进程已停止。
     * @param cancellation 可信Run停止信号，旧独立测试可为空；不得从HTTP接收
     */
    Status dispatchPrepared(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root,
            UUID id,long expectedVersion,com.cmagent.core.runtime.RunCancellation cancellation)throws IOException {
        authorize(principal,binding,root);
        if(operations.hasUncertainOperation(binding.tenantId(),binding.ownerId(),binding.projectId(),id))
            throw new IllegalStateException("项目存在其他运行中或结果未知的操作");
        var operation=owned(principal,binding,id);
        if(operation.state()!=LocalProjectOperation.State.PREPARED||operation.version()!=expectedVersion)
            throw new IllegalStateException("命令尚未取得精确批准或已被派发");
        Manifest manifest=decode(operation,binding);
        Toolchain tool=tools.get(manifest.toolchainId());
        if(tool==null||!tool.executable().toString().equals(manifest.executable())
                ||!fileHash(tool.executable()).equals(manifest.executableHash())||!buildHashes(root).equals(manifest.buildHashes()))
            throw new IllegalStateException("工具链或构建配置已变化，请重新提案");
        Path directory=directory(root,manifest.relativeDirectory());
        prune();
        if(!slot.tryAcquire())throw new IllegalStateException("已有本机命令运行，请稍后重试");
        boolean handed=false;
        com.cmagent.core.runtime.RunCancellation.Invocation invocation = null;
        try{
            if(cancellation!=null)invocation=cancellation.enter();
            var running=metadata(()->{
                if(operations.hasUncertainOperation(binding.tenantId(),binding.ownerId(),binding.projectId(),id))
                    throw new IllegalStateException("项目出现新的运行中操作");
                auditor.append(binding,id,"COMMAND_DISPATCH",operation.digest());
                return operations.claimPrepared(binding.tenantId(),binding.ownerId(),id,expectedVersion,clock.instant())
                        .orElseThrow(()->new IllegalStateException("命令派发版本已变化"));
            });
            var state=new Active(running,manifest,root.directory());
            state.invocation=invocation;
            if(cancellation!=null)cancellation.signal().thenRun(()->state.cancel.set(true));
            active.put(id,state);
            try{workers.submit(()->monitor(state,new WindowsLocalProjectProcess.Launch(tool.executable(),manifest.arguments(),directory,manifest.environment())));}
            catch(RuntimeException failure){
                state.uncertain=true;
                operations.advance(binding.tenantId(),binding.ownerId(),id,running.version(),LocalProjectOperation.State.UNKNOWN,clock.instant());
                diagnostic(state,failure,"LOCAL_PROJECT_COMMAND_SCHEDULING");
                throw new IllegalStateException("命令派发结果无法确认，请先核对状态");
            }
            handed=true;
            return view(state);
        }finally{if(!handed){slot.release();if(invocation!=null)invocation.close();}}
    }

    /** 只供原owner的受控预览端点转换为脱敏DTO，不允许把含宿主环境的Manifest原样作为响应。 */
    Manifest preview(PrincipalRef principal,LocalProjectBinding binding,UUID id)throws IOException {
        authorizeMetadata(principal,binding); return decode(owned(principal,binding,id),binding);
    }

    /** 固定工具链只交出逻辑标识/程序名/构建标志，模型不能读取宿主EXE目录和基础环境。 */
    List<AllowedToolchain> toolchains() {
        return tools.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(value->new AllowedToolchain(value.getKey(),value.getValue().executable().getFileName().toString(),value.getValue().build())).toList();
    }

    /** 可信Run清理入口只设置本实例已创建Job的停止位，不按PID寻找或接管进程。 */
    void requestRunStop(UUID tenant,String owner,UUID runId) {
        for(Active value:active.values())if(value.operation.tenantId().equals(tenant)&&value.operation.ownerId().equals(owner)&&value.operation.runId().equals(runId))value.cancel.set(true);
    }

    /** owner/固定来源复核后返回完整安全快照；重启后丢失的运行句柄只标UNKNOWN，不重新启动。 */
    public Status status(PrincipalRef principal,LocalProjectBinding binding,UUID id)throws IOException {
        authorizeMetadata(principal,binding);
        var operation=owned(principal,binding,id);
        Active found=active.get(id);
        if(found!=null){
            synchronized(found){
                // 输出缓存不拥有状态权威；GC和人工终结可能已推进数据库版本，不能持续返回旧版本。
                // uncertain只在本实例Job关闭后或提交启动任务失败时设置，此时可补记UNKNOWN，绝不重新派发。
                if(found.uncertain&&operation.state()==LocalProjectOperation.State.RUNNING){
                    operation=operations.advance(binding.tenantId(),binding.ownerId(),id,operation.version(),
                            LocalProjectOperation.State.UNKNOWN,clock.instant()).orElseGet(()->owned(principal,binding,id));
                }
                if(operation.version()>found.operation.version())found.operation=operation;
                return view(found);
            }
        }
        if(operation.state()==LocalProjectOperation.State.RUNNING){
            operation=operations.advance(binding.tenantId(),binding.ownerId(),id,operation.version(),
                    LocalProjectOperation.State.UNKNOWN,clock.instant()).orElse(operation);
        }
        return new Status(id,operation.state(),operation.version(),0,null,false,false,"","");
    }

    /** 只取消当前实例精确Job；不按PID/名称扫杀，不伪称撤销命令已经产生的外部副作用。 */
    public Status cancel(PrincipalRef principal,LocalProjectBinding binding,UUID id)throws IOException {
        authorizeMetadata(principal,binding);
        owned(principal,binding,id);
        Active found=active.get(id);
        if(found==null)return status(principal,binding,id);
        found.cancel.set(true);
        return status(principal,binding,id);
    }

    private void monitor(Active state,WindowsLocalProjectProcess.Launch launch){
        LocalProjectOperation.State terminal=LocalProjectOperation.State.UNKNOWN;
        Integer exit=null;
        try(var running=new WindowsLocalProjectProcess().start(launch,true)){
            long deadline=System.nanoTime()+Duration.ofSeconds(state.manifest.timeoutSeconds()).toNanos();
            while(true){
                boolean any=drain(state,running);
                if(state.cancel.get()||System.nanoTime()>=deadline){running.stop(Duration.ofSeconds(5));terminal=state.cancel.get()?LocalProjectOperation.State.CANCELLED:LocalProjectOperation.State.FAILED;break;}
                if(!running.alive()){while(drain(state,running)){}exit=running.exitCode();terminal=exit==0?LocalProjectOperation.State.SUCCEEDED:LocalProjectOperation.State.FAILED;break;}
                if(!any)LockSupport.parkNanos(5_000_000);
            }
        }catch(IOException|RuntimeException failure){
            // 无法证明启动/停止/关闭结果时保留UNKNOWN；原异常可能包含路径，不存入状态或普通日志。
            terminal=LocalProjectOperation.State.UNKNOWN;
            diagnostic(state,failure,"LOCAL_PROJECT_COMMAND_UNKNOWN");
        }finally{
            synchronized(state){
                state.exit=exit;
                try{
                    auditor.append(state.operationBinding(),state.operation.id(),"COMMAND_"+terminal.name(),state.operation.digest());
                    state.operation=operations.advance(state.operation.tenantId(),state.operation.ownerId(),state.operation.id(),
                            state.operation.version(),terminal,clock.instant()).orElse(state.operation);
                }catch(RuntimeException failure){state.uncertain=true;diagnostic(state,failure,"LOCAL_PROJECT_COMMAND_PERSISTENCE");}
                state.sequence++;
            }
            slot.release();
            if(state.invocation!=null)state.invocation.close();
        }
    }

    private boolean drain(Active state,WindowsLocalProjectProcess.Running running)throws IOException{
        byte[] out=running.poll(false),err=running.poll(true);
        synchronized(state){
            int remaining=state.manifest.maxOutputBytes()-state.output.size()-state.errors.size();
            int accepted=remaining;
            int take=Math.min(remaining,out.length);state.output.write(out,0,take);remaining-=take;
            take=Math.min(remaining,err.length);state.errors.write(err,0,take);
            if(out.length+err.length>accepted)state.truncated=true;
            if(out.length+err.length>0)state.sequence++;
        }
        boolean any=out.length+err.length>0;Arrays.fill(out,(byte)0);Arrays.fill(err,(byte)0);return any;
    }
    private Status view(Active state){
        synchronized(state){
            // 人工CANCELLED仅终结原意图；保留不确定标志和输出，不能让缓存把已确认终结重新显示为UNKNOWN。
            boolean unresolved=state.uncertain&&state.operation.state()!=LocalProjectOperation.State.CANCELLED;
            return new Status(state.operation.id(),unresolved?LocalProjectOperation.State.UNKNOWN:state.operation.state(),
                    state.operation.version(),state.sequence,state.exit,state.truncated,true,
                    safe(state.output.toString(StandardCharsets.UTF_8),state),safe(state.errors.toString(StandardCharsets.UTF_8),state));
        }
    }
    private void prune(){
        if(active.size()<32)return;
        for(var entry:active.entrySet()){
            synchronized(entry.getValue()){
                var state=entry.getValue().operation.state();
                if(state!=LocalProjectOperation.State.RUNNING&&state!=LocalProjectOperation.State.PREPARED){
                    active.remove(entry.getKey(),entry.getValue());
                    if(active.size()<32)return;
                }
            }
        }
        throw new IllegalStateException("本机命令历史缓存已满");
    }
    private static void diagnostic(Active state,Throwable failure,String code){
        var safe=new IllegalStateException("本机命令结果或持久状态无法核实");
        safe.setStackTrace(failure.getStackTrace());
        LOG.error("errorId={} operation=LOCAL_PROJECT_COMMAND errorCode={} tenantId={} principalId={} resourceType=LOCAL_PROJECT resourceId={} runId={}",
                state.operation.id(),code,state.operation.tenantId(),state.operation.ownerId(),state.operation.projectId(),state.operation.runId(),safe);
    }
    private String safe(String text,Active state){
        String value=text.replace(state.root.toString(),"<项目目录>");
        for(var entry:state.manifest.environment().entrySet()){
            if(!entry.getValue().isEmpty()&&(sensitive(entry.getKey())||entry.getValue().length()>=8))
                value=value.replace(entry.getValue(),"<受控环境值>");
        }
        value=redactor.redact(value);
        return value.replaceAll("[A-Za-z]:[\\\\/][^\\s\\\"<>]+","<本机路径>")
                .replaceAll("\\u001b\\[[0-?]*[ -/]*[@-~]","").replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]","");
    }
    private void authorize(PrincipalRef principal,LocalProjectBinding binding,LocalProjectDirectoryTickets.Root root)throws IOException{
        authorizeMetadata(principal,binding);
        if(!hash(root.identity().getBytes(StandardCharsets.UTF_8)).equals(binding.rootIdentity()))throw new IllegalArgumentException("命令根身份不一致");
        new LocalProjectFiles(List.of()).list(root,"",null,1);
    }
    private void authorizeMetadata(PrincipalRef principal,LocalProjectBinding binding)throws IOException{
        if(binding.runId()==null||!principal.tenantId().equals(binding.tenantId())||!principal.principalId().equals(binding.ownerId())
                ||!principal.permissions().contains("project:execute")||!principal.permissions().contains("agent:run"))
            throw new IllegalArgumentException("无权执行该项目命令");
        authorizer.check(principal,binding);
    }
    private LocalProjectOperation owned(PrincipalRef principal,LocalProjectBinding binding,UUID id){
        var value=operations.operation(principal.tenantId(),principal.principalId(),id).orElseThrow(()->new IllegalArgumentException("命令操作不存在"));
        if(value.kind()!=LocalProjectOperation.Kind.COMMAND||!value.projectId().equals(binding.projectId())||!value.runId().equals(binding.runId())
                ||!value.agentId().equals(binding.agentId())||!value.conversationId().equals(binding.conversationId()))throw new IllegalArgumentException("命令操作不存在");
        return value;
    }
    private Manifest decode(LocalProjectOperation operation,LocalProjectBinding binding)throws IOException{
        // 分批GC不是到期闸门；过了固定七天，即使正文尚在也不能再派发旧清单。
        if(!operation.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant()))throw new IOException("命令清单保留期限已到，请重新提案");
        try{
            String json=cipher.decrypt(operation.encryptedPayload());
            if(!hash(json.getBytes(StandardCharsets.UTF_8)).equals(operation.digest()))throw new IllegalArgumentException();
            Manifest value=mapper.readValue(json,Manifest.class);
            if(!value.binding().equals(binding))throw new IllegalArgumentException();
            return value;
        }catch(IOException|RuntimeException failure){throw new IOException("命令清单无法核实");}
    }
    private static Path directory(LocalProjectDirectoryTickets.Root root,String relative)throws IOException{
        com.cmagent.core.runtime.LocalProjectPathPolicy.requireRelative(relative,true);
        if(!relative.isEmpty()&&com.cmagent.core.runtime.LocalProjectPathPolicy.excluded(relative))throw new IllegalArgumentException("命令工作目录不受支持");
        Path current=root.directory();
        if(!relative.isEmpty())for(String part:relative.split("/")){
            current=current.resolve(part);
            if(!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(current)||!current.equals(current.toRealPath()))
                throw new IOException("命令工作目录不可用");
        }
        return current;
    }
    private Map<String,String> buildHashes(LocalProjectDirectoryTickets.Root root)throws IOException{
        var hashes=new TreeMap<String,String>();
        var files=new LocalProjectFiles(List.of());
        for(String path:List.of("pom.xml","package.json","package-lock.json","build.gradle","build.gradle.kts","settings.gradle","Makefile")){
            try{byte[] content=files.mutationSnapshot(root,path);try{hashes.put(path,hash(content));}finally{Arrays.fill(content,(byte)0);}}
            catch(LocalProjectFileException missing){if(missing.reason()!=LocalProjectFileException.Reason.NOT_FOUND)throw missing;}
        }
        return Map.copyOf(hashes);
    }
    private String json(Object value)throws IOException{return mapper.writeValueAsString(value);}
    private <T> T metadata(java.util.function.Supplier<T> action){
        return transaction==null?action.get():transaction.execute(status->action.get());
    }
    private static String fileHash(Path file)throws IOException{
        try(var stream=Files.newInputStream(file)){var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];long count=0;
            for(int n;(n=stream.read(buffer))>=0;){count+=n;if(count>134217728)throw new IOException("工具链程序超过校验预算");digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException failure){throw new IOException("命令摘要能力不可用");}
    }
    private static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception failure){throw new IllegalStateException("命令摘要能力不可用");}}
    private static boolean sensitive(String key){return key.toUpperCase(Locale.ROOT).matches(".*(SECRET|PASSWORD|TOKEN|API_KEY|PRIVATE_KEY).*");}
    private static boolean sensitiveAssignment(String value){return value.matches("(?is).*(password|passwd|api[-_.]?key|secret|token)\\s*[:=].*")
            ||value.matches("(?i)--?(password|passwd|api[-_.]?key|secret|token)")||value.matches("(?i).*Bearer\\s+.+");}

    @Override public void close(){for(Active value:active.values())value.cancel.set(true);workers.shutdown();try{if(!workers.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("本机命令仍在停止中");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("本机命令停止被中断");}}

    /** 当前授权撤销/归档/节点校验，不可由外部请求提供实现。 */
    @FunctionalInterface public interface Authorizer{void check(PrincipalRef principal,LocalProjectBinding binding)throws IOException;}
    /** 只接收安全元数据；宿主负责同号审计与事务边界。 */
    @FunctionalInterface public interface Auditor{void append(LocalProjectBinding binding,UUID operationId,String action,String digest);}

    /**
     * @param executable 服务端固定的EXE绝对路径
     * @param prefixArguments 显式解释器/固定启动参数，属于批准清单
     * @param environment 完整受控基础环境，不自动继承宿主环境
     * @param allowedEnvironment 用户可覆盖的环境名称
     * @param build 是否是构建工具，不减弱批准或权限
     */
    public record Toolchain(Path executable,List<String> prefixArguments,Map<String,String> environment,Set<String> allowedEnvironment,boolean build){
        public Toolchain(Path executable,List<String> prefixArguments,Map<String,String> environment,Set<String> allowedEnvironment){this(executable,prefixArguments,environment,allowedEnvironment,false);}
        public Toolchain{prefixArguments=List.copyOf(prefixArguments);environment=Map.copyOf(environment);allowedEnvironment=Set.copyOf(allowedEnvironment);}
        @Override public String toString(){return "Toolchain[受保护配置]";}
    }
    /** @param toolchainId 部署工具链逻辑标识 @param executable 程序文件名，不含宿主目录 @param build 是否为明确配置的构建工具链 */
    record AllowedToolchain(String toolchainId,String executable,boolean build){}
    /**
     * @param toolchainId 服务端工具链标识
     * @param arguments 精确参数，不隐式执行shell语法
     * @param relativeDirectory 根内工作目录，空表示根
     * @param environment 获准的环境覆盖
     * @param timeoutSeconds 超时1至1800秒
     * @param maxOutputBytes 合计stdout/stderr最多1MiB
     */
    public record CommandRequest(String toolchainId,List<String> arguments,String relativeDirectory,Map<String,String> environment,int timeoutSeconds,int maxOutputBytes){
        public CommandRequest{arguments=List.copyOf(arguments);environment=Map.copyOf(environment);Objects.requireNonNull(relativeDirectory);if(arguments.size()>128||timeoutSeconds<1||timeoutSeconds>1800||maxOutputBytes<1||maxOutputBytes>1048576)throw new IllegalArgumentException("命令预算不合法");}
        @Override public String toString(){return "CommandRequest[受保护启动请求]";}
    }
    /**
     * @param operationId 已持久提案标识
     * @param version 当前版本
     * @param digest 精确清单摘要
     * @param toolchainId 允许工具链标识
     * @param arguments 精确已审核参数，不允许秘密赋值
     * @param relativeDirectory 根内目录
     * @param timeoutSeconds 获准超时
     * @param maxOutputBytes 获准输出预算
     */
    public record Proposal(UUID operationId,long version,String digest,String toolchainId,List<String> arguments,String relativeDirectory,int timeoutSeconds,int maxOutputBytes){}
    /**
     * @param operationId 固定操作
     * @param state 持久状态，失联/失败核对为UNKNOWN
     * @param version 条件版本
     * @param sequence 当前安全快照序号
     * @param exitCode 已确认退出码，未确认时为空
     * @param truncated 是否达到输出上限
     * @param outputAvailable 本实例是否仍持有输出
     * @param stdout 完整脱敏输出快照
     * @param stderr 完整脱敏错误输出快照
     */
    public record Status(UUID operationId,LocalProjectOperation.State state,long version,long sequence,Integer exitCode,boolean truncated,boolean outputAvailable,String stdout,String stderr){
        @Override public String toString(){return "Status[operationId="+operationId+",state="+state+"]";}
    }
    /**
     * @param binding 固定主体/Run/根/节点
     * @param toolchainId 固定工具链标识
     * @param executable 服务端允许程序绝对路径，只进入密文
     * @param executableHash 批准时程序身份
     * @param arguments 精确参数
     * @param relativeDirectory 根内目录
     * @param environment 精确环境
     * @param timeoutSeconds 精确超时
     * @param maxOutputBytes 精确输出预算
     * @param buildHashes 已审阅的主要构建配置摘要，不承诺覆盖所有插件/外部副作用
     */
    public record Manifest(LocalProjectBinding binding,String toolchainId,String executable,String executableHash,List<String> arguments,String relativeDirectory,Map<String,String> environment,int timeoutSeconds,int maxOutputBytes,Map<String,String> buildHashes){
        public Manifest{arguments=List.copyOf(arguments);environment=Map.copyOf(environment);buildHashes=Map.copyOf(buildHashes);}
        @Override public String toString(){return "Manifest[受保护执行清单]";}
    }
    private static final class Active{
        /** 从进入派发保持到Job实际关闭，供Run停止等待真实资源退出。 */ private com.cmagent.core.runtime.RunCancellation.Invocation invocation;
        /** 仅在实例锁内更新的持久状态镜像。 */ private LocalProjectOperation operation;
        /** 批准的不可变清单。 */ private final Manifest manifest;
        /** 仅用于清理输出中的宿主路径。 */ private final Path root;
        /** 停止请求不意味着已停止。 */ private final AtomicBoolean cancel=new AtomicBoolean();
        /** 有界stdout，由监控者写入。 */ private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        /** 有界stderr，由监控者写入。 */ private final ByteArrayOutputStream errors=new ByteArrayOutputStream();
        /** 单调快照版本。 */ private long sequence;
        /** 只有确认退出后才设置。 */ private Integer exit;
        /** 超额数据被丢弃但管道继续排空。 */ private boolean truncated;
        /** 最终持久化/审计失败不能伪称已成功。 */ private boolean uncertain;
        private Active(LocalProjectOperation operation,Manifest manifest,Path root){this.operation=operation;this.manifest=manifest;this.root=root;}
        private LocalProjectBinding operationBinding(){return manifest.binding();}
    }
}

