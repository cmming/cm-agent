<#
.SYNOPSIS
管理 CM Agent 本机运行环境：启动、查看状态、关闭和重启。

.DESCRIPTION
默认同时管理 127.0.0.1:8080 服务端和已构建的本机客户端 fat JAR。
脚本使用后台隐藏进程，日志写入运行目录；不会打印 .env、JWT、数据库密码或模型密钥。
开始前应已存在已构建的 fat JAR 和可用 Maven 依赖。

.EXAMPLE
.\scripts\manage-local-runtime.ps1 status

.EXAMPLE
.\scripts\manage-local-runtime.ps1 start

.EXAMPLE
.\scripts\manage-local-runtime.ps1 start -Target client

.EXAMPLE
.\scripts\manage-local-runtime.ps1 restart -Target server

.EXAMPLE
.\scripts\manage-local-runtime.ps1 restart -Build
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'status', 'stop', 'restart')]
    [string]$Action = 'status',

    [ValidateSet('both', 'server', 'client')]
    [string]$Target = 'both',

    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$RuntimeDirectory = 'F:\cm-agent-local-client-runtime-20261105',
    [string]$JavaHome = 'F:\java21',
    [string]$MavenExecutable = 'F:\java\apache-maven-3.9.4\bin\mvn.cmd',

    [ValidateRange(1, 65535)]
    [int]$Port = 8080,

    [ValidateRange(10, 600)]
    [int]$TimeoutSeconds = 120,

    [switch]$Build
)

$ErrorActionPreference = 'Stop'

function Resolve-RequiredPath {
    param([string]$Path, [string]$Description)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Description 不存在：$Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Get-ServerProcess {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
        if ($process -and $process.Name -eq 'java.exe' -and
                $process.CommandLine -match 'CmAgentServerApplication') {
            return $process
        }
    }
    return Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -eq 'java.exe' -and $_.CommandLine -match 'CmAgentServerApplication'
        } |
        Select-Object -First 1
}

function Get-ClientProcesses {
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -eq 'java.exe' -and
            $_.CommandLine -match 'cm-agent-local-client.*-all\.jar\s+run'
        })
}

function Get-ClientJar {
    $runtimeJar = Get-ChildItem -LiteralPath $RuntimeDirectory -Filter 'cm-agent-local-client-*-all.jar' -File `
        -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($runtimeJar) {
        return $runtimeJar.FullName
    }
    $targetDirectory = Join-Path $ProjectRoot 'cm-agent-local-client/target'
    $builtJar = Get-ChildItem -LiteralPath $targetDirectory -Filter 'cm-agent-local-client-*-all.jar' -File `
        -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $builtJar) {
        throw "未找到客户端 fat JAR；请先运行 mvn -pl cm-agent-local-client -am -DskipTests package"
    }
    New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
    $destination = Join-Path $RuntimeDirectory $builtJar.Name
    Copy-Item -LiteralPath $builtJar.FullName -Destination $destination -Force
    return $destination
}

function Import-DotEnv {
    $envPath = Join-Path $ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        return
    }
    foreach ($line in [IO.File]::ReadLines($envPath)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and
                    (($value[0] -eq '"' -and $value[-1] -eq '"') -or
                     ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Set-JavaEnvironment {
    $javaExecutable = Resolve-RequiredPath (Join-Path $JavaHome 'bin/java.exe') 'Java 21 可执行文件'
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
    return $javaExecutable
}

function Test-ServerHealthy {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" `
            -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Wait-ServerHealthy {
    param([int]$MavenProcessId)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-ServerHealthy) {
            return $true
        }
        if ($MavenProcessId -and -not (Get-Process -Id $MavenProcessId -ErrorAction SilentlyContinue)) {
            return $false
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-Server {
    $existing = Get-ServerProcess
    if ($existing) {
        if (Test-ServerHealthy) {
            Write-Host "服务端已运行：PID $($existing.ProcessId)，http://127.0.0.1:$Port"
            return
        }
        Write-Host "服务端进程已存在但尚未健康：PID $($existing.ProcessId)，继续等待..."
        if (Wait-ServerHealthy $null) {
            Write-Host "服务端已恢复健康：PID $($existing.ProcessId)，HTTP 200"
            return
        }
        throw "服务端进程存在但健康检查未通过：PID $($existing.ProcessId)"
    }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $Port 已被其他进程占用，不能启动 CM Agent 服务端。"
    }

    $maven = Resolve-RequiredPath $MavenExecutable 'Maven 启动程序'
    [void](Set-JavaEnvironment)
    Import-DotEnv
    $env:CM_AGENT_LOCAL_CLIENT_ENABLED = 'true'
    $env:CM_AGENT_LOCAL_CLIENT_AUDIENCE = "http://127.0.0.1:$Port"

    New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
    $outLog = Join-Path $RuntimeDirectory 'server-8080.out.log'
    $errLog = Join-Path $RuntimeDirectory 'server-8080.err.log'
    $process = Start-Process -FilePath $maven `
        -ArgumentList @('-q', '-pl', 'cm-agent-server', 'spring-boot:run') `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru

    Write-Host "服务端启动中：Maven PID $($process.Id)，等待 http://127.0.0.1:$Port/actuator/health"
    if (-not (Wait-ServerHealthy $process.Id)) {
        throw "服务端在 $TimeoutSeconds 秒内未通过健康检查；请查看 $outLog 和 $errLog"
    }
    $server = Get-ServerProcess
    Write-Host "服务端已启动：PID $($server.ProcessId)，HTTP 200"
}

function Start-Client {
    $existing = Get-ClientProcesses
    if ($existing.Count -gt 0) {
        Write-Host "本机客户端已运行：PID $($existing[0].ProcessId)"
        return
    }
    if (-not (Test-ServerHealthy)) {
        throw "8080 服务端未就绪，不能启动本机客户端。"
    }
    $java = Set-JavaEnvironment
    $jar = Get-ClientJar
    New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
    $outLog = Join-Path $RuntimeDirectory 'client-run.out.log'
    $errLog = Join-Path $RuntimeDirectory 'client-run.err.log'
    $process = Start-Process -FilePath $java `
        -ArgumentList @('-jar', $jar, 'run') `
        -WorkingDirectory $RuntimeDirectory `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 3
    if ($process.HasExited) {
        throw "本机客户端启动后立即退出；请查看 $outLog 和 $errLog"
    }
    Write-Host "本机客户端已启动：PID $($process.Id)"
}

function Stop-Client {
    $processes = Get-ClientProcesses
    if ($processes.Count -eq 0) {
        Write-Host '本机客户端未运行。'
        return
    }
    foreach ($process in $processes) {
        Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline -and (Get-ClientProcesses).Count -gt 0) {
        Start-Sleep -Milliseconds 300
    }
    Write-Host '本机客户端已关闭；配对关系不会删除。'
}

function Stop-Server {
    $server = Get-ServerProcess
    if (-not $server) {
        Write-Host '服务端未运行。'
        return
    }
    $maven = Get-CimInstance Win32_Process -Filter "ProcessId=$($server.ParentProcessId)" -ErrorAction SilentlyContinue
    Stop-Process -Id $server.ProcessId -ErrorAction SilentlyContinue
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline -and
            (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 300
    }
    if ($maven -and $maven.CommandLine -match 'cm-agent-server.*spring-boot:run') {
        Stop-Process -Id $maven.ProcessId -ErrorAction SilentlyContinue
    }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $Port 仍未释放，请检查服务端父进程或日志。"
    }
    Write-Host '服务端已关闭。'
}

function Show-Status {
    $server = Get-ServerProcess
    if ($server) {
        $health = if (Test-ServerHealthy) { 'HEALTHY (HTTP 200)' } else { 'NOT HEALTHY' }
        Write-Host "服务端：RUNNING，PID $($server.ProcessId)，$health"
        Write-Host "地址：http://127.0.0.1:$Port"
    } else {
        Write-Host '服务端：STOPPED'
    }

    $clients = Get-ClientProcesses
    if ($clients.Count -gt 0) {
        Write-Host "本机客户端：RUNNING，PID $($clients.ProcessId -join ', ')"
    } else {
        Write-Host '本机客户端：STOPPED'
    }

    $identityPath = Join-Path $HOME '.cm-agent/local-client/identity.json'
    if (Test-Path -LiteralPath $identityPath) {
        $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
        Write-Host "已配对设备：$($identity.deviceName)，$($identity.deviceId)"
        Write-Host "服务端地址：$($identity.serverUrl)，凭据：$($identity.storageKind)"
    } else {
        Write-Host '已配对设备：无'
    }
}

function Invoke-Build {
    $maven = Resolve-RequiredPath $MavenExecutable 'Maven 启动程序'
    [void](Set-JavaEnvironment)
    Push-Location $ProjectRoot
    try {
        Write-Host '构建客户端 fat JAR...'
        & $maven -q -pl cm-agent-local-client -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw '客户端构建失败。'
        }
        Write-Host '安装服务端依赖...'
        & $maven -q -pl cm-agent-server -am -DskipTests install
        if ($LASTEXITCODE -ne 0) {
            throw '服务端安装失败。'
        }
    } finally {
        Pop-Location
    }
    New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
    $built = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'cm-agent-local-client/target') `
        -Filter 'cm-agent-local-client-*-all.jar' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $built) {
        throw '构建完成后未找到客户端 fat JAR。'
    }
    Copy-Item -LiteralPath $built.FullName -Destination (Join-Path $RuntimeDirectory $built.Name) -Force
    Write-Host "运行包已更新：$($built.Name)"
}

if (-not (Test-Path -LiteralPath $ProjectRoot)) {
    throw "项目目录不存在：$ProjectRoot"
}
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ($Build -and $Action -notin @('start', 'restart')) {
    throw '-Build 只能与 start 或 restart 一起使用。'
}

switch ($Action) {
    'status' {
        Show-Status
    }
    'start' {
        if ($Build) {
            Invoke-Build
        }
        if ($Target -in @('both', 'server')) {
            Start-Server
        }
        if ($Target -in @('both', 'client')) {
            Start-Client
        }
        Show-Status
    }
    'stop' {
        if ($Target -in @('both', 'client')) {
            Stop-Client
        }
        if ($Target -in @('both', 'server')) {
            Stop-Server
        }
        Show-Status
    }
    'restart' {
        if ($Target -in @('both', 'client')) {
            Stop-Client
        }
        if ($Target -in @('both', 'server')) {
            Stop-Server
        }
        if ($Build) {
            Invoke-Build
        }
        if ($Target -in @('both', 'server')) {
            Start-Server
        }
        if ($Target -in @('both', 'client')) {
            Start-Client
        }
        Show-Status
    }
}
