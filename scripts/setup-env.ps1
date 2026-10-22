$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '../.env'
if (Test-Path -LiteralPath $envPath) {
    Write-Host '.env already exists; nothing changed.'
    exit 0
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
$content = @"
DATABASE_PASSWORD=$(New-Secret)
BOOTSTRAP_ADMIN_KEY=$(New-Secret)
BOOTSTRAP_ADMIN_EMAIL=admin@campus.local
BOOTSTRAP_ADMIN_NAME=Administrator
HTTP_PORT=8080
DB_PORT=5432
LOG_LEVEL=info
"@
[IO.File]::WriteAllText($envPath, $content, [Text.UTF8Encoding]::new($false))
Write-Host 'Created .env with random secrets. Keep this file private and preserve it when restarting the database.'
