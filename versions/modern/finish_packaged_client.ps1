param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference = 'Stop'
$profilePath = (Resolve-Path -LiteralPath $Directory).Path
$state = Get-Content -LiteralPath (Join-Path $profilePath 'process.json') -Raw | ConvertFrom-Json
$clientInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$($state.pid)"
$argumentPath = Join-Path $profilePath 'launch-arguments.txt'
if (-not $clientInfo -or -not $clientInfo.CommandLine.Contains($argumentPath)) {
    throw "Client process is absent or its identity does not match $argumentPath"
}
$clientProcess = Get-Process -Id $state.pid
$windowTitle = $clientProcess.MainWindowTitle
$consolePath = Join-Path $profilePath 'console.log'
$logText = Get-Content -LiteralPath $consolePath -Raw
if ($windowTitle -notmatch 'Minecraft' -or $logText -notmatch 'active_generation=1 published=true.*diagnostics=0') {
    throw 'Client window and successful BlendLib resource-reload evidence are required.'
}
$unexpectedErrors = @($logText -split "`n" | Where-Object {
    $_ -match '/ERROR\]' -and $_ -notmatch 'Failed to fetch user properties|Failed to fetch Realms feature flags|Failed to request yggdrasil public key|Unable to locate English counter names in registry Perflib 009'
})
if ($unexpectedErrors.Count -gt 0 -or $logText -match 'Mixin apply failed|Exception in thread|InjectionError|Caught error loading resourcepacks') {
    throw ('Unexpected startup failure: ' + ($unexpectedErrors -join "`n"))
}
$closeSent = $clientProcess.CloseMainWindow()
$closed = $clientProcess.WaitForExit(15000)
$shutdownLog = Get-Content -LiteralPath $consolePath -Raw
$shutdownFailures = @($shutdownLog -split "`n" | Where-Object { $_ -match 'shutdown diagnostic snapshot:.*state=FAILED' })
$result = [ordered]@{
    minecraft = $state.minecraft
    blendlib = $state.blendlib
    runtime_sha256 = $state.runtime_sha256
    pid = $state.pid
    window_title = $windowTitle
    startup_pass = $true
    resource_reload = 'published=true, diagnostics=0'
    unexpected_error_count = $unexpectedErrors.Count
    close_sent = $closeSent
    exited = $closed
    shutdown_failures = $shutdownFailures
    exit_code = $(if($closed){$clientProcess.ExitCode}else{$null})
    visual_acceptance = 'not performed'
    offline_auth_errors = @($logText -split "`n" | Where-Object { $_ -match '/ERROR\].*(Failed to fetch user properties|Failed to fetch Realms feature flags|Failed to request yggdrasil public key)' })
    environment_diagnostics = @($logText -split "`n" | Where-Object { $_ -match '/ERROR\].*Unable to locate English counter names in registry Perflib 009' })
}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $profilePath 'result.json') -Encoding utf8
$result | ConvertTo-Json -Compress
if (-not $closed) { throw 'Client startup passed, but its shutdown is still pending.' }
if ($shutdownFailures.Count -gt 0) { throw ('Client shutdown failed: ' + ($shutdownFailures -join "`n")) }
