param(
    [switch]$SkipNodeInstall,
    [switch]$SkipBrowserInstall,
    [switch]$SkipToolImages,
    [switch]$Quick
)

if ($Quick) { & (Join-Path $PSScriptRoot 'start-demo.ps1'); return }

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $projectRoot

$localJdkRoot = Join-Path $projectRoot '.runtime\jdk-21'
$localJdk = Get-ChildItem -LiteralPath $localJdkRoot -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($localJdk) {
    $env:JAVA_HOME = $localJdk.FullName
    $env:Path = "$(Join-Path $localJdk.FullName 'bin');$env:Path"
}

$dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
if ((Test-Path -LiteralPath $dockerBin) -and (($env:Path -split ';') -notcontains $dockerBin)) {
    $env:Path = "$dockerBin;$env:Path"
}

foreach ($command in @('java', 'mvn', 'npm')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "缺少运行依赖：$command。请按 README 安装后重试。"
    }
}

$javaVersionLine = (& java -version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersionLine -notmatch 'version "(\d+)') {
    throw "无法识别 Java 版本：$javaVersionLine"
}
if ([int]$Matches[1] -lt 21) {
    throw "需要 Java 21 或更高版本，当前为 Java $($Matches[1])。"
}

$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot '.env.example') -Destination $envFile
    Write-Host '已创建 .env。当前密码仅适合本地开发。' -ForegroundColor Yellow
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $parts = $line.Split('=', 2)
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}

if (-not $SkipToolImages) {
    & (Join-Path $projectRoot 'start-docker.ps1')
    foreach ($image in @($env:ZAP_IMAGE, $env:K6_IMAGE)) {
        if ($image) {
            & docker pull $image
            if ($LASTEXITCODE -ne 0) { throw "检测工具镜像下载失败：$image" }
        }
    }
}

Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not $SkipNodeInstall) { & npm ci }
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
} finally {
    Pop-Location
}

Push-Location (Join-Path $projectRoot 'backend')
try {
    if (-not $SkipBrowserInstall) {
        & mvn exec:java '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium'
        if ($LASTEXITCODE -ne 0) { throw 'Chromium 安装失败。' }
    }
    & mvn clean package '-DskipTests'
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }
} finally {
    Pop-Location
}
& (Join-Path $projectRoot 'start-demo.ps1')
