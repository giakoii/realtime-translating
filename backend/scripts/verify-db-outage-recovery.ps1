param(
    [Parameter(Mandatory = $true)]
    [string]$PostgresRoot,
    [string]$WebSocketUrl = 'ws://127.0.0.1:8081/ws/audio',
    [string]$VerifyUrl = 'http://127.0.0.1:8082'
)

$script = Join-Path $PSScriptRoot 'verify-db-outage-recovery.cjs'
& node.exe $script $PostgresRoot $WebSocketUrl $VerifyUrl
if ($LASTEXITCODE -ne 0) {
    throw "Database outage recovery verification failed with exit code $LASTEXITCODE."
}
