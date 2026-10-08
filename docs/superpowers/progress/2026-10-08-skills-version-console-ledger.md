# 技能版本升级与控制台优化进度账本

日期 2026-10-08，当前修订 R1，默认自主模式。基线 master 5b881a8，工作分支 codex/skills-version-console。未提交。下方 T1～T6 与 105 项测试记录保留为 R0 历史证据。

| 编号 | 状态 | 证据 |
|---|---|---|
| T1 | 完成 | AGENTS、Git、产品/设计、当前版本上传 API 已核对；CodeGraph 缺失 Skill 符号后回退源码 |
| T2 | 完成 | 详情升级表单、multipart 双指针、权限、重复/迟到保护、冲突恢复与新建引导 |
| T3 | 完成 | 版本号修复、中文历史、导入/升级区分、作用域 CSS、九页缓存同步 |
| T4 | 完成 | Node 105/105、ConsoleResourceTest 14/14、SkillControllerTest 7/7；零失败/错误/跳过 |
| T5 | 部分完成 | 桌面、390×844、1024×768；无横向溢出、必填/焦点/键盘折叠已检查；真实服务沙箱页签切换正常；原生上传受阻 |
| T6 | 完成 | 原六份本任务文档、README、发布草案同步；无提交 |

## 实际命令与结果

- node --check cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js：通过。
- node --test cm-agent-console/src/test/js/*.test.cjs：105 项通过，技能页 25 项（新增 13 项）。
- JDK 21.0.11、Maven 3.9.4；mvn -q -pl cm-agent-console -am test：ConsoleResourceTest 14 项通过。
- mvn -q -pl cm-agent-server -am test -Dtest=SkillControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false -Dcm-agent.skills.sandbox.enabled=false：7 项通过。
- 首次服务器测试缺少 sandbox.enabled=false 覆盖，受用户本地配置影响，能力断言期望 false 而得到 true；原样保留用户配置，显式测试命令覆盖后通过。首次新增 Node 用例错误共享可变响应夹具；按真实 JSON 响应改为快照后全部通过。
- impeccable detect 静态扫描：检测出说明/页头对比度问题，已局部修正；仍有共享 CSS 的历史 stat-card 条纹、深色导航阴影，以及设计 sidecar/字体与色阶建议，未扩大修改其他页面或设计文件。检测结果不是无缺陷证书。
- git diff --check：通过。

## 外部阻塞与未执行项

Chrome 扩展未开启“允许访问文件网址”，fileChooser.setFiles 失败，浏览器实际选择 ZIP/上传链路未完成；未扩大扩展权限。独立预览夹具不访问 8080 服务、真实模型或 Docker。不执行真实 TEST、发布、回滚、真实沙箱连接、JDBC/Flyway 或全量测试，因为本轮仅前端接入已有 API。

未重启或更改用户运行服务。已另开 Chrome 标签只读检查 localhost:8080：运行服务实际显示新版导入/升级入口与 v1，技能脚本为 2.1.0；未提交上传、TEST 或发布操作。原用户页面刷新后可使用升级入口。最终真实服务键盘 Enter 能开关升级折叠区；390px 内容宽 375px、两枚升级按钮宽约 306px；已恢复浏览器默认视口。

## R1 验收记录

| 编号 | 状态 | 证据 |
|---|---|---|
| T7 | 完成 | 九个 v2 入口预载沙箱 CSS/JS，app.js 2.0.28、沙箱 JS 1.1.1；资源测试覆盖 |
| T8 | 完成 | 成功导航替换前释放、退出释放、重入创建新实例；沙箱材料清理与迟到回写拒绝 |
| T9 | 完成 | Node 111/111、ConsoleResourceTest 14/14；真实 Chrome 跨页、重入、刷新、后退内容比对均为 true |

- node --test cm-agent-console/src/test/js/*.test.cjs：111 项通过，新增导航 3 项、沙箱生命周期 3 项；无失败/跳过。
- node --check：assets/app.js、console/v2/assets/sandbox-endpoints.js 通过（均位于 console 静态资源目录）。
- JDK 21.0.11 / Maven 3.9.4；mvn -q -pl cm-agent-console -am test：14 项通过。构建复制资源后，运行中的 localhost:8080 已读取新版资源；未重启、更改配置或执行部署。
- 真实 Chrome：概览 → 技能，技能 → 工具 → 技能，直接刷新，工具页后退返回技能，四种初始内容对比一致；页签均两项，列表均两项。重入后详情打开、沙箱端点只读加载通过；390px 下 clientWidth/scrollWidth 均 375px，无溢出，已恢复默认视口。
- 截图保存在仓库外 C:/Users/chmi/.codex/visualizations/2026/10/08/skills-navigation-fixed.jpg。
- git diff --check：通过；确认当前分支 codex/skills-version-console。
- R1 不涉及后端、数据库或容器验证；未重跑 R0 SkillControllerTest，保留 R0 7 项通过证据。原生 ZIP 上传阻塞仍为 R0 待验收项；R1 无剩余阻塞。
- 未提交、未推送、未合并、未部署；用户 application 配置、.codex/.workbuddy 原样保留。已有标签页需刷新一次以加载新的入口依赖和脚本缓存。

## 关联工作包

- [清单](../checklists/2026-10-08-skills-version-console-checklist.md)
- [提示词](../prompts/2026-10-08-skills-version-console-prompt.md)
- [设计](../specs/2026-10-08-skills-version-console-design.md)
- [计划](../plans/2026-10-08-skills-version-console.md)
- [实现](../implementation/2026-10-08-skills-version-console-implementation-design.md)
- [账本](../progress/2026-10-08-skills-version-console-ledger.md)
