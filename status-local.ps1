. (Join-Path $PSScriptRoot 'runtime-common.ps1')
$owned = Get-OwnedApplication
$hostingWorker = Get-OwnedHostingWorker
$statusPort = if ($owned) { $owned.RecordedPort } else { $appPort }
[pscustomobject]@{Project=$projectRoot;OwnedProcess=([bool]$owned);CurrentBuild=(!$owned -or $owned.IsCurrentBuild);ApplicationReady=(Test-ApplicationReady -Port $statusPort);Url="http://127.0.0.1:$statusPort";HostingWorkerReady=([bool]$hostingWorker);BuildExists=(Test-Path -LiteralPath $jarFile)} | Format-List
Push-Location $projectRoot
try { & docker compose ps mysql } finally { Pop-Location }
