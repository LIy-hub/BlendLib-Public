# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Blender-headless X5 registration and pure-export adapter verifier.

It does not claim interactive viewport proof.  It only proves that the add-on
can register/unregister its X5 panel/operators in a real Blender process and
that the pure protocol module imports without an eager ``bpy`` dependency.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.dont_write_bytecode = True

ADDON_ROOT = Path(__file__).resolve().parents[1]
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

import blendlib_exporter  # noqa: E402
import blendlib_x5_toolchain  # noqa: E402


def main() -> int:
    blender = blendlib_exporter._require_blender()
    blendlib_x5_toolchain.register_blender_ui(blender)
    required = (
        "blendlib_x5_authoring_output_root",
        "blendlib_x5_batch_manifest",
        "blendlib_x5_dev_refresh_file",
        "blendlib_x5_last_status",
        "blendlib_x5_preview_model",
        "blendlib_x5_preview_state",
    )
    missing = [name for name in required if not hasattr(blender.types.Scene, name)]
    blendlib_x5_toolchain.unregister_blender_ui(blender)
    leaked = [name for name in required if hasattr(blender.types.Scene, name)]
    if missing or leaked:
        print(f"BLENDLIB_X5_HEADLESS_ERROR missing={missing} leaked={leaked}", file=sys.stderr)
        return 2
    print("BLENDLIB_X5_HEADLESS_OK register_unregister")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
