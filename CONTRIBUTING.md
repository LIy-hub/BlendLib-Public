# Contributing to BlendLib

BlendLib welcomes focused bug reports, documentation fixes, tests, and implementation contributions.

Before opening a pull request:

1. Target Minecraft 26.1.2, Fabric Loader 0.19.3, Fabric API `0.154.2+26.1.2`, and Java 25.
2. Preserve strict GLB 2.0, client/server separation, immutable render snapshots, and server-authority boundaries.
3. Do not add runtime `.blend`, FBX, OBJ, raw OpenGL, resource I/O on hot paths, or gameplay decisions driven by visuals.
4. Add or update tests and documentation for externally visible behavior.
5. Run `./gradlew clean check` and `git diff --check`; report any manual client verification separately.

Contributions outside `blender-addon/` are accepted under Apache-2.0. Contributions inside
`blender-addon/` are accepted under GPL-3.0-or-later. By submitting a contribution, you confirm you
have the right to license it under the applicable scope.

Use [GitHub issues](https://github.com/LIy-hub/BlendLib-Public/issues) for normal bugs and feature
proposals. Follow [SECURITY.md](./SECURITY.md) for vulnerabilities. Alpha API changes should be
explicit, documented, and consistent with the accepted architecture and ADR process.
