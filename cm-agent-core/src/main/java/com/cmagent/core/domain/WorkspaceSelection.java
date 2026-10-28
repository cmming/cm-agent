package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 用户选择的逻辑目录；不接受宿主文件路径，也不授予文件或工具权限。
 * @param mode 省略使用 DEFAULT，显式不用资料使用 NONE
 * @param workspaceId SELECTED 的登记目录标识或 IMPORTED 的 importId，由 mode 区分命名空间；其他模式为空
 */
public record WorkspaceSelection(Mode mode, UUID workspaceId) {
    /** 选择模式必须显式区分旧调用与主动清空，避免静默注入旧映射。 */
    public enum Mode {
        /** 保留管理员按 tenant/Agent 配置的旧映射。 */ DEFAULT,
        /** 使用当前主体获准选择的登记目录。 */ SELECTED,
        /** 本轮明确不使用任何工作区参考资料。 */ NONE,
        /** 使用当前主体上传的不可变只读资料批次，标识不能解析为登记目录。 */ IMPORTED
    }

    public WorkspaceSelection {
        Objects.requireNonNull(mode, "工作区选择模式不能为空");
        if ((mode == Mode.SELECTED || mode == Mode.IMPORTED) != (workspaceId != null)) {
            throw new IllegalArgumentException("指定目录或导入资料时必须提供标识，其他模式不能提供标识");
        }
    }

    /** @return 兼容旧无选择参数的调用；不会替用户记忆最近目录 */
    public static WorkspaceSelection defaults() { return new WorkspaceSelection(Mode.DEFAULT, null); }
}
