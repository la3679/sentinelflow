<#
.SYNOPSIS
    Gather the mechanical facts a checkpoint needs.

.DESCRIPTION
    A thin wrapper so Windows PowerShell users run the same code as everyone
    else. The logic lives in checkpoint.mjs; see the comment block there for
    what it does and, more importantly, what it deliberately does not do.

        .\scripts\claude\checkpoint.ps1
        .\scripts\claude\checkpoint.ps1 --json
        .\scripts\claude\checkpoint.ps1 --write
#>

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$target = Join-Path $PSScriptRoot 'checkpoint.mjs'

# bun first: it is a declared prerequisite of this repository, so it is the one
# runtime a contributor is guaranteed to have.
$runtime = if (Get-Command bun -ErrorAction SilentlyContinue) { 'bun' }
elseif (Get-Command node -ErrorAction SilentlyContinue) { 'node' }
else { $null }

if ($null -eq $runtime) {
    Write-Error "Neither bun nor node is on PATH. Both are checked by 'sf.ps1 bootstrap'."
    exit 1
}

# $ErrorActionPreference is relaxed around the native call: Windows PowerShell
# 5.1 turns a native command's stderr into a terminating ErrorRecord otherwise.
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $runtime $target @Arguments
    exit $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previous
}
