package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import com.jcraft.jsch.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 一次调用独占的内存SSH密码连接，固定转发到远端Docker Unix socket。
 * <p>JSch 2.28.7提供direct-streamlocal通道；仅密码分支使用，不启动SSH助手或写密码文件。
 * 调用方先校验租户与目标，本类固定已解析IP并以原host验证known_hosts，不读取用户配置/agent。
 * 连接由本类持有；close会终止监听、通道、socket和线程，不能为旧调用重连新密码。</p>
 */
final class PasswordSshRelay implements AutoCloseable {
    /** 本次认证会话，禁止共享给其他租户或调用。 */ private Session session;
    /** 随机IPv4回环端口，只供同一次Docker调用。 */ private ServerSocket listener;
    /** 建连中及运行中的socket均由close终止。 */ private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    /** 每个Docker HTTP连接对应一个独占SSH通道。 */ private final Set<Channel> channels=ConcurrentHashMap.newKeySet();
    /** 保留R1中继并发上限，超限立即关闭而不积累等待。 */ private final Semaphore permits=new Semaphore(8);
    /** 虚拟线程和后台泵只存活于当前句柄。 */ private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    /** 关闭后不再接收或登记新连接。 */ private volatile boolean closed;
    /** 首次建连和通道打开共享调用方剩余预算。 */ private int timeout;
    /** 截止时间只约束首次认证与预探测，不能重置调用方预算。 */ private long deadline;

    /**
     * @param host 已验证原主机，作为主机身份别名
     * @param target 策略固定IP，不在库中重新解析host
     * @param port 已允许端口
     * @param user 已验证用户名
     * @param credentials 仅本次内存认证材料
     * @param budget 剩余建连预算
     * @return 由调用方负责关闭的独占连接
     */
    static PasswordSshRelay open(String host,InetAddress target,int port,String user,SandboxCredentials credentials,Duration budget) {
        var relay=new PasswordSshRelay();
        try { relay.connect(host,target,port,user,credentials,budget);return relay; }
        catch(Exception failure) {
            relay.close();
            if(failure instanceof SkillAccessException controlled)throw controlled;
            if(Thread.currentThread().isInterrupted())throw failed(ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"SSH密码连接已中断");
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SocketTimeoutException)throw failed(ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"SSH密码连接超时");
                if(cause instanceof IOException)throw failed(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,"SSH密码连接不可用");
            }
            // 不传播JSch异常原文，避免内部地址、服务端banner或认证提示进入API/日志。
            throw failed(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED,"SSH密码认证或主机身份校验失败，请核对凭据与认证策略");
        }
    }
    private void connect(String host,InetAddress target,int port,String user,SandboxCredentials c,Duration budget)throws Exception {
        if(c.password().isEmpty() || c.knownHosts().isBlank())
            throw failed(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE,"SSH密码或主机信任材料未配置");
        deadline=System.nanoTime()+budget.toNanos();
        timeout=remaining();
        JSch client=new JSch();
        // 仅当前实例禁原始诊断，不改库的静态全局配置；技术诊断由上层稳定失败码收口。
        client.setInstanceLogger(new com.jcraft.jsch.Logger(){public boolean isEnabled(int level){return false;}public void log(int level,String message){}});
        client.setKnownHosts(new ByteArrayInputStream(c.knownHosts().getBytes(StandardCharsets.UTF_8)));
        session=client.getSession(user,host,port);
        session.setHostKeyAlias(host);
        session.setConfig("StrictHostKeyChecking","yes");
        session.setConfig("PreferredAuthentications","password");
        session.setConfig("MaxAuthTries","1");
        session.setDaemonThread(true);
        session.setSocketFactory(new com.jcraft.jsch.SocketFactory(){
            public Socket createSocket(String ignoredHost,int ignoredPort)throws IOException {
                Socket socket=new Socket();sockets.add(socket);
                if(closed){socket.close();throw new IOException();}
                socket.connect(new InetSocketAddress(target,port),remaining());return socket;
            }
            public InputStream getInputStream(Socket socket)throws IOException{return socket.getInputStream();}
            public OutputStream getOutputStream(Socket socket)throws IOException{return socket.getOutputStream();}
        });
        byte[] password=c.password().getBytes(StandardCharsets.UTF_8);
        Thread caller=Thread.currentThread();
        // 库的阻塞握手不会仅因线程中断退出；关闭本次socket解除IO，禁止留下后台认证。
        Future<?> cancellation=workers.submit(()->{
            while(!closed && !Thread.currentThread().isInterrupted()){
                if(caller.isInterrupted()){
                    for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}
                    return;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        });
        try {
            // 2.28.7的Session先检查主机密钥再进入用户认证；setPassword(byte[])复制输入，connect清理内部副本。
            session.setPassword(password);session.connect(timeout);
        } finally { cancellation.cancel(true);Arrays.fill(password,(byte)0);session.setPassword((byte[])null); }
        // 认证成功不等于允许socket转发；先真实打开固定通道，拒绝将回环监听视为探测成功。
        try { ChannelDirectStreamLocal check=channel();check.connect(remaining());check.disconnect();channels.remove(check); }
        catch(JSchException unavailable){throw failed(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,"SSH账户未获Docker socket访问或转发权限");}
        listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        workers.submit(()->{
            while(!closed)try {
                Socket socket=listener.accept();
                if(!permits.tryAcquire()){socket.close();continue;}
                sockets.add(socket);
                if(closed){socket.close();sockets.remove(socket);permits.release();break;}
                workers.submit(()->relay(socket));
            } catch(IOException stopped){break;}
        });
    }
    private int remaining(){
        long left=deadline-System.nanoTime();
        if(left<=0)throw failed(ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"SSH密码连接超时");
        return (int)Math.max(1,Math.min(8000,TimeUnit.NANOSECONDS.toMillis(left)));
    }
    /** 固定Unix socket通道，模型或管理API不能指定路径，也不能发送远端命令。 */
    private ChannelDirectStreamLocal channel()throws JSchException {
        var channel=(ChannelDirectStreamLocal)session.openChannel("direct-streamlocal@openssh.com");
        channel.setSocketPath("/var/run/docker.sock");channels.add(channel);
        if(closed){channel.disconnect();throw new JSchException("连接已关闭");}
        return channel;
    }
    private void relay(Socket socket) {
        ChannelDirectStreamLocal channel=null;Future<?> pump=null;
        try {
            channel=channel();
            // JSch的通道IO必须在connect前装配；后装配会丢失已到达数据，表现为Docker探测挂起。
            InputStream input=channel.getInputStream();OutputStream output=channel.getOutputStream();
            channel.connect(timeout);
            pump=workers.submit(()->{
                try{
                    byte[] buffer=new byte[8192];int count;
                    // JSch输出按包缓冲；HTTP客户端等待响应前不会关闭请求流，必须逐批flush防止双向等待。
                    while((count=socket.getInputStream().read(buffer))!=-1){output.write(buffer,0,count);output.flush();}
                }catch(IOException closedSocket){}
                finally{try{output.close();}catch(IOException ignored){}}
            });
            input.transferTo(socket.getOutputStream());
        } catch(IOException | JSchException closedConnection) {
            // Docker进程观察到断流并由治理层转换错误；这里不输出SSH原文或重复打印堆栈。
        } finally {
            if(channel!=null){channel.disconnect();channels.remove(channel);}
            try{socket.close();}catch(IOException ignored){}
            sockets.remove(socket);if(pump!=null)pump.cancel(true);permits.release();
        }
    }
    /** @return 当前随机回环端口，不包含认证材料 */
    int port(){return listener.getLocalPort();}
    /** 幂等关闭先阻止接入再终止活动IO；资源终止失败不能假装清理完成。 */
    @Override public synchronized void close() {
        if(closed)return;closed=true;boolean failed=false;
        if(listener!=null)try{listener.close();}catch(IOException error){failed=true;}
        if(session!=null)session.disconnect();
        for(Channel channel:channels)channel.disconnect();
        for(Socket socket:sockets)try{socket.close();}catch(IOException error){failed=true;}
        workers.shutdownNow();
        // 已有取消信号不能跳过清理等待；结束后恢复，以免误判线程仍存活或丢失上层取消。
        boolean interrupted=Thread.interrupted();
        try{if(!workers.awaitTermination(2,TimeUnit.SECONDS))failed=true;}
        catch(InterruptedException error){Thread.currentThread().interrupt();failed=true;}
        finally{if(interrupted)Thread.currentThread().interrupt();}
        if(failed)throw failed(ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED,"SSH密码连接清理未完成");
    }
    private static SkillAccessException failed(ApiErrorCode code,String message){return new SkillAccessException(code,message,UUID.randomUUID().toString(),true);}
}
