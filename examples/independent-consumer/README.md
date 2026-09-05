# Independent BlendLib consumer

This minimal standalone Fabric project demonstrates a consumer boundary that has no project
dependency on the BlendLib checkout. It resolves only
com.liy.blendlib:blendlib-fabric:1.0.0-rc.1+26.1.2 from the local Maven repository selected by
blendlib_local_maven_repo.

Both main and client entrypoints compile against public API/facade types only. They intentionally
build immutable semantic registrations rather than installing a global Experimental platform
adapter. Platform adapter ownership belongs to a version-specific bootstrap owner, not a normal
consumer mod.

No test source, test task, validation harness, implementation import, GeckoLib runtime dependency,
raw GL call, reflection, or runtime authoring-format parser is present. Static compilation and
launch remain **WAITING** because this X8 task does not execute build or runtime commands.
