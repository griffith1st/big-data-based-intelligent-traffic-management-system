[CmdletBinding()]
param(
    [ValidatePattern('^itms-(verify|completion-tests)[a-z0-9-]*$')]
    [string]$ProjectName = 'itms-verify',
    [ValidateRange(1024,65535)][int]$PostgresPort = 55432,
    [ValidateRange(1024,65535)][int]$RedisPort = 16379,
    [ValidateRange(1024,65535)][int]$KafkaPort = 19092,
    [string]$WslDistribution,
    [switch]$SkipInstall,
    [switch]$KeepDependencies
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$summaryPath = Join-Path $repoRoot 'output/verification/summary.json'
New-Item -ItemType Directory -Force (Split-Path -Parent $summaryPath) | Out-Null
@{status='running'; timestamp=(Get-Date -Format o)} | ConvertTo-Json | Set-Content -Encoding UTF8 $summaryPath
$savedEnvironment = @{}
foreach ($name in @('ITMS_TEST_POSTGRES_PORT','ITMS_TEST_REDIS_PORT','ITMS_TEST_KAFKA_PORT','CI','MAVEN_OPTS','NODE_OPTIONS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}
$composeArgs = @('compose','-f',(Join-Path $repoRoot 'compose.test.yml'),'-p',$ProjectName)
$started = $false
Push-Location $repoRoot
try {
    $dependencyProgram = if ($WslDistribution) { 'wsl' } else { 'docker' }
    foreach ($program in @($dependencyProgram,'mvn','npm','python')) { Get-Command $program -ErrorAction Stop | Out-Null }
    $env:ITMS_TEST_POSTGRES_PORT = "$PostgresPort"
    $env:ITMS_TEST_REDIS_PORT = "$RedisPort"
    $env:ITMS_TEST_KAFKA_PORT = "$KafkaPort"
    $env:CI = 'true'
    if (-not $env:MAVEN_OPTS) { $env:MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=4' }
    if (-not $env:NODE_OPTIONS) { $env:NODE_OPTIONS = '--max-old-space-size=1536' }
    if ($WslDistribution) {
        # Explicitly selected, already running isolated dependencies; this script does not own their lifecycle.
        Invoke-Checked python @('scripts/verify-migrations.py','--native-wsl',$WslDistribution,'--postgres-port',"$PostgresPort")
    } else {
        $started = $true
        Invoke-Checked docker ($composeArgs + @('up','-d','--wait','--wait-timeout','180'))
        Invoke-Checked python @('scripts/verify-migrations.py','--project',$ProjectName)
    }
    Invoke-Checked mvn @('-B','-ntp','-f','backend/pom.xml','-Ditms.build.directory=target-completion',
        '-Pconcurrency-it',"-Dtest.postgres.port=$PostgresPort",'-Dtest.postgres.isolated=true',
        "-Dtest.redis.port=$RedisPort",'-Dtest.redis.isolated=true',
        "-Dtest.kafka.port=$KafkaPort",'-Dtest.kafka.isolated=true','clean','verify')
    if (-not $SkipInstall) {
        Invoke-Checked npm @('--prefix','frontend','ci','--legacy-peer-deps','--no-audit','--no-fund')
    }
    Invoke-Checked npm @('--prefix','frontend','test','--','--watchAll=false','--runInBand')
    Invoke-Checked npm @('--prefix','frontend','run','build')
    $totals = @{tests=0; failures=0; errors=0; skipped=0}
    foreach ($folder in @('surefire-reports','failsafe-reports')) {
        foreach ($file in Get-ChildItem "backend/target-completion/$folder/TEST-*.xml") {
            [xml]$report = Get-Content -Raw $file.FullName
            foreach ($key in @('tests','failures','errors','skipped')) {
                $totals[$key] += [int]$report.testsuite.GetAttribute($key)
            }
        }
    }
    $summary = [ordered]@{status='passed'; timestamp=(Get-Date -Format o); dependencies=$dependencyProgram; backend=$totals; migrations='passed'; frontend='tests and build passed'}
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $summaryPath
    Write-Output ($summary | ConvertTo-Json -Depth 4)
} catch {
    @{status='failed'; timestamp=(Get-Date -Format o); message=$_.Exception.Message} |
        ConvertTo-Json | Set-Content -Encoding UTF8 $summaryPath
    throw
} finally {
    if ($started -and -not $KeepDependencies) {
        # Only the explicitly named test project and its disposable test volumes.
        & docker @composeArgs down -v
    }
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    Pop-Location
}
