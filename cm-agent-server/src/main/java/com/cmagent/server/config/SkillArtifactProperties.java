package com.cmagent.server.config;
/** 文件预算与部署存储策略，不能由技能包或模型覆盖。 */
public class SkillArtifactProperties {
    /** 是否收集文件，默认关闭。 */
    private boolean enabled = false;
    /** 部署注册的存储后端。 */
    private String storage = "filesystem";
    /** 显式指定的私有持久卷根目录。 */
    private String rootDirectory = "";
    /** 独立 AES-256 主密钥，不可输出或复用其他密钥。 */
    private String encryptionKey = "";
    /** 允许收集的附件扩展名。 */
    private java.util.List<String> allowedTypes = java.util.List.of(".docx",".pptx",".pdf",".txt",".csv",".json");
    /** 单调用文件数上限。 */
    private int maxFilesPerCall = 8;
    /** 单文件明文字节上限。 */
    private long maxFileBytes = 4194304;
    /** 单调用明文总字节上限。 */
    private long maxTotalBytesPerCall = 8388608;
    /** 含审批恢复的单运行累计字节上限。 */
    private long maxTotalBytesPerRun = 33554432;
    /** 租户暂存及发布文件总字节上限。 */
    private long maxStoredBytesPerTenant = 268435456;
    /** 单运行文件数上限，避免小文件耗尽元数据。 */
    private int maxFilesPerRun = 64;
    /** 租户存储文件数上限。 */
    private int maxFilesPerTenant = 4096;
    /** 发布后的有效期。 */
    private java.time.Duration retention = java.time.Duration.ofDays(7);
    /** 补偿与过期清理间隔。 */
    private java.time.Duration cleanupInterval = java.time.Duration.ofMinutes(5);
    /** 收集阶段额外预算。 */
    private java.time.Duration collectionTimeout = java.time.Duration.ofSeconds(5);
    /** @return 是否收集文件，默认关闭 */
    public boolean isEnabled(){return enabled;}
    /** @param value 是否收集文件，默认关闭 */
    public void setEnabled(boolean value){enabled=value;}
    /** @return 部署注册的存储后端 */
    public String getStorage(){return storage;}
    /** @param value 部署注册的存储后端 */
    public void setStorage(String value){storage=value;}
    /** @return 显式指定的私有持久卷根目录 */
    public String getRootDirectory(){return rootDirectory;}
    /** @param value 显式指定的私有持久卷根目录 */
    public void setRootDirectory(String value){rootDirectory=value;}
    /** @return 独立 AES-256 主密钥，不可输出或复用其他密钥 */
    public String getEncryptionKey(){return encryptionKey;}
    /** @param value 独立 AES-256 主密钥，不可输出或复用其他密钥 */
    public void setEncryptionKey(String value){encryptionKey=value;}
    /** @return 允许收集的附件扩展名 */
    public java.util.List<String> getAllowedTypes(){return allowedTypes;}
    /** @param value 允许收集的附件扩展名 */
    public void setAllowedTypes(java.util.List<String> value){allowedTypes=value;}
    /** @return 单调用文件数上限 */
    public int getMaxFilesPerCall(){return maxFilesPerCall;}
    /** @param value 单调用文件数上限 */
    public void setMaxFilesPerCall(int value){maxFilesPerCall=value;}
    /** @return 单文件明文字节上限 */
    public long getMaxFileBytes(){return maxFileBytes;}
    /** @param value 单文件明文字节上限 */
    public void setMaxFileBytes(long value){maxFileBytes=value;}
    /** @return 单调用明文总字节上限 */
    public long getMaxTotalBytesPerCall(){return maxTotalBytesPerCall;}
    /** @param value 单调用明文总字节上限 */
    public void setMaxTotalBytesPerCall(long value){maxTotalBytesPerCall=value;}
    /** @return 含审批恢复的单运行累计字节上限 */
    public long getMaxTotalBytesPerRun(){return maxTotalBytesPerRun;}
    /** @param value 含审批恢复的单运行累计字节上限 */
    public void setMaxTotalBytesPerRun(long value){maxTotalBytesPerRun=value;}
    /** @return 租户暂存及发布文件总字节上限 */
    public long getMaxStoredBytesPerTenant(){return maxStoredBytesPerTenant;}
    /** @param value 租户暂存及发布文件总字节上限 */
    public void setMaxStoredBytesPerTenant(long value){maxStoredBytesPerTenant=value;}
    /** @return 单运行文件数上限，避免小文件耗尽元数据 */
    public int getMaxFilesPerRun(){return maxFilesPerRun;}
    /** @param value 单运行文件数上限，避免小文件耗尽元数据 */
    public void setMaxFilesPerRun(int value){maxFilesPerRun=value;}
    /** @return 租户存储文件数上限 */
    public int getMaxFilesPerTenant(){return maxFilesPerTenant;}
    /** @param value 租户存储文件数上限 */
    public void setMaxFilesPerTenant(int value){maxFilesPerTenant=value;}
    /** @return 发布后的有效期 */
    public java.time.Duration getRetention(){return retention;}
    /** @param value 发布后的有效期 */
    public void setRetention(java.time.Duration value){retention=value;}
    /** @return 补偿与过期清理间隔 */
    public java.time.Duration getCleanupInterval(){return cleanupInterval;}
    /** @param value 补偿与过期清理间隔 */
    public void setCleanupInterval(java.time.Duration value){cleanupInterval=value;}
    /** @return 收集阶段额外预算 */
    public java.time.Duration getCollectionTimeout(){return collectionTimeout;}
    /** @param value 收集阶段额外预算 */
    public void setCollectionTimeout(java.time.Duration value){collectionTimeout=value;}
    /** 启用时拒绝无密钥/目录及不受支持的后端；所有限额均有明确硬上限。 */
    public void validate() {
        if(maxFilesPerCall<1 || maxFilesPerCall>8 || maxFileBytes<1 || maxFileBytes>4194304
            || maxTotalBytesPerCall<maxFileBytes || maxTotalBytesPerCall>8388608
            || maxTotalBytesPerRun<maxTotalBytesPerCall || maxTotalBytesPerRun>33554432
            || maxStoredBytesPerTenant<maxTotalBytesPerRun || maxStoredBytesPerTenant>1073741824
            || maxFilesPerRun<maxFilesPerCall || maxFilesPerRun>64
            || maxFilesPerTenant<maxFilesPerRun || maxFilesPerTenant>4096
            || retention==null || retention.compareTo(java.time.Duration.ofMinutes(1))<0 || retention.compareTo(java.time.Duration.ofDays(30))>0
            || cleanupInterval==null || cleanupInterval.compareTo(java.time.Duration.ofSeconds(10))<0 || cleanupInterval.compareTo(java.time.Duration.ofHours(1))>0
            || collectionTimeout==null || collectionTimeout.isNegative() || collectionTimeout.isZero() || collectionTimeout.compareTo(java.time.Duration.ofSeconds(5))>0
            || allowedTypes==null || allowedTypes.isEmpty()
            || !java.util.Set.of(".docx",".pptx",".pdf",".txt",".csv",".json").containsAll(allowedTypes))
            throw new IllegalStateException("文件产物配额或类型策略不合法");
        if(enabled) {
            try {
                if(!"filesystem".equals(storage) || rootDirectory==null || rootDirectory.isBlank()
                    || java.util.Base64.getDecoder().decode(encryptionKey).length!=32)
                    throw new IllegalArgumentException();
            } catch(RuntimeException invalid){throw new IllegalStateException("文件产物持久目录或独立加密密钥未配置");}
        }
    }
}
