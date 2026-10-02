# Minecraft 26.3 port

## Changes

- Add `26.3` to the existing modern shared-source build, with Fabric API `0.161.0+26.3`, Loader `0.19.5`, Loom `1.17.21`, Java 25 and Gradle 9.6.0.
- Port GPU namespaces and pipeline metadata to RenderPearl, use compiled pipelines and combined-image-sampler uniforms, and retain the existing direct-GPU availability gates.
- Update quaternion pose operations and frustum-culling partial-tick signatures.
- Move the final-present Mixin to `com.mojang.renderpearl.api.device.GpuSurface.present()` and pin verified official/processed Minecraft class hashes.
- Keep older target sources, dependency versions, root 26.1.2 build and public release history unchanged.

## Build

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 --no-daemon build verifyRuntimeJar
```

Outputs: `versions/modern/build/26.3/libs/blendlib-fabric-1.0.0-beta.3+26.3.jar` and its sources JAR.

## Evidence

Code checkpoint: `5c5354c42876e769c5e4915005ec3341502d0ef0`.
[Focused 26.3 CI](https://github.com/LIy-hub/BlendLib-Public/actions/runs/37046940395) and the existing root Build both passed.
[Runtime/source artifacts and test reports](https://github.com/LIy-hub/BlendLib-Public/actions/runs/37046940395/artifacts/11244772603).

The focused build compiles both source sets, validates metadata and all baseline classes in the complete runtime JAR, and passes four tests: three precise vertex-layout/pipeline checks and a Fabric Loader test proving both final-present hooks are injected into Minecraft and the processed class matches the exact runtime pin. The test uses the transforming test classloader and does not create a window/world.

Runtime JAR SHA-256: `8cf9adc9e415e83d4b987852867d150ec925ee4b9ba2898da22a1895920c30b4`.

## Boundaries

No full client/window, world, dedicated-server or rendered GPU acceptance is claimed. The experimental GPU paths remain experimental. No EULA acceptance, GitHub Release/tag or CurseForge upload was performed.

The cloud machine's Unix-domain socket restriction prevents local Loom initialization. Unmodified official tools on GitHub Actions completed the full build; no sandbox workarounds were installed.
