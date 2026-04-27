[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.yml",
    [string]$MySqlService = "mysql",
    [string]$RootPassword,
    [switch]$SkipDamaiAiSql
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "import-local-env.ps1")

$RootPassword = if ($PSBoundParameters.ContainsKey("RootPassword")) { $RootPassword } elseif ($env:DAMAI_MYSQL_ROOT_PASSWORD) { $env:DAMAI_MYSQL_ROOT_PASSWORD } else { "root" }

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$cloudSqlDir = Join-Path $projectRoot "sql\cloud"
$seataSql = Join-Path $projectRoot "sql\seata\seata_server_mysql.sql"
$aiSql = Resolve-Path (Join-Path $projectRoot "..\damai-ai\sql\damai_ai.sql") -ErrorAction SilentlyContinue

if (-not (Test-Path $cloudSqlDir)) {
    throw "SQL directory not found: $cloudSqlDir"
}

function Invoke-MySqlFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    Write-Host "Importing $Path" -ForegroundColor Cyan
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 |
        docker compose -f $ComposeFile exec -T $MySqlService mysql --default-character-set=utf8mb4 -uroot "-p$RootPassword"
}

$sqlFiles = New-Object System.Collections.Generic.List[string]
$sqlFiles.Add((Join-Path $cloudSqlDir "1_damai_cloud_create_database.sql"))

Get-ChildItem -LiteralPath $cloudSqlDir -File |
    Where-Object { $_.Name -ne "1_damai_cloud_create_database.sql" } |
    Sort-Object Name |
    ForEach-Object { $sqlFiles.Add($_.FullName) }

Get-ChildItem -LiteralPath (Join-Path $projectRoot "sql") -Recurse -File |
    Where-Object {
        $_.DirectoryName -notlike "*\sql\cloud" -and
        $_.DirectoryName -notlike "*\sql\seata"
    } |
    Sort-Object FullName |
    ForEach-Object { $sqlFiles.Add($_.FullName) }

$sqlFiles.Add($seataSql)

if (-not $SkipDamaiAiSql -and $aiSql) {
    $sqlFiles.Add($aiSql.Path)
}

foreach ($file in $sqlFiles) {
    Invoke-MySqlFile -Path $file
}

Write-Host "Database initialization completed." -ForegroundColor Green
