package com.cmagent.localclient;

import java.io.IOException;
import java.util.Optional;

/**
 * 本机系统凭据存储的最小内部合同。
 *
 * <p>实现只能访问本客户端新建的随机引用，不枚举用户其他凭据；调用方持有并负责清除秘密数组。</p>
 */
interface SystemCredentialStore {
    /**
     * 为秘密创建一个新的随机引用。
     *
     * @param secret 调用方持有的秘密字节，不得为空
     * @return 本客户端后续可读取或删除的随机引用
     * @throws IOException 系统存储不可用或创建失败
     */
    String create(byte[] secret) throws IOException;

    /**
     * 读取本客户端创建的引用。
     *
     * @param reference 创建时返回的引用
     * @return 存在时返回新建的秘密字节数组；调用方负责清除
     * @throws IOException 引用无效或系统存储不可用
     */
    Optional<byte[]> read(String reference) throws IOException;

    /**
     * 删除本客户端创建的指定引用，不影响其他系统凭据。
     *
     * @param reference 创建时返回的引用
     * @return 删除成功时为 {@code true}，原本不存在时为 {@code false}
     * @throws IOException 引用无效或系统存储不可用
     */
    boolean delete(String reference) throws IOException;
}
