# Explicit Blender runtime authoring v1

The Blender 5.1+ add-on can now author existing strict-v1 animation states, visual events,
sockets and optional locomotion rules. This is an **opt-in export boundary**. Runtime APIs
and descriptor schemas are unchanged. The legacy X5 authoring sidecar remains authoring-only;
its state/marker/socket metadata is not imported or reinterpreted.

## Blender sidebar

1. Save the `.blend`, use a single export-root collection and attach each Action to an object
   or an NLA strip. Unattached/fake-user-only Actions are not runtime clips.
2. In Blender's Text Editor, create a Text datablock called `BlendLib.runtime.json` and paste
   the [complete working configuration](../test-assets/blender-authoring/runtime-authoring.json).
   Edit exact Action names, namespaced state/event/socket keys and exported node paths.
3. In the BlendLib sidebar set project root, namespace, model ID, collection and profile.
   Enable **Runtime Animation Authoring** and pick that Text in **Runtime Authoring Text**.
   Blender-relative `//` project roots resolve relative to the saved `.blend`.
4. Export. Invalid settings report `BLENDLIB-AUTHOR-001`; existing successful assets are not
   replaced when authoring validation fails. This does not promise transactional filesystem
   recovery for I/O failures. Uncheck the toggle to retain historical automatic loop states.

There is deliberately no automatic Text-name discovery, hidden migration or separate giant UI.
The Text is stored in the `.blend`; it is not a runtime resource. Selecting an absent Text fails.
CLI uses only its explicit flag, regardless of saved sidebar settings:

```sh
blender --background --python-exit-code 1 \
  --python blender-addon/scripts/export_blendlib.py -- \
  --blend test-assets/blender-authoring/source.blend --project-root build/my-export \
  --namespace blendlib_authoring --model-id actor --profile blendlib:rigid_v1 \
  --collection BlendLibExport --runtime-authoring-text BlendLib.runtime.json --report report.json
```

Omit `--runtime-authoring-text` for the historical automatic Action-to-loop mapping. The X5
export/batch controls retain their prior metadata and behavior; this new Text picker applies
only to the strict **Export BlendLib Model** button and explicit strict CLI flag. Passing the
new flag to X5 is rejected explicitly rather than silently discarded.

## Text contract

Root keys: `schema_version: 1`, required `animation`, optional `sockets` and `locomotion`.
Unknown/duplicate fields and non-finite JSON numbers fail. Text is limited to 1 MiB.

- `animation` contains `initial_state` and `states` (1..256). State keys are resource IDs.
  Each state requires exact attached Action `clip`, boolean `loop`, and `speed` in (0,64].
  Optional `next` names another authored state; `blend_seconds` is finite and nonnegative.
  A subset of attached clips may be exposed, or multiple states may reference one clip.
- A state's optional `events` contains up to 4096 `{ "marker": "Footstep", "event": "demo:footstep" }`
  entries (16,384 total across the descriptor). `marker` is an **Action pose marker**, not a scene timeline marker. Create it in
  the Action Editor's pose-marker context or through `action.pose_markers.new(name).frame = frame`.
  Each referenced marker must occur exactly once in that exact Action and lie within its range.
  Event keys carry visual notifications only, with no gameplay authority.
- Opted-in export samples the full Action range and slides each clip to zero. Effective FPS
  is `scene.render.fps / scene.render.fps_base`; event time is `(marker_frame - action_start) / FPS`.
  `speed` changes runtime playback, not stored event timestamps. The exporter checks actual GLB
  sample bounds against this time contract and fails if baking/cropping changes it. Duration
  must be positive and <=600 seconds. Fractional FPS is supported: this boundary detects and corrects Blender 5.1 glTF time inputs
  that use `fps * fps_base`, without changing scene settings or exporters already using effective
  FPS. Real Blender fractional-FPS regression coverage accompanies the pure calculation test.
  Unsupported fractional-frame
  ranges that bake differently are rejected. End markers are clamped only for floating-point
  roundoff to the actual exported duration. Equal-time events retain authored order.
- `sockets` maps up to 512 resource IDs to `{ "node": "Root/Hand" }`. Paths start at the
  exported scene root and match node/bone names exactly, including case. No leaf-name lookup,
  fuzzy resolution, offsets or coordinate conversion is inferred. Position follows the node's
  actual evaluated transform. Blender Y/Z conversion is performed once by the GLB exporter.
- `locomotion`, when supplied, is the existing [strict rule schema](../schemas/blendlib-locomotion-rules-v1.schema.json).
  Its `schema_version` is independently 1. Every target/default must be an authored loop with
  no `next`. Ordered rules, typed input names, boolean conditions, inclusive min/max thresholds,
  correct enter/exit hysteresis ordering, 32-rule/8-condition/32-uniquely-typed-input/64-KiB limits and integer 0..200
  `minimum_interval_ticks` are validated. Read [runtime integration](locomotion-rules.md) to
  provide typed input callbacks and opt a standard entity controller into these rules.

## Outputs and stale optional resources

The descriptor remains `assets/<namespace>/blend_models/<model-id>.json`; GLB and external PNG
paths are unchanged. Locomotion is written separately to
`assets/<namespace>/blend_animation_rules/<model-id>.json`. It is never put in the descriptor
or old X5 sidecar. Reports include the optional sidecar's path and SHA-256.

If explicit authoring removes `locomotion` while that sidecar already exists, export fails with
an instruction to remove it explicitly. This prevents stale rules from silently remaining active.
Disabled/automatic export never deletes or rewrites previously authored optional resources;
remove an old rules resource yourself when intentionally returning the model to manual behavior.

## Verified fixture and reproduction

[Source fixture](../test-assets/blender-authoring/source.blend) stores three genuine Actions
with frames 10..34 at 24 FPS: Idle, Walk and non-looping Attack→Idle; footstep at frame22/.5s,
impact at frame16/.25s, and a socket at `Root/Hand` with exported (1,1,0) position.
[Committed output](../test-assets/blender-authoring/exported/) is consumed by the Java
`BlenderAuthoringLocomotionAcceptanceTest`, including real visual-event callbacks and actual
client locomotion/layered playback. It does not claim a rendered Minecraft graphics pass.

```sh
python3 -m unittest discover -s blender-addon/tests -v
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_runtime_authoring.py -- --project-root .
```

The second command genuinely creates/saves a Blender source and exports twice, checks byte
identity, executes the registered sidebar operator, rejects invalid markers/stale rules while
preserving previous assets, and tests disabled behavior with malformed unselected Text.
Existing P2 and X5 Blender verification scripts remain separate regression gates.

All `blender-addon/` sources are GPL-3.0-or-later; Java runtime and first-party test assets retain
root Apache-2.0 scope. No Blender/Python/GPL source is linked into the runtime JAR.

Platform note: the legacy X5 atomic publication flow deliberately rejects this POSIX runtime
before staging (`BLENDLIB-X5-ATOMIC-001`, exact-handle publication unavailable). Its Blender
registration, preview and invalid-binding preflight can be checked here; successful X5 atomic
publication is not claimed by this batch. The strict exporter above and its real output pass
independently, without weakening X5's existing safety gate.
