param([string[]]$Minecraft = @('1.21.1', '1.21.2', '1.21.3', '1.21.4', '1.21.5', '1.21.6', '1.21.7', '1.21.8'))
$ErrorActionPreference = 'Stop'
$legacyProject = $PSScriptRoot
$legacyRepository = [IO.Path]::GetFullPath((Join-Path $legacyProject '..\..'))
$legacyLogs = Join-Path $legacyProject 'build\logs'
New-Item -ItemType Directory -Path $legacyLogs -Force | Out-Null
foreach ($legacyVersion in $Minecraft) {
    if ($legacyVersion -notin @('1.21.1', '1.21.2', '1.21.3', '1.21.4', '1.21.5', '1.21.6', '1.21.7', '1.21.8')) {
        throw "Unsupported legacy Minecraft target: $legacyVersion"
    }
    $legacyLog = Join-Path $legacyLogs "$legacyVersion-$(Get-Date -Format yyyyMMdd-HHmmss).log"
    & (Join-Path $legacyRepository 'gradlew.bat') -p $legacyProject "-Pminecraft_version=$legacyVersion" --no-daemon --max-workers=1 build verifyRuntimeJar --console=plain *> $legacyLog
    $legacyExitCode = $LASTEXITCODE
    if ($legacyExitCode -ne 0) {
        Get-Content -LiteralPath $legacyLog -Tail 80
        throw "Minecraft $legacyVersion failed with exit $legacyExitCode. Evidence: $legacyLog"
    }
    Write-Output "Minecraft $legacyVersion build/tests/JAR verification passed. Evidence: $legacyLog"
}
