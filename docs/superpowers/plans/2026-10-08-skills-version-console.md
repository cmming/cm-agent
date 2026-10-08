# 技能版本升级与控制台优化实施计划

1. T1：核对仓库和 CodeGraph。当前索引未收录 Skill 类，使用源码回退核实 SkillController、SkillCandidateService 和 SkillResponses。读取 PRODUCT、DESIGN 与 impeccable 规范。
2. T2：修改 console/v2/assets/skills.js；新增详情内版本表单，复用 API multipart；双指针、权限、请求代际和上传锁；同内容复用提示，冲突/断线恢复说明。
3. T3：修改 skills.html 与 multipage.css，区分新建和升级，修复版本字段，中文历史与资源版本提示。v2 九个页面同步 CSS 2.3.0、技能脚本 2.1.0，确保跨页导航继续生效。
4. T4：扩展 skills.test.cjs，覆盖真实页面编排的上传载荷、同内容、冲突、缺文件、权限撤销、重复、迟到、创建反馈和旧表单。更新 ConsoleResourceTest，运行 Node 与 JDK 21 Maven。复核已有 SkillControllerTest。
5. T5：独立内存夹具使用实际页面/脚本/CSS；Chrome 检查桌面、390px 与 1024px、必填和焦点、导入和沙箱切换。原生上传如受环境权限阻挡，记录确切原因，不伪造实际上传证据。
6. T6：同步六份中文工作包、README 和发布草案；明确无提交、无部署、真实模型与容器未执行。

## R1 追加实施顺序

7. T7：复核 loadMultiPage 的 body 替换规则。v2 九页补齐 sandbox-endpoints.css 1.0.0 与 sandbox-endpoints.js 1.1.1；全部共享 app.js 引用升为 2.0.28，包括旧入口 index.html；资源测试逐入口断言。
8. T8：app.js 新增 disposeSkillPages，在有效导航替换 body 前和退出时释放组件。sandbox-endpoints.js 增加 dispose、列表/保存异步边界与旧表单拒绝；保留已有 skills.js 的 dispose 实现。
9. T9：新增 console-navigation.test.cjs 三项真实导航函数回归，沙箱组件新增三项释放/迟到用例；运行 Node、语法、JDK 21 console Maven 和 diff 检查。用真实 Chrome 检查概览进入、工具页返回、刷新、后退、详情与沙箱事件及 390px；同步原六文档和生产说明，不修改运行服务或实际数据。

## 关联工作包

- [清单](../checklists/2026-10-08-skills-version-console-checklist.md)
- [提示词](../prompts/2026-10-08-skills-version-console-prompt.md)
- [设计](../specs/2026-10-08-skills-version-console-design.md)
- [计划](../plans/2026-10-08-skills-version-console.md)
- [实现](../implementation/2026-10-08-skills-version-console-implementation-design.md)
- [账本](../progress/2026-10-08-skills-version-console-ledger.md)
