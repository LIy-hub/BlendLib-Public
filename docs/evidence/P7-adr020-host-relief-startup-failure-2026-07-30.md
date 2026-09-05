# P7 ADR-020 host-relief startup failure — 2026-07-30

Status: retained **WAITING** evidence. This is an environment/startup failure,
not a P7 functional, visual, or performance result.

## Scope and preflight

After ADR-020 alignment, the coordinator verified that the isolated generated
P7 pack's `reference-scene.json` contains exactly:

```text
/tp @s 0 67 24 180 0
```

One fresh local-only `runP7BenchmarkClient` launch was then made against only
`D:\BlendLib\blendlib-showcase\run\p7-benchmark`. It used only
`JAVA_TOOL_OPTIONS=-Xms512m -Xmx3g`; no P7 scene count, geometry, camera,
warm-up/sample interval, or acceptance target was reduced. Before this one
launch, the coordinator stopped only a verified idle BlendLib-owned Gradle
daemon. It did not stop user-owned applications, a formal server, or a
formal world.

## Observed result

The Java 25 client started and reached the isolated resource reload. It never
reached a stable controllable UI, created/selected a local world, spawned the
125 P7 hosts, executed a teleport, started JFR/profiling, made screenshots, or
produced a P7 runtime report.

At 18:41:05, the retained Minecraft log recorded the NVIDIA driver error and
`GL_OUT_OF_MEMORY`: a `2048x2048` texture with 27 layers could not be allocated
during the block-atlas reload. The VM then stopped at 18:41:06 with native
`malloc` failure `Chunk::new` for 2,714,840 bytes. The fatal log reports a
3,072 MiB maximum Java heap with only about 528 MiB committed, so this is not
evidence of a Java-heap exhaustion or a benchmark workload result.

A subsequent read-only machine check found `C:` had 0 bytes free while `D:`
had about 14.0 GiB free. The collaboration subsystem also failed to create an
additional read-only helper with Windows error 112 (disk full). An after-exit
GPU query reported 10,014 MiB free of 12,288 MiB; because that query occurred
after the client exited, it does not establish the GPU state at the failure or
attribute the fault to any other process.

The same read-only check found 94% system commit in use: `C:\pagefile.sys`
(12,288 MiB allocated) reported 8,790 MiB current usage and
`D:\pagefile.sys` (32,768 MiB allocated) reported 8,749 MiB. These values are
host observations at the time of inspection, not an authorization to resize a
pagefile, clean data, or stop another application's process.

## Retained artifacts

| Artifact | SHA-256 |
| --- | --- |
| `build/manual-p7-adr020-capture-evidence/2026-07-30-183953-xmx3g-hostrelief/01-pre-reference-scene.json` | `6E78116154D27DC73EE56BB7AAA3DDBCF0D3F3464BFD88236190164E518090F5` |
| `build/manual-p7-adr020-capture-evidence/2026-07-30-183953-xmx3g-hostrelief/10-p7-gradle.out.txt` | `92B520E260B033FEFD078B9F06DC8B0A803A5EE808CE7A49B373A99079467FCE` |
| `build/manual-p7-adr020-capture-evidence/2026-07-30-183953-xmx3g-hostrelief/10-p7-gradle.err.txt` | `767F5B61329B086578B3D2C08894B71858FBAFFB78CFFDC0620D74687DB0592E` |
| `blendlib-showcase/run/p7-benchmark/hs_err_pid36792.log` | `45231105E8AD9171605FB009BFFBCA23A9CB9AD6A2DE792927CC13550354EB90` |
| `blendlib-showcase/run/p7-benchmark/logs/latest.log` | `DC1D2CFA1A9BDEBF2F1E02CD49BE17AC585D9EAB4DA6BF3AFA0F00D3F97750E6` |

## Gate consequence and next action

P7 remains **WAITING**. No visual, all-host-submission, JFR/profiler, frame
time, allocation, cache, Iris/Sodium visual, or reload-leak assertion may be
inferred from this launch. Do not start another P7 client capture from this
machine state. A later isolated capture requires a user-authorized host-level
remedy for the system-drive/pagefile and graphics-resource conditions, followed
by a new full-strength capture under the unchanged ADR-017/ADR-020 protocol.
