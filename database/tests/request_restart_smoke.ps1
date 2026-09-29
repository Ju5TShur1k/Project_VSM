param(
    [Parameter(Mandatory=$true)][string]$DbUrl,
    [string]$DbUser='okno',
    [string]$DbPassword='okno',
    [string]$JavaExe='java',
    [int]$Port=18089,
    [string]$JarPath=(Join-Path $PSScriptRoot '../../backend/target/okno-api-0.0.1-SNAPSHOT.jar')
)
# Use a dedicated test database. Additive fixtures only; no volumes/data are deleted.
$ErrorActionPreference='Stop'
$JarPath=(Resolve-Path -LiteralPath $JarPath).Path
$taskBackend=Split-Path (Split-Path $JarPath -Parent) -Parent
$taskBase="http://127.0.0.1:$Port"
$taskProof=Join-Path (Split-Path $JarPath -Parent) 'task1-restart-proof.json'
$taskSession=$null
$taskApiProcess=$null
$taskPreviousDb=@{ DB_URL=$env:DB_URL; DB_USER=$env:DB_USER; DB_PASSWORD=$env:DB_PASSWORD }
function Assert-Task($condition,$message) { if (-not $condition) { throw $message } }
function Start-TaskApi {
    $env:DB_URL=$DbUrl; $env:DB_USER=$DbUser; $env:DB_PASSWORD=$DbPassword
    $script:taskApiProcess=Start-Process -FilePath $JavaExe -WindowStyle Hidden -PassThru -WorkingDirectory $taskBackend `
        -ArgumentList @('-jar',('"'+$JarPath+'"'),'--spring.profiles.active=database',"--server.port=$Port",'--server.address=127.0.0.1') `
        -RedirectStandardOutput (Join-Path (Split-Path $JarPath -Parent) 'task1-restart-api.log') `
        -RedirectStandardError (Join-Path (Split-Path $JarPath -Parent) 'task1-restart-api-error.log')
    for ($attempt=0; $attempt -lt 150; $attempt++) {
        if ($script:taskApiProcess.HasExited) { throw 'Test API exited; inspect task1-restart-api logs' }
        try { $health=Invoke-RestMethod "$taskBase/actuator/health" -TimeoutSec 2; if ($health.status -eq 'UP') { return } } catch {}
        Start-Sleep -Milliseconds 200
    }
    throw 'Test API did not become healthy'
}
function Stop-TaskApi {
    if ($script:taskApiProcess -and -not $script:taskApiProcess.HasExited) {
        Stop-Process -InputObject $script:taskApiProcess -Force
        $script:taskApiProcess.WaitForExit()
    }
}
function Task-Csrf {
    $cookie=$script:taskSession.Cookies.GetCookies([Uri]$taskBase) | Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -First 1
    if (-not $cookie) { throw 'CSRF cookie missing' }
    return @{ 'X-XSRF-TOKEN'=$cookie.Value }
}
function Login-Task($username,$password) {
    $script:taskSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
    try { $null=Invoke-WebRequest "$taskBase/api/v1/auth/me" -WebSession $script:taskSession -UseBasicParsing } catch {}
    $null=Invoke-WebRequest "$taskBase/api/v1/auth/login" -Method Post -WebSession $script:taskSession -UseBasicParsing `
        -Headers (Task-Csrf) -ContentType 'application/x-www-form-urlencoded' -Body @{username=$username;password=$password}
    $null=Invoke-RestMethod "$taskBase/api/v1/auth/me" -WebSession $script:taskSession
}
function Get-Task($path) { return Invoke-RestMethod "$taskBase/api/v1$path" -WebSession $script:taskSession }
function Task-Date($value) {
    # PowerShell 7 may decode ISO JSON strings into DateTime. Keep its Kind/offset;
    # converting that object to a culture string and parsing it would lose UTC.
    if ($value -is [DateTime] -or $value -is [DateTimeOffset]) { return [DateTimeOffset]$value }
    return [DateTimeOffset]::Parse([string]$value)
}
function Post-Task($path,$body) {
    return Invoke-RestMethod "$taskBase/api/v1$path" -Method Post -WebSession $script:taskSession -Headers (Task-Csrf) `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 30 -Compress)))
}
try {
    Start-TaskApi
    Login-Task 'planner' 'planner-demo'
    $taskOriginal=Post-Task '/demo/source' @{}
    $taskOldSource=Get-Task "/scenarios/$($taskOriginal.scenarioId)/source"
    $taskTrip=$taskOldSource.fixedTrips | Where-Object label -eq 'R1'
    Login-Task 'dispatcher' 'disp-demo'
    $taskCommand=@{
        clientRequestId=[Guid]::NewGuid().ToString(); expectedSnapshotHash=$taskOriginal.snapshotHash; comment='Dedicated restart smoke fixture'
        payload=@{kind='TRIP_CHANGE'; trainId=$taskTrip.train_id; tripId=$taskTrip.id; reason='Сдвиг конкретного рейса на 5 минут'; source='Звонок с линии'
            newDepartureAt=((Task-Date $taskTrip.departure_at).AddMinutes(5)).ToString('yyyy-MM-ddTHH:mm:sszzz')
            newArrivalAt=((Task-Date $taskTrip.arrival_at).AddMinutes(5)).ToString('yyyy-MM-ddTHH:mm:sszzz')}
    }
    $taskReceipt=Post-Task "/scenarios/$($taskOriginal.scenarioId)/requests" $taskCommand
    $taskMessage=Post-Task '/incidents' @{train='EVS-SYN-1';kind='TRIP_CHANGE';description='Совместимый журнал также должен пережить рестарт'}
    Login-Task 'planner' 'planner-demo'
    $taskJob=Get-Task "/planning-jobs/$($taskReceipt.jobId)"
    for ($attempt=0; $attempt -lt 200; $attempt++) {
        $taskJob=Get-Task "/planning-jobs/$($taskJob.jobId)"
        if ($taskJob.status -notin @('QUEUED','RUNNING')) { break }
        Start-Sleep -Milliseconds 100
    }
    Assert-Task ($taskJob.status -eq 'SUCCEEDED') "Job failed: $($taskJob.error)"
    $taskPlan=Get-Task "/plans/$($taskJob.planId)"
    Assert-Task ($taskPlan.validationStatus -eq 'PASS') 'Changed source did not pass D2'
    $taskApproved=Post-Task "/plans/$($taskPlan.id)/approve" @{expectedVersion=0;comment='Проверено на отдельной тестовой БД'}
    Assert-Task ($taskApproved.status -eq 'APPROVED') 'Approval failed'
    Stop-TaskApi
    Start-TaskApi
    Login-Task 'dispatcher' 'disp-demo'
    $taskReplayed=Post-Task "/scenarios/$($taskOriginal.scenarioId)/requests" $taskCommand
    $taskNewSource=Get-Task "/scenarios/$($taskReceipt.newScenarioId)/source"
    $taskStillOld=Get-Task "/scenarios/$($taskOriginal.scenarioId)/source"
    $taskRecoveredJob=Get-Task "/planning-jobs/$($taskJob.jobId)"
    $taskRecoveredPlan=Get-Task "/plans/$($taskPlan.id)"
    $taskCurrent=Get-Task "/current-plan?scenarioId=$($taskOriginal.scenarioId)"
    $taskHistory=Get-Task "/change-requests/$($taskReceipt.id)/history"
    Assert-Task ($taskReplayed.id -eq $taskReceipt.id) 'Retry created another request'
    Assert-Task ($taskReplayed.status -eq 'APPROVED') 'Lifecycle was not persisted'
    Assert-Task ($taskReceipt.newSnapshotHash -ne $taskOriginal.snapshotHash) 'Hash did not change'
    Assert-Task (($taskStillOld.fixedTrips | Where-Object id -eq $taskTrip.id).arrival_at -eq $taskTrip.arrival_at) 'Old source was overwritten'
    Assert-Task ((Task-Date ($taskNewSource.fixedTrips | Where-Object id -eq $taskTrip.id).arrival_at) -eq (Task-Date $taskTrip.arrival_at).AddMinutes(5)) 'New trip time was lost'
    Assert-Task ($taskRecoveredJob.status -eq 'SUCCEEDED' -and $taskRecoveredPlan.status -eq 'APPROVED') 'Jobs/plans were lost on restart'
    Assert-Task ($taskCurrent.planId -eq $taskPlan.id) 'Effective plan pointer was lost'
    Assert-Task ((Get-Task '/incidents').id -contains $taskMessage.id) 'Legacy journal was lost'
    Assert-Task (($taskHistory.status -join ',') -eq 'RECEIVED,IN_CALCULATION,CALCULATED,VALIDATED,APPROVED') 'Lifecycle history is incomplete'
    # Prove SSE reconnect against the restarted API, using its actual session cookie.
    $taskHandler=New-Object System.Net.Http.HttpClientHandler; $taskHandler.CookieContainer=$script:taskSession.Cookies
    $taskClient=New-Object System.Net.Http.HttpClient($taskHandler)
    $taskRequest=New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get,"$taskBase/api/v1/change-requests/stream")
    $taskRequest.Headers.Add('Last-Event-ID',([long]$taskHistory[0].sequence-1).ToString())
    $taskResponse=$taskClient.SendAsync($taskRequest,[System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    Assert-Task ($taskResponse.IsSuccessStatusCode) 'SSE connection failed'
    $taskStream=$taskResponse.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $taskReader=New-Object IO.StreamReader($taskStream)
    $taskSseSeen=$false
    try {
        for ($line=0; $line -lt 12; $line++) {
            $taskRead=$taskReader.ReadLineAsync()
            if (-not $taskRead.Wait(5000)) { throw 'SSE did not replay events' }
            if ($taskRead.Result -like 'data:*') {
                $taskEvent=$taskRead.Result.Substring(5).Trim() | ConvertFrom-Json
                if ($taskEvent.requestId -eq $taskReceipt.id) { $taskSseSeen=$true; break }
            }
        }
    } finally { $taskReader.Dispose(); $taskResponse.Dispose(); $taskClient.Dispose(); $taskRequest.Dispose() }
    Assert-Task $taskSseSeen 'SSE replay did not contain the saved request'
    $taskActive=Get-Task "/plans/active?scenarioId=$($taskOriginal.scenarioId)"
    Assert-Task ($taskActive.id -eq $taskPlan.id) 'UI active plan contract failed after restart'
    Assert-Task ($taskReplayed.payload.tripId -eq $taskTrip.id -and $taskReplayed.comment -eq $taskCommand.comment) 'Original UI payload/comment were lost'
    @{ result='PASS'; apiRestarted=$true; sourceVersion=$taskReceipt.sourceVersion; sourceScenarioId=$taskReceipt.newScenarioId; requestId=$taskReceipt.id; tripId=$taskTrip.id
        oldSnapshotHash=$taskOriginal.snapshotHash; newSnapshotHash=$taskReceipt.newSnapshotHash
        jobId=$taskJob.jobId; planId=$taskPlan.id; status=$taskReplayed.status; history=$taskHistory.status
        exactRetryCreatedNoDuplicate=$true; sseReconnect=$taskSseSeen } | ConvertTo-Json -Depth 20 | Set-Content -Encoding utf8 $taskProof
    Write-Output "PASS: API restart, five-minute trip change, immutable history, exact retry, jobs/plans, effective plan and SSE replay. Proof: $taskProof"
} finally {
    Stop-TaskApi
    foreach ($name in $taskPreviousDb.Keys) { [Environment]::SetEnvironmentVariable($name,$taskPreviousDb[$name],'Process') }
}
