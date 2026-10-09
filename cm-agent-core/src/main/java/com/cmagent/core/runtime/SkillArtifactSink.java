package com.cmagent.core.runtime;
import java.io.InputStream;
import java.io.IOException;
/** 单次调用的有界文件接收器；实现不得信任容器提供的归属信息。 */
@FunctionalInterface
public interface SkillArtifactSink {
    /**
     * 同步消费一个文件；流只在本调用中有效，不得关闭底层协议流或异步持有。
     * @param name 已初步校验的相对名称，接收方仍需校验
     * @param size 声明字节数，必须核对实际读取大小
     * @param input 仅可读取当前文件的受限流
     * @throws IOException 传输或存储失败时中止整次收集
     */
    void accept(String name,long size,InputStream input) throws IOException;
}
