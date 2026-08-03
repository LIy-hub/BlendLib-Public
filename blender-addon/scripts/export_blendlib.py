# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Blender headless entrypoint. BlendLib options are parsed only after --."""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Set this before importing the add-on so headless exports leave no source-tree
# `__pycache__` files behind.
sys.dont_write_bytecode = True

ADDON_ROOT = Path(__file__).resolve().parents[1]
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

from blendlib_exporter import ExportError, parse_blender_arguments, run_cli  # noqa: E402


def main() -> int:
    try:
        result = run_cli(parse_blender_arguments(sys.argv))
    except ExportError as error:
        print(f"BLENDLIB_EXPORT_ERROR: {error}", file=sys.stderr)
        return 2
    print("BLENDLIB_EXPORT_OK " + json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
