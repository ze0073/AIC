$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$runtimeRoot = Join-Path $projectRoot '.runtime'
$stateFile = Join-Path $runtimeRoot 'application-state.json'
$jarFile = Join-Path $projectRoot 'backend\target\accessibility-0.9.0-SNAPSHOT.jar'
$jdk = Get-ChildItem -LiteralPath (Join-Path $runtimeRoot 'jdk-21') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName; $env:Path = "$(Join-Path $jdk.FullName 'bin');$env:Path" }
$dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
if (Test-Path -LiteralPath $dockerBin) { $env:Path = "$dockerBin;$env:Path" }
$env:DOCKER_CONFIG = Join-Path $runtimeRoot 'docker-cli'
$gitCaFile=Join-Path $runtimeRoot 'git-system-ca.pem'
$gitCaText=New-Object Text.StringBuilder
foreach($gitCertificate in Get-ChildItem Cert:\LocalMachine\Root,Cert:\CurrentUser\Root){
    [void]$gitCaText.AppendLine('-----BEGIN CERTIFICATE-----')
    [void]$gitCaText.AppendLine([Convert]::ToBase64String($gitCertificate.RawData,[Base64FormattingOptions]::InsertLineBreaks))
    [void]$gitCaText.AppendLine('-----END CERTIFICATE-----')
}
[IO.File]::WriteAllText($gitCaFile,$gitCaText.ToString(),[Text.Encoding]::ASCII)
$env:GIT_SSL_CAINFO=$gitCaFile
$envFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line.Trim() -and !$line.Trim().StartsWith('#') -and $line.Contains('=')) {
            $parts = $line.Split('=', 2)
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
        }
    }
}
# Docker's CLI token requests use environment proxies, not Windows Internet Settings.
# Preserve explicit process/.env settings and inherit an enabled system proxy otherwise.
$systemProxySettings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
if ($systemProxySettings.ProxyEnable -eq 1 -and $systemProxySettings.ProxyServer) {
    foreach ($proxyProtocol in @('http', 'https')) {
        $proxyVariable = $proxyProtocol.ToUpperInvariant() + '_PROXY'
        if ([Environment]::GetEnvironmentVariable($proxyVariable, 'Process')) { continue }
        $proxySetting = [string]$systemProxySettings.ProxyServer
        if ($proxySetting.Contains('=')) {
            $proxyEntry = $proxySetting.Split(';') | Where-Object { $_.Trim().StartsWith($proxyProtocol + '=', [StringComparison]::OrdinalIgnoreCase) } | Select-Object -First 1
            if (!$proxyEntry) { continue }
            $proxySetting = $proxyEntry.Split('=', 2)[1].Trim()
        }
        if (!$proxySetting.Contains('://')) { $proxySetting = 'http://' + $proxySetting }
        $proxyUri = $null
        if ([Uri]::TryCreate($proxySetting, [UriKind]::Absolute, [ref]$proxyUri) -and $proxyUri.Scheme -in @('http', 'https') -and $proxyUri.Host) {
            [Environment]::SetEnvironmentVariable($proxyVariable, $proxySetting, 'Process')
        }
    }
}
if ($env:HTTP_PROXY -or $env:HTTPS_PROXY) {
    $env:NO_PROXY = (@($env:NO_PROXY -split ',') + @('localhost', '127.0.0.1', '::1', '.localhost') | Where-Object { $_ } | Select-Object -Unique) -join ','
}
$appPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }

function Get-OwnedApplication {
    if (!(Test-Path -LiteralPath $stateFile)) { return $null }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    if ($state.projectRoot -ne $projectRoot) { throw '运行记录不属于此项目，未操作任何进程。' }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$state.pid)"
    if (!$process) { return $null }
    $started = $process.CreationDate.ToUniversalTime().ToString('o')
    $recordedStart = if ($state.startedAt -is [datetime]) { $state.startedAt.ToUniversalTime().ToString('o') } else { [datetime]::Parse($state.startedAt).ToUniversalTime().ToString('o') }
    if ($process.Name -ne 'java.exe' -or !$process.CommandLine.Contains([string]$state.jar) -or $started -ne $recordedStart) {
        return $null
    }
    return [pscustomobject]@{
        ProcessId = $process.ProcessId
        RecordedJar = [string]$state.jar
        RecordedPort = [int]$state.port
        IsCurrentBuild = ([string]$state.jar -eq $jarFile)
    }
}

function Test-ApplicationReady([int]$Port = $appPort) {
    try { return (Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3).status -eq 'UP' } catch { return $false }
}

function Get-OwnedHostingWorker {
    $workerStateFile=Join-Path $runtimeRoot 'hosting-worker-state.json'
    if(!(Test-Path -LiteralPath $workerStateFile)){return $null}
    $workerState=Get-Content -LiteralPath $workerStateFile -Raw | ConvertFrom-Json
    if($workerState.projectRoot -ne $projectRoot){throw '部署执行器记录不属于此项目。'}
    $workerProcess=Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$workerState.pid)"
    if(!$workerProcess){return $null}
    $workerStarted=if($workerState.startedAt -is [datetime]){$workerState.startedAt.ToUniversalTime().ToString('o')}else{[datetime]::Parse($workerState.startedAt).ToUniversalTime().ToString('o')}
    if($workerProcess.Name -ne 'java.exe' -or !$workerProcess.CommandLine.Contains('--app.hosting.worker-only=true') -or !$workerProcess.CommandLine.Contains([string]$workerState.jar) -or $workerProcess.CreationDate.ToUniversalTime().ToString('o') -ne $workerStarted){return $null}
    return [pscustomobject]@{ProcessId=$workerProcess.ProcessId;Port=[int]$workerState.port}
}
