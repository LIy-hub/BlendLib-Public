"""Validate P5 fixture provenance without generating or copying runtime assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from pathlib import Path
from typing import Any


class FixtureValidationError(AssertionError):
    """Raised for an invalid or non-reproducible P5 fixture package."""


def fail(message: str) -> None:
    raise FixtureValidationError(message)


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    fail(f"Non-finite JSON value is forbidden: {value}")


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"Invalid JSON at {path}: {error}")
    if not isinstance(value, dict):
        fail(f"Expected JSON object at {path}")
    return value


def canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_reference(project_root: Path, package_root: Path, raw: str) -> Path:
    if not isinstance(raw, str) or not raw:
        fail(f"Invalid fixture reference: {raw!r}")
    path = (package_root / raw).resolve()
    try:
        relative = path.relative_to(project_root)
    except ValueError:
        fail(f"Fixture reference escapes project root: {raw}")
    if "third_party" in relative.parts:
        fail(f"P5 fixture provenance may not reference third_party assets: {raw}")
    if not path.is_file():
        fail(f"Missing fixture reference: {relative.as_posix()}")
    return path


def read_glb(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    if len(raw) < 12:
        fail(f"GLB is shorter than its header: {path}")
    magic, version, total_length = struct.unpack_from("<III", raw, 0)
    if (magic, version, total_length) != (0x46546C67, 2, len(raw)):
        fail(f"Unexpected GLB header: {path}")
    offset = 12
    chunks: dict[int, bytes] = {}
    while offset < len(raw):
        if offset + 8 > len(raw):
            fail(f"Truncated GLB chunk header: {path}")
        length, chunk_type = struct.unpack_from("<II", raw, offset)
        offset += 8
        if offset + length > len(raw) or chunk_type in chunks:
            fail(f"Invalid GLB chunk layout: {path}")
        chunks[chunk_type] = raw[offset : offset + length]
        offset += length
    if offset != len(raw) or 0x4E4F534A not in chunks or 0x004E4942 not in chunks:
        fail(f"Expected JSON and BIN GLB chunks: {path}")
    try:
        document = json.loads(
            chunks[0x4E4F534A].decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"Invalid GLB JSON chunk at {path}: {error}")
    if not isinstance(document, dict):
        fail(f"Expected GLB JSON object: {path}")
    return document, chunks[0x004E4942]


def scalar_float_accessor(document: dict[str, Any], binary: bytes, accessor_index: int) -> list[float]:
    accessors = document.get("accessors")
    views = document.get("bufferViews")
    if not isinstance(accessors, list) or not isinstance(views, list):
        fail("GLB has no accessor or bufferView table")
    try:
        accessor = accessors[accessor_index]
        view = views[accessor["bufferView"]]
    except (IndexError, KeyError, TypeError) as error:
        fail(f"Invalid animation accessor {accessor_index}: {error}")
    if accessor.get("componentType") != 5126 or accessor.get("type") != "SCALAR":
        fail(f"Animation time accessor {accessor_index} is not FLOAT/SCALAR")
    count = accessor.get("count")
    if not isinstance(count, int) or count < 1:
        fail(f"Invalid animation time count for accessor {accessor_index}")
    start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
    stride = view.get("byteStride", 4)
    if not isinstance(start, int) or not isinstance(stride, int) or stride < 4:
        fail(f"Invalid animation accessor layout for {accessor_index}")
    final_offset = start + (count - 1) * stride + 4
    if start < 0 or final_offset > len(binary):
        fail(f"Animation accessor {accessor_index} exceeds GLB binary data")
    return [struct.unpack_from("<f", binary, start + index * stride)[0] for index in range(count)]


def assert_close(actual: float, expected: float, label: str) -> None:
    if not math.isclose(actual, expected, rel_tol=0.0, abs_tol=1.0e-8):
        fail(f"{label}: expected {expected!r}, got {actual!r}")


def validate_glb_observation(document: dict[str, Any], binary: bytes, observation: dict[str, Any], fixture_id: str) -> None:
    node_names = [node.get("name") for node in document.get("nodes", [])]
    if node_names != observation["node_names"]:
        fail(f"{fixture_id}: node source identity differs: {node_names!r}")
    palette_node_names = observation["palette_node_names"]
    if len(set(palette_node_names)) != len(palette_node_names) or not set(palette_node_names).issubset(node_names):
        fail(f"{fixture_id}: palette node source identity is invalid: {palette_node_names!r}")

    skin_joint_names = []
    for skin in document.get("skins", []):
        names = []
        for joint_index in skin.get("joints", []):
            try:
                names.append(document["nodes"][joint_index]["name"])
            except (IndexError, KeyError, TypeError) as error:
                fail(f"{fixture_id}: invalid skin joint source identity: {error}")
        skin_joint_names.append(names)
    if skin_joint_names != observation["skin_joint_node_names"]:
        fail(f"{fixture_id}: skin joint source identity differs: {skin_joint_names!r}")

    animations = document.get("animations", [])
    animation_by_name = {animation.get("name"): animation for animation in animations}
    expected_clips = observation["clip_observations"]
    if set(animation_by_name) != {clip["name"] for clip in expected_clips}:
        fail(f"{fixture_id}: animation source identity differs: {sorted(animation_by_name)!r}")
    for expected_clip in expected_clips:
        animation = animation_by_name[expected_clip["name"]]
        channels = animation.get("channels", [])
        samplers = animation.get("samplers", [])
        if len(channels) != len(expected_clip["channels"]):
            fail(f"{fixture_id}: channel count differs for {expected_clip['name']}")
        for expected_channel in expected_clip["channels"]:
            matches = []
            for channel in channels:
                target = channel.get("target", {})
                target_index = target.get("node")
                target_name = node_names[target_index] if isinstance(target_index, int) and target_index < len(node_names) else None
                if target_name == expected_channel["target_node"] and target.get("path") == expected_channel["path"]:
                    matches.append(channel)
            if len(matches) != 1:
                fail(f"{fixture_id}: missing or duplicate channel {expected_channel!r}")
            channel = matches[0]
            try:
                sampler = samplers[channel["sampler"]]
                times = scalar_float_accessor(document, binary, sampler["input"])
            except (IndexError, KeyError, TypeError) as error:
                fail(f"{fixture_id}: invalid sampler binding: {error}")
            interpolation = sampler.get("interpolation", "LINEAR")
            if interpolation != expected_channel["interpolation"]:
                fail(f"{fixture_id}: interpolation differs for {expected_channel['path']}")
            if len(times) != expected_channel["sample_count"]:
                fail(f"{fixture_id}: sample count differs for {expected_channel['path']}")
            assert_close(times[0], expected_channel["first_sample_seconds"], f"{fixture_id} first sample")
            assert_close(times[-1], expected_channel["last_sample_seconds"], f"{fixture_id} last sample")


def validate_fixture(project_root: Path, package_root: Path, fixture: dict[str, Any]) -> None:
    fixture_id = fixture["id"]
    references = fixture["references"]
    paths = {key: resolve_reference(project_root, package_root, raw) for key, raw in references.items()}
    expected = load_json(paths["expected"])
    report = load_json(paths["export_report"])
    structure = load_json(paths["golden_structure"])
    golden_hashes = load_json(paths["golden_sha256"])
    descriptor = load_json(paths["runtime_descriptor"])

    if fixture["profile"] != expected.get("profile") or fixture["profile"] != descriptor.get("profile"):
        fail(f"{fixture_id}: profile does not match frozen canonical data")
    if report.get("normalized_structure") != structure:
        fail(f"{fixture_id}: export report and golden structure differ")
    if descriptor != structure.get("descriptor"):
        fail(f"{fixture_id}: runtime descriptor and frozen structure differ")
    if expected.get("expected_animation_names") != [clip["name"] for clip in fixture["source_observation"]["clip_observations"]]:
        fail(f"{fixture_id}: expected animation names differ from source observation")

    expected_hashes = {
        "source_blend": golden_hashes.get("source_blend"),
        "source_png": golden_hashes.get("source_png"),
        "runtime_descriptor": golden_hashes.get("descriptor"),
        "runtime_glb": golden_hashes.get("exported_glb"),
        "runtime_png": golden_hashes.get("external_runtime_png"),
        "normalized_structure": golden_hashes.get("normalized_structure"),
    }
    if fixture["sha256"] != expected_hashes:
        fail(f"{fixture_id}: manifest hash declarations do not match canonical golden hashes")
    for key in ("source_blend", "source_png", "runtime_descriptor", "runtime_glb", "runtime_png"):
        actual_hash = file_sha256(paths[key])
        if actual_hash != fixture["sha256"][key]:
            fail(f"{fixture_id}: SHA-256 mismatch for {key}: {actual_hash}")
    if canonical_json_sha256(structure) != fixture["sha256"]["normalized_structure"]:
        fail(f"{fixture_id}: normalized structure SHA-256 mismatch")
    if report.get("sha256", {}).get("source_blend") != fixture["sha256"]["source_blend"]:
        fail(f"{fixture_id}: export report source blend hash differs")
    if report.get("sha256", {}).get("mesh_glb") != fixture["sha256"]["runtime_glb"]:
        fail(f"{fixture_id}: export report GLB hash differs")
    if report.get("sha256", {}).get("descriptor") != fixture["sha256"]["runtime_descriptor"]:
        fail(f"{fixture_id}: export report descriptor hash differs")
    if report.get("sha256", {}).get("normalized_structure") != fixture["sha256"]["normalized_structure"]:
        fail(f"{fixture_id}: export report normalized structure hash differs")

    document, binary = read_glb(paths["runtime_glb"])
    validate_glb_observation(document, binary, fixture["source_observation"], fixture_id)


def validate_scenarios(package_root: Path, fixture_ids: set[str]) -> None:
    scenarios_path = package_root / "golden-scenarios.json"
    scenarios_document = load_json(scenarios_path)
    if scenarios_document.get("format") != "blendlib-p5-scenario-golden-v1":
        fail("Unexpected scenario golden format")
    if scenarios_document.get("manifest") != "manifest.json":
        fail("Scenario golden must bind the local manifest")
    if scenarios_document.get("phase_gate_status") != "NOT_EVALUATED":
        fail("Fixture scenario golden must not assert a phase gate")
    expected_ids = {
        "rigid-two-node-palette",
        "controller-state-timing",
        "controller-idle-attack-source-identity",
        "skinned-single-joint-source-identity",
        "skinned-two-joint-source-identity",
        "socket-samples",
        "two-instance-isolation",
        "generation-cache-lifecycle",
        "visual-event-dispatch",
    }
    scenarios = scenarios_document.get("scenarios")
    if not isinstance(scenarios, list):
        fail("Scenario golden must contain a scenario list")
    by_id = {scenario.get("id"): scenario for scenario in scenarios}
    if set(by_id) != expected_ids or len(by_id) != len(scenarios):
        fail("Scenario golden has missing or duplicate required scenario IDs")
    for scenario_id, scenario in by_id.items():
        if not isinstance(scenario.get("status"), str) or not scenario["status"]:
            fail(f"{scenario_id}: missing scenario status")
        references = scenario.get("fixture_ids")
        if not isinstance(references, list) or not references or not set(references).issubset(fixture_ids):
            fail(f"{scenario_id}: invalid fixture binding")
    for scenario_id in (
        "controller-idle-attack-source-identity",
        "skinned-two-joint-source-identity",
        "socket-samples",
        "visual-event-dispatch",
    ):
        scenario = by_id[scenario_id]
        if not scenario["status"].startswith("BLOCKED_CANONICAL_"):
            fail(f"{scenario_id}: missing explicit canonical-source gap status")
        if "reason" not in scenario.get("expected", {}):
            fail(f"{scenario_id}: missing explicit gap reason")
    if by_id["rigid-two-node-palette"]["pose_matrix_and_vertex_golden"]["status"] != "PENDING_CORE_SAMPLING":
        fail("Rigid pose/vertex values must remain pending core sampling")
    if by_id["skinned-single-joint-source-identity"]["post_skin_vertex_golden"]["status"] != "PENDING_CORE_SAMPLING":
        fail("Skinned vertex values must remain pending core sampling")


def assert_package_contains_no_asset_copies(package_root: Path) -> None:
    forbidden_suffixes = {".blend", ".blend1", ".glb", ".gltf", ".bin", ".png", ".jpg", ".jpeg"}
    copied_assets = [path.relative_to(package_root).as_posix() for path in package_root.rglob("*") if path.is_file() and path.suffix.lower() in forbidden_suffixes]
    if copied_assets:
        fail(f"P5 package must reference canonical assets rather than copy them: {copied_assets}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    project_root = args.project_root.resolve()
    package_root = project_root / "test-assets" / "p5"
    manifest = load_json(package_root / "manifest.json")
    if manifest.get("format") != "blendlib-p5-fixture-manifest-v1":
        fail("Unexpected P5 fixture manifest format")
    if manifest.get("phase_gate_status") != "NOT_EVALUATED":
        fail("Fixture manifest must not assert a P5 gate")
    if manifest.get("scenario_golden") != "golden-scenarios.json":
        fail("Manifest must bind the local scenario golden")
    assert_package_contains_no_asset_copies(package_root)
    fixtures = manifest.get("fixtures")
    if not isinstance(fixtures, list) or not fixtures:
        fail("Fixture manifest must contain fixtures")
    fixture_ids = [fixture.get("id") for fixture in fixtures]
    if len(set(fixture_ids)) != len(fixtures) or None in fixture_ids:
        fail("Fixture manifest contains duplicate or missing fixture IDs")
    for fixture in fixtures:
        validate_fixture(project_root, package_root, fixture)
    validate_scenarios(package_root, set(fixture_ids))
    print("BLENDLIB_P5_FIXTURE_VALIDATION_OK rigid-two-node-palette skinned-single-joint-source (not a phase gate)")


if __name__ == "__main__":
    main()
