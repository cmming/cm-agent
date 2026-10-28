package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.*;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 导入API编排：认证权限/归属→有界原始正文→私有密文→短事务元数据/严格审计。
 * 文件I/O在事务外；写结果未知只能GET核对，同请求关联标识不会创建第二批次。
 */
@Service
public final class WorkspaceImportService {
    /** 运行来源见证是导入执行前置条件，旧嵌入实现不能被能力接口误报为可用。 */
    private RunRepository runtimeRuns;
    /** 会话头见证防止绑定行丢失后回退默认来源。 */
    private ConversationRepository runtimeConversations;
    @org.springframework.beans.factory.annotation.Autowired
    void configureRuntimeRepositories(RunRepository runs,ConversationRepository conversations){runtimeRuns=runs;runtimeConversations=conversations;}
    /**
     * 仅对宿主已授权的固定元数据提供可用性，不做文件I/O，不输出路径。
     * @param scope 服务器元数据形成的可信归属
     * @param id 固定批次
     * @param digest 原manifest身份
     * @param frozenRun 已冻结运行可在关闭新上传后排空，新绑定/新运行则拒绝
     * @return 当前状态、身份和预算是否仍可用
     */
    boolean available(WorkspaceImportScope scope,UUID id,String digest,boolean frozenRun){
        if(!policy.isEnabled() || !frozenRun && !runtimeEnabled())return false;
        return rows.find(scope,id).filter(v->v.usable(Instant.now()) && v.manifestDigest().equals(digest)
                && v.files().size()<=policy.getMaxFiles() && v.totalBytes()<=policy.getMaxTotalBytes()
                && v.files().stream().allMatch(f->f.sizeBytes()<=policy.getMaxFileBytes())).isPresent();
    }
    private boolean runtimeEnabled(){return enabled() && runtimeRuns!=null && runtimeRuns.supportsWorkspaceProtocol()
            && runtimeConversations!=null && runtimeConversations.supportsWorkspaceProtocol();}
    /** 部署者预算和独立开关，不能由客户端覆盖。 */
    private final WorkspaceContextProperties policy;
    /** 原子配额、CAS和跨实例租约。 */
    private final WorkspaceImportRepository rows;
    /** Agent归属来自服务端仓储。 */
    private final AgentDefinitionRepository agents;
    /** 关闭新上传时仍需允许已有对象查询/撤销。 */
    private final ObjectProvider<WorkspaceImportStorage> objects;
    /** 只使用认证权限，不能信任请求体tenant/owner。 */
    private final PermissionEvaluator permissions;
    /** 严格审计失败不能发布成功结果。 */
    private final AuditAppender audit;
    /** JDBC共用短事务；memory通过撤销补偿失败写入。 */
    private final ObjectProvider<TransactionTemplate> transactions;
    /** 独立有界HTTP读取池。 */
    private final WorkspaceImportBodyReader reader;
    /** 严格拒绝未知字段，禁止把客户端tenant/绝对路径静默忽略为可信输入。 */
    private final ObjectMapper mapper;
    /** DTO约束在有界解析后执行，不能让MVC先无界读取正文。 */
    private final Validator validator;
    /** 每实例最多四个导入操作，读取线程另有相同真实上限。 */
    private final Semaphore operations = new Semaphore(4);
    /** 单次HTTP读取总等待预算。 */
    private static final Duration BODY_TIMEOUT = Duration.ofSeconds(5);
    /** 固定结构化诊断，不记录DTO、文件名或正文。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceImportService.class);

    /**
     * @param policy 部署策略
     * @param rows 导入仓储
     * @param agents Agent仓储
     * @param objects 私有存储
     * @param permissions 权限策略
     *
     * @param audit 严格审计
     * @param transactions 共用短事务
     * @param reader 有界读取
     * @param mapper JSON解析器
     * @param validator DTO校验器 */
    public WorkspaceImportService(WorkspaceContextProperties policy, WorkspaceImportRepository rows, AgentDefinitionRepository agents,
            ObjectProvider<WorkspaceImportStorage> objects, PermissionEvaluator permissions, AuditAppender audit,
            ObjectProvider<TransactionTemplate> transactions, WorkspaceImportBodyReader reader, ObjectMapper mapper, Validator validator) {
        this.policy=policy; this.rows=rows; this.agents=agents; this.objects=objects; this.permissions=permissions;
        this.audit=audit; this.transactions=transactions; this.reader=reader;
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.validator=validator;
    }

    /** 最终诊断边界只记录一次，HTTP/SSE复用异常内同一errorId；op必须来自Controller固定常量。 */
    public <T> T execute(PrincipalRef principal, UUID agent, UUID resource, String op, String errorId, Action<T> action) {
        var context = new Context(principal, agent, resource == null ? agent : resource, op, errorId);
        boolean acquired = false;
        try {
            for (String permission : List.of("agent:read", "agent:run")) if (!permissions.check(principal, permission).allowed())
                throw failure(context, ApiErrorCode.FORBIDDEN, "需要Agent读取与运行权限才能操作导入资料");
            if (agents.findByTenantAndId(principal.tenantId(), agent).isEmpty()) throw missing(context);
            acquired = operations.tryAcquire();
            if (!acquired) throw failure(context, ApiErrorCode.WORKSPACE_IMPORT_LIMIT_EXCEEDED, "导入请求繁忙，请稍后显式重试并先核对已有结果");
            return action.run(context);
        } catch (Exception cause) {
            // 会话创建/改绑可在外层短事务调用本服务；失败必须等外层回滚释放租户锁后再严格写审计。
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw new DeferredWorkspaceFailure(()->diagnose(context,cause));
            throw diagnose(context, cause);
        }
        finally { if (acquired) operations.release(); }
    }

    /** @return 不带文件名/manifest的能力描述；客户端应在用户点击前预加载 */
    public Capabilities capabilities(Context c) {
        var limits=policy.getImports().limits(policy);
        return new Capabilities(enabled(), List.of("SERVER_REGISTERED", "COMPUTER_IMPORT"), List.of(".md", ".txt", ".json", ".csv"),
                limits.maxFiles(), limits.maxFileBytes(), limits.maxTotalBytes(), limits.ownerImports(), limits.tenantImports(),
                limits.ownerBytes(), limits.tenantBytes(), limits.draftTtl().toSeconds(), limits.readyTtl().toSeconds(),
                1000, 16, 5000, 4, BODY_TIMEOUT.toMillis(), runtimeEnabled());
    }

    /** 有界JSON解析由Controller委托，仍位于认证/权限及诊断边界之内。 */
    public <T> T json(Context c, WorkspaceImportBodyReader.InputFactory input, long contentLength, String contentType, Class<T> type, int maxBytes) throws Exception {
        contentType(c, contentType, "application/json");
        byte[] bytes=body(c, input, contentLength, maxBytes, true);
        try {
            T value;
            try { value=mapper.readValue(bytes,type); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_INVALID,"导入请求格式不合法或包含不支持字段"); }
            if (value==null || !validator.validate(value).isEmpty()) throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_INVALID,"导入参数不合法，请核对确认状态、清单和版本");
            return value;
        } finally { Arrays.fill(bytes,(byte)0); }
    }

    /** 原确认的关联标识不能重复创建；服务端生成importId及所有fileId。 */
    public View create(Context c, Create request) {
        requireEnabled(c);
        var limits=policy.getImports().limits(policy);
        if (request.files().size()>limits.maxFiles() || request.files().stream().anyMatch(f -> f.sizeBytes()>limits.maxFileBytes())
                || request.files().stream().mapToLong(FileRequest::sizeBytes).sum()>limits.maxTotalBytes())
            throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONTENT_INVALID,"所选资料超过当前文件数量或字节预算，请缩小选择范围");
        WorkspaceImport draft;
        try {
            var files=request.files().stream().map(f -> new WorkspaceImportFile(UUID.randomUUID(), f.relativePath(),f.sizeBytes(),f.sha256())).toList();
            var now=Instant.now(); var base=WorkspaceImport.draft(UUID.randomUUID(),scope(c),request.displayName(),files,now,now.plus(limits.draftTtl()));
            draft=new WorkspaceImport(base.id(),base.scope(),base.displayName(),base.files(),base.manifestDigest(),base.state(),base.uploaded(),base.version(),base.createdAt(),base.expiresAt(),false,request.requestId());
        } catch (IllegalArgumentException invalid) { throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_INVALID,"导入名称、相对路径或清单不合法，请排除敏感名称和重复路径"); }
        // 只有服务端创建完成后才替换诊断目标，创建失败的日志不能误把Agent标识标成批次标识。
        c.resourceId=draft.id(); var actual=c;
        return mutate(actual,()-> {
            if (rows.findByRequest(scope(c),request.requestId()).isPresent()) throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"同一次确认已经创建批次，请先按请求关联标识GET核对");
            if (!rows.reserve(draft,limits,Instant.now())) {
                if (rows.findByRequest(scope(c),request.requestId()).isPresent()) throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"同一次确认已经创建批次，请先GET核对");
                throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_LIMIT_EXCEEDED,"导入保留配额已满，请撤销并等待回收或缩小选择范围");
            }
            success(actual); return view(draft);
        });
    }

    /** 授权查询不受上传开关影响；READY可绑定性还须满足运行装配、预算与当前开关。 */
    public View get(Context c, UUID id) { var value=require(c,id);success(c);return view(value); }
    /** 在解析complete版本正文前先核对归属，跨owner的格式错误也不能替代统一404。 */
    public void requireOwner(Context c, UUID id){require(c,id);}
    /** 创建manifest读取前检查部署开关，不在功能关闭时解析用户文件元数据。 */
    public void requireUploadEnabled(Context c){requireEnabled(c);}
    /** @return 原主体未知创建结果；不会创建、补写或上传任何资料 */
    public View getByRequest(Context c, UUID requestId) { var value=rows.findByRequest(scope(c),requestId).orElseThrow(()->missing(c));c.resourceId=value.id();success(c);return view(value); }

    /** 单文件原始正文；先校验owner/版本/状态才获取流，已确认文件不允许覆盖。 */
    public View upload(Context c, UUID id, UUID fileId, long version, WorkspaceImportBodyReader.InputFactory input, long contentLength, String contentType) throws Exception {
        var batch=require(c,id); requireEnabled(c);
        var file=batch.files().stream().filter(f->f.id().equals(fileId)).findFirst().orElseThrow(()->missing(c));
        draft(c,batch,version);
        if(file.sizeBytes()>policy.getMaxFileBytes() || batch.files().size()>policy.getMaxFiles() || batch.totalBytes()>policy.getMaxTotalBytes())
            throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONTENT_INVALID,"当前导入预算已收紧，请缩小选择范围并重新确认");
        if (batch.uploaded().contains(fileId)) throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"该文件已接收，不允许覆盖，请GET核对");
        contentType(c,contentType,"application/octet-stream");
        var lease=lease(c,batch); byte[] bytes=null;
        try {
            // HTTP硬界按当前策略，实际读取按声明长度+1；长度不符由文本校验拒绝，不静默截断。
            if (contentLength>policy.getMaxFileBytes()) throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_TOO_LARGE,"上传正文超过单文件上限");
            bytes=body(c,input,-1,file.sizeBytes(),false);
            try { storage(c).write(batch,file,new ByteArrayInputStream(bytes)); }
            catch (FileSystemWorkspaceImportStorage.ContentFailure invalid) { throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONTENT_INVALID,invalid.getMessage()); }
            final var stored=mutate(c,()-> {
                var saved=rows.stored(scope(c),id,fileId,version,lease.token(),Instant.now()).orElseThrow(()->conflict(c));
                success(c); return saved;
            });
            return view(stored);
        } finally { if(bytes!=null)Arrays.fill(bytes,(byte)0); rows.release(scope(c),id,lease.token()); }
    }

    /** 全量复核真实密文；可恢复已落盘但确认响应未知的文件，不接受新正文、不覆盖对象。 */
    public View complete(Context c, UUID id, long version) throws Exception {
        var batch=require(c,id); requireEnabled(c); draft(c,batch,version); var lease=lease(c,batch);
        try {
            for(var file:batch.files()) {
                if(!storage(c).contains(batch,file)) {
                    if(batch.uploaded().contains(file.id())) throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_UNAVAILABLE,"已接收资料密文缺失，请撤销批次并联系管理员");
                    throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"尚有文件未上传，不能完成导入");
                }
                byte[] verified=storage(c).readVerified(batch,file); Arrays.fill(verified,(byte)0);
            }
            return view(mutate(c,()-> {
                var current=require(c,id); draft(c,current,version);
                for(var file:current.files()) if(!current.uploaded().contains(file.id()))
                    current=rows.stored(scope(c),id,file.id(),current.version(),lease.token(),Instant.now()).orElseThrow(()->conflict(c));
                var ready=rows.complete(scope(c),id,current.version(),lease.token(),policy.getImports().limits(policy),Instant.now()).orElseThrow(()->conflict(c));
                success(c); return ready;
            }));
        } finally { rows.release(scope(c),id,lease.token()); }
    }

    /** 关闭上传后仍能撤销；先逻辑撤销，GC按租约异步回收且不提前归还容量。 */
    public View revoke(Context c, UUID id, long version) {
        var batch=require(c,id);
        if(version<1)throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_INVALID,"导入版本必须大于零");
        if(batch.version()!=version) throw conflict(c);
        return view(mutate(c,()-> {
            var revoked=rows.revoke(scope(c),id,version,Instant.now()).orElseThrow(()->conflict(c));success(c);return revoked;
        }));
    }

    /** 为新绑定和新Run提供归属、READY、期限与部署开关检查，已冻结Run恢复使用专用运行校验。 */
    public WorkspaceImport requireReady(PrincipalRef principal, UUID agent, UUID id) {
        return execute(principal,agent,id,"BIND_CHECK",UUID.randomUUID().toString(),c->{
            var value=require(c,id); requireEnabled(c);
            if(!value.expiresAt().isAfter(Instant.now()))throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_EXPIRED,"导入资料已过期，请在新会话重新导入");
            if(!value.usable(Instant.now()))throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"仅完整且未撤销的READY导入可绑定");
            return value;
        });
    }

    private byte[] body(Context c, WorkspaceImportBodyReader.InputFactory input, long length, int max, boolean strict) throws Exception {
        if(length>max)throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_TOO_LARGE,"导入请求正文超过上限");
        byte[] bytes=reader.read(input,max+1,BODY_TIMEOUT);
        if(strict && bytes.length>max || !strict && bytes.length>policy.getMaxFileBytes()){Arrays.fill(bytes,(byte)0);throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_TOO_LARGE,"导入请求正文超过上限");}
        return bytes;
    }
    private static void contentType(Context c,String raw,String expected) {
        try {
            if(raw==null || !org.springframework.http.MediaType.parseMediaType(raw).isCompatibleWith(org.springframework.http.MediaType.parseMediaType(expected))) throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid){throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_INVALID,"导入请求内容类型不符合接口约定");}
    }
    private WorkspaceImport require(Context c, UUID id){return rows.find(scope(c),id).orElseThrow(()->missing(c));}
    private WorkspaceImportLease lease(Context c,WorkspaceImport batch){return rows.acquire(scope(c),batch.id(),WorkspaceImportLease.Kind.UPLOAD,Duration.ofMinutes(1),Instant.now())
            .orElseThrow(()->failure(c,ApiErrorCode.WORKSPACE_IMPORT_LIMIT_EXCEEDED,"该批次正在上传或核对，请先GET查询状态"));}
    private static WorkspaceImportScope scope(Context c){return new WorkspaceImportScope(c.principal().tenantId(),c.principal().principalId(),c.agentId());}
    private boolean enabled(){return policy.isEnabled() && policy.getImports().isEnabled() && objects.getIfAvailable()!=null;}
    private void requireEnabled(Context c){if(!enabled())throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_DISABLED,"本机资料上传尚未由部署者启用");}
    private WorkspaceImportStorage storage(Context c){var value=objects.getIfAvailable();if(value==null)throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_UNAVAILABLE,"私有导入存储暂不可用");return value;}
    private static void draft(Context c,WorkspaceImport batch,long version){
        if(version<1)throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_INVALID,"导入版本必须大于零");
        if(!batch.expiresAt().isAfter(Instant.now()))throw failure(c,ApiErrorCode.WORKSPACE_IMPORT_EXPIRED,"导入草稿已过期，请重新确认资料");
        if(version<1 || batch.version()!=version || batch.state()!=WorkspaceImport.State.DRAFT || batch.reclaimed())throw conflict(c);
    }
    private <T>T mutate(Context c,Supplier<T> operation){
        var tx=transactions.getIfAvailable();
        try{return tx==null?operation.get():tx.execute(status->operation.get());}
        catch(RuntimeException failure){
            // memory无跨仓储回滚；审计或基础设施失败后撤销已变更批次，绝不能留下可绑定的未审计READY。
            if(tx==null && !(failure instanceof WorkspaceContextException)){
                try{rows.find(scope(c),c.resourceId()).filter(v->v.state()==WorkspaceImport.State.DRAFT || v.state()==WorkspaceImport.State.READY)
                        .ifPresent(v->rows.revoke(scope(c),v.id(),v.version(),Instant.now()));}
                catch(RuntimeException compensation){failure.addSuppressed(compensation);}
            }
            throw failure;
        }
    }
    private void success(Context c){audit.appendControlled(c.principal().tenantId(),c.principal().principalId(),"WORKSPACE_IMPORT_"+c.operation(),"WORKSPACE_IMPORT",c.resourceId().toString(),"SUCCEEDED","errorId="+c.errorId());}
    private WorkspaceContextException diagnose(Context c,Exception cause){
        var code=cause instanceof WorkspaceContextException known?known.code():cause instanceof AuditPersistenceException?ApiErrorCode.AUDIT_UNAVAILABLE
                :cause instanceof DataAccessException?ApiErrorCode.PERSISTENCE_UNAVAILABLE:cause instanceof TimeoutException?ApiErrorCode.WORKSPACE_IMPORT_UPLOAD_TIMEOUT
                :cause instanceof RejectedExecutionException?ApiErrorCode.WORKSPACE_IMPORT_LIMIT_EXCEEDED:cause instanceof IOException?ApiErrorCode.WORKSPACE_IMPORT_UNAVAILABLE:ApiErrorCode.INTERNAL_ERROR;
        String message=cause instanceof WorkspaceContextException known?known.getMessage():switch(code){
            case AUDIT_UNAVAILABLE -> "导入审计暂不可用，请先GET核对结果";
            case PERSISTENCE_UNAVAILABLE -> "导入状态存储暂不可用，请先GET核对结果";
            case WORKSPACE_IMPORT_UPLOAD_TIMEOUT -> "读取上传正文超时，请先GET核对批次后再操作";
            case WORKSPACE_IMPORT_LIMIT_EXCEEDED -> "导入读取并发已满，请稍后显式重试";
            case WORKSPACE_IMPORT_UNAVAILABLE -> "导入私有存储或完整性核对失败，请凭错误编号联系管理员";
            default -> "导入操作发生内部错误，请凭错误编号联系管理员";
        };
        if(code!=ApiErrorCode.AUDIT_UNAVAILABLE)try{audit.appendControlled(c.principal().tenantId(),c.principal().principalId(),"WORKSPACE_IMPORT_"+c.operation(),"WORKSPACE_IMPORT",c.resourceId().toString(),"FAILED","code="+code+" errorId="+c.errorId());}
        catch(AuditPersistenceException unavailable){code=ApiErrorCode.AUDIT_UNAVAILABLE;message="导入拒绝审计暂不可用，请凭错误编号联系管理员";cause=unavailable;}
        boolean internal=Set.of(ApiErrorCode.AUDIT_UNAVAILABLE,ApiErrorCode.PERSISTENCE_UNAVAILABLE,ApiErrorCode.WORKSPACE_IMPORT_UNAVAILABLE,ApiErrorCode.INTERNAL_ERROR).contains(code);
        if(internal){var safe=new IllegalStateException(message);safe.setStackTrace(cause.getStackTrace());log.error("errorId={} operation=WORKSPACE_IMPORT_{} errorCode={} tenantId={} principalId={} agentId={} resourceType=WORKSPACE_IMPORT resourceId={}",c.errorId(),c.operation(),code,c.principal().tenantId(),c.principal().principalId(),c.agentId(),c.resourceId(),safe);}
        else log.warn("errorId={} operation=WORKSPACE_IMPORT_{} errorCode={} tenantId={} principalId={} agentId={} resourceType=WORKSPACE_IMPORT resourceId={}",c.errorId(),c.operation(),code,c.principal().tenantId(),c.principal().principalId(),c.agentId(),c.resourceId());
        var result=new WorkspaceContextException(code,message,c.errorId());result.claimDiagnostic();return result;
    }
    private static WorkspaceContextException failure(Context c,ApiErrorCode code,String message){return new WorkspaceContextException(code,message,c.errorId());}
    private static WorkspaceContextException missing(Context c){return failure(c,ApiErrorCode.WORKSPACE_IMPORT_NOT_FOUND,"资料或派生资源不存在");}
    private static WorkspaceContextException conflict(Context c){return failure(c,ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"导入状态或版本已变化，请先GET核对，不要重复提交");}
    private View view(WorkspaceImport v){
        var state=v.state();if((state==WorkspaceImport.State.DRAFT || state==WorkspaceImport.State.READY) && !v.expiresAt().isAfter(Instant.now()))state=WorkspaceImport.State.EXPIRED;
        return new View(v.id(),v.requestId(),v.displayName(),state,v.version(),v.manifestDigest(),v.files().stream().map(f->new FileView(f.id(),f.relativePath(),f.sizeBytes(),f.sha256(),v.uploaded().contains(f.id()))).toList(),v.expiresAt(),v.usable(Instant.now()),v.usable(Instant.now()) && runtimeEnabled(),v.reclaimed());
    }

    /** 单请求持有的可信诊断上下文；不保存正文，创建后的实际资源只允许由封装服务更新。 */
    public static final class Context {
        /** 来自认证JwtSession的主体。 */ private final PrincipalRef principal;
        /** 权限检查后核实归属的Agent。 */ private final UUID agentId;
        /** 格式已校验的尝试目标；创建后仅由服务端更新为实际批次标识。 */ private UUID resourceId;
        /** Controller固定操作常量。 */ private final String operation;
        /** 同号请求关联，不作为身份凭据。 */ private final String errorId;
        private Context(PrincipalRef principal,UUID agentId,UUID resourceId,String operation,String errorId){this.principal=principal;this.agentId=agentId;this.resourceId=resourceId;this.operation=operation;this.errorId=errorId;}
        /** @return 可信主体 */ public PrincipalRef principal(){return principal;}
        /** @return 已核实Agent */ public UUID agentId(){return agentId;}
        /** @return 当前诊断目标 */ public UUID resourceId(){return resourceId;}
        /** @return 固定操作 */ public String operation(){return operation;}
        /** @return 请求关联编号 */ public String errorId(){return errorId;}
    }
    /**
     * @param displayName 文件夹展示名称
     * @param requestId 同一次用户确认的随机UUID，用于GET找回创建结果
     *
     * @param confirmed 用户明确确认上传，默认false不能创建
     * @param files 用户选定的有界文件清单 */
    public record Create(@NotBlank @Size(max=120) String displayName,@NotNull UUID requestId,@AssertTrue boolean confirmed,@NotEmpty @Size(max=8) List<@NotNull @Valid FileRequest> files){}
    /**
     * @param relativePath 去除单根后的相对文件路径
     * @param sizeBytes 声明长度，实际仍须重读复核
     * @param sha256 声明摘要，实际仍须重算 */
    public record FileRequest(@NotBlank @Size(max=160) String relativePath,@Min(1) @Max(32768) int sizeBytes,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String sha256){}
    /**
     * @param expectedVersion 最后一次GET所见的CAS版本 */
    public record Version(@Min(1) long expectedVersion){}
    /**
     * @param id 服务端文件标识
     * @param relativePath 已授权清单路径
     * @param sizeBytes 固定字节
     * @param sha256 固定摘要
     * @param uploaded 是否已确认接收 */
    public record FileView(UUID id,String relativePath,int sizeBytes,String sha256,boolean uploaded){}
    /**
     * @param id 服务端批次标识
     * @param requestId 原确认关联
     * @param displayName 固定名称
     * @param state 含有效期限的状态
     *
     * @param version CAS版本
     * @param manifestDigest 固定身份
     * @param files 安全清单/进度
     * @param expiresAt 固定截止
     *
     * @param ready 是否完整有效
     * @param bindable 是否完整有效且当前部署和运行装配允许新绑定
     * @param reclaimed 是否已物理回收 */
    public record View(UUID id,UUID requestId,String displayName,WorkspaceImport.State state,long version,String manifestDigest,List<FileView> files,Instant expiresAt,boolean ready,boolean bindable,boolean reclaimed){}
    /**
     * @param enabled 是否可创建/上传
     * @param sources 来源种类
     * @param allowedTypes 支持文本类型
     * @param maxFiles 文件数预算
     *
     * @param maxFileBytes 单文件预算
     * @param maxTotalBytes 总预算
     * @param maxImportsPerOwner 主体批次配额
     * @param maxImportsPerTenant 租户批次配额
     *
     * @param maxBytesPerOwner 主体字节配额
     * @param maxBytesPerTenant 租户字节配额
     * @param draftTtlSeconds 草稿期限
     * @param readyTtlSeconds 就绪期限
     *
     * @param maxCandidates 本地枚举条目硬界
     * @param maxDepth 本地枚举深度
     * @param enumerationTimeoutMillis 本地枚举时间预算
     *
     * @param maxConcurrentRequests 本实例操作硬界
     * @param uploadTimeoutMillis HTTP正文总等待预算
     * @param runtimeAvailable 当前运行和会话仓储是否支持来源见证且允许新导入 */
    public record Capabilities(boolean enabled,List<String> sources,List<String> allowedTypes,int maxFiles,int maxFileBytes,int maxTotalBytes,
            int maxImportsPerOwner,int maxImportsPerTenant,long maxBytesPerOwner,long maxBytesPerTenant,long draftTtlSeconds,long readyTtlSeconds,
            int maxCandidates,int maxDepth,int enumerationTimeoutMillis,int maxConcurrentRequests,long uploadTimeoutMillis,boolean runtimeAvailable){}
    /** 只有固定Controller流程可委托执行，checked异常由最终边界统一转换。 */
    @FunctionalInterface public interface Action<T>{/** @return 当前操作的已授权安全结果 */ T run(Context context) throws Exception;}
}
