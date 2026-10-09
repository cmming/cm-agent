# Windows 沙箱引导参数账本

日期：2026-10-09；修订：R0；工作模式：mode=default。Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。

| 编号 | 状态 | 目标与验收 | 依赖 |
|---|---|---|---|
| T0 | 完成 | 用 Windows JVM、随机密码和 Rocky 独立 Docker 复现协议头前语法错误 | 无 |
| T1 | 完成 | 仅包裹固定引导代码，保持 stdin、隔离与治理边界 | T0 |
| T2 | 完成 | Windows 密码 SSH 的非空输入、DOCX 回传及现有单元测试通过 | T1 |
| T3 | 完成 | Rocky Maven21 容器验证 Linux 文本及文件链路，文档、差异、夹具清理完整 | T2 |
| T4 | 待做 | 用户部署新 Server 后复验真实 TEST，核实原 30 秒超时是否消失 | T3；现场环境 |

T0 当前证据：本机 Temurin JDK21.0.11/Maven3.9.4/Docker CLI27.3.1，Rocky Docker23.0.6；远端项目HEAD与本地均为 dc541e7，Maven3.9.9容器使用JDK21.0.7。

临时夹具：cm-agent-password-win-docx-20261009；项目标签 cm-agent.validation=password-docx；Rocky 回环 SSH 端口32771，随机密码不保存。独立 daemon、导入预拉取 Python 镜像。首次夹具挂载父目录只读导致子挂载创建失败；移到独立目录后，直接 sh 入口又暴露 nested cgroup 错误；切换官方 dind 入口后启动成功。只删除本次夹具，未处理其他资源。

修复前实际输出：PASSWORD_DOCKER_TEXT_OK；文件原始流91字节，前缀为 Python 错误文本，退出1；第14行 `runner=import`，SyntaxError。原始数据仅来自随机夹具和公开样例，位于忽略目录 dist/validation/password-docx-20261009/raw-protocol.bin；不是用户生产输出。

T1/T2/T3 完成，T4 待现场更新 Server 后复验。修复验证结束时未提交；本次提交记录见下文。未推送、未部署。

本轮验证命令和结果：

1. 本机 JAVA_HOME 指向 Temurin21.0.11，CM_AGENT_TEST_PASSWORD_WINDOWS=true。在独立源码副本执行 `mvn -q -f dist/validation/server-lombok-support-20261009/pom.xml -pl cm-agent-server -am -Dtest=PasswordSshWindowsFixtureTest,DockerSkillSandboxTest,SkillArtifactProtocolTest,PasswordSshRelayTest -Dsurefire.failIfNoSpecifiedTests=false -Dsandbox.fixture.name=cm-agent-password-win-docx-20261009 -Dsandbox.fixture.port=32771 -Dsandbox.fixture.public-key=F:/java/cm-agent/dist/validation/password-docx-20261009/host.pub -Dmaven.test.redirectTestOutputToFile=true test`：退出0。Windows夹具1、Docker单元3、协议3、密码中继2，共9项，失败/错误/跳过均0。所有容器及临时账户操作经 ssh rocky，Windows JVM仅为实际客户端。
2. `python dist/validation/password-docx-20261009/run_verification.py`：实际 Windows Java→SSH密码→Rocky独立Docker执行公开 docx-stdlib 1.0.1 ZIP。RAW_PROTOCOL bytes=3878、exit=0、前缀434d41310a（CMA1换行）；WINDOWS_PASSWORD_DOCKER_DOCX_OK bytes=3589，文件结构/长度/摘要一致，Java退出0。临时原始输出与脚本均在忽略目录；密码只有随机内存值，不在命令参数或文件。
3. 通过 scp 同步待验证 Java/POM 到远端独立副本；双方 Git HEAD均为dc541e7；DockerSkillSandbox源码SHA-256双方均为 `961e5b65d00ff888eb7204a51fa84371e227684bc588e50b919f82a2bcdf3782`，远端副本含未提交验证覆盖，不宣称已提交。Rocky中使用 `docker run --rm -e CM_AGENT_TEST_SANDBOX=true -v /root/cm-agent-docx-stdlib-c8e7a90-20261009:/workspace -v /root/.m2:/root/.m2 -v /usr/bin/docker:/usr/bin/docker:ro -v /var/run/docker.sock:/var/run/docker.sock -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-server -am -Dtest=DockerSkillSandboxIntegrationTest,SkillArtifactDockerIntegrationTest,DockerSkillSandboxTest,SkillArtifactProtocolTest,PasswordSshRelayTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.redirectTestOutputToFile=true test`：退出0；对应7+3+3+3+2，共18项，失败/错误/跳过均0。可信 Maven 验证容器持有 daemon socket，技能容器依旧不挂载宿主 socket。
4. 校验本次夹具准确名称及 cm-agent.validation=password-docx 标签后，删除其容器与匿名卷并复查不存在：OWN_FIXTURE_REMOVED；没有清理无关 Docker 资源。
5. 六份工作包链接检查及 git diff --check通过；新增Java注释解释实际复现的参数兼容问题和可信引导边界，没有新增配置项、依赖或数据库迁移。

未执行：用户真实端点的TEST、全量项目测试、JDBC数据库集成测试。本次仅改变固定引导参数传递，已运行相应Windows/Linux真实链路；未读取用户凭据、未部署服务，且未改变数据库。原现场PT30S超时仍需部署后复验，不将一次协议兼容缺陷的复现冒充全部现场原因。


关联文档：[清单](../checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md) · [提示词](../prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) · [设计](../specs/2026-10-09-windows-sandbox-bootstrap-design.md) · [计划](../plans/2026-10-09-windows-sandbox-bootstrap.md) · [实现说明](../implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md)。



## 本次授权提交记录

用户再次明确要求“将修改的内容提交”，本轮将 DOCX 1.0.1 输入流兼容修复、Server Lombok 编译支持和 Windows 固定沙箱引导参数修复合并为一次本地提交；对应六份工作包一并交付。上文未提交状态是验证阶段历史记录，不改变已记录证据。

提交说明：`修复 DOCX 技能兼容性与 Windows 沙箱引导`。提交编号通过 `git log -1 --format="%h %s" --grep="修复 DOCX 技能兼容性与 Windows 沙箱引导"` 查询，避免同一提交文档自引用编号。未推送、未导入技能、未部署或重启用户服务。

只暂存相关源码、测试、说明和工作包；保留用户已有的控制台首页、application 配置、SkillArtifactProtocol 诊断日志及排版、.codex/、.workbuddy/。Lombok 的 provided 依赖是编译支持所必需，和注解处理器一并纳入。

本次重新执行 `python -m unittest discover -s skill-packages/tests -p test_*.py -v`：16 项通过；同一会话的附件前端测试 5 项通过。已有 Windows 9 项、Rocky 18 项和 Lombok 编译/打包证据仍对应功能未变的修复，未重复创建已清理夹具或重跑全量 Maven/数据库测试。最终暂存空白、文本资源、工作包链接和敏感信息检查通过。用户现场 TEST 与下载闭环仍需部署后复验。
