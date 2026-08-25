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
    param(
        [Parameter(Mandatory)][string]$Command,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Command @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        return ($output | Out-String).Trim()
    }
    finally {
        $ErrorActionPreference = $previous
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
        'seed'             = '(Phase 4) Generate and load deterministic demo data'
        'replay'           = '(Phase 4) Replay the default synthetic scenario'
        'build'            = 'Build every application'
        'test'             = 'Run every standard test suite'
        'test-web'         = 'Unit tests for the console'
        'test-api'         = 'Tests for the Spring Boot service'
        'test-scoring'     = 'Tests for the scoring service'
        'test-integration' = '(Phase 2) Testcontainers PostgreSQL and Kafka suites'
        'test-e2e'         = 'Playwright browser, accessibility and responsive checks'
        'lint'             = 'Lint every application'
        'format'           = 'Format everything in place'
        'format-check'     = 'Check formatting without changing anything'
        'security'         = 'Scan the repository for committed secrets'
        'smoke'            = 'Verify the running stack actually serves'
        'docs-check'       = 'Check documentation formatting, links, and placeholders'
        'contracts-check'  = 'Validate OpenAPI, AsyncAPI, and the event schemas'
        'clean'            = 'Remove build output (keeps dependencies and Docker volumes)'
    }
    foreach ($key in $targets.Keys) {
        Write-Host ('  {0,-18} {1}' -f $key, $targets[$key])
    }
    Write-Host ''
    Write-Host 'On Linux, macOS, WSL or Git Bash the same targets are available as: make <target>'
}

function Invoke-Bootstrap {
    Write-Host 'SentinelFlow bootstrap'
    Write-Host ''
    Write-Host 'Prerequisites:'

    $failures = 0
    $warnings = 0

    if (Test-CommandExists 'docker') {
        if ((Invoke-NativeQuiet 'docker' @('info')) -eq 0) {
            Write-Ok "docker $(Invoke-NativeCapture 'docker' @('version', '--format', '{{.Server.Version}}')) - daemon reachable"
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
        Write-Ok "docker compose $(Invoke-NativeCapture 'docker' @('compose', 'version', '--short'))"
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
            $raw = (Invoke-NativeCapture $tool.Name @('--version'))
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
        $missing = @(@('POSTGRES_PASSWORD', 'GRAFANA_ADMIN_PASSWORD') |
            Where-Object { -not $values.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($values[$_]) })
        if ($missing.Count -gt 0) {
            Write-Fail ".env exists but these required secrets are empty: $($missing -join ', ')"
            Write-Host '        Fill them in, or delete .env and rerun to regenerate.'
            $failures++
        }
        else {
            Write-Ok '.env exists with both required secrets set - left untouched'
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
            $bytes = New-Object byte[] 24
            $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            return [Convert]::ToBase64String($bytes)
        }
        $content = Get-Content $exampleFile -Raw
        $content = $content -replace '(?m)^POSTGRES_PASSWORD=$', "POSTGRES_PASSWORD=$(New-Secret)"
        $content = $content -replace '(?m)^GRAFANA_ADMIN_PASSWORD=$', "GRAFANA_ADMIN_PASSWORD=$(New-Secret)"
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
            $cid = Invoke-NativeCapture 'docker' @('compose', 'ps', '-q', $service)
            if ([string]::IsNullOrWhiteSpace($cid)) {
                Fail $service 'not running - is the stack up?'
                continue
            }
            $state = Invoke-NativeCapture 'docker' @('inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}', $cid)
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
        Test-Endpoint 'api /actuator/env is closed'   "http://127.0.0.1:$apiPort/actuator/env"   404
        Test-Endpoint 'api /actuator/beans is closed' "http://127.0.0.1:$apiPort/actuator/beans" 404

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
    'seed' { Invoke-NotImplemented 'seed' 'Phase 4' 'The deterministic synthetic generator it drives' }
    'replay' { Invoke-NotImplemented 'replay' 'Phase 4' 'Scenario replay' }

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
    'test-api' { Invoke-Native $Api '.\mvnw.cmd' @('-B', 'verify') }
    'test-scoring' { Invoke-Native $Scoring 'uv' @('run', 'pytest') }
    'test-integration' { Invoke-NotImplemented 'test-integration' 'Phase 2' 'Testcontainers suites arrive with the schema they verify, and' }
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
