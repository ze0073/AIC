param([ValidateRange(10,300)][int]$TimeoutSeconds = 90)
. (Join-Path $PSScriptRoot 'runtime-common.ps1')
$mutexName = 'Local\ShijieDemo-' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($projectRoot)).Replace('/','_')
$mutex = New-Object Threading.Mutex($false, $mutexName)
if (!$mutex.WaitOne(0)) { throw '另一个启动操作正在进行。' }
try {
    $owned = Get-OwnedApplication
    if ($owned) {
        if (!$owned.IsCurrentBuild) { throw "同项目旧版本仍在运行：$($owned.RecordedJar)。请先运行 stop-local.ps1，再启动当前版本。" }
        if (Test-ApplicationReady -Port $owned.RecordedPort) { & (Join-Path $projectRoot 'start-hosting-worker.ps1'); Write-Host "应用已就绪：http://127.0.0.1:$($owned.RecordedPort)"; return }
        throw '项目进程已存在但尚未就绪，请运行 status-local.ps1 查看状态。'
    }
    if (!(Test-Path -LiteralPath $jarFile)) { throw '缺少 0.9.0-SNAPSHOT 构建产物，请先运行 start-local.ps1 完成构建。' }
    if (Get-NetTCPConnection -LocalPort $appPort -State Listen -ErrorAction SilentlyContinue) { throw "端口 $appPort 已被其他进程使用。" }
    & java -jar $jarFile --runtime-check
    if ($LASTEXITCODE -ne 0) { throw 'Chromium 检查失败，请按 README 安装浏览器。' }
    $savedAppData = $env:APPDATA; $savedLocalData = $env:LOCALAPPDATA
    try { & (Join-Path $projectRoot 'start-docker.ps1') }
    finally { $env:APPDATA = $savedAppData; $env:LOCALAPPDATA = $savedLocalData }
    Push-Location $projectRoot
    try {
        & docker compose up -d --wait --wait-timeout $TimeoutSeconds mysql
        if ($LASTEXITCODE -ne 0) { throw 'MySQL 未就绪。' }
    } finally { Pop-Location }
    $proc = Start-Process -FilePath (Get-Command java).Source -ArgumentList '-jar',('"' + $jarFile + '"') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeRoot 'application-current.stdout.log') -RedirectStandardError (Join-Path $runtimeRoot 'application-current.stderr.log')
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId = $($proc.Id)"
    if (!$identity) { throw '应用启动后立即退出，请检查日志。' }
    @{pid=$proc.Id;projectRoot=$projectRoot;jar=$jarFile;startedAt=$identity.CreationDate.ToUniversalTime().ToString('o');port=$appPort} | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-ApplicationReady) { & (Join-Path $projectRoot 'start-hosting-worker.ps1'); Write-Host "应用已就绪：http://127.0.0.1:$appPort"; return }
        if (!(Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) { throw '应用已退出，请检查 .runtime/application-current.stdout.log。' }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw '应用启动超时；进程记录已保存，可运行 status-local.ps1 或 stop-local.ps1。'
} finally { $mutex.ReleaseMutex(); $mutex.Dispose() }
