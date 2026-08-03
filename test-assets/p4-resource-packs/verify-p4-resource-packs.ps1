[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$packRoot = $PSScriptRoot
$showcaseAssets = Join-Path (Join-Path (Resolve-Path (Join-Path $packRoot '..\..')) 'blendlib-showcase') 'src\main\resources\assets\blendlib_showcase'
$descriptorRelativePath = 'assets\blendlib_showcase\blend_models\fixtures\static_model.json'
$validRoot = Join-Path $packRoot 'valid-override'
$malformedRoot = Join-Path $packRoot 'malformed-missing-mesh'
$expectedValidMesh = 'blendlib_showcase:models3d/fixtures/rigid_model.glb'
$expectedTexture = 'blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png'
$expectedMissingMesh = 'blendlib_showcase:models3d/fixtures/does_not_exist.glb'
$expectedRigidGlbHash = 'a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764'
$expectedRigidTextureHash = '851035d6863e6aea6a03c7a93d8734872e16918938c6f8b962392c24b25502e1'
$expectedPackFormat = 84

function Read-JsonObject([string] $path) {
    return (Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
}

function Assert-Equal([object] $actual, [object] $expected, [string] $message) {
    if ($actual -ne $expected) {
        throw "$message Expected '$expected', got '$actual'."
    }
}

function Assert-True([bool] $condition, [string] $message) {
    if (-not $condition) {
        throw $message
    }
}

function Assert-ExactPropertyNames([object] $object, [string[]] $expected, [string] $message) {
    $actualNames = @($object.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @($expected | Sort-Object)
    if (($actualNames -join "`n") -ne ($expectedNames -join "`n")) {
        throw "$message Expected '$($expectedNames -join ', ')', got '$($actualNames -join ', ')'."
    }
}

foreach ($packRootToValidate in @($validRoot, $malformedRoot)) {
    $metadata = Read-JsonObject (Join-Path $packRootToValidate 'pack.mcmeta')
    Assert-ExactPropertyNames $metadata @('pack') "Unexpected root fields in '$packRootToValidate\\pack.mcmeta'."
    Assert-ExactPropertyNames $metadata.pack @('description', 'min_format', 'max_format') "Unexpected pack fields in '$packRootToValidate\\pack.mcmeta'."
    Assert-Equal $metadata.pack.min_format $expectedPackFormat "Unexpected min_format in '$packRootToValidate'."
    Assert-Equal $metadata.pack.max_format $expectedPackFormat "Unexpected max_format in '$packRootToValidate'."
    Assert-True (-not [string]::IsNullOrWhiteSpace([string] $metadata.pack.description)) "Missing pack description in '$packRootToValidate'."
}

$validDescriptor = Read-JsonObject (Join-Path $validRoot $descriptorRelativePath)
Assert-Equal $validDescriptor.format_version 1 'Valid override must use descriptor schema v1.'
Assert-Equal $validDescriptor.profile 'blendlib:rigid_v1' 'Valid override must use the rigid P2 fixture profile.'
Assert-Equal $validDescriptor.mesh $expectedValidMesh 'Valid override must resolve to the committed rigid GLB.'
Assert-Equal $validDescriptor.materials.RigidSurface.base_color $expectedTexture 'Valid override must resolve to the committed rigid texture.'

$rigidGlb = Join-Path $showcaseAssets 'models3d\fixtures\rigid_model.glb'
$rigidTexture = Join-Path $showcaseAssets 'textures\blendlib\fixtures_rigid_model__rigidsurface.png'
Assert-True (Test-Path -LiteralPath $rigidGlb -PathType Leaf) "Missing Showcase baseline GLB: '$rigidGlb'."
Assert-True (Test-Path -LiteralPath $rigidTexture -PathType Leaf) "Missing Showcase baseline texture: '$rigidTexture'."
Assert-Equal (Get-FileHash -Algorithm SHA256 -LiteralPath $rigidGlb).Hash.ToLowerInvariant() $expectedRigidGlbHash 'Unexpected rigid GLB SHA-256.'
Assert-Equal (Get-FileHash -Algorithm SHA256 -LiteralPath $rigidTexture).Hash.ToLowerInvariant() $expectedRigidTextureHash 'Unexpected rigid texture SHA-256.'

$malformedDescriptor = Read-JsonObject (Join-Path $malformedRoot $descriptorRelativePath)
Assert-Equal $malformedDescriptor.format_version 1 'Malformed fixture must remain schema-valid before mesh resolution.'
Assert-Equal $malformedDescriptor.profile 'blendlib:rigid_v1' 'Malformed fixture must retain the rigid profile.'
Assert-Equal $malformedDescriptor.materials.RigidSurface.base_color $expectedTexture 'Malformed fixture must not add a second primary texture error.'
Assert-Equal $malformedDescriptor.mesh $expectedMissingMesh 'Malformed fixture must fail only at the intentionally missing GLB.'

$missingRelativeFile = 'models3d\fixtures\does_not_exist.glb'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $showcaseAssets $missingRelativeFile))) 'The intentionally missing GLB unexpectedly exists in Showcase baseline assets.'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $malformedRoot (Join-Path 'assets\blendlib_showcase' $missingRelativeFile)))) 'The malformed pack must not supply its intentionally missing GLB.'

$projectRoot = (Resolve-Path (Join-Path $packRoot '..\..')).Path
$matrixVerifier = Join-Path $packRoot 'material-matrix\verify-material-matrix.py'
Assert-True (Test-Path -LiteralPath $matrixVerifier -PathType Leaf) "Missing P4 material-matrix verifier: '$matrixVerifier'."
$python = Get-Command python -ErrorAction Stop
$previousDontWriteBytecode = $env:PYTHONDONTWRITEBYTECODE
try {
    $env:PYTHONDONTWRITEBYTECODE = '1'
    & $python.Source -B $matrixVerifier --project-root $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "P4 material-matrix verifier failed with exit code $LASTEXITCODE."
    }
} finally {
    if ($null -eq $previousDontWriteBytecode) {
        Remove-Item Env:PYTHONDONTWRITEBYTECODE -ErrorAction SilentlyContinue
    } else {
        $env:PYTHONDONTWRITEBYTECODE = $previousDontWriteBytecode
    }
}

Write-Output 'P4 resource-pack fixtures verified: min_format=max_format=84, valid final composition references the committed rigid fixture, malformed descriptor has one intentional missing-GLB fault, and the accepted ADR-014/ADR-019 material matrix is schema/asset verified.'
