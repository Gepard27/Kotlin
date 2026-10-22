$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '../.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Run scripts/setup-env.ps1 first.' }
$previous = @{}
try {
    Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
        $pair = $_.Split('=', 2)
        $previous[$pair[0]] = [Environment]::GetEnvironmentVariable($pair[0], 'Process')
        [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
    }
    foreach ($name in @('DATABASE_URL', 'DATABASE_USER')) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($env:DB_PORT)/campus_equipment"
    $env:DATABASE_USER = 'campus'
    & "$PSScriptRoot/../server/gradle-local.ps1" run --console=plain
    $result = $LASTEXITCODE
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $result
