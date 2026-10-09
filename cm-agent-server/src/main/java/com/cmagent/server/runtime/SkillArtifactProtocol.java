package com.cmagent.server.runtime;
import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.*;
import com.cmagent.server.config.SkillArtifactProperties;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 不可信容器帧的有界解码器；关联身份只从 SkillReadRequest 取得。 */
final class SkillArtifactProtocol {
    /** 固定引导代码，与脚本输出分离；文件在 tmpfs 生命周期结束前传输。 */
    static final String BOOTSTRAP = """
        import json,sys,os,pathlib,subprocess,signal,stat,threading
        payload=json.load(sys.stdin)
        policy=payload['artifactPolicy']
        root=pathlib.Path('/workspace/skill')
        root.mkdir()
        output='/workspace/output'
        os.mkdir(output)
        for name,content in payload['files'].items():
            target=root/name
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(content,encoding='utf-8')
        os.chdir(root)
        os.environ['CM_AGENT_ARTIFACT_DIR']=output
        runner="import sys,os,runpy;sys.path.insert(0,os.getcwd());sys.argv=[sys.argv[1]];runpy.run_path(sys.argv[0],run_name='__main__')"
        child=subprocess.Popen([sys.executable,'-I','-B','-u','-c',runner,payload['path']],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
        def timeout():
            os._exit(4)
        timer=threading.Timer(policy['scriptTimeout'],timeout)
        timer.daemon=True
        timer.start()
        def feed():
            try:
                child.stdin.write(payload['stdin'].encode('utf-8'))
                child.stdin.close()
            except BrokenPipeError:
                pass
        threading.Thread(target=feed,daemon=True).start()
        text=bytearray()
        while True:
            chunk=child.stdout.read(4096)
            if not chunk:
                break
            text.extend(chunk)
            if len(text)>policy['text']:
                os.killpg(child.pid,signal.SIGKILL)
                sys.exit(3)
        exit_code=child.wait()
        timer.cancel()
        try:
            os.killpg(child.pid,signal.SIGKILL)
        except ProcessLookupError:
            pass
        if exit_code:
            sys.exit(2)
        # PID 命名空间内停止脚本遗留子进程，不允许其在文件校验期间继续写入。
        for entry in os.scandir('/proc'):
            if entry.name.isdigit() and int(entry.name) not in (1,os.getpid()):
                try:
                    os.kill(int(entry.name),signal.SIGKILL)
                except ProcessLookupError:
                    pass
        stream=sys.stdout.buffer
        collection_timer=threading.Timer(policy['collectionTimeout'],timeout)
        collection_timer.daemon=True
        collection_timer.start()
        def header(value):
            data=json.dumps(value,separators=(',',':')).encode('utf-8')+b'\\n'
            if len(data)>8192:
                raise ValueError()
            stream.write(data)
        stream.write(b'CMA1\\n')
        header({'kind':'text','size':len(text)})
        stream.write(text)
        counter=[0,0,0]
        def collect(directory,prefix,depth):
            if depth>4:
                raise ValueError()
            for entry in sorted(os.scandir(directory),key=lambda e:e.name):
                counter[2]+=1
                if counter[2]>1024:
                    raise ValueError()
                name=prefix+entry.name
                if len(name)>240 or any(ord(c)<32 or ord(c)==127 for c in name) or '\\\\' in name or ':' in name:
                    raise ValueError()
                before=entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(before.st_mode):
                    nested=os.open(entry.name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=directory)
                    try:
                        collect(nested,name+'/',depth+1)
                    finally:
                        os.close(nested)
                elif stat.S_ISREG(before.st_mode) and before.st_nlink==1:
                    fd=os.open(entry.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=directory)
                    with os.fdopen(fd,'rb') as source:
                        current=os.fstat(source.fileno())
                        if not stat.S_ISREG(current.st_mode) or current.st_nlink!=1 or (before.st_dev,before.st_ino)!=(current.st_dev,current.st_ino):
                            raise ValueError()
                        size=current.st_size
                        extension=pathlib.PurePosixPath(name).suffix.lower()
                        counter[0]+=1
                        counter[1]+=size
                        if extension not in policy['types'] or size<1 or size>policy['file'] or counter[0]>policy['count'] or counter[1]>policy['total']:
                            raise ValueError()
                        header({'kind':'file','name':name,'size':size})
                        remaining=size
                        while remaining:
                            chunk=source.read(min(8192,remaining))
                            if not chunk:
                                raise ValueError()
                            stream.write(chunk)
                            remaining-=len(chunk)
                        if source.read(1):
                            raise ValueError()
                else:
                    raise ValueError()
        directory=os.open(output,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            collect(directory,'',0)
        finally:
            os.close(directory)
        header({'kind':'end'})
        stream.flush()
        collection_timer.cancel()

        """;
    /** 无状态 JSON 解析器；头部在进入解析器前限制字节数。 */
    private final ObjectMapper mapper=new ObjectMapper();
    String read(InputStream input,SkillReadRequest request,SkillArtifactProperties policy,int textLimit,SkillArtifactSink sink)throws IOException{
        try {
            if(!Arrays.equals(input.readNBytes(5),"CMA1\n".getBytes(StandardCharsets.US_ASCII)))throw new IOException();
            String text=null;int files=0;long total=0;Set<String> seen=new HashSet<>();
            while(true){
                JsonNode header=mapper.readTree(line(input));
                if(header==null || !header.isObject() || !header.path("kind").isTextual())throw new IOException();
                String kind=header.path("kind").asText();
                if(kind.equals("end")){
                    if(header.size()!=1||text==null||input.read()!=-1)throw new IOException();
                    return text;
                }
                JsonNode sizeNode=header.get("size");
                if(sizeNode==null||!sizeNode.isIntegralNumber()||!sizeNode.canConvertToLong())throw new IOException();
                long size=sizeNode.longValue();
                if(kind.equals("text")){
                    if(text!=null || files!=0 || header.size()!=2 || size<0 || size>textLimit)
                        throw new IOException();
                    byte[] bytes=input.readNBytes((int)size);if(bytes.length!=size)throw new IOException();
                    text=new String(bytes,StandardCharsets.UTF_8);
                }else if(kind.equals("file")){
                    String name=header.path("name").isTextual()?header.path("name").asText():null;
                    if(text==null || header.size()!=3 || !safeName(name) || !seen.add(name) || size<1)
                        throw new IOException();
                    String extension=name.substring(name.lastIndexOf('.')<0?name.length():name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
                    if(!policy.getAllowedTypes().contains(extension))throw new IOException();
                    if(++files>policy.getMaxFilesPerCall()||size>policy.getMaxFileBytes()||size>policy.getMaxTotalBytesPerCall()-total)
                        throw new SkillAccessException(ApiErrorCode.SKILL_ARTIFACT_LIMIT_EXCEEDED,"文件产物超过收集上限",request.attemptId().toString(),true);
                    total+=size;
                    var bounded=new FileInput(input,size);
                    sink.accept(name,size,bounded);
                    if(bounded.remaining!=0)throw new IOException();
                }else throw new IOException();
            }
        }catch(SkillAccessException controlled){throw controlled;}
        catch(IOException|IllegalArgumentException invalid){
            // 非法帧不将字节或底层异常原文交给模型/日志，使用本次调用关联编号。
            throw new SkillAccessException(ApiErrorCode.SKILL_ARTIFACT_INVALID,"文件产物协议、名称或传输不完整",request.attemptId().toString(),true);
        }
    }
    /** 文件名还拒绝方向控制/不可见格式符，防止安全类型和下载名称的视觉欺骗。 */
    static boolean safeName(String name){return DockerSkillSandbox.safePath(name)&&name.codePoints().noneMatch(c->Character.getType(c)==Character.FORMAT||c>=0xd800&&c<=0xdfff);}
    private byte[] line(InputStream input)throws IOException{
        var bytes=new ByteArrayOutputStream();
        int b;while((b=input.read())!=-1&&b!='\n'){if(bytes.size()>=8192)throw new IOException();bytes.write(b);}
        if(b==-1)throw new EOFException();return bytes.toByteArray();
    }
    /** 单文件流不关闭共享协议；缺字节立即失败，下一文件帧不能由接收器越界读取。 */
    private static final class FileInput extends InputStream {
        /** 同一连接持有的共享协议输入，逻辑文件不拥有关闭权。 */ private final InputStream input;
        /** 仅允许消费当前文件声明的剩余字节。 */ private long remaining;
        FileInput(InputStream input,long remaining){this.input=input;this.remaining=remaining;}
        public int read()throws IOException{
            if(remaining==0)return -1;int b=input.read();if(b==-1)throw new EOFException();remaining--;return b;
        }
        public int read(byte[] bytes,int offset,int count)throws IOException{
            if(count==0)return 0;if(remaining==0)return -1;
            int n=input.read(bytes,offset,(int)Math.min(count,remaining));if(n==-1)throw new EOFException();remaining-=n;return n;
        }
        public void close(){/* 只关闭当前逻辑文件；共享协议由 Docker 读取任务持有。 */}
    }
}
