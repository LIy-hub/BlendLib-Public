# Fabric ports for Minecraft 1.21.9–26.2

This independent build reuses the complete root API, core, common and client source trees.
It does not change the historical 26.1.2 root build or publish the earlier detached X8 adapter.
Each output is a self-contained `blendlib` runtime containing all 511 baseline top-level classes.

From the repository root, with the Java 21 and Java 25 toolchains installed:

```powershell
.\gradlew.bat -p versions/modern -Pminecraft_version=26.2 --no-daemon --max-workers=1 build
```

Targets: `1.21.9`, `1.21.10`, `1.21.11`, `26.1`, `26.1.1`, `26.1.2`, `26.2`.
Each must be validated separately; an entry in this list is not a completed support claim.
See [current evidence](../../docs/release/multiversion-progress.md).

Outputs are under `build/<minecraft>/libs/`, using `1.0.0-beta.3+<minecraft>`.
The 1.21 targets use official Mojang mappings, Loom remapping, and Java 21 bytecode.
The 26.x targets use Mojang's unobfuscated distribution and Java 25.

## Source adaptations

- Unchanged code is copied into Gradle-owned generated source directories.
- Small version-specific call/name changes are explicit in `build.gradle.kts`.
- The 26.2 pipeline metadata uses the new local vertex formats and bind-group layouts.
  Its indexed-draw bridge preserves the original argument meanings despite Mojang's reordered API.
- The final-present Mixin for 26.2 brackets `GpuSurface.present()` in the actual `renderFrame` method.
- The existing experimental GPU paths retain their availability gates. Compilation and the
  three 26.2 vertex-layout tests do not establish GPU rendering or performance acceptance.
- 1.21.9 has no public Fabric world pass-owner events. Its explicit host boundary cannot install
  the unreleased X7 endpoint. 1.21.9/1.21.10 refuse the direct native driver before encoder creation
  because their API cannot supply an independently owned sampler; shared vanilla texture state is untouched.
- Obfuscated targets resolve the Minecraft class resource from its actual runtime name. Exact Fabric
  production and Loom development hashes in `runtime-pins.properties` pin the final-frame adapter.
- The shared 1.21 tests check real cutout pipeline culling and the 32-byte vertex layout.

## Optional dependency transport cache

`fetch_fabric_maven.py` downloads exact Fabric API POM/JAR coordinates from Fabric's official Maven
when Gradle's network transport fails. It checks published SHA-1 or an existing Gradle artifact hash.
It preserves dependency versions and TLS verification. Nothing under `build/fabric-maven` is committed.

## Packaged runtime verification

`smoke_server.py` installs the exact JAR into a new loopback-only server profile and sends `stop`
after Minecraft reports readiness. Its result records the artifact SHA-256 and actual exit code.

`start_packaged_client.py` uses official Mojang/Fabric metadata and production KnotClient, with
the exact JAR installed in a fresh profile's `mods` directory. It starts at the game's initial UI
and never joins a world. `finish_packaged_client.ps1` checks the matching process, resource-reload
result and startup errors, then closes only that test window. Offline authentication failures and
the separately recorded Windows OSHI performance-counter diagnostic are environment evidence.
Client startup remains separate from the user's visual acceptance.
