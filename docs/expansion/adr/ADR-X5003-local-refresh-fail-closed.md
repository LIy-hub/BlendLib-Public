# ADR-X5003: Use a fail-closed local filesystem refresh message

## Status

Proposed/Experimental implementation candidate. X5's isolated track was independently reviewed by /root/x5_postcommit_review_r3 (gpt-5.6-sol/max), Verdict: PASS with no findings. This local X1/X5/X9 integration candidate still awaits a fresh gpt-5.6-sol/max integration review; this status does not accept the proposal or change runtime scope.

## Context

Developers benefit from seeing an exported asset refresh while the game is
open, but network endpoints, unconstrained file watching, and unchecked paths
would create lifecycle/security ownership outside the Blender tooling scope.

## Decision

Define an exact local JSON refresh document with session token, increasing
generation, model key, project-relative artifact paths, and lower-case SHA-256
hashes. A caller-owned `RefreshReceiver` verifies token, monotonic generation,
resolved regular files, and hashes. A caller-owned poll call uses a fixed one
second idle debounce. The refresh document must resolve outside the configured
runtime resource tree, including normalized symlink/junction aliases, so its
session token cannot enter a runtime JAR. No thread, listener, timer, network
endpoint, or client registration is shipped by X5.

Refresh output is excluded from the configured output root,
`src/main/resources`, and `build/resources/main`, including resolved aliases.
Refresh and batch JSON are stat-capped at 512 KiB before allocation and reject
file growth/shrink during the bounded read.

The refresh writer has parity with that reader: it emits compact canonical
UTF-8 through a bounded accumulator, accepts exactly 524,288 bytes, and rejects
524,289 before invoking atomic staging or target replacement. Artifact maps
are independently capped at 4,096, so a 5,000-entry message is a stable
structured rejection rather than an unbounded sort/serialization attempt.

Both writer and caller-polled watcher use the bounded deduplicated union of the
configured resource root, the two source/compiled defaults, and explicit
roots. The watcher checks on construction and every poll, before debounce or
receiver state, so late symlink/junction redirection cannot advance generation.
Generation and caller-supplied clock values require the exact built-in Python
`int` type, are non-negative, and fit a signed Java `long`; booleans,
`IntEnum` members, custom `int` subclasses, negatives, and `2^63` or greater
fail closed instead of being coerced.

## Consequences

- Unsafe/foreign/stale/mismatched messages are rejected before any consumer
  acts.
- The token is absent from sidecars/reports and never logged by this protocol.
- An integration owner must decide actual Minecraft reload scheduling and
  generation ownership later.

## Alternatives rejected

- HTTP/WebSocket preview service: adds authorization/lifecycle concerns.
- Automatic background file watcher: would own shutdown and polling policy.
- Accepting paths/hashes without disk verification: enables stale or wrong
  local content to be accepted.
