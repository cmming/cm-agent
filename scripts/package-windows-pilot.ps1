[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $JavaHome,
    [Parameter(Mandatory)] [string] $ServerJar,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [ValidateSet('AppImage', 'Msi')] [string] $Type = 'Msi',
    [ValidatePattern('^\d+\.\d+\.\d+$')] [string] $Version = '0.1.0',
    [string] $WixDirectory
)

$ErrorActionPreference = 'Stop'
if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64' -or
        [System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw '试装包需要在 Windows x64 上构建。'
}

$javaDirectory = (Resolve-Path -LiteralPath $JavaHome).Path
$release = Get-Content -Raw -LiteralPath (Join-Path $javaDirectory 'release')
if ($release -notmatch '(?m)^JAVA_VERSION="21(?:\.|")' -or $release -notmatch '(?m)^OS_ARCH="(?:amd64|x86_64)"') {
    throw '请选择 Windows x64 的 JDK 21。'
}
$jpackage = Join-Path $javaDirectory 'bin/jpackage.exe'
if (!(Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw '指定的 JDK 缺少 jpackage.exe。'
}

$output = [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)).FullName
$sourceJar = (Resolve-Path -LiteralPath $ServerJar).Path
$appName = 'CM Agent Pilot'
$imageOutput = Join-Path $output $appName
if ((Test-Path -LiteralPath $imageOutput) -or
        (Get-ChildItem -LiteralPath $output -Filter "$appName-$Version.msi*")) {
    throw '输出目录中已有同名试装产物，请选择新的输出目录。'
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Set-PilotServerJar([string] $Path) {
    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $manifestEntry = $zip.GetEntry('META-INF/MANIFEST.MF')
        $launcher = $zip.GetEntry('BOOT-INF/classes/com/cmagent/server/WindowsPilotLauncher.class')
        if (!$manifestEntry -or !$launcher -or !$zip.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class')) {
            throw '输入必须是包含 WindowsPilotLauncher 的 Spring Boot 可执行 JAR，请先重新构建并执行 repackage。'
        }
        $manifestReader = [IO.StreamReader]::new($manifestEntry.Open())
        try {
            $manifest = $manifestReader.ReadToEnd()
        } finally {
            $manifestReader.Dispose()
        }
        if ($manifest -notmatch '(?m)^Start-Class: com\.cmagent\.server\.CmAgentServerApplication\r?$') {
            throw '输入 JAR 的服务入口与仓库的服务端应用不一致。'
        }
        $manifest = $manifest -replace '(?m)^Start-Class: com\.cmagent\.server\.CmAgentServerApplication(?=\r?$)',
                'Start-Class: com.cmagent.server.WindowsPilotLauncher'
        $manifestEntry.Delete()
        $updatedManifest = $zip.CreateEntry('META-INF/MANIFEST.MF')
        $manifestWriter = [IO.StreamWriter]::new($updatedManifest.Open(), [Text.UTF8Encoding]::new($false))
        try {
            $manifestWriter.Write($manifest)
        } finally {
            $manifestWriter.Dispose()
        }

        # 构建者本机的应用配置可能包含数据库、密钥和内网资料。试装版仅加载安装后的外部配置。
        $localConfigurations = @($zip.Entries | Where-Object {
            $_.FullName -match '^BOOT-INF/classes/(?:config/)?(?:application[^/]*\.(?:properties|yaml|yml)|\.env[^/]*)$'
        })
        foreach ($entry in $localConfigurations) {
            $entry.Delete()
        }
    } finally {
        $zip.Dispose()
    }
}

function Invoke-Jpackage([string[]] $Arguments) {
    & $jpackage @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage 打包失败，退出码 $LASTEXITCODE。"
    }
}

$originalPath = $env:Path
$buildName = 'cm-agent-pilot-' + [Guid]::NewGuid().ToString('N')
$tempRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).Path
$build = New-Item -ItemType Directory -Path (Join-Path $tempRoot $buildName)
try {
    if ($Type -eq 'Msi') {
        if ($WixDirectory) {
            $env:Path = (Resolve-Path -LiteralPath $WixDirectory).Path + ';' + $originalPath
        }
        if (!(Get-Command candle.exe -CommandType Application -ErrorAction SilentlyContinue) -or
                !(Get-Command light.exe -CommandType Application -ErrorAction SilentlyContinue)) {
            throw '构建 MSI 需要 WiX v3，应用镜像可使用 -Type AppImage 构建。'
        }
    }

    $payloadStage = New-Item -ItemType Directory -Path (Join-Path $build.FullName 'input')
    $image = New-Item -ItemType Directory -Path (Join-Path $build.FullName 'image')
    $stagedJar = Join-Path $payloadStage.FullName 'cm-agent-server.jar'
    Copy-Item -LiteralPath $sourceJar -Destination $stagedJar
    Set-PilotServerJar -Path $stagedJar
    Invoke-Jpackage -Arguments @('--type', 'app-image', '--name', $appName, '--app-version', $Version,
        '--input', $payloadStage.FullName, '--dest', $image.FullName, '--main-jar', 'cm-agent-server.jar',
        '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
        '--jlink-options', '--strip-debug --no-man-pages --no-header-files')

    $appImage = Join-Path $image.FullName $appName
    if ($Type -eq 'Msi') {
        $installer = New-Item -ItemType Directory -Path (Join-Path $build.FullName 'installer')
        Invoke-Jpackage -Arguments @('--type', 'msi', '--name', $appName, '--app-version', $Version,
            '--app-image', $appImage, '--dest', $installer.FullName, '--win-per-user-install',
            '--win-menu', '--win-menu-group', 'CM Agent', '--win-upgrade-uuid', '85742dce-50df-4505-ae4a-19402c4cf478')
        $msi = Get-ChildItem -LiteralPath $installer.FullName -Filter '*.msi' -File
        if (@($msi).Count -ne 1) {
            throw 'jpackage 未生成唯一的 MSI 文件。'
        }
        $msiOutput = Join-Path $output $msi.Name
        Move-Item -LiteralPath $msi.FullName -Destination $msiOutput
        $sha256 = (Get-FileHash -LiteralPath $msiOutput -Algorithm SHA256).Hash.ToLowerInvariant()
        Set-Content -LiteralPath "$msiOutput.sha256" -Encoding ascii -Value "$sha256  $($msi.Name)"
        Write-Output $msiOutput
    }
    Move-Item -LiteralPath $appImage -Destination $imageOutput
    Write-Output $imageOutput
} finally {
    $env:Path = $originalPath
    $resolvedBuild = (Resolve-Path -LiteralPath $build.FullName).Path
    $intendedBuild = Join-Path $tempRoot $buildName
    if ($resolvedBuild -eq $intendedBuild -and (Split-Path -Parent $resolvedBuild) -eq $tempRoot) {
        Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
    }
}
