package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProjectRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 根密文同时认证归属和节点；复制合法密文到另一项目行不能获得目录访问。 */
final class LocalProjectRootCipher {
    /** 已由部署主密钥装配的认证加密器，不生成临时恢复密钥。 */
    private final ModelCredentialCipher cipher;
    /** 本地固定结构序列化器，不能开启请求驱动的多态反序列化。 */
    private final ObjectMapper json;
    /** 构造时固定的执行节点。 */
    private final String nodeId;

    LocalProjectRootCipher(ModelCredentialCipher cipher, ObjectMapper json, String nodeId) {
        this.cipher = Objects.requireNonNull(cipher); this.json = Objects.requireNonNull(json);
        this.nodeId = Objects.requireNonNull(nodeId);
    }

    /** 明文只来自已消费的原生票据，绝不从HTTP路径字段创建登记。 */
    LocalProjectRoot seal(PrincipalRef owner, UUID id, LocalProjectDirectoryTickets.Root root, Instant now) {
        try {
            String identity = digest(root.identity());
            var envelope = new Envelope(1, id, owner.tenantId(), owner.principalId(), nodeId,
                    root.directory().toString(), root.identity());
            return new LocalProjectRoot(id, owner.tenantId(), owner.principalId(), nodeId,
                    cipher.encrypt(json.writeValueAsString(envelope)), identity, now);
        } catch (Exception failure) { throw new IllegalStateException("本地项目根加密失败"); }
    }

    /** 必须先经过owner仓储过滤，再解密核对全部上下文；不按绝对路径猜测或修复根。 */
    LocalProjectDirectoryTickets.Root open(PrincipalRef owner, LocalProjectRoot record) {
        try {
            Envelope envelope = json.readValue(cipher.decrypt(record.encryptedDirectory()), Envelope.class);
            if (envelope.format() != 1 || !record.id().equals(envelope.id())
                    || !owner.tenantId().equals(record.tenantId()) || !owner.tenantId().equals(envelope.tenant())
                    || !owner.principalId().equals(record.ownerId()) || !owner.principalId().equals(envelope.owner())
                    || !nodeId.equals(record.nodeId()) || !nodeId.equals(envelope.node())
                    || !record.identity().equals(digest(envelope.identity()))) throw new IllegalArgumentException();
            Path path = Path.of(envelope.directory());
            if (!path.isAbsolute() || !path.equals(path.normalize()) || path.getParent() == null)
                throw new IllegalArgumentException();
            return new LocalProjectDirectoryTickets.Root(path, envelope.identity());
        } catch (Exception failure) { throw new IllegalStateException("本地项目根不可用，请检查节点或恢复密钥"); }
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("本地项目摘要算法不可用"); }
    }

    /**
     * @param format 固定格式版本
     * @param id 根登记编号
     * @param tenant 可信租户
     * @param owner 精确原选择主体
     * @param node 固定节点
     * @param directory 仅在认证加密信封内保存的绝对路径
     * @param identity 原生目录身份，打开时仍须实时复核
     */
    private record Envelope(int format, UUID id, UUID tenant, String owner, String node, String directory, String identity) {
        @Override public String toString() { return "Envelope[受保护的项目根]"; }
    }
}
