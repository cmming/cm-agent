package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.domain.LocalProjectBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 项目操作的持久意图/条件推进，不执行文件或命令，不将UNKNOWN重新派发。 */
public interface LocalProjectOperationRepository {
    /**
     * 冷启动内部按可信绑定发现已派发操作，不返回其他状态，不解密正文。
     * @param binding 当前节点发现并复核的固定Run来源，不能来自客户端
     * @param limit 单批1至100；tenant/owner/project/agent/conversation/run条件在LIMIT前核对
     * @return RUNNING的FILE_CHANGE/COMMAND，调用方只可CAS为UNKNOWN，不能重放
     */
    default List<LocalProjectOperation> findRunningForRun(LocalProjectBinding binding, int limit) {
        throw new UnsupportedOperationException("当前操作仓储未实现失联运行发现");
    }
    /**
     * 系统保留期任务专用的跨租户发现，普通请求不得调用；nodeId只能来自受信部署配置。
     * 只返回固定项目/节点及精确owner匹配、创建时间到期的COMMAND，排除RUNNING。
     * @param nodeId 当前受信本机节点
     * @param cutoff 包含边界的最晚创建时间，服务端已减去七天
     * @param after 本轮稳定游标，首次为空；遍历到末尾后由系统任务重置
     * @param limit 单批上限，必须为1至100
     * @return 仅用于内部条件擦除的有界清单，密文不得输出到HTTP或日志
     */
    default List<LocalProjectOperation> findCommandRetentionCandidates(String nodeId,Instant cutoff,CommandRetentionCursor after,int limit){
        throw new UnsupportedOperationException("当前操作仓储未实现命令保留期发现");
    }

    /**
     * @param createdAt 上一候选的固定创建时间
     * @param operationId 上一候选标识，与创建时间共同排序
     * @param tenantId 同时间及UUID在不同租户中允许相同，以租户标识消除分页歧义
     */
    record CommandRetentionCursor(Instant createdAt,UUID operationId,UUID tenantId){
        public CommandRetentionCursor{java.util.Objects.requireNonNull(createdAt);java.util.Objects.requireNonNull(operationId);java.util.Objects.requireNonNull(tenantId);}
    }
    /**
     * 按创建时间和标识倒序读取固定 Run 的内部操作元数据；对外必须转为不含载荷密文的 DTO。
     * tenant、精确 owner、项目和 Run 条件必须先于 LIMIT；旧实现默认拒绝，不能提供不完整的恢复列表。
     * @param tenantId 可信租户
     * @param ownerId 原认证主体，必须精确比较
     * @param projectId 已核实归属的固定项目
     * @param runId 已核实来源的固定 Run
     * @param limit 单页上限，限定 1 到 100
     * @return 有界不可变元数据列表，内部密文不得直接序列化到 HTTP 或日志
     */
    default List<LocalProjectOperation> listRun(UUID tenantId, String ownerId, UUID projectId, UUID runId, int limit) {
        throw new UnsupportedOperationException("当前操作仓储未实现运行操作列表");
    }
    /**
     * 项目行锁下原子消费PREPARED，排除同项目其他RUNNING/UNKNOWN；未知实现必须失败关闭。
     * @return 只有一个操作可得到RUNNING，不能先检查后分开CAS造成文件/命令同时派发
     */
    default Optional<LocalProjectOperation> claimPrepared(UUID tenantId,String ownerId,UUID operationId,long expectedVersion,Instant now){
        throw new UnsupportedOperationException("当前操作仓储未实现项目原子派发");
    }
    /**
     * 检查项目是否仍有执行中或结果未知的副作用；未知实现必须失败关闭。
     * @param exceptOperationId 当前已锁定操作可排除自身，空值表示检查全部
     * @return 有未核对操作时true，不得继续派发新的写入或命令
     */
    default boolean hasUncertainOperation(UUID tenantId,String ownerId,UUID projectId,UUID exceptOperationId){
        throw new UnsupportedOperationException("当前操作仓储未实现项目不确定状态检查");
    }
    /** 仅新增PROPOSED/version=1且归属绑定一致的操作，重复不覆盖。 */
    boolean insertOperation(LocalProjectOperation operation);
    /** @return 精确tenant/owner下的操作，不能用同租户管理员读取他人清单 */
    Optional<LocalProjectOperation> operation(UUID tenantId, String ownerId, UUID operationId);
    /**
     * 在短事务/行锁中校验领域转移并CAS；此方法不产生本地副作用。
     * @return 条件不符时为空，基础设施异常继续抛出；成功返回新版本
     */
    Optional<LocalProjectOperation> advance(UUID tenantId, String ownerId, UUID operationId,
            long expectedVersion, LocalProjectOperation.State next, Instant now);

    /**
     * 条件擦除到期正文，保留不可重放的状态和元数据墓碑。
     * @param replacementCiphertext 已加密的过期标记，不得是空串或明文
     * @return 只有终态/UNKNOWN且固定七天恢复期限已过时可成功，不修改原清单摘要
     */
    default boolean erasePayload(UUID tenantId,String ownerId,UUID operationId,long expectedVersion,String replacementCiphertext,Instant now){
        throw new UnsupportedOperationException("当前操作仓储未实现到期正文擦除");
    }
}

