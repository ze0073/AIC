param([ValidateRange(10,300)][int]$TimeoutSeconds = 90)
. (Join-Path $PSScriptRoot 'runtime-common.ps1')
$workerFile = Join-Path $runtimeRoot 'hosting-worker-state.json'
$ownedWorker = Get-OwnedHostingWorker
if ($ownedWorker) { Write-Host '部署执行器已运行。'; return }
$workerPort = if($env:APP_HOSTING_WORKER_PORT){[int]$env:APP_HOSTING_WORKER_PORT}else{18091}
if(Get-NetTCPConnection -LocalPort $workerPort -State Listen -ErrorAction SilentlyContinue){throw "部署执行器端口 $workerPort 已被占用。"}
$worker = Start-Process -FilePath (Get-Command java).Source -ArgumentList '-Xmx768m','-jar',('"'+$jarFile+'"'),'--app.hosting.worker-only=true','--app.hosting.worker-enabled=true','--server.address=127.0.0.1',("--server.port=$workerPort") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeRoot 'hosting-worker.stdout.log') -RedirectStandardError (Join-Path $runtimeRoot 'hosting-worker.stderr.log')
$identity = Get-CimInstance Win32_Process -Filter "ProcessId = $($worker.Id)"
@{pid=$worker.Id;projectRoot=$projectRoot;jar=$jarFile;startedAt=$identity.CreationDate.ToUniversalTime().ToString('o');port=$workerPort} | ConvertTo-Json | Set-Content -LiteralPath $workerFile -Encoding UTF8
$deadline=(Get-Date).AddSeconds($TimeoutSeconds)
do {
    if(Test-ApplicationReady -Port $workerPort){Write-Host '独立部署执行器已就绪。';return}
    if(!(Get-Process -Id $worker.Id -ErrorAction SilentlyContinue)){throw '部署执行器启动失败，请检查 .runtime/hosting-worker.stdout.log。'}
    Start-Sleep -Seconds 2
}while((Get-Date)-lt $deadline)
throw '部署执行器启动超时。'
