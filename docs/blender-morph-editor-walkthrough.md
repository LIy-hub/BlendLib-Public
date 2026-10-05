# Edit a face/squeeze morph control in Blender

Use **Blender 5.1.2** and the BlendLib add-on from this checkout. This walkthrough
uses `test-assets/blender-morph-authoring/source.blend`, an editable, two-bone face
with four relative shape keys: **Blink**, **Smile**, **Breath**, and **Squeeze**.
It has no Actions, animation clips, or placeholder animation state. Breath's
authored default is **0.15**; the other target defaults are zero.

For the packaged preview, use `BlendLib-26.3-beta4-batch-morph-authoring-preview.zip`
and its matching add-on/JAR set with the `batch-morph-authoring` qualifier, built
from branch `feat/mc26.3-batch-morph-authoring`.

## Open the prepared example

1. Keep `source.blend`, `surface.png`, and `detail.png` together. Open the `.blend`.
2. In the 3D View sidebar, open **BlendLib** and its **Runtime Authoring Editor**.
3. Confirm **Skinned CPU morph v1 (format 2)** is selected, the export collection
   is `BlendLibExport`, and the selected authoring Text is `BlendLib.runtime.json`.
4. Select `morph_editor:squeeze` under **Morph Control**, then **Load Control**.
   Loading makes a temporary real glTF export to discover the exact mesh/target
   pair. It does not write the source Text or exported resource files.
5. The selected pair is `MorphRoot/MorphRig/FaceBody` / `Squeeze`. Set the minimum
   to `0` and maximum to `0.75`, then choose **Apply Morph Control to Text**.
6. Save the `.blend`. Keep **Runtime Animation Authoring** enabled and choose
   **Export Model** to regenerate `exported/assets/morph_editor/...`.

Despite the checkbox's name, manual-only morph Text is supported. The saved Text
and exported descriptor contain `morph_controls` without an `animation` section.
The runtime alias is `morph_editor:squeeze`; the source mesh and shape-key names
remain unchanged.

## See the shape and try Discard

Select `FaceBody` and use Blender's normal **Object Data Properties → Shape Keys**
to preview `Squeeze`: positive values narrow and stretch the face/body. Return its
value to `0` before saving the supplied example. Keep Breath at `0.15`.

The authoring editor edits allowed runtime intervals, not preview weights. It
never changes the authored shape-key values. A shape-key default changed while a
draft is open makes that draft stale; **Discard**, then **Load Control** again.

To see the explicit-save boundary, load `morph_editor:squeeze`, change its maximum
to `0.5`, and choose **Discard**. Reload: the last applied maximum is still `0.75`.
Export always reads the canonical Text and ignores unapplied draft values.

## Start a new morph-only Text

On the supplied scene, **Start Morph-only Text** creates a draft, not a Text
datablock. Choose an alias and exact discovered target, then Apply. Only Apply
creates and selects the new Text; an existing selected Text is left intact, and
the export opt-in checkbox is not enabled automatically.

For a complete new Text on this scene, create/add all four controls:

| Alias | Target | Minimum | Maximum |
| --- | --- | ---: | ---: |
| `morph_editor:blink` | `Blink` | 0 | 1 |
| `morph_editor:smile` | `Smile` | -1 | 1 |
| `morph_editor:breath` | `Breath` | -0.5 | 1 |
| `morph_editor:squeeze` | `Squeeze` | -1 | 1 |

Each selects the same exact exported mesh path,
`MorphRoot/MorphRig/FaceBody`. Use **Add Control** for the remaining entries.
Incomplete drafts can be applied one control at a time, but final export requires
exactly one alias for every exported node/target pair.

## Batch setup for a model with many targets

Use **Draft Missing Controls** to fill every currently unbound exported mesh / shape-key
pair in the selected Text. The existing controls, aliases, ranges, states, events,
sockets and locomotion settings are preserved. A complete Text reports "No missing
morph controls" without opening a draft or rewriting Text, including its formatting.

For a new Text, choose **Draft All Controls in New Text**. This discovers all targets,
including the same target name on different meshes. The currently selected Text is
left intact, even if it is unrelated or invalid JSON. Only **Apply All Missing Controls
to Text** creates and selects the new Text. **Discard** creates nothing.

The draft list shows each proposed control. Select each row to inspect its full exact
mesh path and shape-key name, edit its alias or range, then Apply once for the complete
batch. Target bindings are fixed by discovery. Aliases use the scene namespace,
a readable normalized target slug and a deterministic exact-pair digest; Unicode,
punctuation, repeated target names and existing aliases cannot silently collide.
Existing alias collisions get a numeric suffix. The same unchanged source generates
the same proposals regardless of discovery order.

Ranges start at `[-1, 1]` and expand only as needed to include the authored default,
within `[-2, 2]`. For example, default `1.5` proposes `[-1, 1.5]`, while default `-1.5`
proposes `[-1.5, 1]`. Review ranges for the intended animation; this does not inspect
all animated extremes or change shape-key values. Full export still validates those
extremes. Out-of-bound defaults block setup instead of clamping source data.

Apply validates the whole batch before writing. Duplicate/invalid aliases, invalid
ranges, missing rows or stale source/Text reject the batch without a partial write.
An existing control pointing to a missing target or excluding its current default
reports that alias and asks you to edit it first; batch setup never replaces it.
Apply or Discard the active draft before starting another. Changing namespace after
starting does not rename already drafted aliases; edit them or discard and restart.

## Validation and recovery

- Aliases are namespaced resource IDs, such as `morph_editor:squeeze`. Existing
  aliases cannot be renamed through an edit draft
- Minimum and maximum are finite JSON numbers within `[-2, 2]`. The interval must
  include both zero and the authored default. For Breath, maximum `0.1` is invalid
- Duplicate aliases and duplicate node/target bindings are rejected. Select exact
  discovered pairs; object short names and guessed hierarchy paths do not work
- The CPU profile is required. The editor does not enable morphs in rigid,
  legacy-skinned, native-cubic, or X5 profiles
- Changing the selected Text or its contents while editing blocks Apply. Renaming
  that same Text datablock is safe
- Changing the export collection, source datablock identity, shape-key names or
  defaults, or profile blocks a stale Apply. Mesh/armature Edit Mode and undo/redo
  also invalidate the draft. Discard and reload against the current source
- A failed Apply leaves canonical Text untouched. Saving retains the working
  draft in the current session; reopening clears the draft and keeps applied Text
- Existing states, events, sockets, locomotion rules, and other controls survive
  an Add/Edit. Full export remains responsible for animated weight bounds, dense
  target data, normals, and the CPU profile's supported source restrictions

## Reproduce the example and checks

From the repository root, with the official Blender 5.1.2 on `PATH`:

```sh
python -B -m unittest discover -s blender-addon/tests -p test_authoring_morph_editor.py -v
blender --background --python-exit-code 1 \
  --python blender-addon/scripts/verify_morph_editor.py -- --project-root .
```

For the batch contracts and disposable real-Blender acceptance:

```sh
python -B -m unittest discover -s blender-addon/tests -p test_authoring_morph_batch.py -v
blender --background --python-exit-code 1 \
  --python blender-addon/scripts/verify_morph_batch.py -- --project-root .
```

Batch verification uses `build/morph-batch-acceptance` and does not rewrite the source
fixture. It covers two genuine meshes, Unicode/repeated target names, non-unit defaults,
editable atomic drafts, conflicts, Discard, export isolation, and save/reopen.

The single-control verification command regenerates the editable source, textures, strict exports,
canonical JSON, and `verification.json` under `test-assets/blender-morph-authoring`.
It also uses the existing animated CPU fixture to prove states/events/sockets/rules
are preserved. Reopen scratch output is isolated under `build/morph-editor-reopen`.

Verified locally with official Blender **5.1.2**, build **ec6e62d40fa9**, using the
SHA-256-pinned toolchain in the existing CI workflow. The evidence covers genuine
registered operators, glTF discovery, export, and save/reopen. It does not claim
interactive sidebar visual QA or Minecraft renderer verification.
