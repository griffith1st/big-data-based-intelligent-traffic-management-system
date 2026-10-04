param([string]$RedisImage = 'redis:7-alpine')
$ErrorActionPreference = 'Stop'
$containerName = 'itms-redis-test-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$backendDirectory = Split-Path $PSScriptRoot -Parent
$created = $false
Push-Location $backendDirectory
try {
    docker version --format '{{.Server.Version}}'
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop before running this test suite.' }
    docker run --detach --rm --name $containerName --label itms.purpose=redis-test --publish '127.0.0.1::6379' $RedisImage redis-server --save '""' --appendonly no
    if ($LASTEXITCODE -ne 0) { throw 'Test Redis startup failed.' }
    $created = $true
    $port = docker inspect --format '{{(index (index .NetworkSettings.Ports "6379/tcp") 0).HostPort}}' $containerName
    if ($LASTEXITCODE -ne 0 -or $port -notmatch '^\d+$') { throw 'Could not determine isolated Redis port.' }
    $ready = $false
    for ($i=0; $i -lt 30; $i++) {
        $ping = docker exec $containerName redis-cli ping 2>$null
        if ($LASTEXITCODE -eq 0 -and $ping -eq 'PONG') { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Test Redis did not become ready.' }
    $evidence = Join-Path $backendDirectory 'target/redis-test-evidence'
    New-Item -ItemType Directory -Force -Path $evidence | Out-Null
    docker exec $containerName redis-server --version | Set-Content -Encoding utf8 (Join-Path $evidence 'redis-version.txt')
    mvn -B -ntp -P redis-it "-Dtest.redis.port=$port" '-Dtest.redis.isolated=true' verify 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'maven.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Redis regression suite failed; inspect target/*-reports and target/redis-test-evidence.' }
} finally {
    if ($created) { docker rm --force $containerName | Out-Null }
    Pop-Location
}
