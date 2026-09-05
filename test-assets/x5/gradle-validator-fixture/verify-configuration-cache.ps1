[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $GradleWrapper,

    [Parameter(Mandatory = $true)]
    [string] $ValidatorClasspath,

    [Parameter(Mandatory = $true)]
    [string] $ValidProjectRoot,

    [Parameter(Mandatory = $true)]
    [string] $ValidModelKey
)

$ErrorActionPreference = 'Stop'
$nativePreferenceAvailable = Test-Path variable:PSNativeCommandUseErrorActionPreference
if ($nativePreferenceAvailable) {
    $originalNativeErrorActionPreference = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
}

try {
    if (-not (Test-Path -LiteralPath $GradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper does not exist: $GradleWrapper"
    }
    if (-not (Test-Path -LiteralPath $ValidProjectRoot -PathType Container)) {
        throw "Valid project root does not exist: $ValidProjectRoot"
    }

    $fixtureRoot = $PSScriptRoot
    $runRoot = Join-Path $fixtureRoot (Join-Path 'build' ([Guid]::NewGuid().ToString('N')))
    $projectCache = Join-Path $runRoot 'project-cache'
    $invalidProject = Join-Path $runRoot 'invalid-project'
    New-Item -ItemType Directory -Path $projectCache, $invalidProject -Force | Out-Null

    function Invoke-ValidatorRun {
        param(
            [string] $ProjectRoot,
            [string] $ModelKey,
            [int] $ExpectedExit,
            [string] $ExpectedStatus,
            [string] $ExpectedCache
        )

        $arguments = @(
            '--no-daemon',
            '--configuration-cache',
            '--configuration-cache-problems=fail',
            '--project-cache-dir', $projectCache,
            '-p', $fixtureRoot,
            'validateBlendlibAsset',
            "-PblendlibValidatorClasspath=$ValidatorClasspath",
            "-PblendlibAssetProjectRoot=$ProjectRoot",
            "-PblendlibAssetModelKey=$ModelKey"
        )

        # Windows PowerShell 5.1 promotes native stderr to a NativeCommandError
        # when ErrorActionPreference is Stop. The invalid cases deliberately
        # produce Gradle stderr, so lower that preference only while capturing
        # their expected native failure; the asserted exit/status/cache checks
        # below still make every unexpected result fail the runner.
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            if ($ExpectedExit -ne 0) {
                $ErrorActionPreference = 'Continue'
            }
            $outputLines = & $GradleWrapper @arguments 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        $output = $outputLines -join "`n"
        if ($exitCode -ne $ExpectedExit) {
            throw "Unexpected Gradle exit code $exitCode; expected $ExpectedExit.`n$output"
        }
        if ($output -notmatch $ExpectedStatus -or $output -notmatch $ExpectedCache) {
            throw "Gradle output did not prove validator/cache status.`n$output"
        }
    }

    Invoke-ValidatorRun $ValidProjectRoot $ValidModelKey 0 'VALID ' 'Configuration cache entry stored\.'
    Invoke-ValidatorRun $ValidProjectRoot $ValidModelKey 0 'VALID ' 'Configuration cache entry reused\.'
    Invoke-ValidatorRun $invalidProject 'blendlib_showcase:fixtures/missing' 1 'INVALID ' 'Configuration cache entry (stored|reused)\.'
    Invoke-ValidatorRun $invalidProject 'blendlib_showcase:fixtures/missing' 1 'INVALID ' 'Configuration cache entry reused\.'

    Write-Output 'BLENDLIB_X5_GRADLE_CONFIGURATION_CACHE_PASS valid=2 invalid=2'
    $global:LASTEXITCODE = 0
}
catch {
    $global:LASTEXITCODE = 1
    throw
}
finally {
    if ($nativePreferenceAvailable) {
        $PSNativeCommandUseErrorActionPreference = $originalNativeErrorActionPreference
    }
}
