package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 仅在通用工具grant复核之后执行固定项目工具；HTTP调试和MCP不会调用此专用入口。 */
public final class LocalProjectTools {
    /** 原始Run归属和活动状态。 */ private final RunRepository runs;
    /** 加密意图、精确版本和至多一次派发。 */ private final LocalProjectOperationRepository operations;
    /** 真实HIGH审批证据，不能用请求approved布尔值替代。 */ private final ToolApprovalRepository approvals;
    /** 固定目录和可信工具ID。 */ private final LocalProjectToolCatalog catalog;
    /** 本轮冻结工具集合。 */ private final LocalProjectRunService runtime;
    /** 当前owner/RBAC/根身份。 */ private final LocalProjectAccessService access;
    /** 已脱敏有界文件响应。 */ private final LocalProjectService projects;
    /** 模型首次读取版本，不能用浏览器实时预览替代Run一致性。 */ private final LocalProjectReadSnapshots reads;
    /** 受保护变更服务，不直接暴露原生写入。 */ private final LocalProjectChangeService changes;
    /** 精确清单与受管Job。 */ private final LocalProjectCommandService commands;
    /** 固定目录/实例独占不能在运行中丢失。 */ private final LocalProjectStateLock state;
    /** 安全JSON解析/序列化，不记录原始输入。 */ private final ObjectMapper json;
    /** 仅用于受控专用预览，原始意图不进入通用工具摘要。 */ private final SensitiveDataRedactor redactor;
    /** 批准推进和审计共享短事务，真实文件/命令副作用在事务外。 */ private final TransactionTemplate transaction;
    /** 可信期限依据。 */ private final Clock clock;
    /** 延迟读取当前Run控制句柄，避免Runtime装配循环。 */ private final ObjectProvider<RunExecutionService> executions;

    LocalProjectTools(RunRepository runs, LocalProjectOperationRepository operations, ToolApprovalRepository approvals,
            LocalProjectToolCatalog catalog, LocalProjectRunService runtime, LocalProjectAccessService access, LocalProjectService projects,
            LocalProjectChangeService changes, LocalProjectCommandService commands, LocalProjectStateLock state, ObjectMapper json,
            SensitiveDataRedactor redactor, TransactionTemplate transaction, Clock clock, ObjectProvider<RunExecutionService> executions,LocalProjectReadSnapshots reads) {
        this.runs=runs;this.operations=operations;this.approvals=approvals;this.catalog=catalog;this.runtime=runtime;this.access=access;
        this.projects=projects;this.changes=changes;this.commands=commands;this.state=state;this.json=json;this.redactor=redactor;
        this.transaction=transaction;this.clock=clock;this.executions=executions;
        this.reads=reads;
    }

    boolean handles(ToolInvocationRequest request) { return LocalProjectToolCatalog.managed(request.tenantId(),request.toolId()); }

    /** 必须在通用ToolAuthorizationPolicy允许之后调用；所有载荷都带protected标志，不能退化为普通摘要存储。 */
    ToolInvocationResult invoke(ToolInvocationRequest request,ToolDefinition tool) {
        try {
            state.requireHeld();
            var run=runs.findByTenantAndAgentAndId(request.tenantId(),request.agentId(),request.runId()).orElse(null);
            if(run==null||!run.principalId().equals(request.principal().principalId())
                    ||run.status()!=RunStatus.RUNNING&&run.status()!=RunStatus.WAITING_APPROVAL
                    ||!catalog.valid(tool)||!runtime.allows(request.principal(),run,tool.id()))
                return ToolInvocationResult.deniedProtected("当前运行没有该固定项目工具的有效授权");
            var scope=access.run(request.principal(),run.id(),"project:read");
            Map<String,Object> input=parse(request.inputJson());
            var kind=LocalProjectToolCatalog.kind(request.tenantId(),tool.id()).orElseThrow();
            Object result=switch(kind) {
                case LIST -> { keys(input,Set.of("path","cursor","limit")); yield projects.tree(request.principal(),scope.project().id(),string(input,"path",""),string(input,"cursor",null),integer(input,"limit",100)); }
                case READ -> { keys(input,Set.of("path","offset","maxBytes","expectedSha256")); yield reads.read(request.principal(),scope,string(input,"path",null),integer(input,"offset",0),integer(input,"maxBytes",65536),string(input,"expectedSha256",null)); }
                case SEARCH -> { keys(input,Set.of("query","content")); yield reads.search(request.principal(),scope,string(input,"query",null),bool(input,"content",false)); }
                case TOOLCHAINS -> {keys(input,Set.of());yield commands.toolchains();}
                case PROPOSE_CHANGE -> {
                    keys(input,Set.of("changes")); access.authorize(request.principal(),"project:write",run.id());
                    if(!(input.get("changes") instanceof List<?>))throw new IllegalArgumentException();
                    var proposed=json.readerFor(new TypeReference<List<LocalProjectChangeService.Change>>(){}).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(json.writeValueAsString(Objects.requireNonNull(input.get("changes"))));
                    @SuppressWarnings("unchecked") var typed=(List<LocalProjectChangeService.Change>)proposed;
                    if(typed.stream().anyMatch(value->value==null||value.kind()==null||value.path()==null))throw new IllegalArgumentException();
                    yield changes.propose(request.principal(),scope.binding(),scope.root(),typed);
                }
                case APPLY_CHANGE -> {
                    access.authorize(request.principal(),"project:write",run.id());
                    var intent=changes.preview(request.principal(),scope.binding(),scope.root(),id(input,"operationId"));
                    reads.validateApply(request.principal(),scope.binding(),intent);
                    var prepared=approved(request,scope.binding(),input,LocalProjectOperation.Kind.FILE_CHANGE);
                    var applied=changes.apply(request.principal(),scope.binding(),scope.root(),prepared.id(),prepared.version());
                    reads.afterApply(request.principal(),scope.binding(),intent);
                    yield applied;
                }
                case PROPOSE_UNDO -> { keys(input,Set.of("operationId")); access.authorize(request.principal(),"project:write",run.id()); yield changes.undoProposal(request.principal(),scope.binding(),scope.root(),id(input,"operationId")); }
                case PROPOSE_COMMAND -> {
                    access.authorize(request.principal(),"project:execute",run.id());
                    var command=json.readerFor(LocalProjectCommandService.CommandRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(request.inputJson());
                    yield commands.propose(request.principal(),scope.binding(),scope.root(),(LocalProjectCommandService.CommandRequest)command);
                }
                case RUN_COMMAND -> {
                    access.authorize(request.principal(),"project:execute",run.id());
                    var prepared=approved(request,scope.binding(),input,LocalProjectOperation.Kind.COMMAND);
                    var execution=executions.getObject().controls().find(run.id());
                    if(execution==null)throw new IllegalStateException("项目运行执行句柄不可用");
                    yield commands.dispatchPrepared(request.principal(),scope.binding(),scope.root(),prepared.id(),prepared.version(),execution.cancellation());
                }
                case COMMAND_STATUS -> { keys(input,Set.of("operationId")); yield commands.status(request.principal(),scope.binding(),id(input,"operationId")); }
                case CANCEL_COMMAND -> {
                    keys(input,Set.of("operationId"));
                    authorizeCancelCommand(request,scope.binding(),input);
                    yield commands.cancel(request.principal(),scope.binding(),id(input,"operationId"));
                }
            };
            // 模型得到显式结果，普通ToolCall/消息轨迹只记录固定摘要，绝不从正文自动截取。
            return ToolInvocationResult.succeededProtected(json.writeValueAsString(result),"本地项目工具已完成，请在项目面板查看操作状态");
        } catch(LocalProjectAccessException failure) {
            if(failure.status()>=500)throw new ToolInvocationInfrastructureException("本地项目基础设施不可用，错误编号："+failure.errorId(),failure);
            return ToolInvocationResult.failedProtected(failure.getMessage()+"（"+failure.errorCode()+"，错误编号："+failure.errorId()+"）");
        } catch(LocalProjectFileException failure) {
            var safe=access.fileFailure(request.principal(),request.runId(),failure);
            return ToolInvocationResult.failedProtected(safe.getMessage()+"（错误编号："+safe.errorId()+"）");
        } catch(LocalProjectChangeException failure) {
            var safe=access.reject(request.principal(),request.runId(),"PROJECT_CHANGE_"+failure.reason().name(),409,failure.getMessage());
            return ToolInvocationResult.failedProtected(safe.getMessage()+"（"+safe.errorCode()+"，错误编号："+safe.errorId()+"）");
        } catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException failure) {
            var safe=access.reject(request.principal(),request.runId(),"PROJECT_TOOL_INPUT_INVALID",400,"项目工具参数无效，请核对固定输入模式");
            return ToolInvocationResult.failedProtected(safe.getMessage()+"（错误编号："+safe.errorId()+"）");
        } catch(Exception failure) {
            // 审计/持久化和未预期原生故障必须中止Runtime，不能让模型把支撑设施故障当作普通失败继续执行。
            var safe=access.infrastructure(request.principal(),request.runId(),"PROJECT_TOOL_EXECUTE",failure);
            throw new ToolInvocationInfrastructureException("本地项目基础设施不可用，错误编号："+safe.errorId(),safe);
        }
    }

    /**
     * 询问模式只认可与当前call/tool/inputHash完全一致的人工批准；完全访问记录独立自动授权事实。
     *
     * <p>清单摘要和原版本始终匹配，PROPOSED→WAITING_APPROVAL→PREPARED在短事务内完成；
     * 自动授权审计与状态推进同成同败，外部不能通过批准布尔值绕过。</p>
     */
    private LocalProjectOperation approved(ToolInvocationRequest request,LocalProjectBinding binding,Map<String,Object> input,LocalProjectOperation.Kind kind)throws Exception {
        if(!input.keySet().equals(Set.of("operationId","version","digest")))throw new IllegalArgumentException();
        UUID id=id(input,"operationId");long version=integer(input,"version",-1);String digest=string(input,"digest",null);
        var original=operations.operation(binding.tenantId(),binding.ownerId(),id).orElse(null);
        if(original==null||original.kind()!=kind||!original.projectId().equals(binding.projectId())||!original.runId().equals(binding.runId())
                ||!original.agentId().equals(binding.agentId())||!original.conversationId().equals(binding.conversationId())
                ||original.state()!=LocalProjectOperation.State.PROPOSED||original.version()!=version||!original.digest().equals(digest))
            throw access.reject(request.principal(),id,"PROJECT_PROPOSAL_CHANGED",409,"原提案版本、摘要或状态已变化，请重新核对");
        OperationAuthorizationMode mode=runtime.authorizationMode(request.principal(),runOf(request));
        if(mode!=OperationAuthorizationMode.FULL_ACCESS&&!hasExactApproval(request,binding,input))
            throw access.reject(request.principal(),id,"PROJECT_EXACT_APPROVAL_REQUIRED",403,"该操作尚未取得当前调用的精确审批");
        return transaction.execute(status->{
            if(mode==OperationAuthorizationMode.FULL_ACCESS)
                access.record(request.principal(),id,"PROJECT_AUTO_AUTHORIZATION","AUTHORIZED_"+mode.name());
            var waiting=operations.advance(binding.tenantId(),binding.ownerId(),id,version,LocalProjectOperation.State.WAITING_APPROVAL,clock.instant()).orElseThrow();
            return operations.advance(binding.tenantId(),binding.ownerId(),id,waiting.version(),LocalProjectOperation.State.PREPARED,clock.instant()).orElseThrow();
        });
    }

    /** 停止命令同为外部副作用；询问模式要求精确审批，完全访问记录独立自动授权事实。 */
    private void authorizeCancelCommand(ToolInvocationRequest request,LocalProjectBinding binding,Map<String,Object> input)throws Exception{
        OperationAuthorizationMode mode=runtime.authorizationMode(request.principal(),runOf(request));
        if(mode==OperationAuthorizationMode.FULL_ACCESS){
            access.record(request.principal(),id(input,"operationId"),"PROJECT_AUTO_AUTHORIZATION","AUTHORIZED_"+mode.name());
            return;
        }
        if(!hasExactApproval(request,binding,input))
            throw access.reject(request.principal(),id(input,"operationId"),"PROJECT_EXACT_APPROVAL_REQUIRED",403,"停止命令尚未取得当前调用的精确审批");
    }

    /** 审批必须绑定原Run、同一次toolCall和规范化输入，不能补足其他权限。 */
    private boolean hasExactApproval(ToolInvocationRequest request,LocalProjectBinding binding,Map<String,Object> input)throws Exception{
        String canonical=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(input);
        String hash=LocalProjectRootCipher.digest(canonical);
        return approvals.listHistory(binding.tenantId(),binding.agentId(),binding.conversationId(),new ToolApprovalHistoryPageRequest(100,null,null))
                .stream().filter(value->value.runId().equals(binding.runId())&&value.requestedBy().equals(binding.ownerId())
                        &&binding.ownerId().equals(value.decidedBy())&&value.expiresAt().isAfter(clock.instant()))
                .flatMap(value->value.items().stream()).anyMatch(item->item.toolId().equals(request.toolId())
                        &&item.toolCallId().equals(request.toolCallId())&&item.decision()==ToolApprovalDecision.APPROVE
                        &&item.inputHash().equals(hash));
    }

    private RunRecord runOf(ToolInvocationRequest request){
        return runs.findByTenantAndAgentAndId(request.tenantId(),request.agentId(),request.runId())
                .orElseThrow(()->new IllegalStateException("当前项目运行不存在"));
    }

    /** 原owner专用差异响应；先脱敏再交付，不向通用工具摘要复制正文。 */
    public LocalProjectChangeService.Preview changesPreview(PrincipalRef principal,UUID projectId,UUID runId,UUID operationId)throws Exception {
        var scope=scope(principal,projectId,runId);
        operation(principal,scope,operationId,LocalProjectOperation.Kind.FILE_CHANGE,true);
        var preview=changes.preview(principal,scope.binding(),scope.root(),operationId);
        return new LocalProjectChangeService.Preview(preview.view(),preview.changes().stream().map(value->new LocalProjectChangeService.Diff(value.kind(),value.path(),value.target(),
                value.beforeVersion(),redactor.redact(value.before()),redactor.redact(value.after()))).toList());
    }
    /** 原owner专用启动清单，宿主根/私有卷位置用逻辑占位显示，完整参数/配置hash仍受原摘要绑定。 */
    public CommandPreview commandPreview(PrincipalRef principal,UUID projectId,UUID runId,UUID operationId)throws Exception {
        var scope=scope(principal,projectId,runId);
        var operation=operation(principal,scope,operationId,LocalProjectOperation.Kind.COMMAND,true);
        var manifest=commands.preview(principal,scope.binding(),operationId);
        var environment=new TreeMap<String,String>();
        manifest.environment().forEach((key,value)->environment.put(key,redactor.redact(key+"="+value).replace(scope.root().directory().toString(),"<项目目录>").replace(state.directory().toString(),"<私有状态目录>")));
        return new CommandPreview(operationId,manifest.toolchainId(),java.nio.file.Path.of(manifest.executable()).getFileName().toString(),manifest.executableHash(),
                manifest.arguments().stream().map(redactor::redact).toList(),manifest.relativeDirectory(),environment,manifest.timeoutSeconds(),manifest.maxOutputBytes(),manifest.buildHashes(),operation.version(),operation.digest());
    }
    /** @return 原owner的完整脱敏输出快照，客户端替换显示，不根据原始字节拼接 */
    public LocalProjectCommandService.Status commandStatus(PrincipalRef principal,UUID projectId,UUID runId,UUID operationId)throws Exception {
        var scope=scope(principal,projectId,runId);
        operation(principal,scope,operationId,LocalProjectOperation.Kind.COMMAND,false);
        return commands.status(principal,scope.binding(),operationId);
    }
    /** @return 已登记精确停止请求，必须继续查询直至确认Job退出 */
    public LocalProjectCommandService.Status cancelCommand(PrincipalRef principal,UUID projectId,UUID runId,UUID operationId)throws Exception {
        var scope=scope(principal,projectId,runId);
        operation(principal,scope,operationId,LocalProjectOperation.Kind.COMMAND,false);
        return commands.cancel(principal,scope.binding(),operationId);
    }
    /** 专用HTTP入口先分类可预期的权限/不存在/到期，不能让下层安全IOException全部变成基础设施500。 */
    private LocalProjectOperation operation(PrincipalRef principal,LocalProjectAccessService.Scope scope,UUID id,LocalProjectOperation.Kind kind,boolean payload) {
        access.authorize(principal,kind==LocalProjectOperation.Kind.COMMAND?"project:execute":"project:write",id);
        var binding=scope.binding();
        var operation=operations.operation(binding.tenantId(),binding.ownerId(),id).filter(value->value.kind()==kind
                &&value.tenantId().equals(binding.tenantId())&&value.ownerId().equals(binding.ownerId())
                &&value.projectId().equals(binding.projectId())&&value.agentId().equals(binding.agentId())
                &&value.conversationId().equals(binding.conversationId())&&value.runId().equals(binding.runId()))
                .orElseThrow(()->access.reject(principal,id,"PROJECT_OPERATION_NOT_FOUND",404,"项目操作不存在或不可访问"));
        // 保留期按创建时间固定，不能因尚未轮到分批GC便允许旧正文预览或延长执行资格。
        if(payload&&!operation.createdAt().plus(Duration.ofDays(7)).isAfter(clock.instant()))
            throw access.reject(principal,id,"PROJECT_OPERATION_EXPIRED",410,"操作清单已超过保留期限，请重新生成提案；历史状态仍可查看");
        return operation;
    }
    private LocalProjectAccessService.Scope scope(PrincipalRef principal,UUID projectId,UUID runId) {
        state.requireHeld();var scope=access.run(principal,runId,"project:read");
        if(!scope.project().id().equals(projectId))throw access.reject(principal,runId,"PROJECT_RESOURCE_NOT_FOUND",404,"项目运行不存在或不可访问");
        var run=runs.findByTenantAndAgentAndId(principal.tenantId(),scope.binding().agentId(),runId).orElseThrow();
        runtime.verifyResource(principal,run);
        return scope;
    }
    private Map<String,Object> parse(String raw)throws Exception {
        if(raw.length()>2_500_000)throw new IllegalArgumentException();
        return json.readerFor(new TypeReference<Map<String,Object>>(){}).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(raw);
    }
    private static void keys(Map<String,Object> input,Set<String> allowed) { if(input==null||!allowed.containsAll(input.keySet()))throw new IllegalArgumentException(); }
    private static String string(Map<String,Object> input,String key,String fallback) { Object value=input.get(key);if(value==null)return fallback;if(!(value instanceof String text))throw new IllegalArgumentException();return text; }
    private static UUID id(Map<String,Object> input,String key) { String value=string(input,key,null);if(value==null)throw new IllegalArgumentException();return UUID.fromString(value); }
    private static int integer(Map<String,Object> input,String key,int fallback) { Object value=input.get(key);if(value==null)return fallback;if(!(value instanceof Number number)||number.doubleValue()!=number.intValue())throw new IllegalArgumentException();return number.intValue(); }
    private static boolean bool(Map<String,Object> input,String key,boolean fallback) { Object value=input.get(key);if(value==null)return fallback;if(!(value instanceof Boolean result))throw new IllegalArgumentException();return result; }

    /** @param operationId 操作编号 @param toolchainId 受信工具链 @param executable 文件名，不暴露宿主目录 @param executableHash 精确程序版本 @param arguments 精确参数的脱敏显示 @param relativeDirectory 项目内工作目录 @param environment 环境逻辑视图 @param timeoutSeconds 精确期限 @param maxOutputBytes 输出预算 @param buildHashes 主要构建配置版本 @param version 原提案条件版本 @param digest 审批绑定的不可变清单摘要 */
    public record CommandPreview(UUID operationId,String toolchainId,String executable,String executableHash,List<String> arguments,String relativeDirectory,
            Map<String,String> environment,int timeoutSeconds,int maxOutputBytes,Map<String,String> buildHashes,long version,String digest) {
        public CommandPreview{arguments=List.copyOf(arguments);environment=Map.copyOf(environment);buildHashes=Map.copyOf(buildHashes);}
        @Override public String toString(){return "CommandPreview[受保护启动清单]";}
    }
}
