[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.yml",
    [switch]$SkipDockerUp,
    [switch]$SkipDatabaseInit,
    [switch]$ForceDatabaseInit,
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipNpmInstall,
    [switch]$KeepRunning,
    [int]$StartupTimeoutSeconds = 240,
    [int]$FrontendTimeoutSeconds = 120,
    [string]$LogDir = $(Join-Path $env:TEMP ("damai-validation-" + (Get-Date -Format "yyyyMMdd-HHmmss")))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "import-local-env.ps1")

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$startedProcesses = New-Object System.Collections.Generic.List[System.Diagnostics.Process]

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Invoke-RequiredCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
    }
}

function Get-ListeningProcess {
    param([Parameter(Mandatory = $true)][int]$Port)

    return Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Test-DatabaseInitialized {
    param(
        [Parameter(Mandatory = $true)][string]$ComposeFilePath,
        [Parameter(Mandatory = $true)][string]$RootPassword
    )

    try {
        $dbCount = docker compose -f $ComposeFilePath exec -T mysql mysql -N -uroot "-p$RootPassword" -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('damai_base_data','damai_customize','damai_order_0','damai_order_1','damai_pay_0','damai_pay_1','damai_program_0','damai_program_1','damai_user_0','damai_user_1');"
        $seedCount = docker compose -f $ComposeFilePath exec -T mysql mysql -N -uroot "-p$RootPassword" -e "SELECT COUNT(*) FROM damai_base_data.d_channel_data WHERE code='0001';"
        $seataTableCount = docker compose -f $ComposeFilePath exec -T mysql mysql -N -uroot "-p$RootPassword" -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='seata' AND TABLE_NAME IN ('global_table','branch_table','lock_table','distributed_lock');"
        return $dbCount.Trim() -eq "10" -and $seedCount.Trim() -eq "1" -and $seataTableCount.Trim() -eq "4"
    } catch {
        return $false
    }
}

function Get-SignContent {
    param([Parameter(Mandatory = $true)][hashtable]$Parameters)

    $keys = $Parameters.Keys | Sort-Object
    $pairs = foreach ($key in $keys) {
        "{0}={1}" -f $key, $Parameters[$key]
    }
    return ($pairs -join "&")
}

function New-RsaSha256Signature {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Parameters,
        [Parameter(Mandatory = $true)][string]$PrivateKeyBase64
    )

    $content = Get-SignContent -Parameters $Parameters
    $rsa = [System.Security.Cryptography.RSA]::Create()
    try {
        $keyBytes = [Convert]::FromBase64String($PrivateKeyBase64)
        $bytesRead = 0
        $rsa.ImportPkcs8PrivateKey($keyBytes, [ref]$bytesRead)
        $signatureBytes = $rsa.SignData(
            [System.Text.Encoding]::UTF8.GetBytes($content),
            [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.RSASignaturePadding]::Pkcs1
        )
        return [Convert]::ToBase64String($signatureBytes)
    } finally {
        $rsa.Dispose()
    }
}

function New-GatewaySignedPayload {
    param(
        [Parameter(Mandatory = $true)][string]$ChannelCode,
        [Parameter(Mandatory = $true)][string]$BusinessBodyJson,
        [Parameter(Mandatory = $true)][string]$PrivateKeyBase64
    )

    $signParameters = @{
        businessBody = $BusinessBodyJson
        code = $ChannelCode
    }
    $sign = New-RsaSha256Signature -Parameters $signParameters -PrivateKeyBase64 $PrivateKeyBase64
    $payload = [ordered]@{
        code = $ChannelCode
        businessBody = $BusinessBodyJson
        sign = $sign
    }
    return ($payload | ConvertTo-Json -Compress)
}

function Wait-ForHttpStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                return $response
            }
        } catch {
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Uri"
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Body
    )

    try {
        return Invoke-RestMethod -Uri $Uri -Method POST -ContentType "application/json" -Body $Body -TimeoutSec 15
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.GetResponseStream()) {
            $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $responseBody = $reader.ReadToEnd()
            $reader.Dispose()
            throw "Request to $Uri failed. Response body: $responseBody"
        }
        throw
    }
}

function Wait-ForApiSuccess {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Body,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-JsonPost -Uri $Uri -Body $Body
            if ($response.code -eq 0) {
                return $response
            }
        } catch {
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for a successful API response from $Uri"
}

function Get-ServiceJar {
    param([Parameter(Mandatory = $true)][string]$ModulePath)

    $targetDir = Join-Path $ModulePath "target"
    $jar = Get-ChildItem -LiteralPath $targetDir -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "original-*" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "No runnable jar found under $targetDir"
    }

    return $jar.FullName
}

function Start-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    $stdoutPath = Join-Path $LogDir "$Name.out.log"
    $stderrPath = Join-Path $LogDir "$Name.err.log"
    $process = Start-Process -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru
    $startedProcesses.Add($process)
    Write-Host "Started $Name (PID $($process.Id))" -ForegroundColor DarkGray
}

function Ensure-ServiceStarted {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Service,
        [int]$TimeoutSeconds = 240
    )

    $listener = Get-ListeningProcess -Port $Service.Port
    if ($listener) {
        Write-Host "Reusing $($Service.Name) on port $($Service.Port) (PID $($listener.OwningProcess))" -ForegroundColor Yellow
    } else {
        $modulePath = Join-Path $projectRoot $Service.Module
        $jarPath = Get-ServiceJar -ModulePath $modulePath
        $arguments = @("-jar", $jarPath)
        if ($Service.Profile) {
            $arguments += "--spring.profiles.active=$($Service.Profile)"
        }
        Start-ManagedProcess -Name $Service.Name -FilePath $javaCommand -Arguments $arguments -WorkingDirectory $modulePath
    }

    Wait-ForHttpStatus -Uri $Service.HealthUri -TimeoutSeconds $TimeoutSeconds | Out-Null
    Write-Host "$($Service.Name) is ready." -ForegroundColor Green
}

function Cleanup-StartedProcesses {
    if ($KeepRunning) {
        Write-Host "KeepRunning specified, skipping local process cleanup." -ForegroundColor Yellow
        return
    }

    foreach ($process in $startedProcesses) {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    Write-Step "Checking required commands"
    foreach ($command in @("docker", "java", "mvn", "node", "npm")) {
        Assert-Command -Name $command
    }
    $dockerCommand = (Get-Command docker).Source
    $javaCommand = (Get-Command java).Source
    $mvnCommand = if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) { (Get-Command mvn.cmd).Source } else { (Get-Command mvn).Source }
    $nodeCommand = (Get-Command node).Source
    $npmCommand = if (Get-Command npm.cmd -ErrorAction SilentlyContinue) { (Get-Command npm.cmd).Source } else { (Get-Command npm).Source }

    Invoke-RequiredCommand -FilePath $dockerCommand -Arguments @("compose", "version")
    Invoke-RequiredCommand -FilePath $javaCommand -Arguments @("-version")
    Invoke-RequiredCommand -FilePath $mvnCommand -Arguments @("-version")
    Invoke-RequiredCommand -FilePath $nodeCommand -Arguments @("-v")
    Invoke-RequiredCommand -FilePath $npmCommand -Arguments @("-v")

    $env:SPRING_BOOT_ADMIN_CLIENT_URL = "http://127.0.0.1:10082"
    $env:SPRING_BOOT_ADMIN_CLIENT_USERNAME = "admin"
    $env:SPRING_BOOT_ADMIN_CLIENT_PASSWORD = "admin"

    $mySqlHost = if ($env:DAMAI_MYSQL_HOST) { $env:DAMAI_MYSQL_HOST } else { "127.0.0.1" }
    $mySqlPort = if ($env:DAMAI_MYSQL_PORT) { [int]$env:DAMAI_MYSQL_PORT } else { 3306 }
    $mySqlUsername = if ($env:DAMAI_MYSQL_USERNAME) { $env:DAMAI_MYSQL_USERNAME } else { "root" }
    $mySqlPassword = if ($env:DAMAI_MYSQL_PASSWORD) { $env:DAMAI_MYSQL_PASSWORD } else { "root" }
    $databaseRootPassword = if ($env:DAMAI_MYSQL_ROOT_PASSWORD) { $env:DAMAI_MYSQL_ROOT_PASSWORD } else { "root" }

    if ($mySqlHost -ne "127.0.0.1" -or $mySqlPort -ne 3306 -or $mySqlUsername -ne "root" -or $mySqlPassword -ne "root") {
        Write-Step "Rendering ShardingSphere local override files"
        & (Join-Path $PSScriptRoot "render-local-shardingsphere-configs.ps1") `
            -MySqlHost $mySqlHost `
            -MySqlPort $mySqlPort `
            -MySqlUsername $mySqlUsername `
            -MySqlPassword $mySqlPassword
        . (Join-Path $projectRoot "local-config\damai-sharding-env.ps1")
    }

    if (-not $SkipDockerUp) {
        Write-Step "Starting Docker infrastructure"
        Invoke-RequiredCommand -FilePath $dockerCommand -Arguments @("compose", "-f", $ComposeFile, "up", "-d")
    }

    if (-not $SkipDatabaseInit) {
        if ($ForceDatabaseInit -or -not (Test-DatabaseInitialized -ComposeFilePath $ComposeFile -RootPassword $databaseRootPassword)) {
            Write-Step "Initializing databases"
            & (Join-Path $PSScriptRoot "init-databases.ps1") -ComposeFile $ComposeFile -RootPassword $databaseRootPassword
        } else {
            Write-Step "Database initialization already present, skipping SQL import"
        }
    }

    Write-Step "Checking infrastructure health"
    & (Join-Path $PSScriptRoot "check-local-env.ps1") -ComposeFile $ComposeFile

    if (-not $SkipBuild) {
        Write-Step "Packaging backend services"
        Push-Location $projectRoot
        try {
            Invoke-RequiredCommand -FilePath $mvnCommand -Arguments @("-q", "-DskipTests", "package")
        } finally {
            Pop-Location
        }
    }

    $services = @(
        @{ Name = "damai-admin-service"; Module = "damai-server\damai-admin-service"; Port = 10082; HealthUri = "http://127.0.0.1:10082/login"; Profile = $null },
        @{ Name = "damai-base-data-service"; Module = "damai-server\damai-base-data-service"; Port = 6083; HealthUri = "http://127.0.0.1:6083/actuator/health"; Profile = "local" },
        @{ Name = "damai-customize-service"; Module = "damai-server\damai-customize-service"; Port = 6084; HealthUri = "http://127.0.0.1:6084/actuator/health"; Profile = "local" },
        @{ Name = "damai-user-service"; Module = "damai-server\damai-user-service"; Port = 6082; HealthUri = "http://127.0.0.1:6082/actuator/health"; Profile = "local" },
        @{ Name = "damai-program-service"; Module = "damai-server\damai-program-service"; Port = 6086; HealthUri = "http://127.0.0.1:6086/actuator/health"; Profile = "local" },
        @{ Name = "damai-pay-service"; Module = "damai-server\damai-pay-service"; Port = 6087; HealthUri = "http://127.0.0.1:6087/actuator/health"; Profile = "local" },
        @{ Name = "damai-order-service"; Module = "damai-server\damai-order-service"; Port = 8081; HealthUri = "http://127.0.0.1:8081/actuator/health"; Profile = "local" },
        @{ Name = "damai-migrate-service"; Module = "damai-server\damai-migrate-service"; Port = 6088; HealthUri = "http://127.0.0.1:6088/actuator/health"; Profile = "local" },
        @{ Name = "damai-gateway-service"; Module = "damai-server\damai-gateway-service"; Port = 6085; HealthUri = "http://127.0.0.1:6085/actuator/health"; Profile = "pro" }
    )

    Write-Step "Starting backend services"
    foreach ($service in $services) {
        Ensure-ServiceStarted -Service $service -TimeoutSeconds $StartupTimeoutSeconds
    }

    $businessBody = '{"code":"0001"}'
    Write-Step "Validating direct base-data service"
    $directResponse = Wait-ForApiSuccess `
        -Uri "http://127.0.0.1:6083/channel/data/getByCode" `
        -Body $businessBody `
        -TimeoutSeconds $StartupTimeoutSeconds

    if (-not $directResponse.data -or $directResponse.data.code -ne "0001" -or [string]::IsNullOrWhiteSpace($directResponse.data.signSecretKey)) {
        throw "Direct base-data validation returned an unexpected payload."
    }

    $gatewayBody = New-GatewaySignedPayload `
        -ChannelCode "0001" `
        -BusinessBodyJson $businessBody `
        -PrivateKeyBase64 $directResponse.data.signSecretKey

    Write-Step "Validating gateway route"
    $gatewayResponse = Wait-ForApiSuccess `
        -Uri "http://127.0.0.1:6085/damai/basedata/channel/data/getByCode" `
        -Body $gatewayBody `
        -TimeoutSeconds $StartupTimeoutSeconds

    if (-not $gatewayResponse.data -or $gatewayResponse.data.code -ne "0001") {
        throw "Gateway validation returned an unexpected payload."
    }

    if (-not $SkipFrontend) {
        Write-Step "Starting or reusing vue3 dev server"
        $frontendListener = Get-ListeningProcess -Port 5173
        if ($frontendListener) {
            Write-Host "Reusing frontend dev server on port 5173 (PID $($frontendListener.OwningProcess))" -ForegroundColor Yellow
        } else {
            $vue3Path = Join-Path $projectRoot "vue3"
            if (-not $SkipNpmInstall -and -not (Test-Path (Join-Path $vue3Path "node_modules"))) {
                Push-Location $vue3Path
                try {
                    Invoke-RequiredCommand -FilePath $npmCommand -Arguments @("install")
                } finally {
                    Pop-Location
                }
            }

            Start-ManagedProcess `
                -Name "vue3-dev" `
                -FilePath $npmCommand `
                -Arguments @("run", "dev", "--", "--host", "127.0.0.1") `
                -WorkingDirectory $vue3Path
        }

        Wait-ForHttpStatus -Uri "http://127.0.0.1:5173/index.html" -TimeoutSeconds $FrontendTimeoutSeconds | Out-Null

        Write-Step "Validating frontend proxy"
        $frontendResponse = Wait-ForApiSuccess `
            -Uri "http://127.0.0.1:5173/barley-dev/basedata/channel/data/getByCode" `
            -Body $gatewayBody `
            -TimeoutSeconds $FrontendTimeoutSeconds

        if (-not $frontendResponse.data -or $frontendResponse.data.code -ne "0001") {
            throw "Frontend proxy validation returned an unexpected payload."
        }
    }

    Write-Host ""
    Write-Host "Local startup validation passed. Logs: $LogDir" -ForegroundColor Green
} finally {
    Cleanup-StartedProcesses
}
