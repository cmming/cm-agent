package com.cmagent.server.config;

import com.cmagent.server.runtime.LocalProjectCommandService;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本机命令固定部署工具链；默认空，不能因安装了Java就宣称命令可用。
 * 可执行文件/前缀/基础环境来自受信部署配置，HTTP或模型只能选择工具链id。
 */
@ConfigurationProperties("cm-agent.local-projects.commands")
public final class LocalProjectCommandProperties {
    /** 由部署管理员显式配置的工具链映射，不自动扫描PATH。 */
    private Map<String,Tool> tools=new LinkedHashMap<>();
    public Map<String,Tool> getTools(){return tools;}
    public void setTools(Map<String,Tool> tools){this.tools=tools==null?new LinkedHashMap<>():new LinkedHashMap<>(tools);}

    /**
     * @param privateTemp 宿主在项目根之外建立并验证的私有临时目录，不由模型覆盖
     * @return 严格验证且不可变的工具链映射；空配置仍返回空
     */
    public Map<String,LocalProjectCommandService.Toolchain> resolve(Path privateTemp){
        if(tools.size()>32)throw new IllegalStateException("本机命令工具链数量超过上限");
        var result=new LinkedHashMap<String,LocalProjectCommandService.Toolchain>();
        for(var entry:tools.entrySet()){
            if(!entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalStateException("本机工具链标识不合法");
            Tool tool=Objects.requireNonNull(entry.getValue(),"本机工具链不能为空");
            try{
                Path exe=Path.of(tool.executable);
                if(!exe.isAbsolute()||!Files.isRegularFile(exe,LinkOption.NOFOLLOW_LINKS)||!exe.equals(exe.toRealPath())
                        ||!exe.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                    throw new IllegalStateException("本机工具链程序不可用");
                if(privateTemp==null||!privateTemp.isAbsolute()||!Files.isDirectory(privateTemp,LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("本机工具链需要私有临时目录");
                var env=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
                env.putAll(tool.environment);
                // 子进程不能继承Server全量环境，临时位置固定为宿主私有状态，不使用用户项目。
                String system=System.getenv("SystemRoot");
                if(system!=null)env.put("SystemRoot",system);
                env.put("TEMP",privateTemp.toString());env.put("TMP",privateTemp.toString());
                if(tool.allowedEnvironment.stream().anyMatch(key->Set.of("TEMP","TMP","SYSTEMROOT").contains(key.toUpperCase(Locale.ROOT))))
                    throw new IllegalStateException("本机工具链不能开放临时目录或系统根覆盖");
                result.put(entry.getKey(),new LocalProjectCommandService.Toolchain(exe,tool.prefixArguments,env,tool.allowedEnvironment,tool.build));
            }catch(java.io.IOException|InvalidPathException failure){throw new IllegalStateException("本机工具链路径无法核实");}
        }
        return Map.copyOf(result);
    }

    /** 固定工具链定义；不将环境值复制到默认字符串或日志。 */
    public static final class Tool{
        /** 受信EXE绝对路径，脚本必须显式配置解释器和前缀。 */
        private String executable="";
        /** 显式解释器/固定参数，也是批准清单的一部分。 */
        private List<String> prefixArguments=new ArrayList<>();
        /** 受控基础环境，默认不继承。 */
        private Map<String,String> environment=new LinkedHashMap<>();
        /** 模型/用户获准覆盖的环境名称，不包含秘密/临时目录。 */
        private Set<String> allowedEnvironment=new LinkedHashSet<>();
        /** 构建工具标识，仅影响能力展示，不设置默认超时或减弱HIGH审批。 */
        private boolean build;
        public String getExecutable(){return executable;} public void setExecutable(String value){executable=value;}
        public List<String> getPrefixArguments(){return prefixArguments;} public void setPrefixArguments(List<String> value){prefixArguments=List.copyOf(value);}
        public Map<String,String> getEnvironment(){return environment;} public void setEnvironment(Map<String,String> value){environment=Map.copyOf(value);}
        public Set<String> getAllowedEnvironment(){return allowedEnvironment;} public void setAllowedEnvironment(Set<String> value){allowedEnvironment=Set.copyOf(value);}
        public boolean isBuild(){return build;} public void setBuild(boolean value){build=value;}
        @Override public String toString(){return "Tool[受保护配置]";}
    }
    @Override public String toString(){return "LocalProjectCommandProperties[受保护配置]";}
}

