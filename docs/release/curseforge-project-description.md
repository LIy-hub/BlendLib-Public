# CurseForge 项目描述

BlendLib is a Fabric library for strict GLB 2.0 models and animation on Minecraft 26.1.2.

The `1.0.0-alpha.1+26.1.2` line targets Fabric Loader 0.19.3, Fabric API
`0.154.2+26.1.2`, and Java 25. It provides rigid and skinned model loading, animation
semantics, version-specific entity/block-entity/item client adapters, diagnostics, and a Showcase.

This is an early test version. APIs and behavior may change. Do not use it in critical production
environments. Only the declared Minecraft 26.1.2, Fabric, and Java environment is supported.

Runtime assets must be strict `.glb` files. BlendLib never uses visual models or animation events to
decide server-authoritative collision, damage, hit detection, or drops.

Source and issues: https://github.com/LIy-hub/BlendLib-Public

License: Apache-2.0 for non-Add-on code. The Blender exporter Add-on remains separately licensed
under GPL-3.0-or-later.
