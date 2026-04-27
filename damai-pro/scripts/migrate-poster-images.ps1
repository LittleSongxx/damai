[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.yml",
    [string]$MySqlService = "mysql",
    [string]$RootPassword,
    [string]$OutputDir = $(Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) "vue3\public\posters"),
    [string]$MappingDir = $(Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) "local-config"),
    [switch]$ForceDownload,
    [switch]$SkipDatabaseUpdate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "import-local-env.ps1")

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$RootPassword = if ($PSBoundParameters.ContainsKey("RootPassword")) { $RootPassword } elseif ($env:DAMAI_MYSQL_ROOT_PASSWORD) { $env:DAMAI_MYSQL_ROOT_PASSWORD } else { "root" }
$outputPath = New-Item -ItemType Directory -Force -Path $OutputDir
$mappingPath = New-Item -ItemType Directory -Force -Path $MappingDir
$mappingCsvPath = Join-Path $mappingPath "poster-image-map.csv"
$sqlBackupPath = Join-Path $mappingPath "poster-image-restore.sql"
$updateSqlPath = Join-Path $mappingPath "poster-image-update.sql"

function Get-UrlExtension {
    param([Parameter(Mandatory = $true)][string]$Url)

    try {
        $uri = [System.Uri]$Url
        $extension = [System.IO.Path]::GetExtension($uri.AbsolutePath)
        if ([string]::IsNullOrWhiteSpace($extension)) {
            return ".jpg"
        }
        return $extension.ToLowerInvariant()
    } catch {
        return ".jpg"
    }
}

function Get-UrlHash {
    param([Parameter(Mandatory = $true)][string]$Url)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Url)
        $hashBytes = $sha256.ComputeHash($bytes)
        return ([BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Escape-SqlValue {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return "NULL"
    }
    return "'" + $Value.Replace("\", "\\").Replace("'", "''") + "'"
}

$query = @"
SELECT 'damai_program_0' AS schema_name, 'd_program_0' AS table_name, id, item_picture
FROM damai_program_0.d_program_0
WHERE item_picture IS NOT NULL AND item_picture <> ''
UNION ALL
SELECT 'damai_program_1' AS schema_name, 'd_program_1' AS table_name, id, item_picture
FROM damai_program_1.d_program_1
WHERE item_picture IS NOT NULL AND item_picture <> ''
ORDER BY schema_name, id;
"@

$rawRows = docker compose -f $ComposeFile exec -T $MySqlService mysql -N -B -uroot "-p$RootPassword" -e $query
if ($LASTEXITCODE -ne 0) {
    throw "Failed to query poster image rows from MySQL."
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($line in $rawRows) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    $parts = $line -split "`t", 4
    if ($parts.Count -lt 4) {
        continue
    }
    $rows.Add([PSCustomObject]@{
        SchemaName = $parts[0]
        TableName = $parts[1]
        Id = [long]$parts[2]
        ItemPicture = $parts[3]
    })
}

if ($rows.Count -eq 0) {
    Write-Host "No poster images found." -ForegroundColor Yellow
    return
}

$downloadMap = @{}
$mappingRows = New-Object System.Collections.Generic.List[object]
$updateSqlLines = New-Object System.Collections.Generic.List[string]
$restoreSqlLines = New-Object System.Collections.Generic.List[string]
$downloadedCount = 0
$reusedCount = 0
$skippedCount = 0

foreach ($row in $rows) {
    $originalUrl = $row.ItemPicture
    if ($originalUrl.StartsWith("/posters/")) {
        $skippedCount++
        continue
    }

    if (-not $downloadMap.ContainsKey($originalUrl)) {
        $extension = Get-UrlExtension -Url $originalUrl
        $fileName = (Get-UrlHash -Url $originalUrl) + $extension
        $targetPath = Join-Path $outputPath $fileName

        if ($ForceDownload -or -not (Test-Path -LiteralPath $targetPath)) {
            Write-Host "Downloading $originalUrl" -ForegroundColor Cyan
            & curl.exe --fail --location --silent --show-error --output $targetPath $originalUrl
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Failed to download $originalUrl"
                continue
            }
            $downloadedCount++
        } else {
            $reusedCount++
        }

        $downloadMap[$originalUrl] = @{
            FileName = $fileName
            LocalPath = "/posters/$fileName"
        }
    }

    $localPath = $downloadMap[$originalUrl].LocalPath
    $mappingRows.Add([PSCustomObject]@{
        schema_name = $row.SchemaName
        table_name = $row.TableName
        id = $row.Id
        original_url = $originalUrl
        local_path = $localPath
    })

    $updateSqlLines.Add(
        "UPDATE $($row.SchemaName).$($row.TableName) SET item_picture = $(Escape-SqlValue -Value $localPath) WHERE id = $($row.Id);"
    )
    $restoreSqlLines.Add(
        "UPDATE $($row.SchemaName).$($row.TableName) SET item_picture = $(Escape-SqlValue -Value $originalUrl) WHERE id = $($row.Id);"
    )
}

$mappingRows | Export-Csv -LiteralPath $mappingCsvPath -NoTypeInformation -Encoding UTF8
[System.IO.File]::WriteAllLines($updateSqlPath, $updateSqlLines, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllLines($sqlBackupPath, $restoreSqlLines, [System.Text.UTF8Encoding]::new($false))

if (-not $SkipDatabaseUpdate -and $updateSqlLines.Count -gt 0) {
    Get-Content -LiteralPath $updateSqlPath -Raw -Encoding UTF8 |
        docker compose -f $ComposeFile exec -T $MySqlService mysql --default-character-set=utf8mb4 -uroot "-p$RootPassword"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to update poster image URLs in MySQL."
    }
}

Write-Host ""
Write-Host "Poster image migration completed." -ForegroundColor Green
Write-Host "Downloaded: $downloadedCount" -ForegroundColor Green
Write-Host "Reused existing files: $reusedCount" -ForegroundColor Green
Write-Host "Already local / skipped: $skippedCount" -ForegroundColor Green
Write-Host "Output directory: $OutputDir" -ForegroundColor Green
Write-Host "Mapping file: $mappingCsvPath" -ForegroundColor Green
Write-Host "Restore SQL: $sqlBackupPath" -ForegroundColor Green
