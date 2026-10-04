# Explicit Blender runtime authoring v1

The Blender 5.1+ add-on can now author existing strict-v1 animation states, visual events,
sockets and optional locomotion rules. This is an **opt-in export boundary**. Runtime APIs
and descriptor schemas are unchanged. The legacy X5 authoring sidecar remains authoring-only;
its state/marker/socket metadata is not imported or reinterpreted.

## Blender sidebar

1. Save the `.blend`, use a single export-root collection and attach each Action to an object
   or an NLA strip. Unattached/fake-user-only Actions are not runtime clips.
2. Open **Runtime Authoring Editor** under the export panel. **Start New Text**
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

There is no automatic Text-name discovery or hidden migration. The state/event/socket/rule editor is a small
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

- Choose a Text, select a **State**, then **Load State**. Changes to Action, Loop, Speed, Next State, Blend In
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
- **Next State** and **Set Blend In** edit the selected state as described below.
  Other states, sockets and locomotion keep their own authored values. Apply may reformat JSON whitespace, but
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

## Transitions and blend controls

**Next State** offers **None**, **This State**, and existing states from the loaded
Text snapshot. For a new Text, only None/self exist until more states are added.
Create destination states first, then Load the source to choose its next state.
**This State** intentionally restarts a non-loop state when it finishes, and stays
bound to that draft's state key if a new-state key is edited before Apply.

- With **Loop** on, playback repeats; an authored Next is preserved but is not
  followed. Turn Loop off for an automatic completion transition. **None** omits
  `next`: a non-loop holds its final pose. The existing contract permits self and
  multi-state cycles with the exporter's positive-duration clips; the editor adds
  no new graph restrictions. Locomotion defaults and rule targets must still have
  Loop on and Next None. Attempting to change either incompatibly fails Apply.
- Enable **Set Blend In** and enter seconds such as `0.2`. This is the cross-fade
  duration when entering this state, whether by an explicit trigger or automatic
  next transition. For Attack→Idle, Idle's Blend In controls the return to Idle;
  Attack's controls entering Attack. It is not a wait before following Next.
- Blend accepts finite nonnegative JSON-number text, including precise doubles.
  An explicit `0` cuts immediately. Unchecking Set Blend In removes the field and
  uses the same zero runtime default; unchanged absent/explicit-zero values stay
  distinct in Text. Text input avoids Blender float-slider rounding.
- Apply validates the entire config before writing. Missing next references,
  invalid numbers and rule-invalidating edits leave canonical Text and the draft
  intact. External target rename/deletion or any Text edit requires Discard/reload;
  choices are never silently remapped. The editor does not rename/delete states.

### Complete common setup without editing JSON

1. Prepare Idle, Walk, Run and Attack Actions on the exported object/NLA, plus
   Footstep and Impact **Action pose markers**. Prepare a child Empty such as Hand
   for a socket. These are normal Blender source assets, not Text configuration.
2. Start New Text: key `demo:idle`, Idle Action, Loop on, Next None, Set Blend In
   on and `0.4`. Create New Text. Add Walk and Run with corresponding Actions,
   Loop on and Next None. Add Footstep event rows mapped to `demo:footstep`.
3. Add `demo:attack`, choose Attack, Loop off, Next `demo:idle`, Set Blend In on
   and `0.2`; map Impact to `demo:impact`. Set Speed to `2` only if twice-speed
   playback is desired. Apply. A one-second Attack clip then finishes in 0.5 real
   seconds and cross-fades into Idle over Idle's 0.4 seconds.
4. Add Socket, choose the discovered Hand node, set `demo:hand`, Apply. Load / Add
   Locomotion Rules: default Idle; add Run first with Number >= speed enter `2`,
   exit `1.5`, then Walk with enter `0.1`, exit `0.05`. Apply Rules to Text.
5. Save the `.blend`, enable Runtime Animation Authoring, and export. The existing
   runtime integration supplies the typed speed input and triggers Attack; the
   editor does not invent gameplay inputs or event authority.

The [transition fixture](../test-assets/blender-transitions/README.md) performs all
these configuration steps through real Blender operators, including socket/rule
creation. It also demonstrates non-loop hold, self/cycles and loop-with-next
semantics, then verifies actual exported next traversal and blend poses in Java.

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

State, socket and locomotion editing share one working draft. Apply or Discard before switching.
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
  Optional `next` names an authored state (including itself); `blend_seconds` is finite and nonnegative.
  Loop repeats instead of following next; non-loop follows next at completion or holds its last pose.
  Blend is the entered state's cross-fade duration in wall-clock seconds, independently of playback speed.
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

## Locomotion rule editor

Choose **Load / Add Locomotion Rules** in **Runtime Authoring Editor**. It loads
existing rules or starts an empty draft if the Text has no locomotion section.
Choose **Default Loop** explicitly. Target choices include only authored loop
states without `next`; the editor never substitutes a different state. Create
Idle, Walk and Run first using the state editor if they do not yet exist.

- Add a rule, choose its **Target Loop**, then add its conditions. All conditions
  must match; a rule with no conditions is unconditional. Rule order is priority:
  the first matching row wins. Use the arrows deliberately to put Run above Walk.
  Removing a row only removes that draft row until Apply. There is no implicit
  sorting, deduplication, section removal or sidecar deletion.
- Choose **Boolean**, **Number >=** or **Number <=** per condition. Boolean has an
  exact **Equals** checkbox. Numeric conditions have separate **Enter Threshold**
  and **Exit Threshold** JSON-number text fields (write `0.1`, not `.1`). `>=`
  requires Exit <= Enter; `<=` requires Enter <= Exit. Comparisons include the
  boundary. These distinct shapes cannot emit mixed min/max or boolean bounds.
- Use the same type everywhere an input name appears. Input names are ASCII
  identifiers and are supplied by your existing client integration, not inferred
  from Blender. **Minimum Interval (ticks)** is 0..200; it holds accepted state
  changes for that many ticks. Existing runtime input/evaluation behavior is
  unchanged. An absent interval stays absent on a no-op zero-valued edit.
- **Apply Rules to Text** validates the whole config through the common strict
  compiler. It preserves all states, events, sockets, advanced fields and numeric
  precision. Invalid values leave both the Text and recoverable draft intact.
  Repeated Load and cross-editor switching require Apply or Discard first.
- **Discard** never changes the Text. Text identity/content guards and save/reload
  behavior are shared with the state editor. Export uses only applied Text even
  while an invalid draft is open. This editor does not turn export on or remove an
  optional sidecar. Remove the section/old resource explicitly when desired.

The [idle/walk/run fixture](../test-assets/blender-rules/README.md) was authored
with these genuine Blender operators and consumed by Java runtime tests. Rebuild:

```sh
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_rules_editor.py -- --project-root .
```
