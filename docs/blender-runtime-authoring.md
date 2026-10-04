# Explicit Blender runtime authoring v1

The Blender 5.1+ add-on can now author existing strict-v1 animation states, visual events,
sockets and optional locomotion rules. This is an **opt-in export boundary**. Runtime APIs
and descriptor schemas are unchanged. The legacy X5 authoring sidecar remains authoring-only;
its state/marker/socket metadata is not imported or reinterpreted.

## Blender sidebar

1. Save the `.blend`, use a single export-root collection and attach each Action to an object
   or an NLA strip. Unattached/fake-user-only Actions are not runtime clips.
2. Open **Runtime State / Event / Socket Editor** under the export panel. **Start New Text**
   prepares a first-state draft. Choose a namespaced state key and attached Action, then
   **Create New Text**. This creates a uniquely named `BlendLib.runtime.json` without
   overwriting any existing Text or enabling export. Alternatively create a Text in Blender's
   Text Editor and paste the [complete working configuration](../test-assets/blender-authoring/runtime-authoring.json).
3. In the BlendLib sidebar set project root, namespace, model ID, collection and profile.
   Enable **Runtime Animation Authoring** and pick that Text in **Runtime Authoring Text**.
   Blender-relative `//` project roots resolve relative to the saved `.blend`.
4. Export. Invalid settings report `BLENDLIB-AUTHOR-001`; existing successful assets are not
   replaced when authoring validation fails. This does not promise transactional filesystem
   recovery for I/O failures. Uncheck the toggle to retain historical automatic loop states.

There is no automatic Text-name discovery or hidden migration. The state/event/socket editor is a small
front end for the same strict Text, not a second asset or export format.
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

## State and event editor

- Choose a Text, select a **State**, then **Load State**. Changes to Action, Loop, Speed
  and event rows are drafts until **Apply to Selected Text**. **Add State** creates a draft
  with a new key; an existing state key cannot be renamed or overwritten through that flow.
- Action choices use exactly the exporter's supported object set and attached/NLA Actions.
  Camera/light-only and unattached/fake-user-only Actions are excluded. Marker search reads
  **Action pose markers** from the chosen Action. Create those markers in the Action Editor;
  the editor assigns them to namespaced visual event keys and never creates/moves markers.
  Use the arrows to retain intentional equal-time event ordering. Changing Action does not
  silently remap existing events; incompatible markers fail Apply.
- **Make Initial State** explicitly chooses the edited/added state. Unchecked means keep the
  existing initial state, including when editing that initial state itself. A new Text's first
  state is its initial state. The draft shows both the Action and state key before creation.
- Existing `next`, `blend_seconds`, other states, sockets and locomotion remain in the Text.
  Edit next/blend and locomotion in the Text Editor; sockets have their own draft below. Apply may reformat JSON whitespace, but
  preserves all untouched values (including double-precision numbers and absent fields).
  Unknown fields fail the same strict validation; they are never silently discarded.
- Speed accepts a JSON number such as `1` or `1.25` in (0,64]. Text input preserves precision
  that Blender float sliders would round. Invalid source references, events, loops targeted
  by locomotion, limits and other strict constraints fail without replacing the Text.
- Switching Text datablocks or editing Text contents while a draft is open makes Apply fail.
  **Discard** then reload to reconcile. Renaming the same Text is safe. A second load/new draft
  is blocked until Apply/Discard, so repeated clicks cannot silently throw away working edits.
- Save the `.blend` after applying. Export always reads the applied Text, never an open draft.
  Working drafts are discarded on file load or add-on restart. Blender can serialize Scene
  properties despite `SKIP_SAVE`, so explicit lifecycle cleanup enforces this boundary.
- Apply validates source facts only. Export still verifies actual GLB clip membership/bounds
  and exact socket paths. A successful Apply is not an export-success guarantee.
- The editor does not enable **Runtime Animation Authoring** by itself. The historical strict
  CLI opt-out and all X5 controls remain unchanged.

## Socket editor and local offsets

1. Prepare the scene first. For a local offset or orientation, create an ordinary
   **Empty**, put it inside the selected export collection and parent/transform it
   in Blender. Give it a globally unique name such as `GripSocket`. Existing
   strict-v1 positive uniform-scale and baked-constraint rules still apply. There
   are no socket offset/rotation fields: the exported helper's transform owns them.
2. In **Object Mode**, choose **Add Socket** or select a socket and **Load Socket**.
   This performs a real export to a private temporary directory using the same
   filtered collection and glTF options as the strict exporter. It does not write
   runtime assets or change the Text. The pass can take as long as a normal export.
3. Pick **Exported Node**. Choices distinguish `Object: GripSocket` from
   `Bone: Armature / Hand`; the exact active-scene GLB path is shown below. A bone's
   exported hierarchy is read from the GLB, never inferred from Blender names.
   The normal exporter rejects duplicate object/bone names, omitted source nodes
   or ambiguous hierarchy. Rename conflicting source nodes before loading a draft.
4. Set the namespaced socket key and **Apply Socket to Text**, then save the `.blend`.
   Apply repeats discovery and validates all socket paths with the common strict
   compiler. It preserves every other socket, animation, event and locomotion value.
   The editor neither creates helpers nor changes object/bone transforms.

State and socket editing share one working draft. Apply or Discard before switching.
Text selection/content changes, replaced same-name source objects/bones, collection
changes and exported path changes fail Apply without rewriting the Text. Entering
Armature Edit Mode or undo/redo invalidates an open socket draft, even if the
final names/transforms look unchanged, because Blender can reuse deleted bone
addresses. Discard
and reload after changing scene hierarchy. Loading an old socket whose path no
longer exists leaves the node unselected so you can choose its replacement; other
stale paths must also be repaired before the complete configuration validates.
An existing socket key cannot be renamed, silently overwritten through Add, or
deleted by this editor. Same-Text renames remain safe. Reload/restart discards only
the working draft, and export still reads only applied canonical Text.

For bone-relative offsets, prepare the Empty with Blender's normal bone-parenting
and transform tools, then discover its actual exported path. No parent-tail offset,
axis convention, or coordinate adjustment is guessed by the editor. Exported
transforms include Blender's one-time Y-up conversion and the runtime evaluates
that exact node hierarchy. Invalid/unexportable rigs must be fixed in the source.

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
python3 -B -m unittest discover -s blender-addon/tests -v
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_runtime_authoring.py -- --project-root .
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_authoring_editor.py -- --project-root .
```

The second command genuinely creates/saves a Blender source and exports twice, checks byte
identity, executes the registered sidebar operator, rejects invalid markers/stale rules while
preserving previous assets, and tests disabled behavior with malformed unselected Text.
The third command exercises real state/event editor operators, stale/renamed Text, explicit
initial state, new-Text opt-out, failed validation, draft lifecycle and editor-produced
exports byte-identical to the Java acceptance fixture. Existing P2 and X5 Blender
verification scripts remain separate regression gates.

All `blender-addon/` sources are GPL-3.0-or-later; Java runtime and first-party test assets retain
root Apache-2.0 scope. No Blender/Python/GPL source is linked into the runtime JAR.

Platform note: the legacy X5 atomic publication flow deliberately rejects this POSIX runtime
before staging (`BLENDLIB-X5-ATOMIC-001`, exact-handle publication unavailable). Its Blender
registration, preview and invalid-binding preflight can be checked here; successful X5 atomic
publication is not claimed by this batch. The strict exporter above and its real output pass
independently, without weakening X5's existing safety gate.

The [socket fixture](../test-assets/blender-sockets/README.md) includes transformed
Empty and armature/object examples, their portable `.blend` sources, runtime
exports and Blender-evaluated expected poses. Reproduce socket and restoration
checks with:

```sh
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_socket_editor.py -- --project-root .
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_socket_discovery_state.py -- --project-root .
```

Discovery requires Object Mode and exits early if any source is in NLA Tweak Mode;
leave tweak mode deliberately first. Both successful and interrupted discovery
restore source Actions, slots, unkeyed pose channels, NLA flags, scene frames and
window/view-layer context. All temporary files are removed after the pass.
