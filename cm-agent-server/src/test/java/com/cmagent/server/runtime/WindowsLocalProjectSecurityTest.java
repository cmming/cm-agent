package com.cmagent.server.runtime;

import java.nio.file.*;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 只验证本测试创建的文件，不启用令牌特权或修改真实项目权限。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsLocalProjectSecurityTest {
    @TempDir Path temp;
    @Test void 当前账号默认归属文件可以恢复原ACL并核实描述符() throws Exception{
        Path source=Files.writeString(temp.resolve("source.txt"),"合成正文");
        var original=WindowsLocalProjectSecurity.capture(source);
        assertThat(original.restorable()).isTrue();
        assertThat(original.toString()).doesNotContain(original.descriptor(),temp.toString());
        var acl=Files.getFileAttributeView(source,AclFileAttributeView.class);
        var originalAcl=List.copyOf(acl.getAcl());
        Path restored=Files.writeString(temp.resolve("restored.txt"),"");
        WindowsLocalProjectSecurity.restore(restored,original);
        assertThat(Files.getFileAttributeView(restored,AclFileAttributeView.class).getAcl()).isEqualTo(originalAcl);
        assertThat(WindowsLocalProjectSecurity.capture(restored).fingerprint()).isEqualTo(original.fingerprint());
        assertThat(WindowsLocalProjectSecurity.capture(restored).identity()).isNotEqualTo(original.identity());
    }
    @Test void 不可恢复归属和篡改快照在权限应用前拒绝() throws Exception{
        Path file=Files.writeString(temp.resolve("file.txt"),"合成正文");
        var captured=WindowsLocalProjectSecurity.capture(file);
        var unsupported=new WindowsLocalProjectSecurity.Snapshot(captured.descriptor(),captured.fingerprint(),false,captured.identity());
        assertThatThrownBy(()->WindowsLocalProjectSecurity.restore(file,unsupported)).isInstanceOf(java.io.IOException.class);
        var changed=new WindowsLocalProjectSecurity.Snapshot(captured.descriptor(),"wrong",true,captured.identity());
        assertThatThrownBy(()->WindowsLocalProjectSecurity.restore(file,changed)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(file)).isEqualTo("合成正文");
    }
}

