# Impeccable 产品初始化设计

## 背景与目标

项目已安装 Impeccable，并开展过控制台局部优化，但缺少根目录产品上下文。用户请求执行 `impeccable init`，并确认优先服务平台管理员与开发者，新增页面默认直接编码、延续现有控制台。

## 范围与方案

- 创建根目录 `PRODUCT.md`，保存已确认的用户、目的、流程、能力、安全约束与证据。
- 创建 `.impeccable/config.json`，保存用户明确选择的 `buildPath: code`。
- 将未确定的业务人员入口、无障碍目标及浏览器范围标记为未约定。

## 非目标与约束

不改页面、接口、数据库或安全配置；不生成 `DESIGN.md`，不发起 Live 会话。Live 注入需要进一步确认 Spring 服务实际提供的 HTML 及 CSP 兼容性，本次不宣称已配置或验证。不得记录本地验证凭据，不覆盖已有工作区修改。

## 验收

产品文件包含版本标记与 `web` 平台；受众与制作默认值符合真实回答；JSON 可解析；文件差异无空白错误；四份记录一致。

## 关联

- [计划](../plans/2026-09-20-impeccable-product-init.md)
- [实现说明](../implementation/2026-09-20-impeccable-product-init-implementation-design.md)
- [进度账本](../progress/2026-09-20-impeccable-product-init-ledger.md)
