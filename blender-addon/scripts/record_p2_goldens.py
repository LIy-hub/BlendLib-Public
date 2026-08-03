# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Write checked-in P2 normalized structure and SHA-256 golden records."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

# Golden recording is a source-checkout operation, not a bytecode producer.
sys.dont_write_bytecode = True


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    if "--" not in sys.argv:
        raise SystemExit("Arguments must follow '--'.")
    arguments = sys.argv[sys.argv.index("--") + 1 :]
    if len(arguments) != 2 or arguments[0] != "--project-root":
        raise SystemExit("Usage: record_p2_goldens.py -- --project-root <root>")
    root = Path(arguments[1]).resolve()
    resource_root = root / "blendlib-showcase" / "src" / "main" / "resources" / "assets" / "blendlib_showcase"
    for fixture in ("static", "rigid", "skinned"):
        fixture_root = root / "test-assets" / fixture
        expected = json.loads((fixture_root / "expected.json").read_text(encoding="utf-8"))
        report = json.loads((fixture_root / "export-report.json").read_text(encoding="utf-8"))
        model_id = expected["model_id"]
        descriptor = resource_root / "blend_models" / f"{model_id}.json"
        glb = resource_root / "models3d" / f"{model_id}.glb"
        source_texture = fixture_root / expected["source_texture"]
        material = next(iter(report["texture_paths"]))
        exported_texture = Path(report["texture_paths"][material])
        if not all(path.is_file() for path in (descriptor, glb, source_texture, exported_texture)):
            raise SystemExit(f"Missing canonical output for {fixture}")
        structure = report["normalized_structure"]
        write_json(fixture_root / "golden" / "structure.json", structure)
        write_json(
            fixture_root / "golden" / "sha256.json",
            {
                "format": "blendlib-p2-golden-sha256-v1",
                "fixture": fixture,
                "source_blend": sha256(fixture_root / "source.blend"),
                "source_png": sha256(source_texture),
                "exported_glb": sha256(glb),
                "descriptor": sha256(descriptor),
                "external_runtime_png": sha256(exported_texture),
                "normalized_structure": hashlib.sha256(
                    json.dumps(structure, sort_keys=True, separators=(",", ":")).encode("utf-8")
                ).hexdigest(),
            },
        )
    print("BLENDLIB_P2_GOLDENS_RECORDED static rigid skinned")


if __name__ == "__main__":
    main()
