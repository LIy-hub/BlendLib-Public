# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Blender headless X5 entrypoint for one-click or manifest batch export.

Arguments deliberately reuse the strict-v1 parser after Blender's ``--``.  A
``--batch-manifest`` switches to deterministic manifest order; all outputs are
first staged and then published by one rollback-capable bundle operation.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True

ADDON_ROOT = Path(__file__).resolve().parents[1]
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

from blendlib_exporter import ExportError, parse_blender_arguments  # noqa: E402
from blendlib_x5_toolchain import X5ToolingError, run_x5_cli  # noqa: E402


def main() -> int:
    try:
        result = run_x5_cli(parse_blender_arguments(sys.argv))
    except (ExportError, X5ToolingError) as error:
        print(f"BLENDLIB_X5_EXPORT_ERROR: {error}", file=sys.stderr)
        return 2
    print("BLENDLIB_X5_EXPORT_OK " + json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
