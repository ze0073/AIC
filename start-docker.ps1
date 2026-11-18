param(
    [ValidateRange(10, 300)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeRoot = Join-Path $projectRoot '.runtime'
$dockerAppData = Join-Path $runtimeRoot 'docker-appdata'
$dockerLocalAppData = Join-Path $runtimeRoot 'docker-localappdata'
$dockerCliConfig = Join-Path $runtimeRoot 'docker-cli'
$dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
$dockerExe = Join-Path $dockerBin 'docker.exe'

if (-not (Test-Path -LiteralPath $dockerDesktop) -or -not (Test-Path -LiteralPath $dockerExe)) {
    throw '未找到 Docker Desktop，请先安装 Docker Desktop。'
}

New-Item -ItemType Directory -Force -Path $dockerAppData, $dockerLocalAppData, $dockerCliConfig | Out-Null

# 本机 AppData 启用了无法由当前账户解密的 EFS。Docker Desktop 的原子写入
# 与该目录不兼容，因此只为 Docker 进程使用项目内的独立配置目录。
$env:APPDATA = $dockerAppData
$env:LOCALAPPDATA = $dockerLocalAppData
$env:DOCKER_CONFIG = $dockerCliConfig
if (($env:Path -split ';') -notcontains $dockerBin) {
    $env:Path = "$dockerBin;$env:Path"
}

function Get-DockerServerVersion {
    $ErrorActionPreference = 'Continue'
    $version = & $dockerExe info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -eq 0) { return $version }
    return $null
}
$serverVersion = Get-DockerServerVersion
if ($serverVersion) {
    Write-Host "Docker 已运行，服务端版本：$serverVersion" -ForegroundColor Green
    return
}

$running = Get-Process -Name 'Docker Desktop', 'com.docker.backend' -ErrorAction SilentlyContinue
if ($running) {
    throw 'Docker 进程已存在但引擎不可用。首次安装后请重启 Windows，再重新运行本脚本。'
}

Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
Write-Host '正在启动 Docker Desktop...' -ForegroundColor Cyan

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 3
    $serverVersion = Get-DockerServerVersion
    if ($serverVersion) {
        Write-Host "Docker 启动完成，服务端版本：$serverVersion" -ForegroundColor Green
        return
    }
} while ((Get-Date) -lt $deadline)

throw 'Docker 引擎未在限定时间内就绪。首次安装或启用 WSL 后，请先重启 Windows。'
