package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.cmagent.server.store.InMemoryRuntimeCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实NTFS/Win32合成目录验收；只修改本例创建的随机根，不触碰用户reference/imports。
 * Linux显式跳过，不以纯策略或Rocky结果替代Windows证据。
 */
@EnabledOnOs(OS.WINDOWS)
@ExtendWith(OutputCaptureExtension.class)
class WindowsWorkspaceAclTest {
    /** 每例自建驱动器根下的随机目录，避免应用/用户主目录保护范围。 */
    private Path root;
    /** 当前测试根的真实SID，由原生读取，绝非固定测试账户显示名。 */
    private String ownerSid;
    /** 使用真实生产读取器，不覆盖保护源或权限决策。 */
    private final WorkspaceContextFiles files = new WorkspaceContextFiles();
    private final WorkspaceContextProperties policy = new WorkspaceContextProperties();
    private final UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID();
    private WorkspaceContextProperties.Binding binding;

    @BeforeEach void prepare() throws Exception {
        root = Files.createTempDirectory(Path.of(System.getProperty("user.dir")).toAbsolutePath().getRoot(), "cm-agent-r2-acl-");
        ownerSid = new WindowsSecurityDescriptorReader().read(root, deadline()).ownerSid();
        apply(root, "D:P" + allow(ownerSid, "OICI") + allow("SY", "OICI") + allow("BA", "OICI"));
        Files.createDirectory(root.resolve("reference"));Files.writeString(root.resolve("reference/guide.md"), "合成参考资料");
        policy.setEnabled(true);policy.setRootDirectory(root.toString());
        binding = new WorkspaceContextProperties.Binding(tenant, agent, "reference", List.of("guide.md"));policy.setBindings(List.of(binding));
    }
    @AfterEach void cleanup() throws Exception {
        if (root == null) return;
        // 仅处理本例固定随机根；先恢复自己创建对象的ACL，避免DENY/空DACL夹具影响清理。
        assertThat(root.getFileName().toString()).startsWith("cm-agent-r2-acl-");
        try (var walk = Files.walk(root)) {
            for (var path : walk.toList()) apply(path, "D:P" + allow(ownerSid, Files.isDirectory(path) ? "OICI" : ""));
        }
        try (var walk = Files.walk(root)) { for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    @Test void 所有者SYSTEM内置管理员和真实继承ACE可以读取() throws Exception {
        var nativeView = new WindowsSecurityDescriptorReader().read(root.resolve("reference/guide.md"), deadline());
        assertThat(nativeView.entries()).anyMatch(e -> e.sid().equals(WindowsWorkspaceAclPolicy.SYSTEM));
        assertThat(nativeView.entries()).anyMatch(e -> e.sid().equals(WindowsWorkspaceAclPolicy.ADMINISTRATORS));
        assertThat(nativeView.entries()).anyMatch(e -> (e.flags() & 0x10) != 0);
        assertThat(files.read(policy, binding)).hasSize(1);
    }
    @Test void 只有所有者的原私有根仍可读取() throws Exception {
        apply(root, "D:P" + allow(ownerSid, "OICI"));assertThat(files.read(policy, binding)).hasSize(1);
    }
    @Test void 普通组权限在根或登记子目录或文件上均拒绝() throws Exception {
        for (Path path : List.of(root, root.resolve("reference"), root.resolve("reference/guide.md"))) {
            for (String sid : List.of("WD", "AU", "BU", "S-1-5-21-9-8-7-1234")) {
                apply(path, "D:P" + allow(ownerSid, "") + "(A;;GR;;;" + sid + ")");
                assertThatThrownBy(() -> files.read(policy, binding)).isInstanceOf(WorkspaceContextFiles.Invalid.class);
                apply(path, "D:P" + allow(ownerSid, Files.isDirectory(path) ? "OICI" : ""));
            }
        }
    }
    @Test void 空和NULL权限描述符均不当作安全() throws Exception {
        for (String dacl : List.of("D:P", "D:NO_ACCESS_CONTROL")) {
            apply(root.resolve("reference/guide.md"), dacl);
            assertThatThrownBy(() -> files.read(policy, binding)).isInstanceOf(WorkspaceContextFiles.Invalid.class);
            apply(root.resolve("reference/guide.md"), "D:P" + allow(ownerSid, ""));
        }
    }
    @Test void 真实OS拒绝正文读取时不会被可信SID绕过() throws Exception {
        apply(root.resolve("reference/guide.md"), "D:P(D;;0x1;;;" + ownerSid + ")" + allow(ownerSid, ""));
        assertThatThrownBy(() -> files.read(policy, binding)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void 原生描述符反复读取后可移动删除且不持有文件句柄() throws Exception {
        var reader = new WindowsSecurityDescriptorReader();
        for (int i=0;i<128;i++) reader.read(root.resolve("reference/guide.md"), deadline());
        var moved = root.resolve("reference/moved.md");Files.move(root.resolve("reference/guide.md"), moved);Files.move(moved, root.resolve("reference/guide.md"));
    }
    @Test void SID释放失败必须准确失败而不遗漏实际释放() {
        var system = Native.load("Kernel32",WindowsSecurityDescriptorReader.Kernel.class);
        var failing = mock(WindowsSecurityDescriptorReader.Kernel.class);
        when(failing.LocalFree(any())).thenAnswer(call -> {
            Pointer allocated = call.getArgument(0);assertThat(system.LocalFree(allocated)).isNull();
            return Pointer.createConstant(1);
        });
        var reader = new WindowsSecurityDescriptorReader(Native.load("Advapi32",WindowsSecurityDescriptorReader.Advapi.class),failing);
        assertThatThrownBy(() -> reader.read(root,deadline())).isInstanceOf(java.io.IOException.class).hasMessageContaining("资源释放失败");
        verify(failing,times(1)).LocalFree(any());
    }
    @Test void 审批恢复复核ACL且错误日志同号脱敏(CapturedOutput output) throws Exception {
        var owner = new PrincipalRef(tenant,"synthetic-owner","合成主体",Set.of("agent:run"));
        var run = RunRecord.create(UUID.randomUUID(),tenant,agent,owner.principalId(),"合成输入",Instant.now());
        var rows = new InMemoryRuntimeCheckpointRepository();var audit = mock(AuditAppender.class);
        var cipher = new ModelCredentialCipher(new SecretKeySpec(new java.security.SecureRandom().generateSeed(32),"AES"));
        var service = new WorkspaceContextService(policy,rows,cipher,new ObjectMapper().findAndRegisterModules(),audit,Clock.systemUTC(),Duration.ofMinutes(15),files);
        var original = service.prepare(owner,run,false);
        apply(root.resolve("reference/guide.md"), "D:P"+allow(ownerSid,"")+"(A;;GW;;;AU)");
        assertThatThrownBy(() -> service.prepare(owner,run,true)).isInstanceOfSatisfying(WorkspaceContextException.class,e->{
            assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_INVALID);assertThat(e.errorId()).isEqualTo(run.id().toString());
            assertThat(e.getMessage()).contains("不可信主体").doesNotContain(root.toString(),ownerSid);
        });
        assertThatThrownBy(() -> service.retain(run,original)).isInstanceOf(WorkspaceContextException.class);
        assertThat(output.getAll()).contains("errorId="+run.id(),"WORKSPACE_CONTEXT_INVALID").doesNotContain(root.toString(),ownerSid,"合成参考资料");
        apply(root.resolve("reference/guide.md"), "D:P"+allow(ownerSid,""));
        assertThat(service.prepare(owner,run,true)).isEqualTo(original);
        Files.delete(root.resolve("reference/guide.md"));assertThat(service.prepare(owner,run,true)).isEqualTo(original);
    }
    private static long deadline() { return System.nanoTime()+Duration.ofSeconds(2).toNanos(); }
    private static String allow(String sid,String flags) { return "(A;"+flags+";FA;;;"+sid+")"; }
    private void apply(Path path,String sddl) throws Exception {
        setTestAcl(root,path,sddl);
    }
    /** 仅供本例和既有合同的独占合成根使用，不接收部署目录或任意外部路径。 */
    static void setTestAcl(Path testRoot,Path path,String sddl) throws Exception {
        if (!path.toAbsolutePath().normalize().startsWith(testRoot) || !(testRoot.getFileName().toString().startsWith("cm-agent-r2-acl-")
                || testRoot.getFileName().toString().startsWith("cm-agent-reference-contract-"))) throw new IllegalStateException("测试ACL路径越界");
        var pointer = new PointerByReference();
        assertThat(TestApi.ADVAPI.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(sddl),1,pointer,new IntByReference())).isTrue();
        try {
            var dacl=new PointerByReference();
            assertThat(TestApi.ADVAPI.GetSecurityDescriptorDacl(pointer.getValue(),new IntByReference(),dacl,new IntByReference())).isTrue();
            // SetNamedSecurityInfo按Windows规则传播可继承项，不能以仅设置根的夹具冒充实际继承。
            assertThat(TestApi.ADVAPI.SetNamedSecurityInfoW(new WString(path.toString()),1,0x80000004,null,null,dacl.getValue(),null)).isZero();
        }
        finally { assertThat(TestApi.KERNEL.LocalFree(pointer.getValue())).isNull(); }
    }
    /** 仅测试进程持有写ACL函数，生产适配器无修改权限接口。 */
    private static final class TestApi {
        static final TestAdvapi ADVAPI=Native.load("Advapi32",TestAdvapi.class);
        static final WindowsSecurityDescriptorReader.Kernel KERNEL=Native.load("Kernel32",WindowsSecurityDescriptorReader.Kernel.class);
    }
    interface TestAdvapi extends StdCallLibrary {
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(WString sddl,int revision,PointerByReference descriptor,IntByReference size);
        boolean GetSecurityDescriptorDacl(Pointer descriptor,IntByReference present,PointerByReference dacl,IntByReference defaulted);
        int SetNamedSecurityInfoW(WString path,int objectType,int information,Pointer owner,Pointer group,Pointer dacl,Pointer sacl);
    }
}
