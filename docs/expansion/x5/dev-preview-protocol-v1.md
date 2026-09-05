# X5 local dev-preview refresh protocol v1

X5's refresh seam is deliberately a local filesystem protocol, not a service.
There is no socket, HTTP endpoint, network listener, background polling loop,
Minecraft event registration, or runtime binding in this deliverable.

## Wire document

The document is UTF-8 JSON with exactly these fields:

```json
{
  "artifact_hashes": {"src/main/resources/assets/example/models3d/model.glb": "<64 lower-case hex>"},
  "format": "blendlib-x5-dev-refresh-v1",
  "generation": 7,
  "model_key": "example:model",
  "session_token": "local-session-token-at-least-16-chars"
}
```

Artifact-map keys are portable project-relative paths only: no drive path,
backslash, URI, empty segment, dot segment, parent segment, or colon. Values
are lower-case SHA-256. Unknown/missing fields, malformed JSON, an unknown
format, unsafe path, missing artifact, or a hash mismatch are rejected.
The watcher stats the resolved regular message file and enforces its 512 KiB
cap before allocating; shrink or growth between stat and the bounded read is
also rejected. The writer uses the same compact canonical UTF-8 representation
and inclusive 524,288-byte cap, serializing through a bounded accumulator
before invoking the atomic bundle writer. A 4,097th artifact-map entry or a
524,289-byte document is rejected with a stable refresh diagnostic before any
file stage or target replacement.
The refresh document itself is authoring-only and must resolve outside the
configured output resource root, `src/main/resources`, and
`build/resources/main`, including through normalized paths and existing
symlink/junction aliases. This prevents the session token from being packaged
into a source or compiled runtime JAR.

The caller-polled reader enforces the same bounded union as the writer: the
configured resource root, `src/main/resources`, `build/resources/main`, and
any explicit roots, deduplicated to at most 16. It checks that boundary when
the watcher is constructed and again on every poll, so a later-created
symlink/junction cannot redirect the message into a runtime tree. Rejection
occurs before offer/debounce/receive and cannot advance the accepted
generation.

## Proposed client binding

A future explicitly-authorized client binding constructs
`RefreshReceiver(sessionToken, projectRoot)` and a
`FilesystemRefreshWatcher(projectRoot, refreshRelativePath, adapter)`. Its own
lifecycle may call `poll_once(nowMillis)`; X5 does not schedule it. The
receiver verifies all four safety dimensions before accepting an update:

1. exact session token;
2. generation strictly greater than the accepted generation;
3. every artifact key resolves under the authorized project root;
4. every resolved regular file exists and has the declared SHA-256.

`DebouncedRefreshAdapter` is caller-driven and has a fixed 1,000 ms idle
window. The fake-clock test proves `t=0` and `t=999` do not deliver, while
`t=1000` delivers exactly once. No token is placed in the sidecar/report.

Generation and caller clock values are exact non-negative built-in integers in
the signed Java `long` range. Python `bool`, `IntEnum`, custom `int` subclasses,
negative values, and `2^63` or above are rejected with bounded refresh
diagnostics; JSON booleans are never reinterpreted as generation numbers.
