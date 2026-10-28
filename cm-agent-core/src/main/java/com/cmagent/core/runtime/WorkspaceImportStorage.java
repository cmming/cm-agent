package com.cmagent.core.runtime;

import com.cmagent.core.domain.*;
import java.io.*;

/**
 * 独立私有导入密文存储；仅接收服务端核实的 manifest，不接收客户端对象路径。
 * 调用者在事务外执行并持有相应用途的仓储租约；所有方法阻塞且读取有硬界。
 */
public interface WorkspaceImportStorage {
    /** @return 固定对象是否存在；只供持租约的显式complete核对未知上传结果，不代表密文已经验证 */
    boolean contains(WorkspaceImport batch, WorkspaceImportFile file) throws IOException;
    /**
     * 重算长度/摘要并严格检查文本后以不可覆盖方式持久化；失败不发布半个对象。
     * @param batch 可信不可变批次
     * @param file 属于批次的文件项
     * @param input 调用方拥有且负责关闭的输入流，底层须配置超时与并发预算；最多读取声明长度加一字节
     * @throws IOException 非法文本、长度/摘要不符、已有对象或存储不可用，消息必须脱敏
     */
    void write(WorkspaceImport batch, WorkspaceImportFile file, InputStream input) throws IOException;
    /**
     * 完整验证 GCM、AAD、长度及摘要后才返回明文，调用方用完应清零数组。
     * @return 仅属于该 manifest 项的已验证明文
     * @throws IOException 密钥/密文缺失、错配、损坏或非法对象；不得回退其他路径
     */
    byte[] readVerified(WorkspaceImport batch, WorkspaceImportFile file) throws IOException;
    /**
     * GC 租约持有者删除固定对象及残留暂存；失败不得确认 reclaimed，重跑可以恢复。
     * 持久删除标记拒绝迟到写入；该小标记随元数据保留用于 fencing，不含文件信息。
     */
    void delete(WorkspaceImport batch) throws IOException;
}
