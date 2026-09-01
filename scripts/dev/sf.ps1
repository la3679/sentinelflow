<#
.SYNOPSIS
    SentinelFlow developer command surface for Windows PowerShell.

.DESCRIPTION
    The PowerShell equivalent of the Makefile, for Windows machines without
    make. Target names and behaviour match the Makefile exactly.

        .\scripts\dev\sf.ps1 help
        .\scripts\dev\sf.ps1 up
        .\scripts\dev\sf.ps1 smoke

    Two targets - bootstrap and smoke - carry real logic. The Makefile delegates
    those to scripts/dev/bootstrap.sh and scripts/smoke/smoke.sh; this script
    implements them natively so that no bash installation is required.

.PARAMETER Target
    The target to run. Run with no argument, or with 'help', for the list.
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Target = 'help'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Join-Path takes exactly two paths in Windows PowerShell 5.1; the three-argument
# form is PowerShell 6+. This script targets 5.1, which is what ships with
# Windows and what a contributor is most likely to have.
$RepoRoot = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
$Web = Join-Path $RepoRoot 'apps\web'
$Api = Join-Path $RepoRoot 'apps\api'
$Scoring = Join-Path $RepoRoot 'apps\scoring'

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Write-Ok { param([string]$Message) Write-Host "  ok    $Message" -ForegroundColor Green }
function Write-Warn { param([string]$Message) Write-Host "  warn  $Message" -ForegroundColor Yellow }
function Write-Fail { param([string]$Message) Write-Host "  FAIL  $Message" -ForegroundColor Red }

# Windows PowerShell 5.1 turns every stderr line from a native executable into
# an ErrorRecord. With $ErrorActionPreference = 'Stop' that aborts the script the
# first time a perfectly healthy tool writes a warning - `docker info` does
# exactly this on a normal Docker Desktop install. These helpers relax the
# preference for the duration of a native call only, so cmdlet errors still
# terminate as intended.

# Run a native command, discard its output, and return its exit code.
function Invoke-NativeQuiet {
    param(
        [Parameter(Mandatory)][string]$Command,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command @Arguments 2>&1 | Out-Null
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

# Run a native command and return its stdout as trimmed text; empty on failure.
function Invoke-NativeCapture {
    <#
        Takes a working directory, like Invoke-Native, and for the same reason:
        `docker compose` resolves compose.yaml from the current directory, so a
        capture that ran wherever the caller happened to be would fail on a
        machine where the script was invoked by absolute path.

        The parameter was added after finding that Invoke-Seed and
        Invoke-ExportDataset had been calling this with $RepoRoot first since
        they were written. PowerShell binds that to $Command and then has
        nowhere to put the argument array, so both targets failed with "a
        positional parameter cannot be found" - a hard error under
        $ErrorActionPreference = 'Stop'. Fixed by making the shape match the
        call rather than the other way round, so the two helpers now differ only
        in whether they throw.
    #>
    param(
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$Command,
        [string[]]$Arguments = @()
    )
    Push-Location $WorkingDirectory
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Command @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        return ($output | Out-String).Trim()
    }
    finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
}

# Run a native command in a directory and throw on a non-zero exit code.
# PowerShell does not do this by itself: $ErrorActionPreference does not apply
# to native exit codes, so without the explicit check a failing build would be
# reported as a successful target.
function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$Command,
        [string[]]$Arguments = @()
    )
    Push-Location $WorkingDirectory
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            $ErrorActionPreference = $previous
            throw "$Command $($Arguments -join ' ') exited with code $LASTEXITCODE"
        }
    }
    finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
}

function Test-CommandExists {
    param([Parameter(Mandatory)][string]$Name)
    $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-DotEnv {
    $envFile = Join-Path $RepoRoot '.env'
    $values = @{}
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                $values[$Matches[1]] = $Matches[2].Trim()
            }
        }
    }
    return $values
}

# ---------------------------------------------------------------------------
# Targets
# ---------------------------------------------------------------------------

function Invoke-Help {
    Write-Host 'SentinelFlow - available targets:'
    Write-Host ''
    $targets = [ordered]@{
        'bootstrap'        = 'Verify prerequisites and generate local .env (idempotent)'
        'up'               = 'Start the full local stack and wait until every service is healthy'
        'down'             = 'Stop the stack, keeping durable volumes'
        'logs'             = 'Follow logs for every service'
        'ps'               = 'Show the status of every service'
        'reset-demo'       = 'DESTRUCTIVE - stop the stack and delete all local data volumes'
        'seed'             = 'Generate and load deterministic demo data'
        'export-dataset'   = 'Export the labelled training dataset (ADR-0010)'
        'train'            = 'Train, evaluate and register a risk model (ADR-0010)'
        'replay'           = 'Replay an operational scenario against the running stack'
        'build'            = 'Build every application'
        'test'             = 'Run every standard test suite'
        'test-web'         = 'Unit tests for the console'
        'test-api'         = 'Tests for the Spring Boot service'
        'test-scoring'     = 'Tests for the scoring service'
        'test-integration' = 'Testcontainers PostgreSQL suites (requires Docker)'
        'test-e2e'         = 'Playwright browser, accessibility and responsive checks'
        'lint'             = 'Lint every application'
        'format'           = 'Format everything in place'
        'format-check'     = 'Check formatting without changing anything'
        'security'         = 'Scan the repository for committed secrets'
        'smoke'            = 'Verify the running stack actually serves'
        'docs-check'       = 'Check documentation formatting, links, and placeholders'
        'contracts-check'  = 'Validate OpenAPI, AsyncAPI, and the event schemas'
        'bench'            = 'Benchmark the running stack and write docs/performance/BENCHMARK.md'
        'clean'            = 'Remove build output (keeps dependencies and Docker volumes)'
    }
    foreach ($key in $targets.Keys) {
        Write-Host ('  {0,-18} {1}' -f $key, $targets[$key])
    }
    Write-Host ''
    Write-Host 'On Linux, macOS, WSL or Git Bash the same targets are available as: make <target>'
}

# Every secret .env must carry, and how many random bytes each gets.
#
# One list rather than two, because the two this file used to keep - a check
# that knew about two secrets and a generator that wrote the same two - drifted
# behind the shell script as Phase 8 added SENTINELFLOW_INGEST_API_KEY. A
# Windows user following the README's PowerShell path on a fresh clone got an
# .env that compose refused outright: "required variable
# SENTINELFLOW_INGEST_API_KEY is missing a value". Found by the Phase 9
# clean-clone verification.
#
# 48 bytes for the two that have a minimum length: the API refuses a signing key
# under 32 characters and IngestionProperties refuses an ingest key under 32
# (ADR-0017 section 1), and base64 of 24 bytes is exactly 32 - generated right at
# a limit is generated wrong. The same reasoning, and the same numbers, as
# scripts/dev/bootstrap.sh.
$RequiredSecrets = [ordered] @{
    'POSTGRES_PASSWORD'                   = 24
    'GRAFANA_ADMIN_PASSWORD'              = 24
    'SENTINELFLOW_JWT_SECRET'             = 48
    'SENTINELFLOW_DEMO_OPERATOR_PASSWORD' = 24
    'SENTINELFLOW_INGEST_API_KEY'         = 48
}

function Invoke-Bootstrap {
    Write-Host 'SentinelFlow bootstrap'
    Write-Host ''
    Write-Host 'Prerequisites:'

    $failures = 0
    $warnings = 0

    if (Test-CommandExists 'docker') {
        if ((Invoke-NativeQuiet 'docker' @('info')) -eq 0) {
            Write-Ok "docker $(Invoke-NativeCapture $RepoRoot 'docker' @('version', '--format', '{{.Server.Version}}')) - daemon reachable"
        }
        else {
            Write-Fail 'docker is installed but the daemon is not reachable. Start Docker Desktop.'
            $failures++
        }
    }
    else {
        Write-Fail 'docker not found. Install Docker Desktop.'
        $failures++
    }

    if ((Invoke-NativeQuiet 'docker' @('compose', 'version')) -eq 0) {
        Write-Ok "docker compose $(Invoke-NativeCapture $RepoRoot 'docker' @('compose', 'version', '--short'))"
    }
    else {
        Write-Fail 'docker compose (v2 plugin) not found.'
        $failures++
    }

    foreach ($tool in @(
            @{ Name = 'bun'; Hint = 'See https://bun.sh - it is the only package manager this repository uses.' },
            @{ Name = 'uv'; Hint = 'See https://docs.astral.sh/uv - it provisions Python 3.13 for apps/scoring.' },
            @{ Name = 'git'; Hint = 'Install Git for Windows.' }
        )) {
        if (Test-CommandExists $tool.Name) {
            # Version output is inconsistent across these tools: bun prints
            # "1.4.0", git prints "git version 2.42.0", uv prints "uv 0.12.2
            # (hash date)". Extract the number so every line reads alike.
            $raw = (Invoke-NativeCapture $RepoRoot $tool.Name @('--version'))
            $version = if ($raw -match '(\d+\.\d+\.\d+\S*)') { $Matches[1] } else { $raw }
            Write-Ok "$($tool.Name) $version"
        }
        else {
            Write-Fail "$($tool.Name) not found. $($tool.Hint)"
            $failures++
        }
    }

    # Java is only needed to build apps/api outside a container. `up` builds it
    # in Docker, so a missing or wrong JDK is a warning rather than a failure.
    $javaExe = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    elseif (Test-CommandExists 'java') { 'java' } else { $null }

    if ($null -eq $javaExe) {
        Write-Warn "no JDK found. Only needed to build apps/api outside Docker; 'up' does not need it."
        $warnings++
    }
    else {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $versionLine = (& $javaExe -version 2>&1 | Select-Object -First 1) }
        finally { $ErrorActionPreference = $previousPreference }
        if ($versionLine -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -eq 25) { Write-Ok "java $($Matches[0] -replace 'version "', '')" }
            else {
                Write-Warn "java $major - apps/api needs 25. Point JAVA_HOME at a JDK 25 to run .\mvnw directly."
                $warnings++
            }
        }
    }

    Write-Host ''
    Write-Host 'Local configuration:'

    $envFile = Join-Path $RepoRoot '.env'
    $exampleFile = Join-Path $RepoRoot '.env.example'

    if (-not (Test-Path $exampleFile)) {
        Write-Fail '.env.example is missing - this is not a complete checkout.'
        $failures++
    }
    elseif (Test-Path $envFile) {
        $values = Get-DotEnv
        # @(...) around the pipeline: Where-Object returns a scalar rather than
        # an array when exactly one item matches, and Set-StrictMode then rejects
        # .Count on it.
        $missing = @($RequiredSecrets.Keys | Sort-Object |
            Where-Object { -not $values.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($values[$_]) })
        if ($missing.Count -gt 0) {
            Write-Fail ".env exists but these required secrets are empty: $($missing -join ', ')"
            Write-Host '        Fill them in, or delete .env and rerun to regenerate.'
            $failures++
        }
        else {
            Write-Ok '.env exists with every required secret set - left untouched'
        }
    }
    elseif ($failures -gt 0) {
        Write-Warn '.env not generated because a prerequisite failed.'
        $warnings++
    }
    else {
        # A cryptographic RNG, not Get-Random: Get-Random is seeded from a
        # predictable source and must never be used to generate a secret.
        function New-Secret {
            param([int] $Bytes = 24)
            $buffer = New-Object byte[] $Bytes
            $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
            try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
            return [Convert]::ToBase64String($buffer)
        }
        $content = Get-Content $exampleFile -Raw
        foreach ($name in $RequiredSecrets.Keys) {
            $secret = New-Secret -Bytes $RequiredSecrets[$name]
            $replaced = $content -replace "(?m)^$name=`$", "$name=$secret"
            if ($replaced -eq $content) {
                Write-Fail "could not set $name in .env - .env.example has no empty '$name=' line"
                $failures++
            }
            $content = $replaced
        }
        Set-Content -Path $envFile -Value $content -Encoding utf8 -NoNewline
        Write-Ok '.env generated from .env.example with fresh local secrets'
        Write-Host '        It is git-ignored. Do not commit it, and do not reuse these values anywhere else.'
    }

    Write-Host ''
    if ($failures -gt 0) {
        Write-Host "Bootstrap failed: $failures prerequisite(s) missing." -ForegroundColor Red
        exit 1
    }
    if ($warnings -gt 0) {
        Write-Host "Bootstrap complete with $warnings warning(s)." -ForegroundColor Yellow
    }
    else {
        Write-Host 'Bootstrap complete.' -ForegroundColor Green
    }
    Write-Host ''
    Write-Host 'Next:  .\scripts\dev\sf.ps1 up      start the stack'
    Write-Host '       .\scripts\dev\sf.ps1 smoke   verify it is serving'
}

function Invoke-Smoke {
    $values = Get-DotEnv
    function Get-Port { param($Key, $Default) if ($values.ContainsKey($Key) -and $values[$Key]) { $values[$Key] } else { $Default } }

    $apiPort = Get-Port 'API_PORT' '8080'
    $scoringPort = Get-Port 'SCORING_PORT' '8000'
    $webPort = Get-Port 'WEB_PORT' '5173'
    $promPort = Get-Port 'PROMETHEUS_PORT' '9090'
    $grafanaPort = Get-Port 'GRAFANA_PORT' '3000'
    $pgUser = Get-Port 'POSTGRES_USER' 'sentinelflow'
    $pgDb = Get-Port 'POSTGRES_DB' 'sentinelflow'

    $script:passed = 0
    $script:failed = 0
    function Pass { param($Message) Write-Host "  pass  $Message" -ForegroundColor Green; $script:passed++ }
    function Fail { param($Message, $Detail) Write-Host "  FAIL  $Message" -ForegroundColor Red; if ($Detail) { Write-Host "        $Detail" }; $script:failed++ }

    function Test-Endpoint {
        param($Label, $Url, [int]$ExpectStatus, $ExpectBody)
        # Windows PowerShell 5.1 has no -SkipHttpErrorCheck: a 4xx or 5xx throws.
        # The status code is recovered from the exception's response, because a
        # 404 is an expected result for the endpoints that must stay closed.
        $status = $null
        $content = ''
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 10 -UseBasicParsing
            $status = [int]$response.StatusCode
            # Invoke-WebRequest hands back .Content as a byte array whenever it
            # does not recognise the media type as text - which includes
            # Actuator's application/vnd.spring-boot.actuator.v3+json. Decoding
            # it explicitly keeps the body comparison working for every endpoint
            # rather than only the ones that answer text/plain.
            $content = if ($response.Content -is [byte[]]) {
                [System.Text.Encoding]::UTF8.GetString($response.Content)
            }
            else {
                [string]$response.Content
            }
        }
        catch [System.Net.WebException] {
            if ($null -ne $_.Exception.Response) {
                $status = [int]$_.Exception.Response.StatusCode
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
        }
        catch {
            Fail $Label 'request failed - is the stack up?'
            return
        }

        if ($null -eq $status) {
            Fail $Label 'request failed - is the stack up?'
            return
        }
        if ($status -ne $ExpectStatus) {
            Fail $Label "expected HTTP $ExpectStatus, got $status"
            return
        }
        if ($ExpectBody -and ($content -notlike "*$ExpectBody*")) {
            Fail $Label "response did not contain '$ExpectBody'"
            return
        }
        Pass $Label
    }

    Push-Location $RepoRoot
    try {
        Write-Host 'SentinelFlow smoke test'
        Write-Host ''
        Write-Host 'Container health:'
        foreach ($service in @('postgres', 'kafka', 'scoring', 'api', 'web', 'prometheus', 'grafana')) {
            $cid = Invoke-NativeCapture $RepoRoot 'docker' @('compose', 'ps', '-q', $service)
            if ([string]::IsNullOrWhiteSpace($cid)) {
                Fail $service 'not running - is the stack up?'
                continue
            }
            $state = Invoke-NativeCapture $RepoRoot 'docker' @('inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}', $cid)
            if ($state -eq 'healthy') { Pass "$service is healthy" } else { Fail $service "health status is '$state'" }
        }

        Write-Host ''
        Write-Host 'Service endpoints:'
        Test-Endpoint 'api readiness'          "http://127.0.0.1:$apiPort/actuator/health/readiness" 200 '"status":"UP"'
        Test-Endpoint 'api liveness'           "http://127.0.0.1:$apiPort/actuator/health/liveness"  200 '"status":"UP"'
        Test-Endpoint 'api metrics'            "http://127.0.0.1:$apiPort/actuator/prometheus"       200 'application="sentinelflow-api"'
        Test-Endpoint 'scoring readiness'      "http://127.0.0.1:$scoringPort/health/ready"          200 '"status":"UP"'
        Test-Endpoint 'scoring build identity' "http://127.0.0.1:$scoringPort/info"                  200 'sentinelflow-scoring'
        Test-Endpoint 'scoring metrics'        "http://127.0.0.1:$scoringPort/metrics"               200 'python_info'
        Test-Endpoint 'console shell'          "http://127.0.0.1:$webPort/"                          200 'SentinelFlow'
        Test-Endpoint 'console deep link'      "http://127.0.0.1:$webPort/alerts/ALT-0007"           200 'SentinelFlow'
        Test-Endpoint 'prometheus'             "http://127.0.0.1:$promPort/-/healthy"                200
        Test-Endpoint 'grafana'                "http://127.0.0.1:$grafanaPort/api/health"            200 '"database"'

        Write-Host ''
        Write-Host 'Closed by design:'
        # 401 rather than 404 since ADR-0012: the filter chain refuses an
        # unauthenticated request before the actuator decides whether the
        # endpoint exists. Neither serves, which is what this checks.
        Test-Endpoint 'api /actuator/env is closed'   "http://127.0.0.1:$apiPort/actuator/env"   401
        Test-Endpoint 'api /actuator/beans is closed' "http://127.0.0.1:$apiPort/actuator/beans" 401

        Write-Host ''
        Write-Host 'Wiring:'
        try {
            $targets = Invoke-RestMethod -Uri "http://127.0.0.1:$promPort/api/v1/targets?state=active" -TimeoutSec 10
            foreach ($job in @('sentinelflow-api', 'sentinelflow-scoring')) {
                $target = $targets.data.activeTargets | Where-Object { $_.labels.job -eq $job } | Select-Object -First 1
                if ($null -eq $target) { Fail "prometheus scrapes $job" 'no such active target' }
                elseif ($target.health -ne 'up') { Fail "prometheus scrapes $job" "target is $($target.health)" }
                else { Pass "prometheus scrapes $job" }
            }
        }
        catch {
            Fail 'prometheus targets' 'could not query the targets API'
        }

        if ((Invoke-NativeQuiet 'docker' @('compose', 'exec', '-T', 'postgres', 'pg_isready', '-U', $pgUser, '-d', $pgDb)) -eq 0) {
            Pass "postgres accepts connections to $pgDb"
        }
        else { Fail 'postgres accepts connections' 'pg_isready failed' }

        $topic = "sentinelflow-smoke-$PID"
        $kafkaTopics = @('compose', 'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092')
        if ((Invoke-NativeQuiet 'docker' ($kafkaTopics + @('--create', '--topic', $topic, '--partitions', '1', '--replication-factor', '1'))) -eq 0) {
            Invoke-NativeQuiet 'docker' ($kafkaTopics + @('--delete', '--topic', $topic)) | Out-Null
            Pass 'kafka accepts topic creation and deletion'
        }
        else {
            Fail 'kafka accepts topic creation' 'topic admin request failed'
        }

        Write-Host ''
        if ($script:failed -gt 0) {
            Write-Host "$($script:passed) passed, $($script:failed) FAILED." -ForegroundColor Red
            exit 1
        }
        Write-Host "$($script:passed) passed, 0 failed." -ForegroundColor Green
    }
    finally {
        Pop-Location
    }
}

function Invoke-Seed {
    <#
        The Makefile's `seed` target, expressed natively. The two are changed
        together, every time: a Makefile edit without the matching change here
        is a defect, because this machine has no make and this is the only way
        the target is ever exercised on it.

        Seeding runs at API startup behind SENTINELFLOW_SEED_ENABLED, so this
        recreates that one service with the flag set and then recreates it again
        without. Two recreates rather than one, deliberately: leaving the flag on
        would make every later restart re-run the seed.

        Both loaders are idempotent, so running this twice is a no-op rather
        than a doubled dataset.
    #>
    $profileName = if ($env:SENTINELFLOW_SEED_PROFILE) { $env:SENTINELFLOW_SEED_PROFILE } else { 'DEMO' }
    $seedValue = if ($env:SENTINELFLOW_SEED) { $env:SENTINELFLOW_SEED } else { '20260826' }
    Write-Host "Seeding with profile $profileName, seed $seedValue."

    $previous = $env:SENTINELFLOW_SEED_ENABLED
    try {
        $env:SENTINELFLOW_SEED_ENABLED = 'true'
        Invoke-Native $RepoRoot 'docker' @(
            'compose', 'up', '-d', '--force-recreate', '--wait', '--wait-timeout', '300', 'api')
    }
    finally {
        # Restored even if the recreate failed, so a failed seed cannot leave
        # the flag set for the next person's `up`.
        $env:SENTINELFLOW_SEED_ENABLED = $previous
    }

    # Best effort: the manifest lines are the useful output, and a stack with no
    # matching line is not a failure worth stopping on.
    $logs = Invoke-NativeCapture $RepoRoot 'docker' @('compose', 'logs', 'api')
    $logs -split "`n" |
        Select-String -Pattern 'Seed complete|Scenario load complete|seed skipped|load skipped' |
        ForEach-Object { Write-Host $_.Line.Trim() }

    Write-Host 'Returning the API to its unseeded configuration.'
    Invoke-Native $RepoRoot 'docker' @(
        'compose', 'up', '-d', '--force-recreate', '--wait', '--wait-timeout', '300', 'api')
    Write-Host 'Done. Re-running this is a no-op: both loaders are idempotent.'
}

function Invoke-ExportDataset {
    <#
        The Makefile's `export-dataset` target, expressed natively. The two are
        changed together, every time, for the reason Invoke-Seed records: this
        machine has no make, so this is the only way the target is ever
        exercised on it.

        The export runs at API startup behind SENTINELFLOW_SCORING_EXPORT_ENABLED,
        so this recreates that one service with the flag set and then recreates
        it again without - two recreates, so a later restart does not re-export.

        The output directory is created here rather than left to the bind mount.
        Docker creates a missing bind-mount source itself, but as root and
        outside the repository's own conventions; making it first keeps it a
        plain directory the current user owns.
    #>
    $profileName = if ($env:SENTINELFLOW_SEED_PROFILE) { $env:SENTINELFLOW_SEED_PROFILE } else { 'DEMO' }
    $seedValue = if ($env:SENTINELFLOW_SEED) { $env:SENTINELFLOW_SEED } else { '20260826' }
    Write-Host "Exporting the labelled training dataset for seed $seedValue, profile $profileName."

    $outputDirectory = Join-Path $RepoRoot 'data/generated/training'
    if (-not (Test-Path $outputDirectory)) {
        New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    }

    $previous = $env:SENTINELFLOW_SCORING_EXPORT_ENABLED
    try {
        $env:SENTINELFLOW_SCORING_EXPORT_ENABLED = 'true'
        Invoke-Native $RepoRoot 'docker' @(
            'compose', 'up', '-d', '--force-recreate', '--wait', '--wait-timeout', '300', 'api')
    }
    finally {
        # Restored even if the recreate failed, so a failed export cannot leave
        # the flag set for the next person's `up`.
        $env:SENTINELFLOW_SCORING_EXPORT_ENABLED = $previous
    }

    # Best effort: the manifest line is the useful output, and a stack with no
    # matching line is not a failure worth stopping on.
    $logs = Invoke-NativeCapture $RepoRoot 'docker' @('compose', 'logs', 'api')
    $logs -split "`n" |
        Select-String -Pattern 'Training export complete|had no stored row' |
        ForEach-Object { Write-Host $_.Line.Trim() }

    Write-Host 'Returning the API to its normal configuration.'
    Invoke-Native $RepoRoot 'docker' @(
        'compose', 'up', '-d', '--force-recreate', '--wait', '--wait-timeout', '300', 'api')
    Write-Host 'Written to data/generated/training/ - git-ignored, regenerate rather than commit.'
}

function Invoke-Train {
    <#
        The Makefile's `train` target, expressed natively. The two are changed
        together, every time, for the reason Invoke-Seed records.

        Training is an explicit offline command and never an API side effect
        (section 12.6), so this runs the module directly rather than going
        through the stack. It exits non-zero when nothing is promoted, which is a
        pre-registered outcome of ADR-0010 section 5 rather than a failure - the
        command says which it was.
    #>
    Write-Host 'Training from data/generated/training. Offline, never an API side effect.'
    Invoke-Native $Scoring 'uv' @('run', 'python', '-m', 'sentinelflow_scoring.training')
}

function Invoke-Replay {
    <#
        The Makefile's `replay` target, expressed natively. The two are changed
        together, every time, for the reason Invoke-Seed records - and this one
        more than most, because the reference Windows machine has no make and
        `scripts/dev/replay.sh` would need a bash it may not have.

        Replays the operational scenarios from section 8.3, which nothing else
        produces: a temporary scoring-service outage, and a malformed event
        reaching the dead-letter path. The transaction *shapes* are generated by
        `seed` and replaying them here would be a second implementation of
        something that exists.

        Everything involved is synthetic. Nothing this prints is a claim about
        any real system.
    #>
    param([string]$Scenario = 'all')

    $apiBase = if ($env:SENTINELFLOW_API_BASE) { $env:SENTINELFLOW_API_BASE } else { 'http://localhost:8080' }
    # One run id per invocation, so idempotency keys never collide with an
    # earlier run against the same database. Replaying twice must post twice,
    # not silently return the first run's transactions.
    $runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + $PID
    # Bounded, deliberately. An unbounded replay is a denial-of-service
    # primitive against a developer's own laptop.
    $perPhase = 4

    Write-Host ''
    Write-Host '== Checking the stack'
    $running = Invoke-NativeCapture $RepoRoot 'docker' @('compose', 'ps', '--status', 'running', '--services')
    # Split and trim rather than a multiline regex. Invoke-NativeCapture joins
    # with the platform's line ending, so `^api$` never matches: the anchor sits
    # after the carriage return that Windows leaves on the line.
    $runningServices = @($running -split "`n" | ForEach-Object { $_.Trim() })
    if ($runningServices -notcontains 'api') {
        Write-Fail "the api service is not running. Start the stack with 'up' first."
        exit 1
    }
    $states = Invoke-NativeCapture $RepoRoot 'docker' @('compose', 'ps', '--format', '{{.Service}} {{.Status}}')
    if (@($states -split "`n" | ForEach-Object { $_.Trim() }) -match '^api .*Restarting') {
        # The crash loop worth naming: an export or seed flag left set makes the
        # service fail startup, restart, and fail again, and every symptom after
        # that is a symptom of the wrong thing.
        Write-Fail 'the api container is restarting. See PROJECT_STATE.md on the export flag.'
        exit 1
    }
    try {
        Invoke-RestMethod -Uri "$apiBase/actuator/health/readiness" -TimeoutSec 10 | Out-Null
    }
    catch {
        Write-Fail "the api is not ready at $apiBase"
        exit 1
    }
    Write-Ok "api is up and ready at $apiBase"

    # Resolved here rather than discovered as a 401 on the first post, which is
    # the failure that sends somebody to debug the pipeline instead.
    $ingestApiKey = Get-IngestApiKey $RepoRoot
    if (-not $ingestApiKey) {
        Write-Fail "SENTINELFLOW_INGEST_API_KEY is not set and was not found in .env. Run 'bootstrap'."
        exit 1
    }

    switch ($Scenario) {
        'scoring-outage' { Invoke-ReplayScoringOutage $apiBase $runId $perPhase $ingestApiKey }
        'poison-event' { Invoke-ReplayPoisonEvent $runId $apiBase }
        'all' {
            Invoke-ReplayScoringOutage $apiBase $runId $perPhase $ingestApiKey
            Invoke-ReplayPoisonEvent $runId $apiBase
        }
        default {
            Write-Fail "unknown scenario '$Scenario'. Choose scoring-outage, poison-event, or all."
            exit 1
        }
    }

    Write-Host ''
    Write-Host '== Done'
    Write-Host '   Every transaction, account and merchant involved is synthetic.'
}

# A read-only query against the demo database. Reading, never editing: a replay
# that edited rows behind the pipeline would be demonstrating the script rather
# than the system.
function Invoke-ReplayQuery {
    param([Parameter(Mandatory)][string]$Sql)
    $user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'sentinelflow' }
    $database = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'sentinelflow' }
    return (Invoke-NativeCapture $RepoRoot 'docker' @(
            'compose', 'exec', '-T', 'postgres', 'psql', '-U', $user, '-d', $database, '-At', '-c', $Sql))
}

function Get-IngestApiKey {
    <#
        .SYNOPSIS
        The ingestion credential POST /api/v1/transactions requires (ADR-0017 section 1).

        .DESCRIPTION
        The environment first, then the git-ignored .env, because that is where
        `make bootstrap` generates it. Never defaulted and never invented: a
        replay posting a made-up key fails with a 401 that reads like a broken
        stack rather than a missing variable.
    #>
    param([Parameter(Mandatory)][string]$RepoRoot)

    if ($env:SENTINELFLOW_INGEST_API_KEY) { return $env:SENTINELFLOW_INGEST_API_KEY }

    $envFile = Join-Path $RepoRoot '.env'
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^SENTINELFLOW_INGEST_API_KEY=' } | Select-Object -First 1
        if ($line) { return $line.Substring($line.IndexOf('=') + 1) }
    }
    return ''
}

function Invoke-ReplayPost {
    param(
        [Parameter(Mandatory)][string]$ApiBase,
        [Parameter(Mandatory)][string]$Account,
        [Parameter(Mandatory)][string]$Merchant,
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][string]$Amount,
        [Parameter(Mandatory)][string]$ApiKey
    )
    $body = @{
        idempotencyKey    = $Key
        accountReference  = $Account
        merchantReference = $Merchant
        type              = 'PURCHASE'
        channel           = 'CARD_NOT_PRESENT'
        amount            = @{ value = $Amount; currency = 'GBP' }
        originCountry     = 'GB'
        occurredAt        = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    } | ConvertTo-Json -Depth 4

    $response = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/transactions" `
        -ContentType 'application/json' -Headers @{ 'X-API-Key' = $ApiKey } -Body $body -TimeoutSec 30
    return $response.transactionId
}

function Invoke-ReplayPhase {
    param(
        [Parameter(Mandatory)][string]$ApiBase,
        [Parameter(Mandatory)][string]$Account,
        [Parameter(Mandatory)][string]$Merchant,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][int]$Count,
        [Parameter(Mandatory)][string]$RunId,
        [Parameter(Mandatory)][string]$ApiKey
    )
    $ids = @()
    for ($index = 1; $index -le $Count; $index++) {
        $ids += Invoke-ReplayPost $ApiBase $Account $Merchant "replay-$RunId-$Label-$index" "$((120 + $index * 37)).00" $ApiKey
    }
    return $ids
}

# The outbox publishes on a poll interval and the consumer then scores, so
# nothing here is instant. Bounded, and it says what it was waiting for.
function Wait-ReplayAssessments {
    param([Parameter(Mandatory)][string[]]$Ids)
    $list = ($Ids | ForEach-Object { "'$_'" }) -join ','
    for ($waited = 0; $waited -lt 60; $waited += 2) {
        $count = Invoke-ReplayQuery "SELECT count(*) FROM risk_assessments WHERE transaction_id IN ($list)"
        if ([int]$count -eq $Ids.Count) { return }
        Start-Sleep -Seconds 2
    }
    Write-Fail "not every transaction was assessed within 60s"
    exit 1
}

function Show-ReplayAssessments {
    param([Parameter(Mandatory)][string[]]$Ids)
    $list = ($Ids | ForEach-Object { "'$_'" }) -join ','
    $rows = Invoke-ReplayQuery @"
SELECT '   ' || substr(t.transaction_reference, 1, 12)
       || '  final=' || rpad(r.final_score::text, 6, ' ')
       || '  band=' || rpad(r.risk_band, 8, ' ')
       || '  ' || CASE WHEN r.degraded
                       THEN 'DEGRADED (rules only, model unavailable)'
                       ELSE 'scored, model ' || r.model_version END
  FROM risk_assessments r
  JOIN transactions t ON t.id = r.transaction_id
 WHERE r.transaction_id IN ($list)
 ORDER BY t.transaction_reference
"@
    # Re-indent rather than print what psql returned. Invoke-NativeCapture trims
    # the whole captured string, which eats the leading spaces on the first row
    # only - so the first line of every block came out flush left and the rest
    # did not.
    $rows -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { Write-Host ('   ' + $_.Trim()) }
}

function Invoke-ReplayScoringOutage {
    <#
        ADR-0008 section 3 says an unreachable scoring service degrades an
        assessment rather than losing it or rejecting the transaction. That is a
        claim about behaviour under failure, and the only honest way to show it
        is to cause the failure.
    #>
    param([string]$ApiBase, [string]$RunId, [int]$PerPhase, [string]$ApiKey)

    $account = Invoke-ReplayQuery 'SELECT account_reference FROM accounts ORDER BY account_reference LIMIT 1'
    $merchant = Invoke-ReplayQuery 'SELECT merchant_reference FROM merchants ORDER BY merchant_reference LIMIT 1'
    if (-not $account -or -not $merchant) {
        Write-Fail "no seeded account or merchant found. Run 'seed' first."
        exit 1
    }

    Write-Host ''
    Write-Host '== Scenario: temporary scoring-service outage'
    Write-Host '   Stopping the scoring service. Ingestion is unaffected by design:'
    Write-Host '   the transaction is accepted, committed and published before scoring runs.'
    Invoke-Native $RepoRoot 'docker' @('compose', 'stop', 'scoring') | Out-Null

    $degraded = Invoke-ReplayPhase $ApiBase $account $merchant 'outage' $PerPhase $RunId $ApiKey
    Write-Host "   Posted $PerPhase transactions with scoring down."
    Wait-ReplayAssessments $degraded
    Show-ReplayAssessments $degraded

    Write-Host ''
    Write-Host '== Restarting the scoring service'
    Invoke-Native $RepoRoot 'docker' @(
        'compose', 'up', '-d', '--wait', '--wait-timeout', '180', 'scoring') | Out-Null
    # The breaker stays open for its configured duration after the service is
    # healthy again. Posting inside that window would produce degraded
    # assessments and look like the restart had not worked.
    Write-Host "   Waiting for the circuit breaker's open window to elapse before the next call."
    Start-Sleep -Seconds 32

    $scored = Invoke-ReplayPhase $ApiBase $account $merchant 'recovered' $PerPhase $RunId $ApiKey
    Write-Host "   Posted $PerPhase transactions with scoring back up."
    Wait-ReplayAssessments $scored
    Show-ReplayAssessments $scored

    Write-Host ''
    Write-Host '== What that showed'
    Write-Host '   No transaction was rejected and no assessment was lost.'
    Write-Host '   A degraded assessment is a real answer from the rule baseline, and it'
    Write-Host '   says so: degraded = true, no model score, no model version.'
}

function Get-ReplayDlqDepth {
    <#
        The dead-letter topic's end offsets, summed.

        kafka-get-offsets.sh rather than kafka-run-class.sh with
        kafka.tools.GetOffsetShell: the class moved package in Kafka 4 and the
        wrapper script is the supported entry point. Found by running this
        against the 4.2.1 broker the stack actually uses, where the old form
        fails with ClassNotFoundException.
    #>
    $output = Invoke-NativeCapture $RepoRoot 'docker' @(
        'compose', 'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-get-offsets.sh',
        '--bootstrap-server', 'localhost:9092', '--topic', 'transaction.processing.dlq.v1')
    if (-not $output) {
        Write-Fail "could not read the dead-letter topic's offsets"
        exit 1
    }
    $total = 0
    foreach ($line in ($output -split "`n")) {
        $parts = $line.Trim() -split ':'
        if ($parts.Count -ge 3) { $total += [int]$parts[2] }
    }
    return $total
}

# The counter the consumer increments for a record it can neither handle nor
# dead-letter. Read from the API's own Prometheus endpoint rather than inferred.
function Get-ReplayUndeliverableCount {
    param([Parameter(Mandatory)][string]$ApiBase)
    $metrics = Invoke-RestMethod -Uri "$ApiBase/actuator/prometheus" -TimeoutSec 10
    $total = 0.0
    foreach ($line in ($metrics -split "`n")) {
        if ($line -match '^sentinelflow_consumer_undeliverable_total\S*\s+(\S+)$') {
            $total += [double]$Matches[1]
        }
    }
    return $total
}

function New-ReplayUuid {
    return [guid]::NewGuid().ToString()
}

function Publish-ReplayRecord {
    param([Parameter(Mandatory)][string]$RunId, [Parameter(Mandatory)][string]$Record)
    "replay-${RunId}:$Record" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server localhost:9092 --topic transaction.created.v1 `
        --property 'parse.key=true' --property 'key.separator=:' | Out-Null
}

function Invoke-ReplayPoisonEvent {
    <#
        ADR-0006 section 4 classifies a message that will fail identically on
        every delivery as non-retryable, so it goes straight to the dead-letter
        topic rather than blocking its partition. Nothing that arrives through
        the API can produce one, which is why these are published directly.

        TWO RECORDS, BECAUSE THERE ARE TWO OUTCOMES AND ONLY ONE IS A DEAD
        LETTER. The first is a well-formed envelope at a schema version this
        build does not read, and it is dead-lettered. The second is not a
        readable envelope at all, and it is deliberately NOT: the DLQ schema
        requires a valid envelope and ADR-0006 section 4 forbids copying
        unsanitised content onto an operational topic, so it is counted and
        logged with its coordinates instead. Showing only the first would leave
        an operator believing everything unparseable reaches the dead-letter
        topic.
    #>
    param([string]$RunId, [string]$ApiBase)

    Write-Host ''
    Write-Host '== Scenario: a malformed event reaching the dead-letter path'

    $before = Get-ReplayDlqDepth
    $undeliverableBefore = Get-ReplayUndeliverableCount $ApiBase

    Write-Host '   Publishing a well-formed envelope at a schema version this build does not read.'
    $envelope = @{
        eventId       = New-ReplayUuid
        eventType     = 'transaction.created'
        schemaVersion = 99
        occurredAt    = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        producer      = 'sentinelflow-api'
        correlationId = New-ReplayUuid
        traceId       = $null
        aggregateType = 'transaction'
        aggregateId   = New-ReplayUuid
        payload       = @{}
    } | ConvertTo-Json -Depth 4 -Compress
    Publish-ReplayRecord $RunId $envelope

    Write-Host '   Waiting for the dead-letter topic to record it.'
    $after = $before
    for ($waited = 0; $waited -lt 40; $waited += 2) {
        $after = Get-ReplayDlqDepth
        if ($after -gt $before) { break }
        Start-Sleep -Seconds 2
    }
    if ($after -le $before) {
        Write-Fail 'no dead-letter record appeared within 40s'
        exit 1
    }
    Write-Host "   Dead-letter topic went from $before to $after records."

    Write-Host ''
    Write-Host '   Publishing a record that is not a readable envelope at all.'
    Publish-ReplayRecord $RunId 'this is not json'

    Write-Host '   Waiting for the undeliverable counter to move.'
    $undeliverableAfter = $undeliverableBefore
    for ($waited = 0; $waited -lt 40; $waited += 2) {
        $undeliverableAfter = Get-ReplayUndeliverableCount $ApiBase
        if ($undeliverableAfter -ne $undeliverableBefore) { break }
        Start-Sleep -Seconds 2
    }
    if ($undeliverableAfter -eq $undeliverableBefore) {
        Write-Fail 'the undeliverable counter did not move within 40s'
        exit 1
    }
    Write-Host "   sentinelflow_consumer_undeliverable_total went from $undeliverableBefore to $undeliverableAfter."

    if ((Get-ReplayDlqDepth) -gt $after) {
        Write-Fail 'an unreadable record reached the dead-letter topic, which ADR-0006 section 4 forbids'
        exit 1
    }

    Write-Host ''
    Write-Host '== What that showed'
    Write-Host '   The first record was classified once, not retried five times, and the'
    Write-Host '   partition behind it kept moving. Its dead-letter record carries the'
    Write-Host '   failure class, the source topic and the offset.'
    Write-Host '   The second was NOT copied onto the dead-letter topic, because the DLQ'
    Write-Host '   schema requires a valid envelope and unsanitised content may not be'
    Write-Host '   republished. It is counted and logged with its coordinates instead.'
}

function Invoke-ResetDemo {
    Write-Host 'This deletes the PostgreSQL, Kafka, Prometheus and Grafana volumes.'
    Write-Host 'All local demo data will be lost. This cannot be undone.'
    $reply = Read-Host "Type 'reset' to confirm"
    if ($reply -ne 'reset') {
        Write-Host 'Aborted. Nothing was deleted.'
        exit 1
    }
    Invoke-Native $RepoRoot 'docker' @('compose', 'down', '-v')
    Write-Host "Volumes deleted. Run '.\scripts\dev\sf.ps1 up' for a clean stack."
}

function Invoke-NotImplemented {
    param($Name, $Phase, $What)
    Write-Host "$Name is not implemented yet."
    Write-Host "$What is delivered in $Phase."
    Write-Host 'See docs/planning/IMPLEMENTATION_PLAN.md.'
    exit 1
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

switch ($Target) {
    'help' { Invoke-Help }
    'bootstrap' { Invoke-Bootstrap }
    'up' { Invoke-Native $RepoRoot 'docker' @('compose', 'up', '-d', '--build', '--wait', '--wait-timeout', '420') }
    'down' { Invoke-Native $RepoRoot 'docker' @('compose', 'down') }
    'logs' { Invoke-Native $RepoRoot 'docker' @('compose', 'logs', '-f') }
    'ps' { Invoke-Native $RepoRoot 'docker' @('compose', 'ps') }
    'reset-demo' { Invoke-ResetDemo }
    'seed' { Invoke-Seed }
    'export-dataset' { Invoke-ExportDataset }
    'train' { Invoke-Train }
    'replay' { Invoke-Replay $(if ($env:SCENARIO) { $env:SCENARIO } else { 'all' }) }

    'build' {
        Invoke-Native $RepoRoot 'bun' @('install', '--frozen-lockfile')
        Invoke-Native $Web 'bun' @('run', 'build')
        Invoke-Native $Api '.\mvnw.cmd' @('-B', '-DskipTests', 'package')
        Invoke-Native $Scoring 'uv' @('sync', '--frozen')
    }
    'test' {
        Invoke-Native $Web 'bun' @('run', 'test')
        Invoke-Native $Api '.\mvnw.cmd' @('-B', 'verify')
        Invoke-Native $Scoring 'uv' @('run', 'pytest')
    }
    'test-web' { Invoke-Native $Web 'bun' @('run', 'test') }
    'test-api' { Invoke-Native $Api '.\mvnw.cmd' @('-B', 'verify', '-DskipITs', '-Djacoco.skip=true') }
    'test-scoring' { Invoke-Native $Scoring 'uv' @('run', 'pytest') }
    'test-integration' { Invoke-Native $Api '.\mvnw.cmd' @('-B', 'verify', '-DskipUnitTests=true') }
    'test-e2e' {
        Invoke-Native $Web 'bun' @('run', 'build')
        Invoke-Native $Web 'bun' @('run', 'test:e2e')
    }

    'lint' {
        Invoke-Native $Web 'bun' @('run', 'lint')
        Invoke-Native $Scoring 'uv' @('run', 'ruff', 'check', '.')
        Invoke-Native $Scoring 'uv' @('run', 'mypy')
        Invoke-Native $Api '.\mvnw.cmd' @('-B', '-q', 'spotless:check')
    }
    'format' {
        Invoke-Native $RepoRoot 'bun' @('run', 'format')
        Invoke-Native $Scoring 'uv' @('run', 'ruff', 'format', '.')
        Invoke-Native $Api '.\mvnw.cmd' @('-B', '-q', 'spotless:apply')
    }
    'format-check' {
        Invoke-Native $RepoRoot 'bun' @('run', 'format:check')
        Invoke-Native $Scoring 'uv' @('run', 'ruff', 'format', '--check', '.')
        Invoke-Native $Api '.\mvnw.cmd' @('-B', '-q', 'spotless:check')
    }
    'security' {
        if (Test-CommandExists 'gitleaks') {
            Invoke-Native $RepoRoot 'gitleaks' @('detect', '--source', '.', '--redact', '--verbose')
        }
        else {
            Write-Host 'gitleaks is not installed locally; running it through Docker.'
            Invoke-Native $RepoRoot 'docker' @('run', '--rm', '-v', "${RepoRoot}:/repo", 'zricethezav/gitleaks:latest', 'detect', '--source', '/repo', '--redact', '--verbose')
        }
    }
    'smoke' { Invoke-Smoke }
    'docs-check' {
        Invoke-Native $RepoRoot 'bun' @('run', 'format:check')
        Invoke-Native $RepoRoot 'bun' @('scripts/dev/check-docs.mjs')
    }
    'contracts-check' { Invoke-Native $RepoRoot 'bun' @('scripts/dev/check-contracts.mjs') }

    # The Makefile target sources .env before running the driver. PowerShell has
    # no `set -a`, so the same two variables are read out of the file and put on
    # the process instead: ingestion needs its key (ADR-0017) and the read
    # measurements need an operator token.
    'bench' {
        foreach ($name in @('SENTINELFLOW_INGEST_API_KEY', 'SENTINELFLOW_DEMO_OPERATOR_PASSWORD')) {
            if (-not (Get-Item "env:$name" -ErrorAction SilentlyContinue)) {
                $envFile = Join-Path $RepoRoot '.env'
                if (Test-Path $envFile) {
                    $line = Get-Content $envFile | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
                    if ($line) { Set-Item "env:$name" $line.Substring($line.IndexOf('=') + 1) }
                }
            }
        }
        Invoke-Native $RepoRoot 'uv' @(
            'run', '--no-project', '--python', '3.13', 'scripts/bench/benchmark.py')
    }

    'clean' {
        foreach ($path in @(
                "$Web\dist", "$Web\coverage", "$Web\test-results", "$Web\playwright-report",
                "$Api\target",
                "$Scoring\.pytest_cache", "$Scoring\.mypy_cache", "$Scoring\.ruff_cache", "$Scoring\.coverage"
            )) {
            if (Test-Path $path) { Remove-Item -Recurse -Force $path }
        }
        Write-Host 'Build output removed.'
    }

    default {
        Write-Host "Unknown target '$Target'." -ForegroundColor Red
        Write-Host ''
        Invoke-Help
        exit 1
    }
}

# Reaching here means the target succeeded. Exit explicitly, because otherwise
# PowerShell propagates $LASTEXITCODE from whatever native command ran last -
# which can be non-zero even when the target did exactly what it should.
exit 0
