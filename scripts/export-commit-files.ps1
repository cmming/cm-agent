[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $Commit,

    [Parameter(Mandatory)]
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'

function Invoke-GitText {
    param([string[]] $Arguments)

    $result = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Git 命令执行失败：git $($Arguments -join ' ')`n$result"
    }
    return ($result -join [Environment]::NewLine).Trim()
}

function Read-GitBlob {
    param([string] $ObjectName)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.ArgumentList.Add('show')
    $startInfo.ArgumentList.Add($ObjectName)

    $process = [System.Diagnostics.Process]::Start($startInfo)
    $bytes = [System.IO.MemoryStream]::new()
    $process.StandardOutput.BaseStream.CopyTo($bytes)
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "无法读取 Git 文件对象 $ObjectName：$errorText"
    }
    return $bytes.ToArray()
}

function Get-SafeOutputPath {
    param(
        [string] $BaseDirectory,
        [string] $RelativePath
    )

    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [System.IO.Path]::IsPathRooted($RelativePath)) {
        throw "Git 路径不合法：$RelativePath"
    }

    $normalizedRelativePath = $RelativePath -replace '/', [System.IO.Path]::DirectorySeparatorChar
    $baseFullPath = [System.IO.Path]::GetFullPath($BaseDirectory)
    $outputPath = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($baseFullPath, $normalizedRelativePath))
    if (-not $outputPath.StartsWith($baseFullPath + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Git 路径不允许越出导出目录：$RelativePath"
    }
    return $outputPath
}

try {
    $repositoryRoot = Invoke-GitText -Arguments @('rev-parse', '--show-toplevel')
    if ([string]::IsNullOrWhiteSpace($repositoryRoot)) {
        throw '当前目录不在 Git 工作区中。'
    }
    $resolvedCommit = Invoke-GitText -Arguments @('rev-parse', '--verify', "$Commit^{commit}")
    $parentCommit = Invoke-GitText -Arguments @('rev-parse', '--verify', "$resolvedCommit^")

    $outputFullPath = [System.IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $outputFullPath) {
        if ((Get-ChildItem -LiteralPath $outputFullPath -Force | Measure-Object).Count -gt 0) {
            throw "导出目录必须不存在或为空：$outputFullPath"
        }
    }
    else {
        New-Item -ItemType Directory -Path $outputFullPath | Out-Null
    }

    $changedPaths = Invoke-GitText -Arguments @('-c', 'core.quotepath=false', 'diff-tree', '--no-commit-id', '--name-status', '-r', '-M', $parentCommit, $resolvedCommit)
    $exportPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $deletedPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

    foreach ($line in ($changedPaths -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line -split "`t"
        $status = $parts[0]
        if ($status -in @('A', 'M')) {
            if ($parts.Count -ne 2) { throw "无法解析 Git 变更记录：$line" }
            [void] $exportPaths.Add($parts[1])
        }
        elseif ($status -like 'R*') {
            if ($parts.Count -ne 3) { throw "无法解析 Git 重命名记录：$line" }
            [void] $exportPaths.Add($parts[2])
        }
        elseif ($status -eq 'D') {
            if ($parts.Count -ne 2) { throw "无法解析 Git 删除记录：$line" }
            [void] $deletedPaths.Add($parts[1])
        }
        else {
            throw "不支持的 Git 变更状态：$status"
        }
    }

    foreach ($relativePath in ($exportPaths | Sort-Object)) {
        $objectName = "${resolvedCommit}:$relativePath"
        $objectType = Invoke-GitText -Arguments @('cat-file', '-t', $objectName)
        if ($objectType -ne 'blob') {
            throw "不支持导出非普通文件：$relativePath"
        }
        $targetPath = Get-SafeOutputPath -BaseDirectory $outputFullPath -RelativePath $relativePath
        $parentDirectory = Split-Path -Parent $targetPath
        New-Item -ItemType Directory -Path $parentDirectory -Force | Out-Null
        [System.IO.File]::WriteAllBytes($targetPath, (Read-GitBlob -ObjectName $objectName))
    }

    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllLines((Join-Path $outputFullPath 'exported-files.txt'), @($exportPaths | Sort-Object), $utf8)
    [System.IO.File]::WriteAllLines((Join-Path $outputFullPath 'deleted-files.txt'), @($deletedPaths | Sort-Object), $utf8)
    Write-Host "已导出 $($exportPaths.Count) 个文件到：$outputFullPath"
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
