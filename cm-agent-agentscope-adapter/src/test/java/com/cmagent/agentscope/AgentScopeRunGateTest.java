package com.cmagent.agentscope;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.core.runtime.SkillReadResult;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证致命技能读取失败会关闭同一运行中的后续调用。 */
class AgentScopeRunGateTest {

    @Test
    void fatalSkillFailureIsRememberedAndBlocksLaterReads() {
        AgentScopeRunGate gate = new AgentScopeRunGate();
        SkillAccessException revoked = new SkillAccessException(ApiErrorCode.SKILL_ACCESS_REVOKED,
                "本轮使用的技能已停用或解绑", "error-1", true);

        assertThatThrownBy(() -> gate.invokeSkill((request, loader) -> {
                    throw revoked;
                }, request(), () -> "不会返回"))
                .isSameAs(revoked);

        AtomicBoolean invoked = new AtomicBoolean();
        assertThatThrownBy(() -> gate.invokeSkill((request, loader) -> {
                    invoked.set(true);
                    return new SkillReadResult(loader.get(), UUID.randomUUID());
                }, request(), () -> "不应读取"))
                .isSameAs(revoked);
        assertThatThrownBy(gate::throwIfSkillFailure).isSameAs(revoked);
        assertThat(invoked).isFalse();
    }

    private static SkillReadRequest request() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        return new SkillReadRequest(new PrincipalRef(tenantId, "tester", "测试主体", Set.of("agent:run")),
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                UUID.fromString("00000000-0000-0000-0000-000000000203"), "call-1", UUID.randomUUID(),
                UUID.fromString("00000000-0000-0000-0000-000000000204"),
                UUID.fromString("00000000-0000-0000-0000-000000000205"), "SKILL.md");
    }
}
