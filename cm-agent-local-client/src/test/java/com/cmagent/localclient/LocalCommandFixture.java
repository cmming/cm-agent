package com.cmagent.localclient;

/** 仅供跨平台命令测试启动的独立进程，不承担生产命令执行职责。 */
public final class LocalCommandFixture {
    private LocalCommandFixture() { }

    /** 根据测试参数输出固定内容或保持运行，供父进程验证输出及停止行为。 */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "wait".equals(args[0])) {
            Thread.sleep(30_000);
        } else {
            System.out.print("abcdef");
        }
    }
}
