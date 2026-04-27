[CmdletBinding()]
param(
    [string]$MySqlHost,
    [int]$MySqlPort = 0,
    [string]$MySqlUsername,
    [string]$MySqlPassword,
    [string]$OutputDir = $(Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) "local-config")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "import-local-env.ps1")

$MySqlHost = if ($PSBoundParameters.ContainsKey("MySqlHost")) { $MySqlHost } elseif ($env:DAMAI_MYSQL_HOST) { $env:DAMAI_MYSQL_HOST } else { "127.0.0.1" }
$MySqlPort = if ($PSBoundParameters.ContainsKey("MySqlPort")) { $MySqlPort } elseif ($env:DAMAI_MYSQL_PORT) { [int]$env:DAMAI_MYSQL_PORT } else { 3306 }
$MySqlUsername = if ($PSBoundParameters.ContainsKey("MySqlUsername")) { $MySqlUsername } elseif ($env:DAMAI_MYSQL_USERNAME) { $env:DAMAI_MYSQL_USERNAME } else { "root" }
$MySqlPassword = if ($PSBoundParameters.ContainsKey("MySqlPassword")) { $MySqlPassword } elseif ($env:DAMAI_MYSQL_PASSWORD) { $env:DAMAI_MYSQL_PASSWORD } else { "root" }

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputPath = New-Item -ItemType Directory -Force -Path $OutputDir
$jdbcPrefix = "jdbc:mysql://$MySqlHost`:$MySqlPort"

$configs = @(
    @{ Env = "DAMAI_USER_SHARDING_URL"; Source = "damai-server\damai-user-service\src\main\resources\shardingsphere-user-local.yaml"; Target = "shardingsphere-user-local.generated.yaml" },
    @{ Env = "DAMAI_PAY_SHARDING_URL"; Source = "damai-server\damai-pay-service\src\main\resources\shardingsphere-pay-local.yaml"; Target = "shardingsphere-pay-local.generated.yaml" },
    @{ Env = "DAMAI_ORDER_SHARDING_URL"; Source = "damai-server\damai-order-service\src\main\resources\shardingsphere-order-local.yaml"; Target = "shardingsphere-order-local.generated.yaml" },
    @{ Env = "DAMAI_PROGRAM_SHARDING_URL"; Source = "damai-server\damai-program-service\src\main\resources\shardingsphere-program-local.yaml"; Target = "shardingsphere-program-local.generated.yaml" },
    @{ Env = "DAMAI_MIGRATE_SHARDING_URL"; Source = "damai-server\damai-migrate-service\src\main\resources\shardingsphere-migrate-local.yaml"; Target = "shardingsphere-migrate-local.generated.yaml" }
)

$envFileLines = New-Object System.Collections.Generic.List[string]

foreach ($config in $configs) {
    $sourcePath = Join-Path $projectRoot $config.Source
    $targetPath = Join-Path $outputPath $config.Target
    $content = Get-Content -LiteralPath $sourcePath -Raw -Encoding UTF8
    $content = $content -replace "jdbc:mysql://127\.0\.0\.1:\d+", $jdbcPrefix
    $content = $content.Replace("username: root", "username: $MySqlUsername")
    $content = $content.Replace("password: root", "password: $MySqlPassword")
    [System.IO.File]::WriteAllText($targetPath, $content, [System.Text.UTF8Encoding]::new($false))
    $envFileLines.Add('$env:' + $config.Env + ' = "jdbc:shardingsphere:absolutepath:' + $targetPath.Replace('\', '\\') + '"')
    Write-Host "Generated $targetPath" -ForegroundColor Cyan
}

$envScriptPath = Join-Path $outputPath "damai-sharding-env.ps1"
[System.IO.File]::WriteAllLines($envScriptPath, $envFileLines, [System.Text.UTF8Encoding]::new($false))
Write-Host "Generated $envScriptPath" -ForegroundColor Green
