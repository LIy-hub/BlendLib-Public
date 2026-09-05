# P4 isolated real-client evidence — 2026-07-29

Status: scoped manual evidence PASS. This document does **not** promote P4 to a
Gate PASS; P3/P4's separate audit and the remaining ADR-014 material visual
matrix are still pending.

## Scope and safety

All interaction used only:

- Client run directory: `D:\BlendLib\blendlib-showcase\run\client`
- Temporary local world: `BlendLib Visual RC`
- Fixture sources: `D:\BlendLib\test-assets\p4-resource-packs\`

No formal Minecraft server, production world, remote service, or external
resource-pack directory was started, stopped, changed, or copied into. The two
fixture copies were removed after their tests; the final isolated
`run\client\resourcepacks` directory is empty.

## Fixture compatibility repair

The first 26.1.2 client load rejected the old `pack_format`-only metadata:

```text
Pack declares support for version newer than 64, but is missing mandatory fields min_format and max_format
```

The two source-controlled fixture metadata files were repaired to
`min_format: 84` / `max_format: 84`, based on the local 26.1.2 runtime and
`PackFormat$IntermediaryFormat` bytecode. The isolated resource-pack UI then
listed and enabled both fixtures without the metadata fallback error.

## Observed sequence

| Item | Result | Evidence |
| --- | --- | --- |
| Baseline static/rigid entity | PASS — blue/purple static mesh was visible in the isolated world. | `2026-07-29_19.29.32.png` |
| Hand item / ADR-013 marker path | PASS — `blendlib_showcase:static_rigid_item` was visible in first-person hand. | `2026-07-29_19.31.15.png` |
| Valid override UI and visual replacement | PASS — resource-pack UI placed `valid-override` above the bundled Showcase pack; the same scene rendered orange rigid geometry instead of the blue/purple baseline. | `2026-07-29_19.52.42.png`, `2026-07-29_20.09.07.png` |
| Valid F3+T reload | PASS — client chat said resource packs reloaded and logger published generation `3`, `models=4`, `missing=0`, `diagnostics=0`; inspect reported `missing=false diagnostic=none`. | Terminal excerpt below; `2026-07-29_20.09.07.png` |
| Baseline restore after valid | PASS — after removing only `valid-override`, baseline visual returned and final reload state had no missing model. | `2026-07-29_19.55.58.png` |
| Malformed override / fallback | PASS — `malformed-missing-mesh` was selectable above Showcase; it displayed the missing-model fallback. | `2026-07-29_20.01.20.png`, `2026-07-29_20.16.12.png` |
| Malformed F3+T / diagnostic | PASS — logger published generation `3`, `models=4`, `missing=1`, `diagnostics=1`; inspect reported `BLENDLIB-DESC-002`, `/mesh`, and the intentionally missing GLB. | Terminal excerpt below; `2026-07-29_20.16.12.png` |
| Same-generation diagnostic repeat | PASS — two consecutive model-specific `diagnostics` commands both returned the same structured primary diagnostic for generation `3`; no per-frame primary error was observed during the client session. | Terminal excerpt below |
| Final baseline restore | PASS — after removing only `malformed-missing-mesh`, F3+T logged generation `2`, `models=4`, `missing=0`, `diagnostics=0`; inspect reported `diagnostic=none`. | `2026-07-29_20.20.15.png` |

## Command and logger excerpts

All commands accepted the unquoted namespaced model key.

```text
20:08:22 [Render thread/INFO] [System] [CHAT] [调试]： 已重新加载资源包
20:08:23 blendlib_reload candidate_generation=3 active_generation=3 published=true stale=false models=4 missing=0 diagnostics=0
20:09:08 [System] [CHAT] BlendLib model=blendlib_showcase:fixtures/static_model generation=3 discovered=true missing=false diagnostic=none

20:14:31 [Render thread/INFO] [System] [CHAT] [调试]： 已重新加载资源包
20:14:32 blendlib_reload candidate_generation=3 active_generation=3 published=true stale=false models=4 missing=1 diagnostics=1
20:15:19 [System] [CHAT] BlendLib model=blendlib_showcase:fixtures/static_model generation=3 discovered=true missing=true diagnostic=ERROR BLENDLIB-DESC-002 model=blendlib_showcase:fixtures/static_model resource=blendlib_showcase:models3d/fixtures/does_not_exist.glb location=/mesh message=Missing required resource
20:15:35 [System] [CHAT] ERROR BLENDLIB-DESC-002 model=blendlib_showcase:fixtures/static_model resource=blendlib_showcase:models3d/fixtures/does_not_exist.glb location=/mesh message=Missing required resource
20:15:48 [System] [CHAT] ERROR BLENDLIB-DESC-002 model=blendlib_showcase:fixtures/static_model resource=blendlib_showcase:models3d/fixtures/does_not_exist.glb location=/mesh message=Missing required resource

20:19:37 [Render thread/INFO] [System] [CHAT] [调试]： 已重新加载资源包
20:19:38 blendlib_reload candidate_generation=2 active_generation=2 published=true stale=false models=4 missing=0 diagnostics=0
20:20:15 [System] [CHAT] BlendLib model=blendlib_showcase:fixtures/static_model generation=2 discovered=true missing=false diagnostic=none
```

The two repeated `diagnostics` command outputs are expected command responses;
the one-primary-detail-per-key/generation logger behavior is separately covered
by the P4 listener/deduplicator automated tests. This manual observation does
not claim that a diagnostics command may only be run once.

## Screenshots and SHA-256

All screenshot paths are under
`D:\BlendLib\blendlib-showcase\run\client\screenshots\`.

| File | SHA-256 |
| --- | --- |
| `2026-07-29_19.29.32.png` | `FA28860F7852713544D13936FAF5A0001D8227D9AFDAE1F2DA5A39C2BC793A39` |
| `2026-07-29_19.31.15.png` | `5267A9D817117FA356C53650D9F0189F0C9C7EFF733CA7B040F55858239E5654` |
| `2026-07-29_19.52.42.png` | `0FC430C575572CCC2860CB9F4A1EDA1F194C3CD45147E8FF4F72CA10E0D3D3C8` |
| `2026-07-29_19.55.58.png` | `8E2EB2166D73FADD76FEC5BC1C9EAA0F95897F7512F5F8C8A65F5520C7364619` |
| `2026-07-29_20.01.20.png` | `93AD8E50167749C491E58D7C8A85001F9CE7E2CAEBB9AA4C3D69726ABF629102` |
| `2026-07-29_20.09.07.png` | `5F59F1D31B4BE29634E4D6D63A08842215D683638BAA0D4C270D58447818E499` |
| `2026-07-29_20.16.12.png` | `0B23DB663F48495515BB197D8E840DD333B3CB7702FAB30C1B1B4884B72BF498` |
| `2026-07-29_20.20.15.png` | `83899B9BADFB7CC3B54F62104A72C4A2174AB9E46983B68B3F7036022E3A6538` |

## Remaining limits

- The real supported material mode/culling/texture/emissive matrix still needs
  its own controlled P4 visual evidence; this run did not claim it.
- P3/P4 Gate review remains user-managed and is not waived by this document.
- This does not substitute for P5 animation/skinning, P6 two-client sync, P7
  performance/Iris-Sodium, or P8 release Gate evidence.
