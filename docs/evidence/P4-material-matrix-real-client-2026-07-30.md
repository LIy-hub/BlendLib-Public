# P4 material matrix — partial real-client evidence (2026-07-30)

Status: **PARTIAL REAL-CLIENT MATRIX EVIDENCE / P4 GATE WAITING**.

This is a narrowly scoped additive record of real Minecraft 26.1.2
resource-pack observations.  It does not promote P4, P3, or any material-matrix
row to a phase Gate PASS.

## ADR-019 accepted alignment

On 2026-07-30 the local project owner accepted ADR-019. It corrects the two
exact-0.10 single-sided cutout rows to supported `entityCutoutCull` inputs;
it does not reinterpret any prior rejection observation or create a visual
result. This record contains no fresh isolated-client observation of
`supported-cutout-single-sided-threshold-010-{lit,emissive}`. Each row remains
waiting for its own one-pack/F3+T/baseline-restore run with `missing=false` and
`diagnostic=none`. P4 remains WAITING and no material-matrix/P4 Gate PASS is
claimed here.

## Isolated scope

The only client run directory was:

```text
D:\BlendLib\blendlib-showcase\run\client
```

The only world entered was the disposable local `BlendLib Visual RC` world
under that directory.  Each pack was temporarily exposed to that directory as
one junction, selected above the built-in Showcase pack, and then reloaded.
The source fixtures remained under
`D:\BlendLib\test-assets\p4-resource-packs\material-matrix\`.

The first session used three packs, one at a time:

- `supported-opaque-single-sided-lit`
- `supported-opaque-single-sided-emissive`
- `rejected-opaque-double-sided-lit`

A later isolated session exercised two additional packs, again one junction at
a time and with a baseline reload between packs:

- `supported-cutout-double-sided-threshold-010-lit`
- `supported-cutout-double-sided-threshold-010-emissive`

A third isolated session, after the scoped P5 cache-teardown remediation,
exercised the two remaining supported translucent packs in the same
one-junction / baseline-between-packs pattern:

- `supported-translucent-double-sided-lit`
- `supported-translucent-double-sided-emissive`

A fourth isolated session exercised one non-conflicting rejected cutout row:

- `rejected-cutout-double-sided-threshold-025-lit`

A fifth isolated session exercised its non-conflicting emissive partner:

- `rejected-cutout-double-sided-threshold-025-emissive`

After the documented normal title-screen exits, no temporary junction remained
in active `run\client\resourcepacks`.  The source fixture directories were
independently rechecked as present.  Where a temporary link was retained for
recoverability, it was moved to the ignored
`build\p4-resourcepack-junction-quarantine` path rather than deleting its source
fixture.  No formal server, formal world, `D:\MinecraftFabricServer-26.1.2`, or
`D:\MinecraftFabricServer-26.1.2-Fresh` was changed.

## Direct observations

| Row sampled | Actual result | Supporting in-session evidence |
| --- | --- | --- |
| `supported-opaque-single-sided-lit` | The static fixture was visible from its observed front (`+Z`) view.  After teleporting to the observed back (`-Z`) view, the fixture geometry was not visible while its scene markers/shadows remained.  This is a single front/back culling observation, not a proof for every mesh angle. | The resource-pack selection screenshot is `01.16.29`; `latest.log` records the pack in `Reloading ResourceManager` at `01:16:47` and again after F3+T at `01:19:37`.  `inspect` then reported generation `3`, `missing=false`, `diagnostic=none`, and `diagnostics` reported `none`.  Front/back screenshots are `01.20.26` and `01.23.54`. |
| `supported-opaque-single-sided-emissive` | At the same dark camera position, the sampled orange fixture was visibly brighter than the corresponding lit capture.  The comparison is a real-client visual sample only; it does not quantify luminance or prove all emissive/material combinations. | `latest.log` records this pack at `01:36:25`, its dark screenshot at `01:38:24`, and a subsequent F3+T reload at `01:38:50`.  The corresponding lit dark capture is `01.29.57`. |
| `rejected-opaque-double-sided-lit` | The fixture became the missing-model fallback rather than silently rendering an ordinary-world double-sided opaque surface.  `inspect` reported `generation=8`, `missing=true`, and `BLENDLIB-MAT-004`; a later `diagnostics` command returned the same structured diagnostic. | `latest.log` records the pack at `01:44:41` and again after F3+T at `01:49:13`; it records `BLENDLIB-MAT-004` at `01:51:20` and the repeated diagnostic at `01:53:35`.  The fallback screenshot is `01.56.00`. |

The observed rejected-row diagnostic was:

```text
ERROR BLENDLIB-MAT-004
resource=blendlib_showcase:blend_models/fixtures/static_model.json
location=/materials/RigidSurface/double_sided
message=OPAQUE_DOUBLE_SIDED_UNSUPPORTED: Minecraft 26.1.2 exposes no verified ordinary-world double-sided opaque public path
```

## Additional cutout observations

The additional session used only the same isolated run directory and disposable
world.  It first selected the cutout/lit pack alone, then removed it and
reloaded baseline, selected the cutout/emissive pack alone, removed it and
reloaded baseline, and finally repeated the lit pack before the final baseline
reload.  No source fixture directory was edited.

| Row sampled | Actual result retained | Supporting in-session evidence |
| --- | --- | --- |
| `supported-cutout-double-sided-threshold-010-lit` | The selected pack loaded as a valid model: `inspect` reported generation `3`, `discovered=true`, `missing=false`, `diagnostic=none`, and `diagnostics` reported `none`.  A later independent re-selection reported generation `11` with the same no-missing/no-diagnostic result.  The retained screenshots are qualitative scene samples only; they do not prove every face angle or the exact 0.10 alpha edge. | The selection UI is `02.20.43`; the log records the pack at `02:21:05`, explicit F3+T at `02:22:09`, inspection at `02:23:23`, diagnostics at `02:24:29`, and scene samples `02.25.00`/`02.26.32`.  After a baseline reload at `02:56:00`/`02:57:37`, the second lit load is recorded at `03:03:42`/`03:05:22`, with generation `11` at `03:07:12` and dark scene samples `03.10.58`/`03.13.30`. |
| `supported-cutout-double-sided-threshold-010-emissive` | The selected pack loaded as a valid model: `inspect` reported generation `7`, `discovered=true`, `missing=false`, `diagnostic=none`, and `diagnostics` reported `none`.  A freshly summoned disposable `static_rigid` sample was visibly orange at the same isolated midnight test condition.  Compared with the separately reloaded lit sample, it appeared qualitatively brighter, but rain, scene movement, and camera variation make this neither a numeric luminance measurement nor a proof for all meshes. | The pack appears in the selection UI `02.36.52`; the log records it at `02:37:18`, F3+T at `02:38:36`, inspection at `02:40:00`, diagnostics at `02:41:19`, disposable summon at `02:43:33`, and scene screenshots `02.47.16` and midnight `02.50.40`. |

After the final removal, a baseline F3+T was completed at `03:22:13`; the
remaining junction and then its empty generated `resourcepacks` directory were
removed only after the client process had exited.  The lit and emissive source
fixture directories were rechecked as present.  This additional session exposed
a separate P5 teardown `ConcurrentModificationException` while returning to the
title screen; it occurred after all material observations and all dimensions
were saved, is recorded in `latest.log` at `03:22:53`, and is not counted as a
normal P4 client exit.  The scoped cache remediation and its later normal
isolated-client exit are recorded separately in
`docs/evidence/P5-client-disconnect-crash-remediation.md`.

## Additional translucent observations

The third session selected `supported-translucent-double-sided-lit` above the
built-in pack, completed F3+T, inspected the model, queried diagnostics, then
removed it and completed a baseline F3+T.  It then repeated that exact sequence
for `supported-translucent-double-sided-emissive`, finally restored baseline,
saved to the title screen, and exited normally.  Exactly one temporary
junction existed at each material observation.

| Row sampled | Actual result retained | Supporting in-session evidence |
| --- | --- | --- |
| `supported-translucent-double-sided-lit` | The selected pack loaded as a valid model: `inspect` reported generation `3`, `discovered=true`, `missing=false`, `diagnostic=none`, and `diagnostics` reported `none`.  The retained scene sample is a qualitative observation only; it does not establish every transparency ordering, view angle, or arbitrary alpha value. | UI selection `03.49.13`; resource manager load `03:49:36` and explicit F3+T `03:50:54`; inspection `03:52:44`; diagnostics `03:54:13`; scene screenshot `03.54.42`. |
| `supported-translucent-double-sided-emissive` | The selected pack loaded as a valid model: `inspect` reported generation `7`, `discovered=true`, `missing=false`, `diagnostic=none`, and `diagnostics` reported `none`.  The night scene sample shows the configured sample remains present under the real client, but does not quantify fullbright behavior or prove every blend ordering. | UI selection `04.09.42`; resource manager load `04:10:16` and explicit F3+T `04:12:56`; inspection `04:15:40`; diagnostics `04:18:55`; scene screenshot `04.19.58`. |

The final baseline F3+T is logged at `04:30:37`.  The client returned through
the title screen, saved every dimension at `04:33:04`, and logged normal
`Render thread ... Stopping!` at `04:34:17`; no new crash-report file appeared.
Only after that exit was the remaining emissive junction removed, its source
fixture rechecked, and the now-empty generated `resourcepacks` directory
removed.

The client completed a normal save/return-to-title/quit sequence.  Gradle
reported `BUILD SUCCESSFUL`; the client `crash-reports` directory still contains
only the two pre-existing reports dated 2026-07-29.

## Additional rejected opaque/emissive observation

After the P5 cache-teardown remediation, a further isolated session exercised
`rejected-opaque-double-sided-emissive` alone. The sole temporary junction was
selected above the built-in pack, F3+T completed, and the same disposable
`BlendLib Visual RC` world was used. This row is a rejection observation only;
it does not assert an emissive/culling fallback or a visual material result.

| Row sampled | Actual result retained | Supporting in-session evidence |
| --- | --- | --- |
| `rejected-opaque-double-sided-emissive` | `inspect` reported `generation=3`, `discovered=true`, `missing=true`, and `BLENDLIB-MAT-004` at `/materials/RigidSurface/double_sided`. The first and repeated `diagnostics` commands returned the same structured `OPAQUE_DOUBLE_SIDED_UNSUPPORTED` error in that generation; therefore emissive did not bypass the strict opaque/double-sided rejection. | The enabled-pack UI screenshot is `05.09.24`; resource-manager loads with the single pack are logged at `05:09:34` and after F3+T at `05:10:25`. `inspect` is logged at `05:11:20`; first/repeated diagnostics at `05:12:09` and `05:13:28`; corresponding screenshot files are `05.11.33`, `05.12.22`, and `05.13.50`. |

The selected pack was removed in the UI, the baseline resource manager reloaded
at `05:16:22`, and the explicit baseline F3+T is logged at `05:17:35` without
the `file/rejected-opaque-double-sided-emissive` provider. The client saved all
three dimensions at `05:18:33` and logged normal `Render thread ... Stopping!`
at `05:18:51`; no new crash report appeared. The active `run\client\resourcepacks`
directory has no entries. Permanent deletion of the exact temporary junction
was blocked by the local execution policy, so it was moved (not copied) to the
recoverable ignored path
`D:\BlendLib\build\p4-resourcepack-junction-quarantine\rejected-opaque-double-sided-emissive`.
Its source fixture remains intact and no active client resource-pack link
exists.

## Additional rejected cutout-threshold observation

After the normal opaque/emissive session, a fresh isolated client selected
`rejected-cutout-double-sided-threshold-025-lit` alone above the built-in
Showcase pack.  It completed the pack-change reload and a separate F3+T,
inspected the static model, queried diagnostics twice, spawned the disposable
`static_rigid` entity only in `BlendLib Visual RC`, then removed the pack,
completed both the baseline pack reload and a baseline F3+T, saved to title,
and quit normally. This retained non-0.10 rejection observation does not
provide a real-client observation for either newly supported ADR-019
single-sided exact-0.10 cutout row.

| Row sampled | Actual result retained | Supporting in-session evidence |
| --- | --- | --- |
| `rejected-cutout-double-sided-threshold-025-lit` | `inspect` reported `generation=3`, `discovered=true`, `missing=true`, and `BLENDLIB-MAT-004` at `/materials/RigidSurface/cutout_threshold`.  The first and repeated `diagnostics` commands returned the same structured `CUTOUT_THRESHOLD_UNSUPPORTED` diagnostic in that generation.  A disposable `static_rigid` spawn visibly showed the missing-model purple/black fallback rather than an ordinary cutout rendering. | `latest.log` records the pack in `Reloading ResourceManager` at `05:57:11` and after explicit F3+T at `05:58:12`; inspection is at `06:01:55`, first/repeated diagnostics at `06:03:06` and `06:03:56`, and the disposable spawn at `06:08:17`.  Screenshot `06.05.05` retains the repeated same-generation chat diagnostics; screenshot `06.08.30` retains the fallback after spawn. |

The selected pack was disabled before the baseline resource reload at `06:09:38`;
the explicit baseline F3+T is logged at `06:10:32` without a
`file/rejected-cutout-double-sided-threshold-025-lit` provider.  The isolated
server saved all dimensions and stopped at `06:11:52`; the client logged
normal `Render thread ... Stopping!` at `06:12:05`.  No new crash-report file
appeared.  The temporary junction was moved, not copied or deleted, to the
recoverable ignored path
`D:\BlendLib\build\p4-resourcepack-junction-quarantine\rejected-cutout-double-sided-threshold-025-lit`;
its source fixture was rechecked and active `run\client\resourcepacks` has
zero entries.

## Additional rejected cutout-threshold emissive observation

A fresh isolated client selected
`rejected-cutout-double-sided-threshold-025-emissive` alone above the built-in
Showcase pack.  It completed the pack-change reload and a separate F3+T,
inspected the static model, queried diagnostics twice, spawned the disposable
`static_rigid` entity only in `BlendLib Visual RC`, then removed the pack,
completed both the baseline pack reload and a baseline F3+T, saved to title,
and quit normally. This retained non-0.10 rejection observation does not
provide a real-client observation for either newly supported ADR-019
single-sided exact-0.10 cutout row.

| Row sampled | Actual result retained | Supporting in-session evidence |
| --- | --- | --- |
| `rejected-cutout-double-sided-threshold-025-emissive` | `inspect` reported `generation=3`, `discovered=true`, `missing=true`, and `BLENDLIB-MAT-004` at `/materials/RigidSurface/cutout_threshold`.  The first and repeated `diagnostics` commands returned the same structured `CUTOUT_THRESHOLD_UNSUPPORTED` diagnostic without a reload between them.  A disposable `static_rigid` spawn visibly showed the missing-model purple/black fallback rather than an ordinary cutout rendering. | `latest.log` records the pack in `Reloading ResourceManager` at `06:45:11` and after explicit F3+T at `06:46:35`; inspection is at `06:49:35`, first/repeated diagnostics at `06:51:18` and `06:54:08`, and the disposable spawn at `06:56:36`.  Screenshot `06.56.57` retains the fallback after spawn. |

The selected pack was disabled before the baseline resource reload at `06:58:55`;
the explicit baseline F3+T is logged at `07:00:31` without a
`file/rejected-cutout-double-sided-threshold-025-emissive` provider.  The
isolated server saved all dimensions and stopped at `07:01:47`; the client
logged normal `Render thread ... Stopping!` at `07:02:16`.  No new crash-report
file appeared.  The temporary junction was moved, not copied or deleted, to the
recoverable ignored path
`D:\BlendLib\build\p4-resourcepack-junction-quarantine\rejected-cutout-double-sided-threshold-025-emissive`;
its source fixture was rechecked and active `run\client\resourcepacks` has zero
entries.

## Screenshots and SHA-256

All files are under
`D:\BlendLib\blendlib-showcase\run\client\screenshots\` and were produced by
the running client with F2.  Hashes were calculated after the client exited.

| File | SHA-256 | Meaning retained |
| --- | --- | --- |
| `2026-07-30_01.16.29.png` | `8F7E100AFC084249F6479A4D14F85AFD2D3C45E4E624B93A306C84E880332D34` | UI shows the supported opaque/single-sided/lit pack above the built-in pack. |
| `2026-07-30_01.20.26.png` | `6AEB8618805912FF87AA314C80CDC98616886D2E21F44D255ED40606CA4B0838` | Observed front view of the supported lit sample. |
| `2026-07-30_01.23.54.png` | `52C8D2408F78B563691655E82E3F3CCA9F736354842C640BAACFE1B8F476A519` | Observed back view of the supported lit sample, with geometry culled. |
| `2026-07-30_01.29.57.png` | `4188DDE382E747582C8EA07E822208130CFCAB1B6F7273EC63955C7A5E996CAA` | Dark-world lit reference sample. |
| `2026-07-30_01.38.24.png` | `2E63C3BF977730AC78ADC41ACA7965C7A6131874C9163FACFBDF06C1A1D693E0` | Dark-world emissive sample for the same comparison. |
| `2026-07-30_01.56.00.png` | `B910C5C48E9E40DD02F7B2B45D456AF8F65FC472CDD966398946BD9501EE1816` | Rejected opaque/double-sided fallback sample. |
| `2026-07-30_02.20.43.png` | `1B4893B83AF50B55A1D9128263C80AD69E1661932800FAD4509CA8116BC78BD8` | UI shows the cutout/double-sided/threshold-0.10/lit pack selected above the built-in pack. |
| `2026-07-30_02.25.00.png` | `8C45075DC4BC175B81255034D8E099FF17E693BFE861F521DC59EA8B35FD1BD1` | Lit cutout scene sample. |
| `2026-07-30_02.26.32.png` | `DD4405D99FEF1F3B65084492B8000F56BD580A09F576BE68B7BC93147D70F969` | Lit cutout alternate-camera scene sample. |
| `2026-07-30_02.36.52.png` | `534ECD9056AF69101FA7A60651DAAC917BA5EB5B1C61AA20D8DA95F4A271AA18` | UI shows the cutout/double-sided/threshold-0.10/emissive pack selected above the built-in pack. |
| `2026-07-30_02.47.16.png` | `CDA294CCDEDA16ABE2258F5902C84513FE1051A646CB25075B5666424CC284B0` | Emissive cutout scene sample after disposable `static_rigid` summon. |
| `2026-07-30_02.50.40.png` | `3E539FD556FCD7D0238A76AE31FD89E132610292899B36D6AC05FDC0AAF764E3` | Emissive cutout midnight qualitative-brightness sample. |
| `2026-07-30_03.10.58.png` | `AEE5AA9412B1ABED72EBA677866AD2FAD6CD92FEDED4B66F4DE1772F456E05CB` | Re-selected lit cutout midnight scene sample. |
| `2026-07-30_03.13.30.png` | `99003DF9EC7D1756520369DD2DA365AE4A0FDA813313540FFE42BDF03D72406B` | Re-selected lit cutout alternate-camera midnight sample. |
| `2026-07-30_03.47.55.png` | `6B6575DA787E5602D02BD3B1B1AB3F6916F1D71B9CE0BE8B2DCB1066DEC0DC18` | Translucent/lit pack available before selection. |
| `2026-07-30_03.49.13.png` | `5196299974B85F2482590D5CACB8B29833FAB170868041E541DB868947151840` | UI shows the translucent/double-sided/lit pack selected above the built-in pack. |
| `2026-07-30_03.54.42.png` | `3BE64652072E93D25C8CB455F82FEB0D4EE4349BEAF76CE68DC715EA4F5EE882` | Translucent/lit scene sample after valid inspection and no diagnostics. |
| `2026-07-30_04.09.42.png` | `087CBE8251F45EAB855419BF2C7A71B0034B7B646AF8BFE9B3BBBA50C868340D` | UI shows the translucent/double-sided/emissive pack selected above the built-in pack. |
| `2026-07-30_04.19.58.png` | `ED17C48FECBFFE9861727CF43A107E6362E66E1B0EEEB42D628D074A60CD6F76` | Translucent/emissive night scene sample after valid inspection and no diagnostics. |
| `2026-07-30_05.09.24.png` | `6BF50A644110E6322A16FD1974319D62FF82557D0FBA58822666939954899279` | UI shows the rejected opaque/double-sided/emissive pack enabled above the built-in pack. |
| `2026-07-30_05.11.33.png` | `1C266F70F08B012FEBAC0C266F3B87DEEDDCE54AA047FD55787A0DE0AA14ACB5` | `inspect` shows generation 3, missing model, and `BLENDLIB-MAT-004`. |
| `2026-07-30_05.12.22.png` | `63B3EFCA5AB0CBD711D26E124EC8E1F525F1AE5976AAED6615983CC76A884A3C` | First same-generation structured diagnostic. |
| `2026-07-30_05.13.50.png` | `EF33E96449B5254EEEDF8789B98FC9A740E7B94AAC2D3E0B7AC6F0EC5A5A5A50` | Repeated same-generation structured diagnostic. |
| `2026-07-30_06.05.05.png` | `7368B7F54BF35DE244EA1648A3BA46E51D8EE6FA4D0449D0E7D563D07EABC4DB` | First and repeated `CUTOUT_THRESHOLD_UNSUPPORTED` diagnostics in generation 3. |
| `2026-07-30_06.08.30.png` | `1600D3E46AE14101678777F6AF8159B2F4E2D16E4EB35B5D50D1EC66A932E451` | Disposable `static_rigid` missing-model fallback sample. |
| `2026-07-30_06.56.57.png` | `B67CF37B885182F5028F9257981BD248FB294D184A64A6005E50F9CE02C20309` | Disposable `static_rigid` missing-model fallback sample for the cutout/0.25/emissive rejection. |

## Limits and remaining work

- This record exercises 6 of 8 supported pack paths (opaque/single-sided,
  cutout/double-sided/threshold-0.10, and translucent/double-sided lit/emissive
  pairs) plus 4 of 10 rejected paths: opaque/double-sided lit and emissive, and
  cutout/double-sided threshold-0.25 lit and emissive. The two accepted
  exact-0.10/single-sided cutout supported rows still need fresh observations;
  the other six rejected paths, including translucent single-sided and
  additive, are likewise not covered. These coverage counts are not a visual
  or Gate PASS. The added evidence proves valid reload/diagnostic handling with
  qualitative scene samples, not universal double-sided, alpha-edge,
  translucency-order, or luminance proof.
- Screenshots cannot prove every view direction, sorting path, blend edge, or
  material numeric property.  The evidence only supports the specific sampled
  front/back, dark lit/emissive, and strict-rejection observations above.
- The existing P4 baseline/valid/malformed reload record remains in
  `docs/evidence/P4-manual-client-2026-07-29.md`; this document is additive.
- P3/P4 user-managed audit, the remaining matrix rows, P5 animation evidence,
  P6 sync, P7 performance/Iris-Sodium, and P8 release conditions remain
  separate work.  No phase commit, push, tag, publication, or production
  deployment is authorized by this record.
