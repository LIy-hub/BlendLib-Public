# ADR-009: Scoped GPL-3.0-or-later for the Blender Add-on

Status: Accepted — directory-limited user authorization

## Decision

The user explicitly authorizes GPL-3.0-or-later for the complete
`blender-addon/` directory only. Its Blender extension manifest declares:

```text
SPDX:GPL-3.0-or-later
```

The canonical GPL-3.0-or-later text is committed as `blender-addon/LICENSE`.
This authorization covers only the Blender exporter code, scripts, manifest,
README, and other files under `blender-addon/`.

It does not select a license for the BlendLib project as a whole or for any
file outside `blender-addon/`. `LICENSE-PENDING` remains the controlling notice
for all non-addon components.

## Rationale

Blender's official extension manifest requires license metadata. The user
replaced the earlier temporary local metadata authorization with a real GPL
scope for the Add-on, while expressly retaining license-pending status for
BlendLib runtime modules and other non-addon files.

## Consequences

- Blender may validate the Add-on manifest as GPL-3.0-or-later.
- GPL scope must not be copied into `blendlib-*`, Showcase, test assets,
  runtime artifacts, or root project source/resources.
- No ZIP is built, installed, distributed, published, pushed, tagged, or
  deployed under this decision.
- P8 still requires explicit user approval for final licenses of non-addon
  components, public name, source availability, publication channel, and
  release artifacts.
