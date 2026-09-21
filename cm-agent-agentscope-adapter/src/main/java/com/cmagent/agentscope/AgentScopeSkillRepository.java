package com.cmagent.agentscope;

import com.cmagent.core.domain.SkillVersionView;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * 将本次 Run 已固定的技能版本映射为 AgentScope 只读仓储。
 *
 * <p>仓储只存在于单次运行内存中，既不关联 AgentScope 的目录型仓储，也不允许框架通过
 * {@link #save(List, boolean)}、{@link #delete(String)} 回写任何宿主路径。使用有序 Map 是为了让
 * 模型看到的技能目录在同一快照下保持稳定，便于复现一次历史运行。</p>
 */
final class AgentScopeSkillRepository implements AgentSkillRepository {
    private static final String SOURCE = "cm-agent-run-snapshot";
    private final Map<String, AgentSkill> skills;

    /**
     * @param versions Server 已完成租户、版本和撤销校验的历史版本视图
     */
    AgentScopeSkillRepository(List<SkillVersionView> versions) {
        Objects.requireNonNull(versions, "versions 不能为空");
        Map<String, AgentSkill> mapped = new LinkedHashMap<>();
        for (SkillVersionView view : versions) {
            String name = view.definition().name();
            AgentSkill skill = new AgentSkill(name, view.version().description(), view.version().content(),
                    view.resources().stream().collect(java.util.stream.Collectors.toMap(
                            resource -> resource.path(), resource -> resource.content(),
                            (left, right) -> { throw new IllegalArgumentException("技能资源路径重复"); }, LinkedHashMap::new)),
                    SOURCE + ":" + view.definition().id() + ":" + view.version().id());
            if (mapped.putIfAbsent(name, skill) != null) {
                throw new IllegalArgumentException("运行技能名称重复");
            }
        }
        this.skills = Collections.unmodifiableMap(new LinkedHashMap<>(mapped));
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skills.get(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(skills.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return List.copyOf(skills.values());
    }

    @Override
    public boolean skillExists(String name) {
        return skills.containsKey(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("cm-agent-run-snapshot", SOURCE, false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /**
     * 拒绝原生框架的保存请求，防止运行期间的模型输入写入本机文件或形成下次运行可见的状态。
     *
     * @param ignored 框架计划保存的技能；本实现不会读取其内容
     * @param overwrite 框架请求的覆盖标志；本实现不会使用
     * @return 本实现从不正常返回
     */
    @Override
    public boolean save(List<AgentSkill> ignored, boolean overwrite) {
        throw new UnsupportedOperationException("运行内技能仓储只读");
    }

    /**
     * 拒绝删除请求；运行快照必须在整个 AgentScope 调用期间保持不可变。
     *
     * @param name 待删除名称；本实现不会根据该值查询外部资源
     * @return 本实现从不正常返回
     */
    @Override
    public boolean delete(String name) {
        throw new UnsupportedOperationException("运行内技能仓储只读");
    }

    /**
     * 保持仓储只读。显式设置 {@code false} 是幂等的，设置 {@code true} 会被拒绝。
     *
     * @param writeable 框架请求的可写状态
     */
    @Override
    public void setWriteable(boolean writeable) {
        if (writeable) {
            throw new UnsupportedOperationException("运行内技能仓储只读");
        }
    }
}
