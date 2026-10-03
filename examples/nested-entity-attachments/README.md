# Nested entity attachment consumer fixture (26.3)

`src/main/java/com/liy/blendlib/examples/attachments/ExampleNestedEntityAttachments.java`
is a client-only public API example for character -> weapon -> ornament composition. It accepts
prepared snapshots and final character/weapon socket sets, preserving existing attachments and
each child's appearance/lighting. It performs no model loading or animation lifecycle work.

The current client JUnit suite compiles this actual source through `JavaCompiler` and exercises
composition, final-socket placement, missing sockets, and stale socket generations. This is an
isolated source fixture, not an independent Gradle mod or registered runnable asset scene; it
does not alter the historical ecosystem example's artifact defaults. Incorporate it only with
the new 26.3 library API and compatible Minecraft/Fabric versions.

**Provide a conservative root-model culling envelope for the complete assembly.** Culling
precedes extraction; this helper does not enlarge bounds. See the
[consumer guide](../../docs/nested-entity-attachments.md) and
[design plan](../../docs/plans/26.3-nested-entity-attachments.md).
