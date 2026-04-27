[CmdletBinding()]
param(
    [string]$EnvFile = $(Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) ".env"),
    [switch]$OverrideExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $EnvFile)) {
    return
}

foreach ($rawLine in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
    $line = $rawLine.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
        continue
    }

    $separatorIndex = $line.IndexOf("=")
    if ($separatorIndex -lt 1) {
        continue
    }

    $name = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()

    if ($value.Length -ge 2) {
        $firstChar = $value[0]
        $lastChar = $value[$value.Length - 1]
        if (($firstChar -eq '"' -and $lastChar -eq '"') -or ($firstChar -eq "'" -and $lastChar -eq "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    if (-not $OverrideExisting -and [Environment]::GetEnvironmentVariable($name, "Process")) {
        continue
    }

    [Environment]::SetEnvironmentVariable($name, $value, "Process")
}
