package com.cmagent.server.runtime;

import com.sun.jna.Pointer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.assertj.core.api.Assertions.*;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

class WindowsLocalProjectDirectoryPickerTest {
    @Test void 无输入桌面时失败关闭且不调用关闭空句柄() {
        var calls = new AtomicInteger();
        var user = new WindowsLocalProjectDirectoryPicker.User() {
            @Override public Pointer OpenInputDesktop(int flags, boolean inherit, int access) { return null; }
            @Override public boolean CloseDesktop(Pointer desktop) { calls.incrementAndGet(); return true; }
        };
        assertThatThrownBy(() -> WindowsLocalProjectDirectoryPicker.checkDesktop(user))
                .isInstanceOfSatisfying(LocalProjectNativeException.class,
                        error -> assertThat(error.reason()).isEqualTo(DESKTOP_UNAVAILABLE));
        assertThat(calls.get()).isZero();
    }

    @Test void 有效桌面句柄恰好释放一次且关闭失败不伪报可用() {
        var calls = new AtomicInteger();
        var user = new WindowsLocalProjectDirectoryPicker.User() {
            @Override public Pointer OpenInputDesktop(int flags, boolean inherit, int access) { return Pointer.createConstant(1); }
            @Override public boolean CloseDesktop(Pointer desktop) { calls.incrementAndGet(); return false; }
        };
        assertThatThrownBy(() -> WindowsLocalProjectDirectoryPicker.checkDesktop(user))
                .isInstanceOfSatisfying(LocalProjectNativeException.class,
                        error -> assertThat(error.reason()).isEqualTo(RESOURCE_RELEASE_FAILED));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void 原生异常不泄漏系统消息或宿主路径() {
        var user = new WindowsLocalProjectDirectoryPicker.User() {
            @Override public Pointer OpenInputDesktop(int flags, boolean inherit, int access) {
                throw new IllegalStateException("合成私有路径 SECRET_TEST_PAYLOAD");
            }
            @Override public boolean CloseDesktop(Pointer desktop) { throw new AssertionError("不应关闭"); }
        };
        assertThatThrownBy(() -> WindowsLocalProjectDirectoryPicker.checkDesktop(user))
                .isInstanceOf(LocalProjectNativeException.class).hasNoCause()
                .hasMessage("本机原生组件暂不可用");
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void 非Windows不加载原生DLL也不伪装可选目录() {
        assertThatThrownBy(() -> new WindowsLocalProjectDirectoryPicker().choose())
                .isInstanceOfSatisfying(LocalProjectNativeException.class,
                        error -> assertThat(error.reason()).isEqualTo(DESKTOP_UNAVAILABLE));
    }
}

