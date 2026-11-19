package com.cmagent.server;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.springframework.boot.SpringApplication;

/** Windows 试装包的交互入口，持有本机后端，服务端 JAR 的正常入口由原构建保持。 */
public final class WindowsPilotLauncher {
    private static final int DEFAULT_PORT = 18180;

    private WindowsPilotLauncher() {
    }

    /**
     * 从当前 Windows 用户的外部配置启动本机项目服务。
     *
     * <p>直接在安装包进程中启动 Spring，既让原生选择器使用交互桌面，也让正常退出
     * 通过 Spring 生命周期关闭服务和项目资源。桌面启动器没有后台子进程。</p>
     *
     * @param args 桌面启动器参数，部署参数只从该用户的试装配置读取
     */
    public static void main(String[] args) throws Exception {
        Path home = null;
        int exitCode = 0;
        try {
            home = pilotHome();
            launch(home, port());
        } catch (LaunchFailure failure) {
            exitCode = 1;
            showFailure(home, failure.code, failure.getMessage());
        } catch (Exception failure) {
            exitCode = 1;
            showFailure(home, "PILOT_START_FAILED", "试装版启动失败。请检查配置和机器的交互桌面。");
        } finally {
            // 文件锁和 Spring 都已退出，显式结束 Swing 事件线程，启动器不留驻进程。
            System.exit(exitCode);
        }
    }

    private static Path pilotHome() throws LaunchFailure {
        String override = System.getenv("CM_AGENT_PILOT_HOME");
        String localAppData = System.getenv("LOCALAPPDATA");
        if ((override == null || override.isBlank()) && (localAppData == null || localAppData.isBlank())) {
            throw new LaunchFailure("PILOT_CONFIG_MISSING", "无法确定 Windows 用户的试装配置目录。");
        }
        try {
            Path home = override == null || override.isBlank()
                    ? Path.of(localAppData, "CM Agent Pilot Data")
                    : Path.of(override);
            if (!home.isAbsolute() || home.toString().startsWith("\\\\")) {
                throw new LaunchFailure("PILOT_CONFIG_INVALID", "试装配置目录必须是本机绝对路径。");
            }
            return home.normalize();
        } catch (InvalidPathException failure) {
            // 路径配置可来自部署输入，启动对话框不复述原始值。
            throw new LaunchFailure("PILOT_CONFIG_INVALID", "试装配置目录格式无效。");
        }
    }

    private static int port() throws LaunchFailure {
        String configuredPort = System.getenv("CM_AGENT_PILOT_PORT");
        if (configuredPort == null || configuredPort.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(configuredPort);
            if (port >= 1024 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException failure) {
            // 与越界端口返回相同的稳定错误，避免泄露环境变量内容。
        }
        throw new LaunchFailure("PILOT_PORT_INVALID", "试装端口必须是 1024 到 65535 的整数。");
    }

    private static void launch(Path home, int port) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new LaunchFailure("PILOT_DESKTOP_REQUIRED", "请从当前用户的 Windows 桌面启动试装版。");
        }
        Path config = home.resolve("pilot.properties");
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) ||
                !home.equals(home.toRealPath()) ||
                !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) ||
                !config.equals(config.toRealPath())) {
            throw new LaunchFailure("PILOT_CONFIG_MISSING", "请由试装管理员在当前用户的试装目录配置 pilot.properties。");
        }
        URI origin = URI.create("http://127.0.0.1:" + port);
        Path state = home.resolve("state");
        Files.createDirectories(state);
        // 启动器自己的锁防止同一份配置打开第二个实例，后端还独立校验项目状态目录的 ACL 和锁。
        try (FileChannel channel = FileChannel.open(home.resolve("launcher.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = tryLock(channel)) {
            if (lock == null) {
                throw new LaunchFailure("PILOT_ALREADY_RUNNING", "试装版已在运行，请从原启动窗口打开聊天。");
            }
            launchServer(config, state, origin);
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException failure) {
            // 同一 JVM 的锁冲突与不同进程的锁冲突统一视为已有实例。
            return null;
        }
    }

    private static void launchServer(Path config, Path state, URI origin) throws Exception {
        PilotWindow window = new PilotWindow(origin.resolve("/console/v2/chat.html"));
        SwingUtilities.invokeAndWait(window::show);
        try {
            SpringApplication application = new SpringApplication(CmAgentServerApplication.class);
            application.setHeadless(false);
            // run() 在 Spring 和内嵌 Web 服务完成启动后返回，打开聊天不会误连占用端口的其他服务。
            try (var context = application.run(launchOptions(config, state, origin))) {
                SwingUtilities.invokeLater(window::ready);
                window.quit.await();
            }
        } catch (RuntimeException failure) {
            // JDBC 驱动和第三方组件异常可能含配置、路径或凭据。启动器只记录稳定的分类。
            if (hasDatabaseCause(failure)) {
                throw new LaunchFailure("PILOT_JDBC_UNAVAILABLE", "无法连接或初始化已配置的 JDBC 数据源，请联系试装管理员。");
            }
            throw new LaunchFailure("PILOT_SERVER_START_FAILED",
                    "本机后端启动失败。请核对端口占用、试装配置和状态目录权限。");
        } finally {
            SwingUtilities.invokeAndWait(window::dispose);
        }
    }

    /** CLI 固定设置优先于外部 JDBC 配置，防止意外切换非回环、模拟执行、无审批或任意命令参数。 */
    static String[] launchOptions(Path config, Path state, URI origin) {
        return new String[]{
                "--spring.config.location=" + config.toUri().toASCIIString(),
                "--spring.profiles.active=local",
                "--server.address=127.0.0.1",
                "--server.port=" + origin.getPort(),
                "--server.ssl.enabled=false",
                "--server.forward-headers-strategy=none",
                "--server.servlet.context-path=",
                // 健康检查与业务共用端口，沿用主服务的回环地址。Spring Boot 拒绝在同端口设置管理地址。
                "--management.server.port=" + origin.getPort(),
                "--management.endpoints.web.exposure.include=health",
                "--cm-agent.persistence.mode=jdbc",
                "--cm-agent.security.allow-dev-jwt-fallback=false",
                "--cm-agent.fake-runtime-enabled=false",
                "--cm-agent.agentscope.enabled=true",
                "--cm-agent.agentscope.permission-enabled=true",
                "--cm-agent.agentscope.studio.enabled=false",
                "--cm-agent.local-projects.enabled=true",
                "--cm-agent.local-projects.allow-non-loopback=false",
                "--cm-agent.local-projects.allowed-origins[0]=" + origin,
                "--cm-agent.local-projects.state-directory=" + state,
                "--cm-agent.local-projects.commands.fixed-arguments-only=true",
                "--cm-agent.local-client.enabled=false",
                "--cm-agent.client-projects.enabled=false"
        };
    }

    private static boolean hasDatabaseCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException || cause.getClass().getName().startsWith("org.flywaydb.")) {
                return true;
            }
        }
        return false;
    }

    private static void showFailure(Path home, String code, String message) throws Exception {
        UUID errorId = recordFailure(home, code);
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                    message + "\n诊断编号 " + errorId, "CM Agent 试装版", JOptionPane.ERROR_MESSAGE));
        } else {
            System.err.println(message + " 诊断编号 " + errorId);
        }
    }

    /** 记录与启动窗口一致的错误编号；边界只接收稳定错误码，从不接收异常或配置内容。 */
    static UUID recordFailure(Path home, String code) {
        UUID errorId = UUID.randomUUID();
        if (home != null && Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.writeString(home.resolve("launcher.log"),
                        Instant.now() + " operation=PILOT_LAUNCH errorCode=" + code + " errorId=" + errorId
                                + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException failure) {
                // 日志不可写时，保留原失败文案和编号供用户报告。
            }
        }
        return errorId;
    }

    private static final class LaunchFailure extends Exception {
        private final String code;

        private LaunchFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 界面与浏览器独立，用户可从同一窗口重复打开聊天或正常关闭服务。 */
    private static final class PilotWindow {
        private final CountDownLatch quit = new CountDownLatch(1);
        private final URI chat;
        private JFrame frame;
        private JLabel status;
        private JButton open;

        private PilotWindow(URI chat) {
            this.chat = chat;
        }

        private void show() {
            frame = new JFrame("CM Agent 试装版");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    stop();
                }
            });
            status = new JLabel("正在启动本机服务…");
            open = new JButton("打开聊天");
            open.setEnabled(false);
            open.addActionListener(event -> browse());
            JButton close = new JButton("退出");
            close.addActionListener(event -> stop());
            JPanel panel = new JPanel();
            panel.add(status);
            panel.add(open);
            panel.add(close);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        private void ready() {
            if (quit.getCount() == 0) {
                return;
            }
            status.setText("本机服务已启动");
            open.setEnabled(true);
            if (!"1".equals(System.getenv("CM_AGENT_PILOT_NO_BROWSER"))) {
                browse();
            }
        }

        private void browse() {
            try {
                Desktop.getDesktop().browse(chat);
                status.setText("本机服务已启动");
            } catch (Exception failure) {
                status.setText("请在浏览器打开 " + chat);
                frame.pack();
            }
        }

        private void stop() {
            open.setEnabled(false);
            status.setText("正在关闭本机服务…");
            quit.countDown();
        }

        private void dispose() {
            frame.dispose();
        }
    }
}
