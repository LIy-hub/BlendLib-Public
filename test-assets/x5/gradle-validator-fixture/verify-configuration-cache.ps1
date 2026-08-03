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
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
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
    $outputLines = & $GradleWrapper @arguments 2>&1
    $exitCode = $LASTEXITCODE
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
Invoke-ValidatorRun $invalidProject 'blendlib_showcase:fixtures/missing' 1 'INVALID ' 'Configuration cache entry reused\.'
Invoke-ValidatorRun $invalidProject 'blendlib_showcase:fixtures/missing' 1 'INVALID ' 'Configuration cache entry reused\.'

Write-Output 'BLENDLIB_X5_GRADLE_CONFIGURATION_CACHE_PASS valid=2 invalid=2'
