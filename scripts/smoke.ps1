param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(5, 300)][int]$StartDelaySeconds = 15
)
$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '../.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Run setup-env.ps1 first.' }
$keyLine = Get-Content -LiteralPath $envPath | Where-Object { $_ -like 'BOOTSTRAP_ADMIN_KEY=*' } | Select-Object -First 1
$adminKey = $keyLine.Split('=', 2)[1]
function Invoke-Api([string]$Method, [string]$Path, [string]$Key, $Body = $null) {
    $options = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = @{ Authorization = "Bearer $Key" }
    }
    if ($null -ne $Body) {
        $options.ContentType = 'application/json; charset=utf-8'
        $options.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    Invoke-RestMethod @options
}
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$student = Invoke-Api POST '/api/users' $adminKey @{ name = 'Demo student'; email = "demo-$suffix@campus.local"; role = 'USER' }
$item = Invoke-Api POST '/api/equipment' $adminKey @{ inventoryNumber = "DEMO-$suffix"; name = 'Demo laptop'; category = 'LAPTOP'; location = 'Room 101' }
$start = [DateTimeOffset]::UtcNow.AddSeconds($StartDelaySeconds)
$startText = $start.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$endText = $start.AddHours(1).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$body = @{ equipmentId = $item.id; start = $startText; end = $endText; purpose = 'Demonstration' }
$first = Invoke-Api POST '/api/bookings' $student.apiKey $body
$second = Invoke-Api POST '/api/bookings' $student.apiKey $body
$approved = Invoke-Api POST "/api/bookings/$($first.id)/approve" $adminKey
try {
    Invoke-Api POST "/api/bookings/$($second.id)/approve" $adminKey | Out-Null
    throw 'Expected HTTP 409 for overlapping booking.'
} catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
    Write-Host 'Overlapping approval correctly rejected with HTTP 409.'
}
Invoke-Api POST "/api/bookings/$($second.id)/cancel" $student.apiKey @{ reason = 'Conflict demonstrated' } | Out-Null
$remaining = ([DateTimeOffset]::Parse($startText) - [DateTimeOffset]::UtcNow).TotalMilliseconds
if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int][Math]::Ceiling($remaining)) }
$issued = Invoke-Api POST "/api/bookings/$($first.id)/issue" $adminKey @{ condition = 'Good' }
if ($issued.status -ne 'ISSUED') { throw 'Issue failed.' }
$returned = Invoke-Api POST "/api/bookings/$($first.id)/return" $adminKey @{ condition = 'Good'; damaged = $false }
if ($returned.status -ne 'RETURNED') { throw 'Return failed.' }
$events = Invoke-Api GET "/api/bookings/$($first.id)/events" $student.apiKey
$statuses = ($events.items | ForEach-Object { $_.status }) -join ' -> '
if ($statuses -ne 'PENDING -> APPROVED -> ISSUED -> RETURNED') { throw "Unexpected history: $statuses" }
Write-Host "Success: $statuses"
Write-Host "Equipment: $($item.id); booking: $($first.id)"
Write-Host 'Demo records are preserved in PostgreSQL. No API keys were printed.'
