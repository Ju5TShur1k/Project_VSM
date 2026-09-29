param(
    [Parameter(Mandatory=$true)][string]$ScenarioId,
    [string]$BaseUrl='http://localhost:8080',
    [string]$Username='technologist',
    [string]$Password='tech-demo'
)
# Explicit model assumptions only. Uses versioned API; never edits old tables/snapshots.
# A rerun reuses deterministic request keys and does not apply a change twice.
$ErrorActionPreference='Stop'
$taskBase=$BaseUrl.TrimEnd('/')
$taskSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Task-Csrf {
    $cookie=$taskSession.Cookies.GetCookies([Uri]$taskBase) | Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -First 1
    if (-not $cookie) { throw 'CSRF cookie missing' }
    return @{ 'X-XSRF-TOKEN'=$cookie.Value }
}
function Get-Task($path) { Invoke-RestMethod "$taskBase/api/v1$path" -WebSession $taskSession }
function Post-Task($path,$body) {
    Invoke-RestMethod "$taskBase/api/v1$path" -Method Post -WebSession $taskSession -Headers (Task-Csrf) `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 30 -Compress)))
}
function Task-Date($value) {
    if ($value -is [DateTime] -or $value -is [DateTimeOffset]) { return [DateTimeOffset]$value }
    return [DateTimeOffset]::Parse([string]$value)
}
try { $null=Invoke-WebRequest "$taskBase/api/v1/auth/me" -WebSession $taskSession -UseBasicParsing } catch {}
$null=Invoke-WebRequest "$taskBase/api/v1/auth/login" -Method Post -WebSession $taskSession -UseBasicParsing `
    -Headers (Task-Csrf) -ContentType 'application/x-www-form-urlencoded' -Body @{username=$Username;password=$Password}
$null=Get-Task '/auth/me'
$taskHead=Get-Task "/scenarios/$ScenarioId/version"
$taskRoot=$taskHead.rootId
$taskOriginal=Get-Task "/scenarios/$($taskHead.scenarioId)/source"
if (-not ([string]$taskOriginal.scenario.provenance).StartsWith('MODELLED FULL43:')) { throw 'This script only accepts the explicitly MODELLED FULL43 source' }
if (@($taskOriginal.trains).Count -ne 43 -or @($taskOriginal.fixedTrips).Count -ne 1428) { throw 'Expected the FULL43 source with 43 trains and 1428 trips' }
$taskPaths=@($taskOriginal.resources | Where-Object { $_.id -like 'SPB-PATH-*' } | ForEach-Object { $_.id })
if ($taskPaths.Count -ne 5) { throw 'Expected five explicitly modelled depot positions' }
function Write-TaskFact($change,$key) {
    $head=Get-Task "/scenarios/$taskRoot/version"
    $body=@{scenarioId=$head.scenarioId;expectedVersion=$head.version;idempotencyKey=$key;
        reason='Explicit E3 demonstration assumption, pending customer confirmation';
        source='MODELLED E3 source assumptions v1; not actual release or operator repair norms';change=$change}
    Post-Task "/scenarios/$($head.scenarioId)/source-fact-versions" $body
}
# Never overwrite a colleague's catalog. Existing matching demo version is left intact.
$taskCatalogResponse=Get-Task "/scenarios/$($taskHead.scenarioId)/urgent-work-rules"
$taskCatalog=@($taskCatalogResponse | ForEach-Object { $_ })
if ($taskCatalog.Count -gt 0 -and @($taskCatalog | Where-Object { $_.rule_version -ne 'demo-e3-source-v1' }).Count -gt 0) {
    throw 'A different urgent catalog already exists. Preserve it and agree rules with the team before proceeding'
}
if ($taskCatalog.Count -eq 0) {
    $taskRules=@(
        @{workKind='Внеплановый осмотр';durationMinutes=60;resourceIds=$taskPaths;confirmationStatus='SYNTHETIC'},
        @{workKind='Ремонт оборудования';durationMinutes=120;resourceIds=$taskPaths;confirmationStatus='SYNTHETIC'}
    )
    foreach ($code in @('IS100','IS200')) {
        $norm=@($taskOriginal.cycleRules | Where-Object { $_.code -eq $code -and $_.rule_set_id -eq $taskOriginal.scenario.rule_set_id })
        if ($norm.Count -ne 1) { throw "Missing active cycle norm: $code" }
        $taskRules+=@{workKind=$code;durationMinutes=[int]$norm[0].duration_minutes;resourceIds=$taskPaths;confirmationStatus='SYNTHETIC'}
    }
    $null=Write-TaskFact @{kind='URGENT_RULE_CHANGE';version='demo-e3-source-v1';rules=$taskRules} "e3-demo-norms-v1:$taskRoot"
}
foreach ($work in @($taskOriginal.frozenWork)) {
    $head=Get-Task "/scenarios/$taskRoot/version"
    $source=Get-Task "/scenarios/$($head.scenarioId)/source"
    $previous=@($source.trainReleases | Where-Object { $_.train_id -eq $work.train_id })
    if ($previous.Count -gt 0) { continue } # Preserve any subsequently confirmed release or forecast.
    $available=(Task-Date $source.scenario.horizon_start).AddDays(1)
    foreach ($occupied in @($source.trainOccupancy | Where-Object { $_.train_id -eq $work.train_id -and $_.kind -eq 'UNAVAILABLE' })) {
        $until=Task-Date $occupied.ends_at; if ($until -gt $available) { $available=$until }
    }
    $until=Task-Date $work.ends_at; if ($until -gt $available) { $available=$until }
    $resource=@($source.resources | Where-Object { $_.id -eq $work.resource_id })[0]
    $null=Write-TaskFact @{kind='TRAIN_RELEASE';trainId=$work.train_id;frozenWorkId=$work.id;
        availableFrom=$available.ToString('o');location=$resource.location;basis='DEMO_ASSUMPTION'} "e3-demo-release-v1:$taskRoot`:$($work.train_id)"
}
$taskFinal=Get-Task "/scenarios/$taskRoot/version"
$taskSource=Get-Task "/scenarios/$($taskFinal.scenarioId)/source"
if (@($taskSource.trainReleases).Count -ne 5) { throw 'Expected five explicit releases; inspect the resulting source' }
[pscustomobject]@{rootId=$taskRoot;scenarioId=$taskFinal.scenarioId;sourceVersion=$taskFinal.version;
    snapshotId=$taskFinal.snapshotId;snapshotHash=$taskFinal.snapshotHash;
    releaseCount=@($taskSource.trainReleases).Count;urgentRuleCount=@($taskSource.urgentWorkRules).Count;
    status='SOURCE_PREPARED_ONLY';solverStatus='NOT_RUN';d2Status='NOT_PERFORMED'} | ConvertTo-Json
