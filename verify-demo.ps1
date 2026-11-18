param([string]$BaseUrl='http://127.0.0.1:8080',[int]$Rounds=2)
$ErrorActionPreference='Stop'
$out=Join-Path $PSScriptRoot 'output\v050'
New-Item -ItemType Directory -Path $out -Force | Out-Null
function Api($path,$method='GET',$body=$null,$key=$null) {
    $argsMap=@{Uri="$BaseUrl/api$path";Method=$method;TimeoutSec=90}
    if($method -eq 'POST') { $argsMap.ContentType='application/json'; $argsMap.Headers=@{'Idempotency-Key'=$(if($key){$key}else{[guid]::NewGuid().ToString()})} }
    if($null -ne $body){$argsMap.Body=[Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 20 -Compress));$argsMap.ContentType='application/json'}
    Invoke-RestMethod @argsMap
}
function Check($ok,$message){if(!$ok){throw "验收失败：$message"}}
function Wait-Scan($id){
    $deadline=(Get-Date).AddSeconds(90)
    do{$s=Api "/scans/$id";if($s.status -notin @('PENDING','RUNNING')){return $s};Start-Sleep -Milliseconds 400}while((Get-Date)-lt $deadline)
    throw "扫描超时 $id"
}
$results=@()
foreach($round in 1..$Rounds){foreach($slug in @('library','course')){
    $installed=Api "/examples/$slug/install" 'POST'
    $projectId=$installed.project.id;$versionId=$installed.version.id
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $scan=Api "/versions/$versionId/rescan" 'POST' @{pages=@('index.html');rules=@();enableKeyboardTest=$true;enableAi=$false;aiConsent=$false}
    $scan=Wait-Scan $scan.id;$timer.Stop();Check ($scan.status -eq 'COMPLETED') '扫描完整完成'
    $items=(Api "/scans/$($scan.id)/findings?size=100").items
    $r009=@($items|Where-Object ruleId -eq 'R009')
    if($slug -eq 'library'){Check ($r009.Count -eq 0) '自然顺序负例不产生 R009'}else{Check ($r009.Count -gt 0) '重排正例产生 R009'}
    $key=[guid]::NewGuid().ToString();$frozen=Api "/scans/$($scan.id)/report-snapshots" 'POST' $null $key
    $again=Api "/scans/$($scan.id)/report-snapshots" 'POST' $null $key
    Check ($frozen.snapshotId -eq $again.snapshotId) '快照幂等'
    if($r009.Count){
        $review=Api "/findings/$($r009[0].id)/reviews" 'POST' @{action='CONFIRM_RISK';comment='已依据阅读顺序证据人工确认'}
        Check ($review.scan.reviewCount -eq ($scan.reviewCount-1)) '确认后待复核数减少'
        $old=Api "/report-snapshots/$($frozen.snapshotId)"
        Check ($old.report.scan.reviewCount -eq $scan.reviewCount) '旧快照不随复核改变'
        Check ($old.contentHash -eq $frozen.contentHash) '旧快照哈希不变'
        $fresh=Api "/scans/$($scan.id)/report-snapshots" 'POST'
        Check ($fresh.report.scan.reviewCount -eq $review.scan.reviewCount) '新快照包含新结论'
        $null=Api "/findings/$($r009[0].id)/reviews" 'POST' @{action='DISMISS_FALSE_POSITIVE';comment='复查后认为当前内容顺序可接受'}
    }
    $target=$items|Where-Object { $_.ruleId -eq $(if($slug -eq 'library'){'R004'}else{'R002'}) -and $_.fixMode -eq 'TEMPLATE_PATCH'}|Select-Object -First 1
    Check ($null -ne $target) '存在模板修复目标'
    $patch=Api "/findings/$($target.id)/fix-patches" 'POST' @{language='zh-CN';decorative=$false}
    $applied=Api "/fix-patches/$($patch.id)/apply" 'POST'
    $after=Wait-Scan $applied.rescan.id;Check ($after.status -eq 'COMPLETED') '复测完成'
    $comparison=Api "/projects/$projectId/comparison?beforeScanId=$($scan.id)&afterScanId=$($after.id)"
    Check $comparison.comparable '同配置可比较'
    Check ($comparison.notRedetected -ge 1) '目标问题未再检出'
    Check ($comparison.verifiedFindingIds -contains $target.id) '精确目标补丁复测通过'
    $demo=Api "/projects/$projectId/demo-state?versionId=$versionId&scanId=$($scan.id)"
    Check ($demo.version.id -eq $versionId) '历史演示保持原版本'
    Check ($demo.afterScanId -eq $after.id) '演示关联正确复测'
    $report=Api "/scans/$($after.id)/report-snapshots" 'POST'
    $export=Api "/report-snapshots/$($report.snapshotId)/export?format=json"
    Check ($export.contentHash -eq $report.contentHash -and $export.report.scan.id -eq $after.id) 'JSON 与页面快照一致'
    $pdfFile=Join-Path $out "$round-$slug.pdf"
    $pdf=Invoke-WebRequest "$BaseUrl/api/report-snapshots/$($report.snapshotId)/export?format=pdf" -OutFile $pdfFile -PassThru -TimeoutSec 90
    Check ($pdf.Headers['X-Report-Snapshot-Id'] -eq $report.snapshotId) 'PDF 同快照'
    $hash=(Get-FileHash -LiteralPath $pdfFile).Hash
    $pdfAgain=Join-Path $out "$round-$slug-repeat.pdf"
    Invoke-WebRequest "$BaseUrl/api/report-snapshots/$($report.snapshotId)/export?format=pdf" -OutFile $pdfAgain -TimeoutSec 90
    Check ((Get-FileHash -LiteralPath $pdfAgain).Hash -eq $hash) 'PDF 工件复用'
    $results+=@{round=$round;example=$slug;projectId=$projectId;versionId=$versionId;beforeScanId=$scan.id;afterScanId=$after.id;snapshotId=$report.snapshotId;scanSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,2);notRedetected=$comparison.notRedetected;pdfSha256=$hash}
    Write-Host "PASS round=$round example=$slug scanSeconds=$($timer.Elapsed.TotalSeconds)"
}}
$results|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $out 'acceptance.json') -Encoding UTF8
function Expect-Status($path,$expected) {
    $actual=0
    try { $null=Api $path } catch { $actual=[int]$_.Exception.Response.StatusCode }
    Check ($actual -eq $expected) "HTTP $expected for $path"
}
$first=$results[0];$last=$results[-1]
Expect-Status "/projects/$($first.projectId)/comparison?beforeScanId=$($first.beforeScanId)&afterScanId=$($last.afterScanId)" 404
Expect-Status "/projects/$($first.projectId)/demo-state?scanId=$($last.beforeScanId)" 404
Expect-Status "/projects/$($last.projectId)/demo-state?versionId=$($last.versionId)&scanId=$($last.afterScanId)" 400
$different=Api "/versions/$($last.versionId)/rescan" 'POST' @{pages=@('index.html');rules=@('R001');enableKeyboardTest=$false;enableAi=$false;aiConsent=$false}
$different=Wait-Scan $different.id
$notComparable=Api "/projects/$($last.projectId)/comparison?beforeScanId=$($last.beforeScanId)&afterScanId=$($different.id)"
Check (!$notComparable.comparable -and $null -eq $notComparable.notRedetected) '不同规则配置不计算改善'

# Concurrent snapshot creation and review must expose a coherent old or new state, never mixed statistics.
$sameKey=[guid]::NewGuid().ToString()
$jobs=@(1..2|ForEach-Object {Start-Job -ArgumentList $BaseUrl,$last.beforeScanId,$sameKey -ScriptBlock {
    param($base,$scan,$key)
    Invoke-RestMethod "$base/api/scans/$scan/report-snapshots" -Method POST -Headers @{'Idempotency-Key'=$key}
}})
$reviewJob=Start-Job -ArgumentList $BaseUrl,$r009[0].id -ScriptBlock {
    param($base,$finding)
    Invoke-RestMethod "$base/api/findings/$finding/reviews" -Method POST -Headers @{'Idempotency-Key'=[guid]::NewGuid().ToString()} -ContentType 'application/json' -Body '{"action":"CONFIRM_RISK","comment":"concurrent review verification"}'
}
$null=$jobs+$reviewJob|Wait-Job -Timeout 30
$concurrent=@($jobs|Receive-Job -ErrorAction Stop)
$null=$reviewJob|Receive-Job -ErrorAction Stop
Check ($concurrent.Count -eq 2 -and $concurrent[0].snapshotId -eq $concurrent[1].snapshotId) '并发相同键只创建一份报告'
$coherent=Api "/report-snapshots/$($concurrent[0].snapshotId)"
$pending=@($coherent.report.findings|Where-Object status -eq 'NEEDS_REVIEW').Count
$actionable=@($coherent.report.findings|Where-Object {$_.status -notin @('NEEDS_REVIEW','DISMISSED')})
$high=@($actionable|Where-Object severity -eq 'HIGH').Count
$medium=@($actionable|Where-Object severity -eq 'MEDIUM').Count
$low=@($actionable|Where-Object severity -eq 'LOW').Count
Check ($pending -eq $coherent.report.scan.reviewCount -and $high -eq $coherent.report.scan.highCount -and $medium -eq $coherent.report.scan.mediumCount -and $low -eq $coherent.report.scan.lowCount) '并发复核与快照统计一致'
Check ($coherent.report.scan.riskScore -eq [Math]::Max(0,100-10*$high-4*$medium-$low)) '冻结风险分与记录一致'
$jobs+$reviewJob|Remove-Job
Write-Host 'PASS project isolation, incompatible scopes, concurrent idempotency and coherent review snapshot'
Write-Host '两个校园示例的扫描、复核、修复、快照和报告导出验收完成。'
