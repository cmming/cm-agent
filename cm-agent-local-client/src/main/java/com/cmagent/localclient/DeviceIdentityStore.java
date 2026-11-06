package com.cmagent.localclient;

import com.cmagent.core.domain.LocalClientDevice;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 本机设备身份持久化：优先系统凭据存储，不可用时使用口令加密文件。
 *
 * <p>元数据只保存公开密钥、服务端来源和随机凭据引用；私钥不会进入元数据、命令行、环境变量
 * 或日志。Linux 上的 {@code secret-tool} 不存在、系统密钥服务不可用或当前平台尚无已验证实现时，
 * 调用方必须提供本地口令并使用加密文件回退。</p>
 */
final class DeviceIdentityStore {
    private static final int MAGIC = 0x434D4944;
    private final Path directory;
    private final ObjectMapper json;
    private final SecureRandom random;
    private final SystemCredentialStore systemStore;

    /**
     * @param directory 当前用户可信的客户端状态目录
     * @param json JSON 映射器
     * @param random 密码学安全随机源
     * @param systemStore 可选系统凭据存储；不可用时为空
     */
    DeviceIdentityStore(Path directory, ObjectMapper json, SecureRandom random,
                        Optional<SystemCredentialStore> systemStore) {
        if (directory == null || !directory.isAbsolute()) {
            throw new IllegalArgumentException("客户端状态目录必须是绝对路径");
        }
        this.directory = directory;
        this.json = Objects.requireNonNull(json, "JSON 映射器不能为空");
        this.random = Objects.requireNonNull(random, "随机源不能为空");
        this.systemStore = systemStore == null ? null : systemStore.orElse(null);
    }

    /** 按当前操作系统选择已验证凭据存储；其余平台明确使用口令回退。 */
    static DeviceIdentityStore forCurrentUser() {
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        Path directory = home.resolve(".cm-agent").resolve("local-client");
        Optional<SystemCredentialStore> system = Optional.empty();
        String os = System.getProperty("os.name", "");
        try {
            if (os.startsWith("Windows")) {
                system = Optional.of(new WindowsCredentialStore());
            } else if (os.startsWith("Linux") && Files.isExecutable(Path.of("/usr/bin/secret-tool"))) {
                system = Optional.of(new LinuxSecretToolStore(Path.of("/usr/bin/secret-tool")));
            } else if (os.startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/security"))) {
                system = Optional.of(new MacKeychainStore(Path.of("/usr/bin/security")));
            }
        } catch (RuntimeException unavailable) {
            // 系统凭据实现不可用时继续到口令回退；不能把异常或系统路径写到普通日志。
            system = Optional.empty();
        }
        return new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), system);
    }

    /** @return 当前环境是否可使用系统凭据存储 */
    boolean usesSystemStore() { return systemStore != null; }

    /**
     * 创建新的设备密钥及待配对元数据。
     *
     * @param serverUrl 已核对的 HTTP/HTTPS 服务端来源
     * @param deviceName 供用户核对的设备名称
     * @param platform 当前平台
     * @param password 系统存储不可用时使用的非空口令；系统存储可用时可为空
     * @return 未含私钥的公开身份信息
     */
    StoredIdentity create(URI serverUrl, String deviceName, LocalClientDevice.Platform platform,
                          char[] password) throws IOException, GeneralSecurityException {
        validateServer(serverUrl);
        if (deviceName == null || deviceName.isBlank() || deviceName.length() > 120) {
            throw new IllegalArgumentException("设备名称无效");
        }
        Objects.requireNonNull(platform, "设备平台不能为空");
        Files.createDirectories(directory);
        Path metadataPath = directory.resolve("identity.json");
        if (Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(directory.resolve("identity.key"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("设备身份已存在");
        }
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] secret = encodeKeyPair(keyPair);
        String reference;
        StorageKind kind;
        try {
            if (systemStore != null) {
                reference = systemStore.create(secret);
                kind = StorageKind.SYSTEM;
            } else {
                requirePassword(password);
                reference = "password-file";
                kind = StorageKind.PASSWORD;
                new EncryptedDeviceKeyFile(random).create(directory.resolve("identity.key"), keyPair, password);
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        IdentityMetadata metadata = new IdentityMetadata(1, serverUrl.toString(), deviceName,
                platform.name(), Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                null, null, null, kind.name(), reference);
        try {
            writeMetadata(metadataPath, metadata, true);
        } catch (IOException failure) {
            if (kind == StorageKind.SYSTEM) {
                try { systemStore.delete(reference); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        return new StoredIdentity(metadata, keyPair);
    }

    /**
     * 读取系统存储或口令文件中的身份。
     *
     * @param password 口令回退身份所需口令；系统存储身份可忽略
     * @return 已保存身份
     */
    StoredIdentity load(char[] password) throws IOException, GeneralSecurityException {
        Path metadataPath = directory.resolve("identity.json");
        byte[] encoded = Files.readAllBytes(metadataPath);
        IdentityMetadata metadata = json.readValue(encoded, IdentityMetadata.class);
        validateMetadata(metadata);
        byte[] secret = null;
        try {
            KeyPair pair;
            if ("SYSTEM".equals(metadata.storageKind())) {
                if (systemStore == null) throw new IOException("系统凭据存储当前不可用");
                secret = systemStore.read(metadata.secretReference())
                        .orElseThrow(() -> new IOException("设备系统凭据不存在"));
                pair = decodeKeyPair(secret);
            } else {
                requirePassword(password);
                pair = new EncryptedDeviceKeyFile(random).unlock(directory.resolve("identity.key"), password);
            }
            String expected = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            if (!expected.equals(metadata.publicKey())) throw new GeneralSecurityException("设备公钥与凭据不匹配");
            return new StoredIdentity(metadata, pair);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * 仅在服务端确认配对后写入设备标识。
     *
     * @param identity 当前本机身份
     * @param deviceId 服务端生成的设备标识
     * @param tenantId 服务端确认的租户
     * @param ownerId 服务端确认的原主体
     * @return 更新后的公开身份信息
     */
    StoredIdentity confirm(StoredIdentity identity, UUID deviceId, UUID tenantId, String ownerId) throws IOException {
        Objects.requireNonNull(identity, "设备身份不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("原主体标识不能为空");
        if (identity.metadata().deviceId() != null && !identity.metadata().deviceId().equals(deviceId)) {
            throw new IllegalStateException("设备身份已经绑定到其他服务端记录");
        }
        IdentityMetadata updated = identity.metadata().withBinding(deviceId, tenantId, ownerId);
        writeMetadata(directory.resolve("identity.json"), updated, false);
        return new StoredIdentity(updated, identity.keyPair());
    }

    /**
     * 删除元数据和当前客户端创建的凭据条目。
     *
     * @param identity 当前身份
     * @throws IOException 删除失败；调用方不能声称卸载完成
     */
    void delete(StoredIdentity identity) throws IOException {
        if ("SYSTEM".equals(identity.metadata().storageKind()) && systemStore != null) {
            systemStore.delete(identity.metadata().secretReference());
        }
        Files.deleteIfExists(directory.resolve("identity.key"));
        Files.deleteIfExists(directory.resolve("identity.json"));
    }

    private void writeMetadata(Path path, IdentityMetadata metadata, boolean createNew) throws IOException {
        byte[] bytes = json.writeValueAsBytes(metadata);
        Path temporary = directory.resolve("identity.json.tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            if (createNew) {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] encodeKeyPair(KeyPair pair) throws IOException {
        byte[] privateKey = pair.getPrivate().getEncoded();
        byte[] publicKey = pair.getPublic().getEncoded();
        if (privateKey == null || publicKey == null) throw new IOException("设备密钥不可导出");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(privateKey.length);
            output.write(privateKey);
            output.writeInt(publicKey.length);
            output.write(publicKey);
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
        return bytes.toByteArray();
    }

    private static KeyPair decodeKeyPair(byte[] encoded) throws IOException, GeneralSecurityException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("设备凭据格式无效");
            int privateLength = input.readInt();
            if (privateLength < 1 || privateLength > 4096) throw new IOException("设备凭据格式无效");
            byte[] privateKey = input.readNBytes(privateLength);
            int publicLength = input.readInt();
            if (publicLength < 1 || publicLength > 1024) throw new IOException("设备凭据格式无效");
            byte[] publicKey = input.readNBytes(publicLength);
            if (privateKey.length != privateLength || publicKey.length != publicLength || input.available() != 0) {
                throw new IOException("设备凭据格式无效");
            }
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            try {
                return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(publicKey)),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(privateKey)));
            } finally {
                Arrays.fill(privateKey, (byte) 0);
            }
        }
    }

    private static void validateServer(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("服务端来源必须是 HTTP 或 HTTPS 规范来源");
        }
        if ("http".equals(uri.getScheme()) && !("localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()))) {
            throw new IllegalArgumentException("远端本机客户端通道必须使用 HTTPS");
        }
    }

    private static void validateMetadata(IdentityMetadata metadata) throws IOException {
        if (metadata == null || metadata.version() != 1
                || metadata.publicKey() == null || metadata.publicKey().isBlank()
                || !("SYSTEM".equals(metadata.storageKind()) || "PASSWORD".equals(metadata.storageKind()))
                || metadata.secretReference() == null || metadata.secretReference().isBlank()
                || (metadata.deviceId() == null) != (metadata.ownerId() == null)
                || (metadata.deviceId() == null) != (metadata.tenantId() == null)) {
            throw new IOException("设备身份元数据无效");
        }
        validateServer(URI.create(metadata.serverUrl()));
        try {
            LocalClientDevice.Platform.valueOf(metadata.platform());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("设备身份平台无效");
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("设备身份口令不能为空");
    }

    /** 凭据实际保存位置。 */
    enum StorageKind { SYSTEM, PASSWORD }

    /**
     * 不含私钥的持久元数据。
     *
     * @param version 文件版本
     * @param serverUrl 配对服务端规范来源
     * @param deviceName 设备名称
     * @param platform 操作系统类别
     * @param publicKey Ed25519 公钥 Base64 编码
     * @param deviceId 配对后的服务端设备标识
     * @param tenantId 配对后的可信租户
     * @param ownerId 配对后的原主体
     * @param storageKind SYSTEM 或 PASSWORD
     * @param secretReference 系统凭据随机引用或固定 password-file
     */
    record IdentityMetadata(int version, String serverUrl, String deviceName, String platform,
                            String publicKey, UUID deviceId, UUID tenantId, String ownerId,
                            String storageKind, String secretReference) {
        IdentityMetadata withBinding(UUID id, UUID tenant, String owner) {
            return new IdentityMetadata(version, serverUrl, deviceName, platform, publicKey, id,
                    tenant, owner, storageKind, secretReference);
        }
    }

    /** 运行中保存的内存身份；调用方不得记录或序列化私钥。 */
    static final class StoredIdentity {
        private final IdentityMetadata metadata;
        private final KeyPair keyPair;
        private StoredIdentity(IdentityMetadata metadata, KeyPair keyPair) {
            this.metadata = Objects.requireNonNull(metadata);
            this.keyPair = Objects.requireNonNull(keyPair);
        }
        IdentityMetadata metadata() { return metadata; }
        KeyPair keyPair() { return keyPair; }
        @Override public String toString() {
            return "StoredIdentity[deviceId=" + metadata.deviceId() + ",storageKind="
                    + metadata.storageKind() + "]";
        }
    }
}
