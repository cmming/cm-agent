---
name: CM Agent
description: 面向平台管理员与开发者的克制型企业智能体治理工作台
colors:
  workspace-frost: "#f3f6fa"
  surface-white: "#ffffff"
  surface-soft: "#f7f9fc"
  panel-stroke: "#dbe2ec"
  border-cool: "#dce3ec"
  border-strong: "#c8d2df"
  ink: "#172238"
  muted-steel: "#68768a"
  nav-deep: "#182a42"
  nav-muted: "#b7c4d4"
  accent-blue: "#2865d8"
  accent-deep: "#1e50b0"
  success: "#168054"
  warning: "#a76509"
  error: "#b42318"
typography:
  display:
    fontFamily: "Segoe UI, Microsoft YaHei, sans-serif"
    fontSize: "clamp(34px, 4vw, 52px)"
    fontWeight: 700
    lineHeight: 1.08
    letterSpacing: "-0.04em"
  headline:
    fontFamily: "Segoe UI, Microsoft YaHei, sans-serif"
    fontSize: "clamp(23px, 2.4vw, 27px)"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.025em"
  title:
    fontFamily: "Segoe UI, Microsoft YaHei, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: 1.5
  body:
    fontFamily: "Segoe UI, Microsoft YaHei, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Segoe UI, Microsoft YaHei, sans-serif"
    fontSize: "11px"
    fontWeight: 700
    lineHeight: 1.5
    letterSpacing: "0.05em"
rounded:
  compact: "8px"
  control: "9px"
  grouped: "10px"
  panel: "12px"
  pill: "999px"
spacing:
  xxs: "4px"
  xs: "8px"
  sm: "12px"
  md: "14px"
  lg: "18px"
  xl: "24px"
components:
  button-primary:
    backgroundColor: "{colors.accent-blue}"
    textColor: "{colors.surface-white}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "0 13px"
    height: "37px"
  button-primary-hover:
    backgroundColor: "{colors.accent-deep}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.control}"
  button-ghost:
    backgroundColor: "{colors.surface-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 13px"
    height: "37px"
  button-danger:
    backgroundColor: "#fff7f6"
    textColor: "{colors.error}"
    rounded: "{rounded.control}"
    padding: "0 13px"
    height: "37px"
  input:
    backgroundColor: "{colors.surface-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "10px 12px"
  nav-active:
    backgroundColor: "{colors.accent-blue}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.compact}"
    padding: "8px 11px"
    height: "38px"
  status-success:
    backgroundColor: "#e8f7f0"
    textColor: "{colors.success}"
    rounded: "{rounded.pill}"
    padding: "3px 8px"
  panel:
    backgroundColor: "{colors.surface-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.panel}"
    padding: "18px"
  resource-item:
    backgroundColor: "{colors.surface-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "11px 12px"
---

# Design System: CM Agent

## Overview

**Creative North Star: "受控工作台"**

CM Agent 的视觉系统像平台工程人员长期使用的精密操作台：安静、可信、状态清楚。深色导航稳定工作边界，冷白内容区承载高密度资源与运行信息，蓝色只在当前状态、主要动作和焦点处建立方向感。

这是一套面向操作的界面，而不是展示型仪表盘。它用紧凑但不拥挤的间距、轻量分层和明确的列表—详情关系帮助使用者持续判断“当前对象、当前状态、下一步动作”。用户已确认沿用当前克制风格；新增页面应扩展既有语法，不引入抢占业务信息的装饰。

**Key Characteristics:**

- 深海军蓝导航与冷白工作区形成稳定的操作边界。
- 内容层级依赖留白、细边框和低强度阴影，不依赖厚重拟物效果。
- 蓝色强调稀少而明确，状态色只表达运行结果、风险或反馈。
- 桌面端保留高效的列表—详情并列关系，窄屏按任务顺序折叠为单列。
- 中文信息优先可扫描性，技术标识和代码内容使用等宽字体补充区分。

## Colors

调色板以冷灰白和深蓝灰为基底，用清晰但克制的工程蓝建立操作层级，用绿色、琥珀色和红色表达可验证的业务状态。

### Primary

- **治理蓝（Governance Blue）**：用于主按钮、活动导航、焦点边框和当前资源，不用于大面积装饰。
- **深治理蓝（Deep Governance Blue）**：用于主操作悬停和需要更强对比的强调状态。

### Neutral

- **工作区霜白（Workspace Frost）**：页面底色，用于衬托白色任务面板。
- **操作面白（Operational White）**：面板、输入和列表项的主要承载色。
- **柔和表面（Soft Surface）**：说明区、代码以外的次级内容和轻量分组。
- **深海军蓝（Deep Navy）**：侧栏背景，建立全局导航的稳定锚点。
- **主墨色（Primary Ink）**：标题、正文与关键数据。
- **钢灰说明（Muted Steel）**：辅助说明、时间、次要元数据。
- **冷灰边界（Cool Border）**：面板、控件和列表的结构分隔。

### Tertiary

- **通过绿（Verified Green）**：仅用于成功、就绪或健康状态。
- **警示琥珀（Caution Amber）**：仅用于风险提示、待处理或需确认状态。
- **失败红（Failure Red）**：仅用于错误、拒绝和破坏性操作。

### Named Rules

**The One Operational Accent Rule.** 每个局部工作区只让一个主要动作占据最强蓝色层级；其余动作使用描边或文本层级。

**The Semantic Color Rule.** 绿色、琥珀色和红色必须对应真实状态，不作为分类装饰或品牌点缀。

## Typography

**Display Font:** Segoe UI（中文回退 Microsoft YaHei、sans-serif）

**Body Font:** Segoe UI（中文回退 Microsoft YaHei、sans-serif）

**Label/Mono Font:** Cascadia Code（回退 Consolas、monospace，仅用于代码、JSON 和技术载荷）

**Character:** 无衬线字体保持中性、清晰和熟悉，适合 Windows 与中文管理场景。层级主要通过字号、字重与间距建立，不依赖多字体制造个性。

### Hierarchy

- **Display**（700，`clamp(34px, 4vw, 52px)`，1.08）：仅用于登录页的产品主张。
- **Headline**（700，`clamp(23px, 2.4vw, 27px)`，1.2）：页面主标题，保持紧凑字距。
- **Title**（700，16px，1.5）：面板标题、资源名称和局部任务标题。
- **Body**（400，16px，1.5）：表单输入与正文基准；辅助说明通常收敛为 12px–14px。
- **Label**（700，11px，0.05em）：分组眉题、表头和短状态标签；仅英文缩写或极短标签使用全大写。

### Named Rules

**The Scan Before Read Rule.** 页面标题、对象名称、状态和下一步动作必须先于说明文字进入视线；长说明不得与标题争夺字重。

## Layout

桌面端采用固定导航与弹性工作区：侧栏宽 256px，内容最大宽度 1480px。页头高 62px 并保持顶部可见；页面主体使用 18px 左右的面板内边距和 14px 左右的模块间距，管理页以约 300px 的资源栏配合自适应详情区。

资源管理页坚持“左侧列表、右侧单一当前功能”的空间模型。列表在桌面端保持于可视工作区内滚动，详情、编辑、授权或调试在右侧按当前任务互斥呈现。总览使用三列统计卡和主次两列工作区；聊天、运行和审计根据内容性质拥有独立的局部滚动容器。

1200px 以下适度压缩列表栏；900px 以下将全局侧栏转换为横向可滚动导航，并让大部分双列区域折叠；工具页在 760px 以下切换单列；600px 以下主操作铺满、表单改单列并减少非必要系统状态。窄屏只改变排列，不改变功能权限和操作语义。

## Elevation & Depth

系统采用“平面为主、轻量抬升”的混合策略。白色面板依靠冷灰边框与极轻环境阴影从霜白工作区中分离；悬停只增加很小的阴影或边界对比。登录卡片和少数主操作允许更明显的阴影，日常管理面板不使用厚重浮层。

### Shadow Vocabulary

- **面板环境层**（`0 4px 14px rgba(25, 47, 79, .04)`）：常规面板和统计卡，仅用于从页面底色中轻微抬升。
- **资源悬停层**（`0 6px 16px rgba(31, 66, 118, .06)`）：可选资源行的悬停反馈。
- **主要动作层**（`0 8px 17px rgba(46, 110, 234, .17)`）：页面级主要动作，不能扩散到普通按钮。
- **登录聚焦层**（`0 14px 38px rgba(23, 34, 56, .09)`）：登录卡片等独立入口表面。

### Named Rules

**The Flat by Default Rule.** 静态层级优先使用背景、边框和留白；阴影只说明可交互、当前焦点或独立入口。

## Shapes

形状语言以小幅圆角为主：控件与资源项使用 9px，紧凑导航使用 8px，分组容器使用 10px，主要面板使用 12px。圆角胶囊仅保留给状态徽标、风险标签和在线指示；不把普通按钮或卡片做成胶囊。

边框保持 1px 冷灰色，活动资源以蓝色边框、浅蓝底或左侧内嵌强调线表达选中。聊天气泡可使用不对称圆角区分发送方，但不改变整体克制的轮廓。

## Components

### Buttons

- **Shape:** 紧凑矩形与轻圆角（9px），常规高度 37px；按钮文字保持 600 字重。
- **Primary:** 治理蓝底、白字、同色边框，页面级主操作可使用主要动作阴影。
- **Hover / Focus:** 悬停转为深治理蓝；键盘焦点使用 2px 蓝色轮廓与 2px 偏移；按下不产生额外位移。
- **Secondary / Ghost:** 白底冷灰边框与主墨色，悬停仅提高边框和背景对比。
- **Danger:** 淡红背景、红色文字和边框，仅用于删除等破坏性动作。

### Chips

- **Style:** 状态徽标使用胶囊圆角、3px × 8px 内边距和 11px 加粗文字。
- **State:** 默认使用中性灰；成功、警示、失败分别使用淡绿、淡琥珀和淡红底色，颜色必须与真实状态对应。

### Cards / Containers

- **Corner Style:** 面板使用柔和圆角（12px），嵌套分组通常使用 8px–10px。
- **Background:** 主面板使用操作面白，嵌套说明与轻分组使用柔和表面。
- **Shadow Strategy:** 遵循“平面为主、轻量抬升”，普通嵌套卡片通常无阴影。
- **Border:** 1px 冷灰边界；活动项使用治理蓝边界或浅蓝选中底。
- **Internal Padding:** 主面板通常 18px，资源项通常 11px × 12px。

### Inputs / Fields

- **Style:** 白色背景、1px 冷灰边框、9px 圆角和 10px × 12px 内边距。
- **Focus:** 边框切换为治理蓝，并出现 `0 0 0 3px rgba(40, 101, 216, .1)` 聚焦环；键盘操作同时保留清晰轮廓。
- **Error / Disabled:** 错误通过受控错误信息与淡红状态面表达；禁用控件降低不透明度并使用不可操作光标，不用颜色暗示仍可点击。

### Navigation

深海军蓝侧栏承载产品标识与七个业务入口。默认文字为柔和蓝灰，悬停使用低透明白色表面，活动项使用治理蓝与白字。900px 以下侧栏变为顶部横向滚动导航，保持入口顺序和当前页状态。

### Resource List / Detail Workspace

资源项以名称为第一行、状态与元数据为第二行；活动项使用浅蓝底和治理蓝边界。详情区一次只显示一个对象或一个功能点，编辑、授权、调试等任务在右侧工作区互斥展开，避免平铺所有表单。

## Do's and Don'ts

### Do:

- **Do** 使用深色导航、冷白工作区、白色面板和治理蓝强调维持现有“受控工作台”关系。
- **Do** 让当前对象、状态和主要动作在首屏可扫描，并让帮助文字紧邻对应字段或操作。
- **Do** 在桌面端保留列表上下文，在窄屏按任务顺序折叠为单列。
- **Do** 为悬停、键盘焦点、禁用、成功和失败提供明确且一致的状态反馈。
- **Do** 尊重 `prefers-reduced-motion`，动效仅用于状态变化，常规过渡保持约 160ms。

### Don't:

- **Don't** 同时使用多个高饱和主色，或让绿色、琥珀色、红色承担装饰用途。
- **Don't** 用重阴影、玻璃拟态、大面积渐变或夸张动效覆盖资源和运行信息。
- **Don't** 把创建、编辑、详情、授权和调试表单全部平铺在同一视图中。
- **Don't** 在窄屏隐藏关键操作、改变权限语义或依靠悬停才能发现功能。
- **Don't** 新增未经当前代码或明确产品决策支持的视觉语言、字体或组件变体。
