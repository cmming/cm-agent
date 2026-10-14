<#
.SYNOPSIS
在本机前台运行已核验的 Harness 候选包，沿用现有 mysql 开发配置。
.DESCRIPTION
先另开终端建立到 Rocky MySQL 的 SSH 转发；本脚本不创建本机 Docker 服务。
仅从当前进程或仓库 .env 读取模型加密主密钥，不生成、显示或复制密钥。
当前包固定为 T6 候选版本；源码变化后须重新构建、验证并显式指定新包与摘要。
日志写入当前用户专属目录，Ctrl+C 正常关闭服务后再关闭 SSH 转发。
#>
[CmdletBinding()]
param(
    [string]$JavaExecutable = 'F:/java21/bin/java.exe',
    [string]$JarPath,
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedJarSha256 = 'b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12',
    [ValidateRange(1, 65535)]
    [int]$TunnelPort = 13306,
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDirectory = Join-Path $repoRoot 'cm-agent-server/target/harness-local-runtime'
if ([string]::IsNullOrWhiteSpace($JarPath)) { $JarPath = Join-Path $runtimeDirectory 'cm-agent-server.jar' }
$jarFile = (Resolve-Path -LiteralPath $JarPath).Path
$javaFile = (Resolve-Path -LiteralPath $JavaExecutable).Path
$version = (& $javaFile -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21\.') { throw '启动需要 JDK 21；请显式指定 JavaExecutable。' }
if ((Get-FileHash -LiteralPath $jarFile -Algorithm SHA256).Hash -ne $ExpectedJarSha256) { throw '候选包摘要不匹配；请先核验新包，不自动采用旧验收。' }

$masterKeyName = 'CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY'
$masterKey = [Environment]::GetEnvironmentVariable($masterKeyName, 'Process')
if ([string]::IsNullOrWhiteSpace($masterKey)) {
    $envPath = Join-Path $repoRoot '.env'
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in [IO.File]::ReadLines($envPath)) {
            if ($line -match '^\s*CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY\s*=\s*(.*)$') {
                $masterKey = $Matches[1].Trim().Trim('"').Trim("'")
                break
            }
        }
    }
}
try { $keyLength = [Convert]::FromBase64String($masterKey).Length } catch { throw '缺少有效的既有模型加密主密钥；不能随机替换旧密文对应密钥。' }
if ($keyLength -ne 32) { throw '既有模型加密主密钥格式不符合 32 字节要求。' }

# 转发必须由独立 SSH 会话持有；检查仅建立短连接，不读取数据库或改变账号。
$tcp = [Net.Sockets.TcpClient]::new()
try {
    $pending = $tcp.ConnectAsync('127.0.0.1', $TunnelPort)
    if (-not $pending.Wait(3000) -or -not $tcp.Connected) { throw 'SSH 转发尚未就绪；请先建立 Rocky MySQL 转发。' }
} catch { throw 'SSH 转发尚未就绪；请先建立 Rocky MySQL 转发。' } finally { $tcp.Dispose() }
if ($CheckOnly) {
    Write-Output '前置检查通过：JDK 21、候选包摘要、主密钥格式及 SSH 转发就绪；没有启动新实例。'
    return
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw '目标 HTTP 端口已占用；请核对并正常关闭原项目实例。' }

# 日志可能包含内部诊断，目录仅授予当前用户；不把日志写入仓库受跟踪文件。
[IO.Directory]::CreateDirectory($runtimeDirectory) | Out-Null
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($owner)
$acl.SetAccessRuleProtection($true, $false)
$rule = [Security.AccessControl.FileSystemAccessRule]::new($owner, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $runtimeDirectory -AclObject $acl
$logPath = Join-Path $runtimeDirectory ('server-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.private.log')
$configDirectory = (Join-Path $repoRoot 'cm-agent-server/src/main/resources').Replace('\', '/')
$previousKey = [Environment]::GetEnvironmentVariable($masterKeyName, 'Process')
$previousJson = [Environment]::GetEnvironmentVariable('SPRING_APPLICATION_JSON', 'Process')
try {
    [Environment]::SetEnvironmentVariable($masterKeyName, $masterKey, 'Process')
    $localJdbc = 'jdbc:mysql://' + '127.0.0.1' + ':' + $TunnelPort + '/' + 'cm_agent' + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
    $settings = @{ 'cm-agent.config.jdbc-url' = $localJdbc; 'cm-agent.persistence.jdbc.url' = $localJdbc }
    [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON', ($settings | ConvertTo-Json -Compress), 'Process')
    Write-Output "前台启动 Harness：登录入口 http://127.0.0.1:$Port/console/v2/login.html；日志 $logPath"
    & $javaFile -jar $jarFile '--spring.profiles.active=mysql' "--spring.config.additional-location=file:$configDirectory/" `
        '--cm-agent.agentscope.execution-mode=harness' '--cm-agent.fake-runtime-enabled=false' `
        '--cm-agent.agentscope.enabled=true' '--cm-agent.agentscope.studio.enabled=false' `
        '--server.address=127.0.0.1' "--server.port=$Port" '--spring.application.admin.enabled=true' `
        '--logging.level.org.flywaydb=WARN' *> $logPath
    if ($LASTEXITCODE -ne 0) { throw '服务启动或运行失败；请在私有日志中定位，不回传凭据或原始响应。' }
} finally {
    [Environment]::SetEnvironmentVariable($masterKeyName, $previousKey, 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON', $previousJson, 'Process')
    $masterKey = $null
}
