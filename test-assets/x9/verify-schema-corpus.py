#!/usr/bin/env python3
"""Verify that the shared X9 descriptor corpus agrees with the JSON Schema."""

import json
from copy import deepcopy
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate key: {key}")
        result[key] = value
    return result


repo_root = Path(__file__).resolve().parents[2]
schema_path = repo_root / "schemas" / "experimental" / "blendlib-model-x9.schema.json"
schema = json.loads(schema_path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicate_keys)
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)

failures: list[str] = []
valid_count = 0
invalid_count = 0
for path in sorted((Path(__file__).resolve().parent / "schema-corpus").glob("*.json")):
    expected_valid = path.name.startswith("valid-")
    if expected_valid:
        valid_count += 1
    else:
        invalid_count += 1
    try:
        instance = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicate_keys)
        valid = not list(validator.iter_errors(instance))
    except (json.JSONDecodeError, ValueError):
        valid = False
    if valid != expected_valid:
        failures.append(f"{path.name}: expected valid={expected_valid}, got valid={valid}")

boundary = json.loads(
    (Path(__file__).resolve().parent / "schema-corpus" / "valid-standard.json").read_text(encoding="utf-8"),
    object_pairs_hook=reject_duplicate_keys,
)
while len(boundary["capabilities"]) < 32:
    index = len(boundary["capabilities"])
    boundary["capabilities"][f"example:metadata/schema-boundary-{index}"] = {
        "requirement": "optional",
        "min_version": "1.0.0",
        "max_version": "2.0.0",
        "fallback": "metadata_ignore",
    }
if list(validator.iter_errors(boundary)):
    failures.append("generated-capability-boundary-32: expected valid=True, got valid=False")
over_boundary = deepcopy(boundary)
over_boundary["capabilities"]["example:metadata/schema-boundary-32"] = {
    "requirement": "optional",
    "min_version": "1.0.0",
    "max_version": "2.0.0",
    "fallback": "metadata_ignore",
}
if not list(validator.iter_errors(over_boundary)):
    failures.append("generated-capability-boundary-33: expected valid=False, got valid=True")

if failures:
    raise SystemExit("\n".join(failures))
print(
    f"X9 schema corpus: {valid_count} valid and {invalid_count} invalid cases matched; "
    "generated capability boundary accepted 32 and rejected 33"
)
