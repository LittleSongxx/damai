[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.yml",
    [string]$MySqlService = "mysql",
    [string]$RabbitMqService = "rabbitmq",
    [string]$RootPassword,
    [int]$MySqlPort = 0,
    [int]$RedisPort = 0,
    [int]$NacosPort = 0,
    [int]$RabbitMqAmqpPort = 0,
    [int]$RabbitMqManagementPort = 0,
    [int]$EsPort = 0,
    [int]$SeataPort = 0,
    [int]$SentinelPort = 0,
    [string]$EsUser,
    [string]$EsPassword
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "import-local-env.ps1")

$RootPassword = if ($PSBoundParameters.ContainsKey("RootPassword")) { $RootPassword } elseif ($env:DAMAI_MYSQL_ROOT_PASSWORD) { $env:DAMAI_MYSQL_ROOT_PASSWORD } else { "root" }
$MySqlPort = if ($PSBoundParameters.ContainsKey("MySqlPort")) { $MySqlPort } elseif ($env:DAMAI_MYSQL_PORT) { [int]$env:DAMAI_MYSQL_PORT } else { 3306 }
$RedisPort = if ($PSBoundParameters.ContainsKey("RedisPort")) { $RedisPort } elseif ($env:DAMAI_REDIS_PORT) { [int]$env:DAMAI_REDIS_PORT } else { 6379 }
$NacosPort = if ($PSBoundParameters.ContainsKey("NacosPort")) { $NacosPort } elseif ($env:DAMAI_NACOS_PORT) { [int]$env:DAMAI_NACOS_PORT } else { 8848 }
$RabbitMqAmqpPort = if ($PSBoundParameters.ContainsKey("RabbitMqAmqpPort")) { $RabbitMqAmqpPort } elseif ($env:DAMAI_RABBITMQ_AMQP_PORT) { [int]$env:DAMAI_RABBITMQ_AMQP_PORT } else { 5672 }
$RabbitMqManagementPort = if ($PSBoundParameters.ContainsKey("RabbitMqManagementPort")) { $RabbitMqManagementPort } elseif ($env:DAMAI_RABBITMQ_PORT) { [int]$env:DAMAI_RABBITMQ_PORT } else { 15672 }
$EsPort = if ($PSBoundParameters.ContainsKey("EsPort")) { $EsPort } elseif ($env:DAMAI_ES_PORT) { [int]$env:DAMAI_ES_PORT } else { 9200 }
$SeataPort = if ($PSBoundParameters.ContainsKey("SeataPort")) { $SeataPort } elseif ($env:DAMAI_SEATA_PORT) { [int]$env:DAMAI_SEATA_PORT } else { 8091 }
$SentinelPort = if ($PSBoundParameters.ContainsKey("SentinelPort")) { $SentinelPort } elseif ($env:DAMAI_SENTINEL_PORT) { [int]$env:DAMAI_SENTINEL_PORT } else { 8082 }
$EsUser = if ($PSBoundParameters.ContainsKey("EsUser")) { $EsUser } elseif ($env:DAMAI_ES_USERNAME) { $env:DAMAI_ES_USERNAME } else { "elastic" }
$EsPassword = if ($PSBoundParameters.ContainsKey("EsPassword")) { $EsPassword } elseif ($env:DAMAI_ES_PASSWORD) { $env:DAMAI_ES_PASSWORD } else { "elastic" }

function Test-TcpPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$HostName,
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(3000, $false)) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

$ports = @(
    @{ Name = "MySQL"; Port = $MySqlPort },
    @{ Name = "Redis"; Port = $RedisPort },
    @{ Name = "Nacos"; Port = $NacosPort },
    @{ Name = "RabbitMQ AMQP"; Port = $RabbitMqAmqpPort },
    @{ Name = "RabbitMQ Management"; Port = $RabbitMqManagementPort },
    @{ Name = "Elasticsearch"; Port = $EsPort },
    @{ Name = "Seata"; Port = $SeataPort },
    @{ Name = "Sentinel"; Port = $SentinelPort }
)

foreach ($entry in $ports) {
    Assert-Condition (Test-TcpPort -HostName "127.0.0.1" -Port $entry.Port) "$($entry.Name) is not reachable on port $($entry.Port)."
}

$dbCount = docker compose -f $ComposeFile exec -T $MySqlService mysql -N -uroot "-p$RootPassword" -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('damai_base_data','damai_customize','damai_order_0','damai_order_1','damai_pay_0','damai_pay_1','damai_program_0','damai_program_1','damai_user_0','damai_user_1');"
Assert-Condition ($dbCount.Trim() -eq "10") "Expected 10 business schemas, got $dbCount."

$undoCount = docker compose -f $ComposeFile exec -T $MySqlService mysql -N -uroot "-p$RootPassword" -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE (TABLE_SCHEMA='damai_order_0' OR TABLE_SCHEMA='damai_order_1' OR TABLE_SCHEMA='damai_program_0' OR TABLE_SCHEMA='damai_program_1') AND TABLE_NAME='undo_log';"
Assert-Condition ($undoCount.Trim() -eq "4") "Expected undo_log tables in four sharded schemas, got $undoCount."

$seataTableCount = docker compose -f $ComposeFile exec -T $MySqlService mysql -N -uroot "-p$RootPassword" -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='seata' AND TABLE_NAME IN ('global_table','branch_table','lock_table','distributed_lock');"
Assert-Condition ($seataTableCount.Trim() -eq "4") "Expected 4 Seata metadata tables, got $seataTableCount."

$nacosResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$NacosPort/nacos/" -UseBasicParsing
Assert-Condition ($nacosResponse.StatusCode -ge 200 -and $nacosResponse.StatusCode -lt 500) "Nacos HTTP check failed."

$esResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$EsPort/_cluster/health" -UseBasicParsing -Headers @{
    Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${EsUser}:${EsPassword}"))
}
Assert-Condition ($esResponse.StatusCode -eq 200) "Elasticsearch cluster health check failed."

$null = docker compose -f $ComposeFile exec -T $RabbitMqService rabbitmq-diagnostics -q ping
$rabbitMqExitCode = $LASTEXITCODE
Assert-Condition ($rabbitMqExitCode -eq 0) "RabbitMQ diagnostics ping failed."

Write-Host "Local infrastructure checks passed." -ForegroundColor Green
