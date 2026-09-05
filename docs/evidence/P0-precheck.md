# P0 Precheck Evidence

Status: READY TO IMPLEMENT P0

## Verified local facts

- D:\BlendLib did not exist before P0 initialization.
- C:\Program Files\Java\latest\jdk-25\bin\java.exe -version reported Java 25.0.2.
- D:\Program Files\Blender\blender.exe --version reported Blender 5.1.2.
- D:\MinecraftFabricServer-26.1.2\libraries\net\fabricmc\fabric-loader\0.19.3\fabric-loader-0.19.3.jar exists.
- D:\MinecraftFabricServer-26.1.2\mods\fabric-api-0.154.2+26.1.2.jar declares Fabric API 0.154.2+26.1.2.
- The 26.1.2 source package and the official Fabric Maven index both contain the frozen Fabric API version. Newer compatible-looking versions are intentionally not substituted.

## Explicit risks carried into P1

- A project-local Gradle Wrapper and Fabric Loom resolution have not yet been proven.
- The local LiyMod reference is a dirty 26.2 worktree and is read-only behavior reference only.
- No 26.1.2 client rendering API signature has been verified. P1 must establish dependencies and P4 must validate real adapter mappings.
- This evidence is static precheck evidence only. It is not dedicated-server, client-visual, reload, performance, or release evidence.
