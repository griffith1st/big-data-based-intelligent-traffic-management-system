#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ScriptVersion = '1.0.0'
$ExpectedCheckCount = 40
$RunId = [guid]::NewGuid().ToString('N')
$Prefix = "learn:itms:${RunId}:"
$ComposePath = Join-Path $PSScriptRoot 'compose.yml'
$JsonPath = Join-Path $PSScriptRoot 'verification-latest.json'
$MarkdownPath = Join-Path $PSScriptRoot 'verification-latest.md'
$StartedAt = [DateTimeOffset]::UtcNow
$Checks = New-Object 'System.Collections.Generic.List[object]'
$Keys = [ordered]@{}
foreach ($suffix in @('string', 'gap', 'hash', 'list', 'set', 'zset', 'failures', 'attempts', 'lock', 'stream')) {
    $Keys[$suffix] = $Prefix + $suffix
}
$Failure = $null
$CleanupError = $null
$CleanupVerified = $false
$RedisVersion = $null
$CurrentStep = 'preflight'

function Invoke-Redis {
    param([Parameter(Mandatory = $true)][string[]]$Command)
    # Each argument is a separate array element; no shell string interpolation.
    $DockerArguments = @('compose', '-f', $ComposePath, 'exec', '-T', 'redis', 'redis-cli', '--raw') + $Command
    $OldPreference = $ErrorActionPreference
    try {
        # Native stderr is captured for a useful error report in Windows PowerShell.
        $ErrorActionPreference = 'Continue'
        $RawOutput = @(& docker @DockerArguments 2>&1)
        $NativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $OldPreference
    }
    $Lines = @($RawOutput | ForEach-Object { [string]$_ })
    if ($NativeExitCode -ne 0) {
        throw "docker/redis-cli exited ${NativeExitCode}: $($Lines -join '; ')"
    }
    # redis-cli can print a Redis error yet exit zero. Never count that as success.
    foreach ($Line in $Lines) {
        if ($Line -match '^(?:\(error\)\s*)?(?:ERR|WRONGTYPE|NOAUTH|NOPERM|OOM|READONLY|MISCONF|BUSY|NOSCRIPT|NOGROUP|BUSYGROUP|LOADING|MASTERDOWN|CLUSTERDOWN|CROSSSLOT|MOVED|ASK)\b') {
            throw "Redis command error: $Line"
        }
    }
    return $Lines
}

function Assert-Check {
    param([string]$Name, [bool]$Condition, [string]$Evidence)
    $Checks.Add([pscustomobject]@{ name = $Name; passed = $Condition; evidence = $Evidence })
    if (-not $Condition) { throw "Check failed: ${Name}; $Evidence" }
    Write-Host "PASS $($Checks.Count): $Name"
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is not on PATH.' }
    $Reply = @(Invoke-Redis -Command @('PING'))
    Assert-Check 'Isolated service responds' ($Reply.Count -eq 1 -and $Reply[0] -eq 'PONG') ($Reply -join ', ')
    $Info = @(Invoke-Redis -Command @('INFO', 'server'))
    $VersionLine = @($Info | Where-Object { $_ -match '^redis_version:' })
    if ($VersionLine.Count -eq 1) { $RedisVersion = ($VersionLine[0] -split ':', 2)[1].Trim() }
    Assert-Check 'Redis major version is 7' ($RedisVersion -match '^7\.') "redis_version=$RedisVersion"

    $CurrentStep = 'String and expiration'
    $null = Invoke-Redis -Command @('SET', $Keys.string, 'session-demo', 'PX', '1500')
    $Reply = @(Invoke-Redis -Command @('GET', $Keys.string))
    Assert-Check 'String returns its stored value' ($Reply.Count -eq 1 -and $Reply[0] -eq 'session-demo') ($Reply -join ', ')
    $Ttl = [long](Invoke-Redis -Command @('PTTL', $Keys.string))
    Assert-Check 'String has a positive TTL' ($Ttl -gt 0 -and $Ttl -le 1500) "pttl_ms=$Ttl"
    Start-Sleep -Milliseconds 1600
    $Exists = [int](Invoke-Redis -Command @('EXISTS', $Keys.string))
    Assert-Check 'Expired String is absent' ($Exists -eq 0) "exists=$Exists"

    $CurrentStep = 'INCR and missing expiration gap'
    $Count = [long](Invoke-Redis -Command @('INCR', $Keys.gap))
    Assert-Check 'INCR creates a missing counter at one' ($Count -eq 1) "count=$Count"
    $Ttl = [long](Invoke-Redis -Command @('PTTL', $Keys.gap))
    Assert-Check 'INCR alone creates no expiration' ($Ttl -eq -1) "pttl_ms=$Ttl; separate EXPIRE leaves a failure gap"
    $Count = [long](Invoke-Redis -Command @('INCR', $Keys.gap))
    Assert-Check 'INCR advances the counter' ($Count -eq 2) "count=$Count"

    $CurrentStep = 'Hash'
    $null = Invoke-Redis -Command @('HSET', $Keys.hash, 'name', 'demo', 'attempts', '2')
    $Reply = @(Invoke-Redis -Command @('HMGET', $Keys.hash, 'name', 'attempts'))
    Assert-Check 'Hash returns multiple named fields' (($Reply -join '|') -eq 'demo|2') ($Reply -join ', ')
    $Count = [long](Invoke-Redis -Command @('HINCRBY', $Keys.hash, 'attempts', '1'))
    Assert-Check 'Hash field can be incremented' ($Count -eq 3) "attempts=$Count"

    $CurrentStep = 'List'
    $Count = [long](Invoke-Redis -Command @('RPUSH', $Keys.list, 'task-a', 'task-b', 'task-c'))
    Assert-Check 'List appends three jobs' ($Count -eq 3) "length=$Count"
    $Reply = @(Invoke-Redis -Command @('LPOP', $Keys.list))
    Assert-Check 'List removes the oldest appended job' (($Reply -join '|') -eq 'task-a') ($Reply -join ', ')
    $Reply = @(Invoke-Redis -Command @('LRANGE', $Keys.list, '0', '-1'))
    Assert-Check 'List preserves remaining order' (($Reply -join '|') -eq 'task-b|task-c') ($Reply -join ', ')

    $CurrentStep = 'Set'
    $Count = [long](Invoke-Redis -Command @('SADD', $Keys.set, 'user-a', 'user-b', 'user-a'))
    Assert-Check 'Set deduplicates members' ($Count -eq 2) "added=$Count"
    $Member = [int](Invoke-Redis -Command @('SISMEMBER', $Keys.set, 'user-a'))
    Assert-Check 'Set supports membership checks' ($Member -eq 1) "member=$Member"
    $Reply = @(Invoke-Redis -Command @('SMEMBERS', $Keys.set) | Sort-Object)
    Assert-Check 'Set contains exactly the distinct members' (($Reply -join '|') -eq 'user-a|user-b') ($Reply -join ', ')

    $CurrentStep = 'Sorted Set'
    $null = Invoke-Redis -Command @('ZADD', $Keys.zset, '10', 'alice', '20', 'bob', '15', 'carol')
    $Reply = @(Invoke-Redis -Command @('ZRANGE', $Keys.zset, '0', '-1', 'WITHSCORES'))
    Assert-Check 'Sorted Set orders members by ascending score' (($Reply -join '|') -eq 'alice|10|carol|15|bob|20') ($Reply -join ', ')
    $Reply = @(Invoke-Redis -Command @('ZREVRANGE', $Keys.zset, '0', '0'))
    Assert-Check 'Sorted Set returns the highest score first' (($Reply -join '|') -eq 'bob') ($Reply -join ', ')

    $CurrentStep = 'Redis error detection'
    $DetectedError = $false
    try { $null = Invoke-Redis -Command @('INCR', $Keys.hash) }
    catch { $DetectedError = $_.Exception.Message -match '^Redis command error:.*WRONGTYPE' }
    Assert-Check 'Redis stdout errors are rejected' $DetectedError 'INCR on a Hash must raise WRONGTYPE even if redis-cli exits zero'

    $CurrentStep = 'Atomic failure counter'
    $Reply = @(Invoke-Redis -Command @('--eval', '/lab/failure_count.lua', $Keys.failures, ',', '60000'))
    Assert-Check 'Lua counts the first failure and sets TTL' ($Reply.Count -eq 2 -and [long]$Reply[0] -eq 1 -and [long]$Reply[1] -gt 0 -and [long]$Reply[1] -le 60000) ($Reply -join ', ')
    $null = Invoke-Redis -Command @('PEXPIRE', $Keys.failures, '30000')
    $Reply = @(Invoke-Redis -Command @('--eval', '/lab/failure_count.lua', $Keys.failures, ',', '60000'))
    Assert-Check 'Lua counts the next failure' ($Reply.Count -eq 2 -and [long]$Reply[0] -eq 2) ($Reply -join ', ')
    Assert-Check 'Failure window is not extended on each failure' ([long]$Reply[1] -gt 0 -and [long]$Reply[1] -le 30000) "pttl_ms=$($Reply[1])"
    $null = Invoke-Redis -Command @('SET', $Keys.failures, '4')
    $Reply = @(Invoke-Redis -Command @('--eval', '/lab/failure_count.lua', $Keys.failures, ',', '60000'))
    Assert-Check 'Failure counter repairs an existing missing TTL' ($Reply.Count -eq 2 -and [long]$Reply[0] -eq 5 -and [long]$Reply[1] -gt 0) ($Reply -join ', ')

    $CurrentStep = 'Fixed window attempt limiter'
    $Reply = @(Invoke-Redis -Command @('--eval', '/lab/attempt_limit.lua', $Keys.attempts, ',', '2', '60000'))
    Assert-Check 'Attempt limiter admits first attempt' ($Reply.Count -eq 3 -and [int]$Reply[0] -eq 1 -and [long]$Reply[1] -eq 1 -and [long]$Reply[2] -gt 0) ($Reply -join ', ')
    $null = Invoke-Redis -Command @('PEXPIRE', $Keys.attempts, '30000')
    $Reply = @(Invoke-Redis -Command @('--eval', '/lab/attempt_limit.lua', $Keys.attempts, ',', '2', '60000'))
    Assert-Check 'Attempt limiter admits attempt at the limit' ($Reply.Count -eq 3 -and [int]$Reply[0] -eq 1 -and [long]$Reply[1] -eq 2) ($Reply -join ', ')
    Assert-Check 'Attempt limiter preserves fixed window' ([long]$Reply[2] -gt 0 -and [long]$Reply[2] -le 30000) "pttl_ms=$($Reply[2])"
    $Reply = @(Invoke-Redis -Command @('--eval', '/lab/attempt_limit.lua', $Keys.attempts, ',', '2', '60000'))
    Assert-Check 'Attempt limiter denies the next attempt' ($Reply.Count -eq 3 -and [int]$Reply[0] -eq 0 -and [long]$Reply[1] -eq 2) ($Reply -join ', ')
    $Count = [long](Invoke-Redis -Command @('GET', $Keys.attempts))
    Assert-Check 'Denied attempt does not exceed the stored cap' ($Count -eq 2) "count=$Count; admitted attempts count regardless of eventual success"

    $CurrentStep = 'Owner token unlock'
    $Reply = @(Invoke-Redis -Command @('SET', $Keys.lock, 'owner-demo', 'NX', 'PX', '60000'))
    Assert-Check 'Lock acquisition uses NX with expiration' (($Reply -join '|') -eq 'OK') ($Reply -join ', ')
    $Reply = @(Invoke-Redis -Command @('SET', $Keys.lock, 'other-demo', 'NX', 'PX', '60000'))
    $Owner = @(Invoke-Redis -Command @('GET', $Keys.lock))
    Assert-Check 'Second owner does not replace a held lock' (($Owner -join '|') -eq 'owner-demo') "owner=$($Owner -join ', ')"
    $Deleted = [int](Invoke-Redis -Command @('--eval', '/lab/unlock.lua', $Keys.lock, ',', 'other-demo'))
    $Owner = @(Invoke-Redis -Command @('GET', $Keys.lock))
    Assert-Check 'Wrong token preserves lock ownership' ($Deleted -eq 0 -and ($Owner -join '|') -eq 'owner-demo') "deleted=$Deleted; owner=$($Owner -join ', ')"
    $Deleted = [int](Invoke-Redis -Command @('--eval', '/lab/unlock.lua', $Keys.lock, ',', 'owner-demo'))
    $Exists = [int](Invoke-Redis -Command @('EXISTS', $Keys.lock))
    Assert-Check 'Matching token releases the lock atomically' ($Deleted -eq 1 -and $Exists -eq 0) "deleted=$Deleted; exists=$Exists"

    $CurrentStep = 'Stream consumer group'
    $Reply = @(Invoke-Redis -Command @('XGROUP', 'CREATE', $Keys.stream, 'learning-group', '0', 'MKSTREAM'))
    Assert-Check 'Stream consumer group starts at zero' (($Reply -join '|') -eq 'OK') ($Reply -join ', ')
    $Id = [string](Invoke-Redis -Command @('XADD', $Keys.stream, '*', 'kind', 'demo', 'id', 'job-1'))
    Assert-Check 'Stream append returns an entry ID' ($Id -match '^\d+-\d+$') "entry_id=$Id"
    $Reply = @(Invoke-Redis -Command @('XREADGROUP', 'GROUP', 'learning-group', 'worker-a', 'COUNT', '1', 'STREAMS', $Keys.stream, '>'))
    Assert-Check 'Consumer reads the expected entry and fields' (($Reply -join '|') -eq "$($Keys.stream)|$Id|kind|demo|id|job-1") ($Reply -join ', ')
    $Reply = @(Invoke-Redis -Command @('XPENDING', $Keys.stream, 'learning-group', '-', '+', '10'))
    Assert-Check 'Delivered entry is pending for first consumer' ($Reply.Count -eq 4 -and $Reply[0] -eq $Id -and $Reply[1] -eq 'worker-a' -and [long]$Reply[3] -eq 1) ($Reply -join ', ')
    # Idle 0 is intentional only for these isolated fixtures; production needs a processing-time threshold.
    $Reply = @(Invoke-Redis -Command @('XAUTOCLAIM', $Keys.stream, 'learning-group', 'worker-b', '0', '0-0', 'COUNT', '1'))
    Assert-Check 'Another consumer claims the abandoned entry' ($Reply.Count -ge 6 -and $Reply[1] -eq $Id -and $Reply[2] -eq 'kind' -and $Reply[3] -eq 'demo' -and $Reply[4] -eq 'id' -and $Reply[5] -eq 'job-1') ($Reply -join ', ')
    $Reply = @(Invoke-Redis -Command @('XPENDING', $Keys.stream, 'learning-group', '-', '+', '10'))
    Assert-Check 'Claim transfers pending ownership and redelivers' ($Reply.Count -eq 4 -and $Reply[0] -eq $Id -and $Reply[1] -eq 'worker-b' -and [long]$Reply[3] -eq 2) ($Reply -join ', ')
    $Acked = [int](Invoke-Redis -Command @('XACK', $Keys.stream, 'learning-group', $Id))
    Assert-Check 'Acknowledgement removes one pending delivery' ($Acked -eq 1) "acknowledged=$Acked"
    $Reply = @(Invoke-Redis -Command @('XPENDING', $Keys.stream, 'learning-group'))
    Assert-Check 'No pending delivery remains after acknowledgement' ($Reply.Count -ge 1 -and $Reply[0] -eq '0') ($Reply -join ', ')
    $Length = [long](Invoke-Redis -Command @('XLEN', $Keys.stream))
    Assert-Check 'Acknowledgement does not delete the stream entry' ($Length -eq 1) "stream_length=$Length"
    if ($Checks.Count -ne $ExpectedCheckCount) {
        throw "Check count mismatch: expected $ExpectedCheckCount, executed $($Checks.Count)."
    }
}
catch {
    $Failure = "${CurrentStep}: $($_.Exception.Message)"
}
finally {
    # Exact run-specific keys only; never FLUSHDB, FLUSHALL, KEYS or wildcard deletion.
    try {
        $ExactKeys = @($Keys.Values | ForEach-Object { [string]$_ })
        $null = Invoke-Redis -Command (@('UNLINK') + $ExactKeys)
        $Remaining = [long](Invoke-Redis -Command (@('EXISTS') + $ExactKeys))
        $CleanupVerified = $Remaining -eq 0
        if (-not $CleanupVerified) { throw "Exact-key cleanup left $Remaining keys." }
    }
    catch { $CleanupError = $_.Exception.Message }

    $PassedCount = @($Checks | Where-Object { $_.passed }).Count
    $FailedCount = @($Checks | Where-Object { -not $_.passed }).Count
    $Succeeded = $null -eq $Failure -and $null -eq $CleanupError -and $CleanupVerified -and $Checks.Count -eq $ExpectedCheckCount
    $Report = [ordered]@{
        scriptVersion = $ScriptVersion
        scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
        startedAtUtc = $StartedAt.ToString('o')
        finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $RunId
        prefix = $Prefix
        composeProject = 'itms-redis-learning'
        redisVersion = $RedisVersion
        expectedChecks = $ExpectedCheckCount
        executedChecks = $Checks.Count
        passedChecks = $PassedCount
        failedChecks = $FailedCount
        succeeded = $Succeeded
        cleanupVerified = $CleanupVerified
        cleanupError = $CleanupError
        failure = $Failure
        checks = @($Checks.ToArray())
    }
    $Report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $JsonPath -Encoding UTF8
    $Lines = New-Object 'System.Collections.Generic.List[string]'
    $Lines.Add('# Redis teaching lab verification')
    $Lines.Add('')
    $Lines.Add("Script version: $ScriptVersion; SHA-256: $($Report.scriptSha256)")
    $Lines.Add("Redis: $RedisVersion; UTC run: $($Report.startedAtUtc)")
    $Lines.Add("Result: $Succeeded; checks: $PassedCount/$ExpectedCheckCount passed; executed: $($Checks.Count); failed assertions: $FailedCount")
    $Lines.Add("Exact-key cleanup verified: $CleanupVerified")
    $Lines.Add("Run prefix: $Prefix")
    if ($Failure) { $Lines.Add("Failure: $Failure") }
    if ($CleanupError) { $Lines.Add("Cleanup error: $CleanupError") }
    $Lines.Add('')
    $Lines.Add('| # | Check | Passed | Evidence |')
    $Lines.Add('| --- | --- | --- | --- |')
    $Index = 0
    foreach ($Check in $Checks) {
        $Index++
        $Evidence = $Check.evidence.Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
        $Lines.Add("| $Index | $($Check.name) | $($Check.passed) | $Evidence |")
    }
    $Lines | Set-Content -LiteralPath $MarkdownPath -Encoding UTF8
}

Write-Host "Reports: $JsonPath ; $MarkdownPath"
if (-not $Succeeded) { throw "Verification failed. $Failure $CleanupError" }
Write-Host "Verified $PassedCount/$ExpectedCheckCount checks; exact run keys cleaned."
