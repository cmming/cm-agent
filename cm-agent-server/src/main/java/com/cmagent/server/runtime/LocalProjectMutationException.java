package com.cmagent.server.runtime;

import java.io.IOException;

/** 条件文件应用的安全失败，明确区分是否可能已发生副作用，不能自动当作零写入重试。 */
final class LocalProjectMutationException extends IOException {
    /** 固定文件失败分类，交由服务关联操作/errorId并记录脱敏诊断。 */
    private final LocalProjectFileException.Reason reason;
    /** true时须核对日志/磁盘及备份，不得把该操作重新当作未执行。 */
    private final boolean possibleSideEffects;

    LocalProjectMutationException(LocalProjectFileException.Reason reason, boolean possibleSideEffects) {
        super(new LocalProjectFileException(reason).getMessage());
        this.reason = reason;
        this.possibleSideEffects = possibleSideEffects;
    }
    LocalProjectFileException.Reason reason() { return reason; }
    boolean possibleSideEffects() { return possibleSideEffects; }
}

