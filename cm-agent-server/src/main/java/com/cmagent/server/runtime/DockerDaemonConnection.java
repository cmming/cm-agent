package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SandboxConnectionMode;
import com.cmagent.core.runtime.SkillAccessException;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;

/**
 * 一次调用的固定 daemon 连接，关闭时释放 SSH 隧道、TLS 中继和私有认证文件。
 * <p>远程目标使用策略固定的 IP；TLS 仍以原 host 校验服务端身份。中继仅监听随机回环端口，
 * 不修改 Docker context、用户 SSH 配置或运行中的 daemon。</p>
 */
public final class DockerDaemonConnection implements AutoCloseable {
    /** 固定 Docker 全局参数，不含私钥正文。 */ private final List<String> prefix;
    /** 是否保留 R0 环境兼容，仅未设置显式连接时为真。 */ private final boolean legacy;
    /** 本次独占的 SSH 隧道进程。 */ private Process tunnel;
    /** 密码分支仅持有内存SSH会话；关闭后不重连或回退原生私钥进程。 */ private PasswordSshRelay passwordRelay;
    /** 回环 TLS 中继监听器。 */ private ServerSocket listener;
    /** 关闭时终止所有中继连接，避免线程池等待网络读。 */ private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    /** 有界中继连接数，Docker单次操作无需无限连接。 */ private final Semaphore relayPermits=new Semaphore(8);
    /** 虚拟线程仅持有本次连接生命周期。 */ private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    /** 原始认证文件只在此私有目录内。 */ private Path directory;
    /** TLS身份错误用于将Docker非零退出归类，不记录原始异常。 */ private volatile boolean authenticationFailed;
    /** 幂等关闭，治理层可在审计前显式关闭并由try-with-resources再次关闭。 */ private boolean closed;
    /** 连接建立消耗同一次执行预算，不能为脚本重新开始计时。 */ private final long openedAt=System.nanoTime();
    /** 首次建立连接使用执行预算，控制面探测另有固定默认上限。 */ private java.time.Duration budget=java.time.Duration.ofSeconds(15);
    /** 仅保留SSH可公开的失败分类，不保存原始诊断文本。 */ private volatile ApiErrorCode sshFailure=ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED;
    private DockerDaemonConnection(List<String> prefix,boolean legacy){
        this.prefix=List.copyOf(prefix);this.legacy=legacy;
        {
            try{
                directory=Files.createTempDirectory("cm-agent-sandbox-auth-");
                if(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
                else{
                    // 先限制目录ACL再写文件，避免材料创建到单文件ACL设置之间存在共享读窗口。
                    var view=Files.getFileAttributeView(directory,java.nio.file.attribute.AclFileAttributeView.class);
                    if(view==null)throw new IOException();
                    view.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(directory)).setPermissions(java.nio.file.attribute.AclEntryPermission.values()).build()));
                }
            }catch(IOException error){close();throw failed(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,"不能建立沙箱私有连接目录");}
        }
    }
    /** @return 保留R0本地socket环境配置；全局context不得使无显式连接的调用变成未授权远程访问 */
    public static DockerDaemonConnection legacy(){
        String host=System.getenv("DOCKER_HOST");
        if(host==null || host.isBlank())host=localHost();
        if(!host.startsWith("unix:") && !host.startsWith("npipe:"))
            throw failed(ApiErrorCode.SKILL_SANDBOX_INVALID,"旧远程DOCKER_HOST须迁移为显式SSH或TLS配置");
        return new DockerDaemonConnection(List.of("docker","--host",host),true);
    }
    /** @return 显式本地socket连接，不继承全局context或DOCKER_HOST */
    public static DockerDaemonConnection local(){
        return new DockerDaemonConnection(List.of("docker","--host",localHost()),false);
    }
    private static String localHost(){return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"npipe:////./pipe/docker_engine":"unix:///var/run/docker.sock";}
    /**
     * 打开已授权远程连接，失败关闭已创建资源。
     * @param mode SSH或TLS
     * @param host 已核验原主机，用于身份验证
     * @param target 策略固定的实际地址
     * @param port 目标端口
     * @param username SSH用户
     * @param credentials 短期认证材料，禁止记录
     * @return 调用方负责关闭的连接
     */
    public static DockerDaemonConnection remote(SandboxConnectionMode mode,String host,InetAddress target,int port,
            String username,SandboxCredentials credentials){
        return remote(mode,host,target,port,username,credentials,java.time.Duration.ofSeconds(15));
    }
    /** @param mode 协议 @param host 身份主机 @param target 固定IP @param port 端口 @param username 用户 @param credentials 短期认证 @param budget 本次执行预算 @return 独占连接 */
    public static DockerDaemonConnection remote(SandboxConnectionMode mode,String host,InetAddress target,int port,
            String username,SandboxCredentials credentials,java.time.Duration budget){
        return remote(mode,host,target,port,username,credentials,budget,com.cmagent.core.domain.SandboxSshAuthType.KEY);
    }
    /** @param mode 协议 @param host 身份主机 @param target 固定IP @param port 端口 @param username 用户 @param credentials 本次材料 @param budget 有界预算 @param auth SSH认证类型 @return 调用方关闭的固定连接 */
    public static DockerDaemonConnection remote(SandboxConnectionMode mode,String host,InetAddress target,int port,
            String username,SandboxCredentials credentials,java.time.Duration budget,com.cmagent.core.domain.SandboxSshAuthType auth){
        if(mode!=SandboxConnectionMode.SSH && mode!=SandboxConnectionMode.TLS)
            throw failed(ApiErrorCode.SKILL_SANDBOX_INVALID,"远程连接仅支持SSH或双向TLS");
        // 扩展调用方也必须遵守协议边界，禁止把SSH口令误用为TLS或隐式回退私钥。
        if((mode!=SandboxConnectionMode.SSH && auth==com.cmagent.core.domain.SandboxSshAuthType.PASSWORD)
                || (auth!=com.cmagent.core.domain.SandboxSshAuthType.PASSWORD && !credentials.password().isEmpty()))
            throw failed(ApiErrorCode.SKILL_SANDBOX_INVALID,"SSH密码与认证方式不一致");
        DockerDaemonConnection connection=new DockerDaemonConnection(List.of("docker"),false);
        connection.budget=budget;
        try {
            if(mode==SandboxConnectionMode.SSH && auth==com.cmagent.core.domain.SandboxSshAuthType.PASSWORD){
                connection.passwordRelay=PasswordSshRelay.open(host,target,port,username,credentials,budget);
                connection.relayPort=connection.passwordRelay.port();
            }
            else if(mode==SandboxConnectionMode.SSH) connection.ssh(host,target,port,username,credentials);
            else connection.tls(host,target,port,credentials);
            return connection;
        } catch(Exception failure){
            connection.close();
            if(failure instanceof SkillAccessException controlled)throw controlled;
            if(failure instanceof SocketTimeoutException)throw failed(ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"沙箱连接超时");
            if(failure instanceof IOException && !(failure instanceof SSLException))throw failed(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,"沙箱远程连接不可用");
            throw failed(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED,"沙箱连接身份或认证材料无效");
        }
    }
    /** @param arguments Docker固定子命令 @return 对同一连接启动的客户端进程 */
    public Process start(List<String> arguments)throws IOException {
        var command=new ArrayList<>(prefix);command.addAll(arguments);
        if(listener!=null || tunnel!=null || passwordRelay!=null){command=new ArrayList<>(List.of("docker","--host","tcp://127.0.0.1:"+relayPort));command.addAll(arguments);}
        // 显式连接不读取用户的Docker context、代理或认证配置，避免全局配置把宿主材料注入容器。
        command.add(1,"--config");command.add(2,directory.toString());
        return process(command,legacy);
    }
    /** @return 最近连接身份校验是否失败 */
    public boolean authenticationFailed(){return authenticationFailed;}
    /** @param budget 部署执行上限 @return 包含认证建连时间的截止时间 */
    public long deadline(java.time.Duration budget){return openedAt+budget.toNanos();}
    /** 本次随机回环端口，只在当前连接中使用。 */ private int relayPort;
    private void ssh(String host,InetAddress target,int port,String username,SandboxCredentials credentials)throws Exception {
        if(credentials.privateKey().isBlank() || credentials.knownHosts().isBlank())throw failed(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE,"SSH私钥或主机信任材料未配置");
        Path key=privateFile("identity",credentials.privateKey()),known=privateFile("known_hosts",credentials.knownHosts()),config=privateFile("config","");
        // 端点只信任明确提交的主机材料，排除系统级known_hosts提供额外信任来源。
        Path global=privateFile("global_known_hosts","");
        try(ServerSocket reserve=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){relayPort=reserve.getLocalPort();}
        tunnel=process(List.of("ssh","-F",config.toString(),"-N","-T","-o","BatchMode=yes","-o","IdentitiesOnly=yes",
            "-o","IdentityAgent=none","-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile="+known,
            "-o","GlobalKnownHostsFile="+global,
            "-o","HostKeyAlias="+host,"-o","ExitOnForwardFailure=yes","-o","ConnectTimeout="+Math.max(1,Math.min(3,budget.toSeconds())),"-i",key.toString(),
            "-p",String.valueOf(port),"-L","127.0.0.1:"+relayPort+":/var/run/docker.sock",username+"@"+target.getHostAddress()),false);
        // SSH诊断仅丢弃且有界消费，不将私钥、内部地址或客户端原文回传。
        var diagnostic=workers.submit(()->{
            try(var in=tunnel.getInputStream()){
                byte[] chunk=new byte[1024];int count;long consumed=0;
                while((count=in.read(chunk))!=-1){
                    if(consumed<65536){String part=new String(chunk,0,count,StandardCharsets.UTF_8);
                        if(part.contains("Connection refused") || part.contains("No route to host") || part.contains("Network is unreachable"))sshFailure=ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE;
                        if(part.contains("Connection timed out"))sshFailure=ApiErrorCode.SKILL_SANDBOX_TIMEOUT;
                    }consumed+=count;
                }
            }catch(IOException ignored){}
        });
        long end=Math.min(openedAt+budget.toNanos(),System.nanoTime()+TimeUnit.SECONDS.toNanos(4));
        while(System.nanoTime()<end){
            if(!tunnel.isAlive()){
                try{diagnostic.get(500,TimeUnit.MILLISECONDS);}catch(ExecutionException | TimeoutException ignored){}
                throw failed(sshFailure,sshFailure==ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED?"SSH认证或主机身份校验失败":sshFailure==ApiErrorCode.SKILL_SANDBOX_TIMEOUT?"SSH连接超时":"SSH远程连接不可用");
            }
            try(Socket probe=new Socket()){probe.connect(new InetSocketAddress("127.0.0.1",relayPort),100);return;}
            catch(IOException waiting){Thread.sleep(30);}
        }
        throw failed(ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"SSH连接超时");
    }
    private Path privateFile(String name,String content)throws IOException {
        Path file=directory.resolve(name);Files.writeString(file,content,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        if(Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        else {
            // Windows通过当前用户独占ACL限制认证材料，禁止继承临时目录的共享读权限。
            var view=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);
            if(view==null)throw new IOException("不能建立私有认证文件");
            var owner=Files.getOwner(file);
            view.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(owner).setPermissions(java.nio.file.attribute.AclEntryPermission.values()).build()));
        }
        return file;
    }
    private void tls(String host,InetAddress target,int port,SandboxCredentials c)throws Exception {
        SSLContext context=sslContext(c);
        // 先完成一次身份校验，避免探测仅验证本地中继可监听。
        try(SSLSocket check=tlsSocket(context,host,target,port,(int)Math.max(1,Math.min(8000,budget.toMillis())))){}
        listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));relayPort=listener.getLocalPort();
        workers.submit(()->{
            while(!listener.isClosed()){
                try{
                    Socket client=listener.accept();
                    if(!relayPermits.tryAcquire()){client.close();continue;}
                    sockets.add(client);
                    workers.submit(()->relay(client,context,host,target,port));
                }catch(IOException stopped){break;}
            }
        });
    }
    private static SSLContext sslContext(SandboxCredentials c)throws Exception {
        CertificateFactory factory=CertificateFactory.getInstance("X.509");
        var cas=factory.generateCertificates(new ByteArrayInputStream(c.caCertificate().getBytes(StandardCharsets.UTF_8)));
        var chain=factory.generateCertificates(new ByteArrayInputStream(c.clientCertificate().getBytes(StandardCharsets.UTF_8)));
        if(cas.isEmpty() || chain.isEmpty())throw new GeneralSecurityException();
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        int index=0;for(var ca:cas)trust.setCertificateEntry("ca"+index++,ca);
        String pem=c.privateKey().replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        byte[] der=Base64.getDecoder().decode(pem);
        PrivateKey key;
        try{key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));}
        catch(GeneralSecurityException notRsa){key=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));}
        finally{Arrays.fill(der,(byte)0);}
        KeyStore keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);
        keys.setKeyEntry("client",key,new char[0],chain.toArray(java.security.cert.Certificate[]::new));
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,new char[0]);
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),tm.getTrustManagers(),null);return context;
    }
    private static SSLSocket tlsSocket(SSLContext context,String host,InetAddress target,int port,int timeout)throws IOException {
        Socket raw=new Socket();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        try{
            raw.connect(new InetSocketAddress(target,port),Math.min(3000,timeout));
            SSLSocket socket=(SSLSocket)context.getSocketFactory().createSocket(raw,host,port,true);
            socket.setSoTimeout((int)Math.max(1,Math.min(5000,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
            SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
            socket.startHandshake();socket.setSoTimeout(0);return socket;
        }catch(IOException failure){raw.close();throw failure;}
    }
    private void relay(Socket client,SSLContext context,String host,InetAddress target,int port){
        Socket remote=null;
        try{
            remote=tlsSocket(context,host,target,port,8000);sockets.add(remote);Socket upstream=remote;
            workers.submit(()->{try{client.getInputStream().transferTo(upstream.getOutputStream());upstream.shutdownOutput();}catch(IOException ignored){}});
            remote.getInputStream().transferTo(client.getOutputStream());
        }catch(SSLHandshakeException identity){authenticationFailed=true;}
        catch(IOException failure){/* Docker进程会观察到断连，原文不进入响应。 */}
        finally{try{client.close();if(remote!=null)remote.close();}catch(IOException ignored){}sockets.remove(client);if(remote!=null)sockets.remove(remote);relayPermits.release();}
    }
    private static Process process(List<String> command,boolean legacy)throws IOException {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        var original=Map.copyOf(builder.environment());builder.environment().clear();
        original.forEach((k,v)->{if(List.of("PATH","SYSTEMROOT","WINDIR").contains(k.toUpperCase(Locale.ROOT))
            || (legacy && List.of("DOCKER_HOST","DOCKER_TLS_VERIFY","DOCKER_CERT_PATH").contains(k)))builder.environment().put(k,v);});
        return builder.start();
    }
    /** 先关闭socket与进程，再结束线程；认证文件删除失败必须向调用方报告。 */
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        boolean failed=false;
        try{if(passwordRelay!=null)passwordRelay.close();}catch(SkillAccessException error){failed=true;}
        try{if(listener!=null)listener.close();}catch(IOException error){failed=true;}
        for(Socket socket:sockets)try{socket.close();}catch(IOException error){failed=true;}
        if(tunnel!=null){tunnel.destroyForcibly();try{if(!tunnel.waitFor(2,TimeUnit.SECONDS))failed=true;}catch(InterruptedException error){Thread.currentThread().interrupt();failed=true;}}
        workers.shutdownNow();
        if(directory!=null){
            try(var children=Files.list(directory)){for(Path child:children.toList())Files.delete(child);}
            catch(IOException error){failed=true;}
            try{Files.deleteIfExists(directory);}catch(IOException error){failed=true;}
        }
        if(failed)throw failed(ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED,"沙箱连接清理失败，请联系管理员");
    }
    private static SkillAccessException failed(ApiErrorCode code,String message){return new SkillAccessException(code,message,UUID.randomUUID().toString(),true);}
}
