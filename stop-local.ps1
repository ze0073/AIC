. (Join-Path $PSScriptRoot 'runtime-common.ps1')
$owned = Get-OwnedApplication
$ownedWorker=Get-OwnedHostingWorker
if($ownedWorker){Stop-Process -Id $ownedWorker.ProcessId -Force;Write-Host '本项目部署执行器已停止。'}
if ($owned) { Stop-Process -Id $owned.ProcessId -Force; Write-Host '本项目应用已停止。' }
else { Write-Host '没有本项目记录的运行进程。' }
Push-Location $projectRoot
try {
    & docker compose stop mysql
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 停止失败。' }
} finally { Pop-Location }
Write-Host '数据库数据卷已保留。'
