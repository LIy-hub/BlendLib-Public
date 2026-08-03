#!/usr/bin/env python3
"""Verify that the shared X9 descriptor corpus agrees with the JSON Schema."""

import json
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

if failures:
    raise SystemExit("\n".join(failures))
print(f"X9 schema corpus: {valid_count} valid and {invalid_count} invalid cases matched")
