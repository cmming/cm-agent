package com.cmagent.core.domain;

import java.time.Instant;
import java.util.*;

/**
 * 可跨实例恢复的短租约；过期令牌不能确认上传、释放其他租约或归还配额。
 * @param token 服务端随机 fencing 令牌
 * @param kind 租约用途
 * @param until 服务端固定截止时间；持有者需在到期前续期
 */
public record WorkspaceImportLease(UUID token, Kind kind, Instant until) {
    /** 读取可共享，上传与回收必须独占同一批次。 */
    public enum Kind {
        /** 顺序上传/完整性确认期间阻止并行写与回收。 */ UPLOAD,
        /** 运行或读取持有，可有多个同时引用且阻止回收。 */ READ,
        /** 仅可信维护任务持有，删除确认前仍占配额。 */ GC
    }
    public WorkspaceImportLease { Objects.requireNonNull(token); Objects.requireNonNull(kind); Objects.requireNonNull(until); }
}
