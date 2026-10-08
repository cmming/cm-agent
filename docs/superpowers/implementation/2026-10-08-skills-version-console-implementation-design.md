# 技能版本升级与控制台优化实现说明

## 实际实现

- T2：skills.js 新增 skill-upgrade 原生折叠区与 multipart 表单。使用当前详情 candidateVersionId / publishedVersionId，保留服务端双指针冲突判断，不调用旧接口参数；上传不自动 TEST 或发布。页级上传锁、请求代际与提交时权限复核保护当前界面。冲突和断线保留文件，显示 API 中文错误和错误编号，人工刷新只查询。
- T2：新建仍调用 POST /api/skills；同名冲突引导选中已有技能上传。成功刷新后保留反馈并选中已创建技能。
- T3：skills.html 将导入收纳到列表栏，并把反馈移到工作区顶部。版本号改用 versionNo，候选和正式指针由历史映射为可读版本号，历史状态中文并折叠，资源说明标明当前查看的候选/正式版本。
- T3：multipage.css 新样式限定 #skillsPage / data-page=skillsPage；300px 列表、自适应详情、移动操作按钮、输入约束、焦点和说明文字对比度。原沙箱页签和表单保持原行为。
- T4：skills.test.cjs 新增 13 项回归；现有 TEST 测试按 class 定位试运行表单，避免新增版本表单干扰。ConsoleResourceTest 更新缓存版本与升级接口断言。
- v2 九个 HTML 入口同步 multipage.css 2.3.0 和 skills.js 2.1.0；其他页面仅修改缓存参数。

## 与方案差异

无后端或数据库变更。浏览器原生上传因 Chrome 扩展未允许访问文件网址而受阻；未修改扩展权限。浏览器采用隔离内存夹具进行布局和交互验证，Node 和 MockMvc 验证请求/状态与实际 API；这些不代表真实模型或容器验收。

## 交付边界

分支 codex/skills-version-console；未提交、未推送、未合并、未部署。保留用户 application 配置及 .codex/.workbuddy。生成物和夹具只在忽略的 target 中。

## R1 实际实现：跨页一致性

- T7：九个 v2 页面统一预载沙箱 CSS 和 JS，避免共享导航只替换 body 时漏掉目标页依赖。沙箱脚本版本 1.1.1，共享 app.js 2.0.28；旧 index.html 同步 app.js 缓存。保留技能脚本 2.1.0 与样式 2.3.0。
- T8：assets/app.js 的 loadMultiPage 在目标校验和导航代际检查通过后调用 disposeSkillPages，随后替换 body；退出登录也释放。skillsPage/sandboxPage 清空，初始化时重新创建，失败和过期导航保留当前组件。
- T8：sandbox-endpoints.js 的 dispose 清空材料、增加请求修订号、丢弃列表与选择。reload、mutate、mount、旧表单提交和页签点击检查 disposed；迟到保存成功/失败/finally 不更新新页面或重载列表。沿用 R0 skills.js 已有释放逻辑。
- T9：console-navigation.test.cjs 执行真实导航函数，覆盖释放顺序、重入、过期导航和加载失败；sandbox-endpoints.test.cjs 覆盖材料清理、旧提交拒绝、迟到读取及保存双终态。资源测试逐个入口核对沙箱依赖和缓存。

真实 8080 浏览器对比确认跨页进入、再次进入、刷新与后退的页签、列表、默认详情、导入入口一致；选择详情、沙箱只读加载及 390px 均通过。未重启服务、未提交实际上传或端点变更。R0 原生上传外部阻塞保留，与 R1 导航验收无冲突。

## 关联工作包

- [清单](../checklists/2026-10-08-skills-version-console-checklist.md)
- [提示词](../prompts/2026-10-08-skills-version-console-prompt.md)
- [设计](../specs/2026-10-08-skills-version-console-design.md)
- [计划](../plans/2026-10-08-skills-version-console.md)
- [实现](../implementation/2026-10-08-skills-version-console-implementation-design.md)
- [账本](../progress/2026-10-08-skills-version-console-ledger.md)
