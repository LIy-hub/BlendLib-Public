param(
    [Parameter(Mandatory=$true)][string]$Manifest,
    [Parameter(Mandatory=$true)][string]$PythonPath,
    [string]$GradleHome = 'F:\Caches\Gradle'
)
$ErrorActionPreference = 'Stop'
$entries = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
foreach ($entry in $entries) {
    $actualHash = (Get-FileHash -LiteralPath $entry.runtime -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $entry.sha256) { throw "Artifact changed before launch: $($entry.minecraft)" }
    $launchArgs = @("$PSScriptRoot/start_packaged_client.py", '--minecraft', $entry.minecraft,
        '--runtime', $entry.runtime, '--fabric-api', $entry.fabric_api, '--java', $entry.java,
        '--directory', $entry.directory, '--gradle-home', $GradleHome)
    & $PythonPath @launchArgs
    if ($LASTEXITCODE -ne 0) { throw "Client launcher failed: $($entry.minecraft)" }
    $record = Get-Content -LiteralPath "$($entry.directory)/process.json" -Raw | ConvertFrom-Json
    try {
        $deadline = (Get-Date).AddSeconds(180)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            $clientProcess = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
            if (-not $clientProcess) { throw "Client exited before resource reload: $($entry.minecraft)" }
            $logPath = "$($entry.directory)/console.log"
            if (Test-Path -LiteralPath $logPath) {
                $logText = Get-Content -LiteralPath $logPath -Raw
                if ($logText -match 'Caught error loading resourcepacks|Mixin apply.*failed|InjectionError|Exception in thread') {
                    throw "Client startup failure: $($entry.minecraft); inspect $logPath"
                }
                if ($logText -match 'active_generation=1 published=true.*diagnostics=0') {
                    $ready = $true
                    break
                }
            }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "Client resource reload timed out: $($entry.minecraft)" }
        & "$PSScriptRoot/finish_packaged_client.ps1" -Directory $entry.directory
    } catch {
        # Only request a normal close from the exact process launched for this fresh profile.
        $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
        $argumentPath = [IO.Path]::GetFullPath("$($entry.directory)/launch-arguments.txt")
        if ($owned -and $owned.CommandLine.Contains($argumentPath)) {
            $clientProcess = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
            if ($clientProcess -and $clientProcess.MainWindowTitle -like 'Minecraft*') {
                $null = $clientProcess.CloseMainWindow()
                $null = $clientProcess.WaitForExit(15000)
            }
        }
        throw
    }
}
