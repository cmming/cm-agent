package com.cmagent.server.runtime;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 仅供T1自建临时目录验收的安全子进程，不读取用户项目或凭据。
 *
 * <p>args模式回写合成参数以验证Windows引用；parent模式生成一个同类sleep子进程，
 * 用于证明Job包含后代。测试结束由精确Job/ProcessHandle清理，不按名称扫描进程。</p>
 */
public final class LocalProjectProcessFixture {
    private LocalProjectProcessFixture() {}

    /** @param args 测试固定模式及自建目录，不用于生产命令执行 */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "output" -> {
                System.out.print("stdout 中文\n");
                System.err.print("stderr 合成\n");
            }
            case "flood" -> {
                for(int i=0;i<4096;i++){System.out.print("stdout-data-0123456789\n");System.err.print("stderr-data-0123456789\n");}
            }
            case "secret-output" -> {
                System.out.print("api-key=synthetic-sensitive-value\n");
                System.err.print("Bearer synthetic-sensitive-token\n");
            }
            case "args" -> {
                try (var output = new DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
                    output.writeUTF(System.getenv().getOrDefault("CM_AGENT_T1_APPROVED", ""));
                    output.writeBoolean(System.getenv("PATH") == null);
                    output.writeInt(args.length - 2);
                    for (int i = 2; i < args.length; i++) output.writeUTF(args[i]);
                }
            }
            case "parent", "parent-exit" -> {
                var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                        "-cp", System.getProperty("java.class.path"), LocalProjectProcessFixture.class.getName(),
                        "sleep", args[1]);
                new ProcessBuilder(command).start();
                if ("parent".equals(args[0])) Thread.sleep(120_000);
            }
            case "sleep" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(120_000);
            }
            default -> throw new IllegalArgumentException("未知测试夹具模式");
        }
    }
}

