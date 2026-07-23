# 指定提交文件导出实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供可将指定 Git 提交的可覆盖文件按原目录结构导出的 PowerShell 脚本。

**Architecture:** `scripts/export-commit-files.ps1` 负责参数、Git 查询、内容读取和清单生成；独立 PowerShell 测试脚本创建临时 Git 仓库，调用导出脚本并断言文件与错误行为。README 仅说明已实现的命令和覆盖边界。

**Tech Stack:** PowerShell 7+、Git、现有 Maven 多模块项目。

## Global Constraints

- 新增脚本注释、测试文本和 README 内容使用中文。
- 不纳入或改动现有 `application.yml`、`application-mysql.yml` 与 `application.yml.bak` 的未提交改动。
- 导出基线固定为目标提交的首个父提交；根提交、无法读取的文件、子模块和类型变更必须失败。
- `OutputDirectory` 只能是不存在或空目录；导出内容使用目标提交版本，并保留仓库相对路径。
- 删除文件仅记录到 `deleted-files.txt`，成功导出的文件记录到 `exported-files.txt`。

---

### Task 1: 编写导出脚本行为测试

**Files:**
- Create: `scripts/test-export-commit-files.ps1`
- Test: `scripts/export-commit-files.ps1`

**Interfaces:**
- Consumes: `scripts/export-commit-files.ps1 -Commit <String> -OutputDirectory <String>`。
- Produces: 可重复执行的退出码测试；失败时抛出异常，成功时输出测试通过信息。

- [ ] **Step 1: 写入失败测试**

创建测试脚本。它使用 `New-Item -ItemType Directory` 建立临时仓库，配置测试专用 Git 用户，提交 `nested/source.txt` 和 `removed.txt`，随后在第二个提交中修改 `nested/source.txt`、重命名为 `nested/renamed.txt` 并删除 `removed.txt`。调用尚不存在的导出脚本后断言：

```powershell
if ((Get-Content -Raw (Join-Path $output 'nested/renamed.txt')) -ne '第二版') {
    throw '重命名后的文件内容不正确。'
}
if (Test-Path (Join-Path $output 'removed.txt')) { throw '已删除文件不应被导出。' }
if ((Get-Content -Raw (Join-Path $output 'deleted-files.txt')).Trim() -ne 'removed.txt') {
    throw '删除清单不正确。'
}
if ((Get-Content -Raw (Join-Path $output 'exported-files.txt')).Trim() -ne 'nested/renamed.txt') {
    throw '导出清单不正确。'
}
```

再以不存在的哈希调用脚本，并断言 `$LASTEXITCODE -ne 0`。使用 `try/finally` 清理由测试创建的临时目录。

- [ ] **Step 2: 运行测试并确认失败原因正确**

Run: `pwsh -NoProfile -File scripts/test-export-commit-files.ps1`

Expected: FAIL，提示找不到 `scripts/export-commit-files.ps1`。

### Task 2: 实现提交文件导出脚本

**Files:**
- Create: `scripts/export-commit-files.ps1`
- Test: `scripts/test-export-commit-files.ps1`

**Interfaces:**
- Consumes: 命名参数 `Commit` 和 `OutputDirectory`。
- Produces: 目标目录中的原路径文件、`exported-files.txt`、`deleted-files.txt`；错误时返回非零退出码。

- [ ] **Step 1: 实现参数和工作区校验**

脚本使用：

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Commit,
    [Parameter(Mandatory)] [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
```

通过 `git rev-parse --show-toplevel` 获取仓库根目录，使用 `git rev-parse --verify "$Commit^{commit}"` 验证提交对象，使用 `git rev-parse --verify "$resolvedCommit^"` 获取首个父提交。输出目录若存在且含任意子项，抛出中文错误；若不存在则创建。

- [ ] **Step 2: 实现状态解析和文件写入**

使用 `git diff-tree --no-commit-id --name-status -r -M $parent $resolvedCommit` 读取状态。对 `A`、`M` 取第二列路径，对 `R*` 取第三列新路径，对 `D` 取第二列旧路径写入删除集合。其他状态抛出中文错误。

对每个导出路径，拒绝空路径、绝对路径和包含 `..` 的路径；通过 `git cat-file -e "$resolvedCommit`:$relativePath"` 检查对象类型必须为 `blob`，然后用 `git show "$resolvedCommit`:$relativePath"` 的原始字节写到 `Join-Path $OutputDirectory $relativePath`。创建父目录并使用 `[System.IO.File]::WriteAllBytes` 写入，避免文本编码破坏二进制文件。

- [ ] **Step 3: 实现清单和错误退出**

按字典序将两个路径集合通过 `[System.IO.File]::WriteAllLines` 以 UTF-8（无 BOM）写入 `exported-files.txt` 和 `deleted-files.txt`。脚本最外层 `try/catch` 输出 `Write-Error $_.Exception.Message` 并 `exit 1`，成功时输出导出文件数及目标目录。

- [ ] **Step 4: 重新运行测试确认通过**

Run: `pwsh -NoProfile -File scripts/test-export-commit-files.ps1`

Expected: PASS，退出码 0；覆盖修改/重命名、删除清单和非法提交。

- [ ] **Step 5: 提交脚本与测试**

```powershell
git add -- scripts/export-commit-files.ps1 scripts/test-export-commit-files.ps1
git commit -m "feat: 支持导出指定提交文件"
```

### Task 3: 编写使用说明并完成验证

**Files:**
- Modify: `README.md`
- Verify: `scripts/export-commit-files.ps1`
- Verify: `scripts/test-export-commit-files.ps1`

**Interfaces:**
- Consumes: 最终脚本参数与清单文件。
- Produces: 面向用户的中文运行命令和覆盖风险提示。

- [ ] **Step 1: 更新 README**

在快速开始之后新增“导出指定提交文件”小节，包含以下命令与说明：

```powershell
.\scripts\export-commit-files.ps1 -Commit <提交哈希> -OutputDirectory <导出目录>
```

说明导出的文件保持目录结构、可复制至目标工作区覆盖；删除文件仅在 `deleted-files.txt` 中列出，覆盖前必须人工复核；`exported-files.txt` 记录已导出文件。

- [ ] **Step 2: 运行完整脚本验证**

Run: `pwsh -NoProfile -File scripts/test-export-commit-files.ps1`

Expected: PASS，退出码 0。

Run: `pwsh -NoProfile -File scripts/export-commit-files.ps1 -Commit HEAD -OutputDirectory (Join-Path $env:TEMP 'cm-agent-export-manual-check')`

Expected: 成功创建清单及 HEAD 相对首个父提交的文件；执行前选择不存在的临时目录，完成后仅删除该临时目录。

- [ ] **Step 3: 检查差异并提交文档**

Run: `git diff --check`

Expected: 无空白错误。

```powershell
git add -- README.md
git commit -m "docs: 说明提交文件导出脚本"
```

最终报告按 `AGENTS.md` 的顺序说明变更摘要、实际验证、影响范围、风险和后续建议。
