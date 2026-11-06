package com.cmagent.localclient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 可选的口令加密设备密钥文件原语；目前未接入客户端启动或配对。
 *
 * <p>私钥只以 AES-GCM 密文写盘，密钥由口令、随机盐和固定成本的 PBKDF2 派生。调用者负责
 * 选择可信的本机目录、保护和及时清除口令；本类不把口令、私钥或文件内容写入日志。</p>
 */
final class EncryptedDeviceKeyFile {
    private static final int MAGIC = 0x434D4B31;
    private static final int ITERATIONS = 310_000;
    private static final int MAX_FILE_BYTES = 8192;
    private static final byte[] PROOF = "cm-agent-device-key-check-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final SecureRandom random;

    /** @param random 由调用方提供的密码学安全随机源 */
    EncryptedDeviceKeyFile(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "随机源不能为空");
    }

    /**
     * 在不存在的目标文件中创建口令加密密钥。
     *
     * <p>不会覆盖已有文件；文件创建后若写入失败可能留下不完整密文，调用方必须核对，不能
     * 将其当作有效设备身份或自动覆盖。调用方仍持有并负责清除传入的口令数组。</p>
     *
     * @param path 位于可信本机目录中的绝对文件路径
     * @param keyPair 需要持久化的 Ed25519 密钥对
     * @param password 非空口令，不会由本方法清除调用方数组
     * @throws IOException 文件创建或写入失败
     * @throws GeneralSecurityException 加密或密钥验证失败
     */
    void create(Path path, KeyPair keyPair, char[] password) throws IOException, GeneralSecurityException {
        requirePath(path);
        requirePassword(password);
        Objects.requireNonNull(keyPair, "设备密钥不能为空");
        byte[] privateBytes = Objects.requireNonNull(keyPair.getPrivate().getEncoded(), "设备私钥不可导出");
        byte[] publicBytes = Objects.requireNonNull(keyPair.getPublic().getEncoded(), "设备公钥不可编码");
        byte[] derived = null;
        try {
            verifyPair(keyPair);
            if (privateBytes.length > 4096 || publicBytes.length > 1024) {
                throw new GeneralSecurityException("设备密钥格式无效");
            }
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            random.nextBytes(salt);
            random.nextBytes(iv);
            byte[] header = header(salt, iv, publicBytes);
            derived = derive(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(privateBytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.write(header);
                data.writeInt(encrypted.length);
                data.write(encrypted);
            }
            writeNew(path, output.toByteArray());
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    /**
     * 解锁并验证口令加密的设备密钥。
     *
     * @param path 位于可信本机目录中的绝对文件路径
     * @param password 用户输入的非空口令，调用方负责清除数组
     * @return 公私钥匹配且完整性验证通过的设备密钥对
     * @throws IOException 文件缺失、格式损坏或无法读取
     * @throws GeneralSecurityException 口令错误、内容被篡改或密钥不可用
     */
    KeyPair unlock(Path path, char[] password) throws IOException, GeneralSecurityException {
        requirePath(path);
        requirePassword(password);
        byte[] encoded;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            encoded = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (encoded.length > MAX_FILE_BYTES) throw new IOException("设备密钥文件过大");
        byte[] privateBytes = null;
        byte[] derived = null;
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int magic = data.readInt();
            int iterations = data.readInt();
            if (magic != MAGIC || iterations != ITERATIONS) throw new IOException("设备密钥文件格式无效");
            byte[] salt = data.readNBytes(16);
            byte[] iv = data.readNBytes(12);
            int publicLength = data.readInt();
            if (salt.length != 16 || iv.length != 12 || publicLength < 1 || publicLength > 1024) {
                throw new IOException("设备密钥文件格式无效");
            }
            byte[] publicBytes = data.readNBytes(publicLength);
            int encryptedLength = data.readInt();
            if (publicBytes.length != publicLength || encryptedLength < 17 || encryptedLength > 4112) {
                throw new IOException("设备密钥文件格式无效");
            }
            byte[] encrypted = data.readNBytes(encryptedLength);
            if (encrypted.length != encryptedLength || data.available() != 0) {
                throw new IOException("设备密钥文件格式无效");
            }
            derived = derive(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header(salt, iv, publicBytes));
            privateBytes = cipher.doFinal(encrypted);
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            var pair = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(publicBytes)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
            verifyPair(pair);
            return pair;
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (privateBytes != null) Arrays.fill(privateBytes, (byte) 0);
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    private static byte[] header(byte[] salt, byte[] iv, byte[] publicKey) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(MAGIC);
            data.writeInt(ITERATIONS);
            data.write(salt);
            data.write(iv);
            data.writeInt(publicKey.length);
            data.write(publicKey);
        }
        return output.toByteArray();
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static void verifyPair(KeyPair pair) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(PROOF);
        byte[] signed = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update(PROOF);
        if (!verifier.verify(signed)) throw new GeneralSecurityException("设备密钥对不匹配");
    }

    private static void writeNew(Path path, byte[] content) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean posix = Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null;
        // POSIX 在创建时设置私有权限，避免先写入再收紧权限的时间窗口；Windows 依赖可信目录 ACL。
        try (var channel = posix
                ? Files.newByteChannel(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.newByteChannel(path, options)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            if (channel instanceof FileChannel file) file.force(true);
        }
    }

    private static void requirePath(Path path) {
        if (path == null || !path.isAbsolute() || path.getParent() == null
                || !Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("设备密钥目录无效");
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("设备密钥口令不能为空");
    }
}
