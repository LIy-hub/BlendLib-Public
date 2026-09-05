# BlendLib ecosystem example mod

This is an independent Fabric consumer project, not a root Gradle subproject. It resolves
BlendLib only from the local Maven coordinate configured by blendlib_local_maven_repo, and contains
no project dependency, internal/impl import, GeckoLib dependency, raw OpenGL call, reflection, or
runtime authoring-format loader.

It provides real common/client source and metadata for:

- entity, block-entity, and marker-item registrations;
- immutable stable BlendLib registration specifications and semantic animation keys;
- server semantic animation publication through BlendAnimations;
- public client renderer/item-binding facades;
- a presentation-only animation event and socket marker;
- a concrete, lifecycle-owner-only X4 GUI preview host factory plus clear X3/X6/X7 boundaries,
  including the standard CPU fallback;
- resource descriptors with strict GLB plus external PNG assets.

The X4 host-adapter and X6/X7 provider pathways are deliberately not installed by this consumer:
they are Experimental SPI and require a version-specific lifecycle owner. The included feature
catalog identifies the handoff point without pretending runtime wiring exists. Status is
**WAITING** for build, reload, client, server, visual, backend, and platform verification because
the X8 task intentionally does not run them.

## Local use

Set blendlib_local_maven_repo to a prepared local Maven repository containing
com.liy.blendlib:blendlib-fabric:1.0.0-rc.1+26.1.2, then use the normal Gradle command for this
standalone directory. The X8 implementation did not execute that command.

Read docs/expansion/x8/developer-tutorial.md and
docs/expansion/x8/third-party-provider-guide.md before adapting this example.
