# 首个 Agent 引导实现说明

能力总览在加载 Agent 与 Tool 的同时读取模型配置。`updateOverviewOnboarding` 根据当前租户状态更新 `overviewPrimaryAction`：没有模型配置时进入模型配置页创建首个模型；只有停用模型时提示启用；存在启用模型时进入 Agent 创建页。存在 Agent 后隐藏首次成功提示和步骤，保留原有快捷入口。

实现只使用已有的 `/api/model-configs` 读取接口和前端内存状态，不写入浏览器存储，不改变服务端权限、租户过滤、审计或凭据处理。共享 CSS 使用文本、编号和 `aria-current` 表达步骤，完成项不只依赖颜色。
