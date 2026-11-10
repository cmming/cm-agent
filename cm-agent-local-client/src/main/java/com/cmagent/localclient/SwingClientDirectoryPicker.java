package com.cmagent.localclient;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * 仅使用本地 Swing 目录窗口，不接受浏览器传入路径。
 *
 * <p>选择在独立 EDT 中执行；调用方轮询线程可以等待用户确认，但取消、无桌面或窗口异常
 * 都会返回受控结果，不会静默选择默认目录。</p>
 */
final class SwingClientDirectoryPicker implements ClientDirectoryPicker {
    @Override public Optional<Path> choose(String deviceName) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前本机客户端没有可用交互桌面");
        }
        AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("在 " + safeName(deviceName) + " 上选择项目文件夹");
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    chooser.setMultiSelectionEnabled(false);
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                            && chooser.getSelectedFile() != null) {
                        selected.set(Optional.of(chooser.getSelectedFile().toPath()));
                    }
                } catch (RuntimeException exception) {
                    failure.set(exception);
                } catch (Exception exception) {
                    failure.set(new IllegalStateException("无法显示系统目录选择窗口", exception));
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("目录选择等待被中断", interrupted);
        } catch (java.lang.reflect.InvocationTargetException failureDuringDisplay) {
            throw new IllegalStateException("无法显示系统目录选择窗口", failureDuringDisplay);
        }
        if (failure.get() != null) throw failure.get();
        return selected.get();
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "当前设备" : value;
    }
}
