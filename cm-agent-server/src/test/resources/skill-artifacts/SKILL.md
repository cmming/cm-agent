---
name: artifact-docx-test
description: 用固定 Python 脚本生成中文 DOCX，验证技能沙箱文件产物。
---

调用技能的固定 `scripts/main.py`。脚本只使用 Python 标准库，将 `测试文档.docx` 写入 `CM_AGENT_ARTIFACT_DIR` 指定目录；不下载资源，不接收代码或路径输入。仅用于隔离验收，实际文档内容需求应由经过审核的固定脚本实现。
