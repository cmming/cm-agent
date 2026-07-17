[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("cm-agent-export-test-" + [Guid]::NewGuid())
$exportScript = Join-Path $PSScriptRoot 'export-commit-files.ps1'
$powerShell = (Get-Command pwsh -ErrorAction Stop).Source

try {
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    Push-Location $temporaryDirectory

    git init -q
    git config user.email 'export-test@example.invalid'
    git config user.name '导出测试'

    New-Item -ItemType Directory -Path 'nested' | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $temporaryDirectory 'nested/source.txt'), '保持内容', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $temporaryDirectory 'nested/changed.txt'), '第一版', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $temporaryDirectory 'removed.txt'), '将删除', [System.Text.UTF8Encoding]::new($false))
    git add -- .
    git commit -q -m '初始提交'

    git mv nested/source.txt nested/renamed.txt
    [System.IO.File]::WriteAllText((Join-Path $temporaryDirectory 'nested/changed.txt'), '第二版', [System.Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath (Join-Path $temporaryDirectory 'removed.txt')
    git add --all
    git commit -q -m '修改文件'
    $commit = (git rev-parse HEAD).Trim()
    $outputDirectory = Join-Path $temporaryDirectory 'export'

    & $powerShell -NoProfile -File $exportScript -Commit $commit -OutputDirectory $outputDirectory
    if ($LASTEXITCODE -ne 0) { throw '导出脚本返回失败状态。' }

    if (([System.IO.File]::ReadAllText((Join-Path $outputDirectory 'nested/renamed.txt'), [System.Text.UTF8Encoding]::new($false))) -ne '保持内容') {
        throw '重命名后的文件内容不正确。'
    }
    if (([System.IO.File]::ReadAllText((Join-Path $outputDirectory 'nested/changed.txt'), [System.Text.UTF8Encoding]::new($false))) -ne '第二版') {
        throw '修改后的文件内容不正确。'
    }
    if (Test-Path -LiteralPath (Join-Path $outputDirectory 'removed.txt')) { throw '已删除文件不应被导出。' }
    if (([System.IO.File]::ReadAllText((Join-Path $outputDirectory 'deleted-files.txt'), [System.Text.UTF8Encoding]::new($false))).Trim() -ne 'removed.txt') {
        throw '删除清单不正确。'
    }
    $exported = ([System.IO.File]::ReadAllLines((Join-Path $outputDirectory 'exported-files.txt'), [System.Text.UTF8Encoding]::new($false)))
    if (@($exported | Where-Object { $_ -eq 'nested/changed.txt' }).Count -ne 1 -or @($exported | Where-Object { $_ -eq 'nested/renamed.txt' }).Count -ne 1) {
        throw '导出清单不正确。'
    }

    & $powerShell -NoProfile -File $exportScript -Commit 'not-a-commit' -OutputDirectory (Join-Path $temporaryDirectory 'invalid') 2>$null
    if ($LASTEXITCODE -eq 0) { throw '非法提交应被拒绝。' }

    Write-Host '提交文件导出脚本测试通过。'
}
finally {
    Pop-Location
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
