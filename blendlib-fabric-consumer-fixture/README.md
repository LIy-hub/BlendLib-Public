# Fabric consumer compile fixtures

`src/main` remains a server-safe semantic consumer whose compile classpath contains only
`blendlib-api` and `blendlib-fabric-common`. Its existing dependency boundary is unchanged.

`src/client/.../NamedSkinConsumerFixture.java` is a separate compile-only example of the
public named-skin registration, entity selector, both item registration overloads and the
read-only item capture observer. The 26.3 modern build includes this source in its test compile
source set against the actual version-specific adapter; it is not packaged in either runtime
or runnable consumer JAR. No internal loader, backend, model preparation or core type is imported.

From the repository root, with Java 25:

```sh
./gradlew -p versions/modern -Pminecraft_version=26.3 test
```

For a live entity and wand using real authored slots and textures, see
[`versions/modern/showcase`](../versions/modern/showcase/README.md#opt-in-named-texture-skins).
