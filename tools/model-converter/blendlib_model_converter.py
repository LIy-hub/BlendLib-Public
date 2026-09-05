#!/usr/bin/env python3
"""Convert supported Blockbench or GeckoLib JSON into BlendLib strict GLB assets.

This program is deliberately an offline authoring utility. It never talks to a
network, imports a mod loader, reads a runtime resource pack, or loads GeckoLib.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import stat
import struct
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Sequence
from urllib.parse import urlparse


MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_BONES = 4096
MAX_CUBES = 10000
MAX_VERTICES = 1_000_000
MAX_ANIMATION_KEYS = 250_000
MAX_HIERARCHY_DEPTH = 128
MAX_ABSOLUTE_NUMBER = 1_000_000_000.0
MAX_TEXTURE_DIMENSION = 65_536.0
MAX_CLIPS = 256
MAX_KEYFRAME_SAMPLES = 1_000_000
MAX_CLIP_DURATION_SECONDS = 600.0
MAX_GLB_BYTES = 64 * 1024 * 1024
MAX_TEXTURE_BYTES = 64 * 1024 * 1024
MAX_EXTERNAL_ANIMATION_INPUTS = 64
MAX_EXTERNAL_TEXTURE_INPUTS = 64
MAX_CUMULATIVE_INPUT_BYTES = 128 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
IDENTIFIER_COMPONENT = re.compile(r"^[a-z0-9._/-]+$")
SIMPLE_SOURCE_FIELD = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
CARDINAL_CUBE_FACES = frozenset(("north", "south", "east", "west", "up", "down"))


class ConversionError(RuntimeError):
    """A fail-closed conversion error whose detail is emitted in the report."""


@dataclass(frozen=True)
class Issue:
    severity: str
    code: str
    location: str
    message: str

    def as_json(self) -> dict[str, str]:
        return {
            "severity": self.severity,
            "code": self.code,
            "location": self.location,
            "message": self.message,
        }


@dataclass
class Issues:
    allow_lossy: bool
    entries: list[Issue] = field(default_factory=list)

    def error(self, code: str, location: str, message: str) -> None:
        self.entries.append(Issue("error", code, location, message))

    def unsupported(self, code: str, location: str, message: str) -> None:
        severity = "warning" if self.allow_lossy else "error"
        self.entries.append(Issue(severity, code, location, message))

    def ignored_author_metadata(self, location: str, message: str) -> None:
        """Record an explicitly recognized non-runtime authoring field.

        These entries are informational rather than lossy omissions: the listed
        fields describe editor provenance or UI state, not model/animation
        behavior that the strict descriptor or GLB must reproduce.
        """
        self.entries.append(Issue("info", "BLX8-IGNORED-AUTHOR-METADATA", location, message))

    def has_errors(self) -> bool:
        return any(issue.severity == "error" for issue in self.entries)

    def has_lossy_omissions(self) -> bool:
        return any(issue.severity == "warning" for issue in self.entries)

    def as_json(self) -> list[dict[str, str]]:
        return [issue.as_json() for issue in self.entries]


@dataclass(frozen=True)
class SourceCube:
    name: str
    bone: str
    origin: tuple[float, float, float]
    size: tuple[float, float, float]
    pivot: tuple[float, float, float]
    rotation: tuple[float, float, float]
    uv: Any
    inflate: float = 0.0
    mirror: bool = False


@dataclass(frozen=True)
class SourceBone:
    name: str
    parent: str | None
    pivot: tuple[float, float, float]
    rotation: tuple[float, float, float]
    scale: tuple[float, float, float]
    location: str


@dataclass(frozen=True)
class AnimationKey:
    time: float
    value: tuple[float, ...]
    interpolation: str
    location: str


@dataclass
class SourceAnimation:
    name: str
    channels: dict[tuple[str, str], list[AnimationKey]] = field(default_factory=dict)


@dataclass
class SourceModel:
    dialect: str
    bones: list[SourceBone]
    cubes: list[SourceCube]
    animations: list[SourceAnimation]
    textures: list[str]
    texture_size: tuple[float, float]


@dataclass
class PrimitiveGeometry:
    positions: list[float] = field(default_factory=list)
    normals: list[float] = field(default_factory=list)
    uvs: list[float] = field(default_factory=list)
    indices: list[int] = field(default_factory=list)


# The converter deliberately recognizes the authoring dialects at object level.
# These are not permissive ``ignore everything except fields we happen to use``
# lists: a field is either (a) emitted faithfully, (b) explicitly classified as
# editor-only metadata, or (c) reported as a named, reviewable omission.
# Keeping the collections adjacent to the parsers makes additions auditable.
GECKO_ROOT_FIELDS = frozenset((
    "animations", "bones", "description", "minecraft:geometry", "texture", "texture_path", "textures",
))
GECKO_ROOT_AUTHOR_METADATA_FIELDS = frozenset((
    "author", "credit", "format_version", "geckolib_format_version", "identifier", "name",
))
GECKO_ROOT_UNSUPPORTED_FIELDS = {
    "animation_controllers": "GeckoLib controller state/condition behavior is not a strict GLB clip.",
    "controllers": "GeckoLib controller state/condition behavior is not a strict GLB clip.",
    "events": "Top-level source events have no strict descriptor/GLB mapping.",
    "particle_effects": "Particle behavior is not represented by a strict GLB clip.",
    "sound_effects": "Sound behavior is not represented by a strict GLB clip.",
    "timeline": "Timeline behavior is not represented by a strict GLB clip.",
}
GECKO_GEOMETRY_FIELDS = frozenset(("bones", "description"))
GECKO_GEOMETRY_AUTHOR_METADATA_FIELDS = frozenset(("name",))
GECKO_GEOMETRY_UNSUPPORTED_FIELDS = {
    "animation_controllers": "Geometry-local controller behavior is not represented by strict GLB.",
    "materials": "Geometry material assignments exceed the converter's one-material strict route.",
}
GECKO_DESCRIPTION_FIELDS = frozenset(("texture_height", "texture_width"))
GECKO_DESCRIPTION_AUTHOR_METADATA_FIELDS = frozenset(("identifier", "name"))
GECKO_DESCRIPTION_UNSUPPORTED_FIELDS = {
    "visible_bounds_height": "Explicit source visible bounds are not a strict descriptor field; bounds are derived from emitted geometry.",
    "visible_bounds_offset": "Explicit source visible bounds are not a strict descriptor field; bounds are derived from emitted geometry.",
    "visible_bounds_width": "Explicit source visible bounds are not a strict descriptor field; bounds are derived from emitted geometry.",
}
GECKO_BONE_FIELDS = frozenset((
    "cubes", "locators", "mirror", "name", "never_render", "parent", "pivot", "reset", "rotation", "scale",
))
GECKO_BONE_AUTHOR_METADATA_FIELDS = frozenset(("uuid",))
GECKO_BONE_UNSUPPORTED_FIELDS = {
    "binding": "Bone binding behavior is not represented by strict GLB.",
    "render_group_id": "Bedrock render-group behavior is not represented by strict GLB.",
}
GECKO_CUBE_FIELDS = frozenset(("inflate", "mirror", "origin", "pivot", "rotation", "size", "uv"))
GECKO_CUBE_AUTHOR_METADATA_FIELDS = frozenset(("name", "uuid"))
GECKO_CUBE_UNSUPPORTED_FIELDS = {
    "material": "Per-cube material assignment exceeds the converter's one-material strict route.",
    "mesh": "Arbitrary mesh geometry has no strict cuboid conversion.",
    "poly_mesh": "Arbitrary polygon mesh geometry has no strict cuboid conversion.",
    "visibility": "Per-cube visibility behavior is not represented by strict GLB.",
}
GECKO_UV_FACE_FIELDS = frozenset(("rotation", "uv", "uv_size"))
GECKO_UV_FACE_UNSUPPORTED_FIELDS = {
    "material": "Per-face material assignment exceeds the converter's one-material strict route.",
    "texture": "Per-face texture assignment exceeds the converter's one-texture strict route.",
}
GECKO_TEXTURE_FIELDS = frozenset(("name", "path", "source"))
GECKO_TEXTURE_AUTHOR_METADATA_FIELDS = frozenset(("id", "name", "uuid"))
GECKO_TEXTURE_UNSUPPORTED_FIELDS = {
    "frame_order": "Animated texture frame ordering is not represented by a strict static PNG material.",
    "frame_time": "Animated texture timing is not represented by a strict static PNG material.",
    "render_mode": "Texture render-mode behavior is not represented by the strict material profile.",
}
GECKO_ANIMATION_FIELDS = frozenset(("bones",))
GECKO_ANIMATION_AUTHOR_METADATA_FIELDS = frozenset(("author", "credit", "name"))
GECKO_ANIMATION_UNSUPPORTED_FIELDS = {
    "animation_length": "Explicit animation length/hold semantics are not emitted into a strict descriptor state graph.",
    "blend_weight": "Controller blend weighting is not represented by a strict GLB clip.",
    "loop": "GeckoLib loop semantics belong in an explicit BlendLib descriptor state, not a raw GLB clip.",
    "override_previous_animation": "Controller override behavior is not represented by a strict GLB clip.",
    "particle_effects": "Particle events are not emitted by the strict GLB converter.",
    "sound_effects": "Sound events are not emitted by the strict GLB converter.",
    "timeline": "Timeline/custom events are not emitted by the strict GLB converter.",
}
GECKO_ANIMATION_BONE_FIELDS = frozenset(("position", "rotation", "scale"))
GECKO_ANIMATION_BONE_UNSUPPORTED_FIELDS = {
    "color": "Bone color animation is not part of the strict v1 GLB profile.",
    "locator": "Locator animation needs explicit socket/event mapping and is not emitted.",
    "relative_to": "Relative animation spaces are not represented by strict GLB channels.",
    "visibility": "Bone visibility animation is not represented by strict GLB channels.",
}
GECKO_KEYFRAME_FIELDS = frozenset(("lerp_mode", "vector"))
GECKO_KEYFRAME_UNSUPPORTED_FIELDS = {
    "post": "Pre/post discontinuity key semantics are not represented by strict LINEAR or STEP channels.",
    "pre": "Pre/post discontinuity key semantics are not represented by strict LINEAR or STEP channels.",
}

BLOCKBENCH_ROOT_FIELDS = frozenset(("animations", "elements", "meta", "outliner", "resolution", "textures"))
BLOCKBENCH_ROOT_AUTHOR_METADATA_FIELDS = frozenset((
    "author", "credit", "export_path", "model_format", "name", "path", "save_path", "uuid", "version",
))
BLOCKBENCH_ROOT_UNSUPPORTED_FIELDS = {
    "animation_controllers": "Blockbench controller behavior is not emitted into a strict descriptor state graph.",
    "animation_variable_placeholders": "Expression variables are not strict GLB animation data.",
    "display": "Minecraft display transforms are not BlendLib model TRS data.",
    "particle_effects": "Particle behavior is not emitted by the strict GLB converter.",
    "sound_effects": "Sound behavior is not emitted by the strict GLB converter.",
    "timeline": "Timeline/custom events are not emitted by the strict GLB converter.",
}
BLOCKBENCH_META_FIELDS = frozenset(("box_uv", "creation_time", "format_version", "model_format"))
BLOCKBENCH_META_AUTHOR_METADATA_FIELDS = frozenset(("backup", "creation_time", "format_version", "model_format"))
BLOCKBENCH_META_UNSUPPORTED_FIELDS = {
    "bedrock_format": "Bedrock format flags can imply source behavior not represented by the strict converter.",
}
BLOCKBENCH_OUTLINER_GROUP_FIELDS = frozenset(("children", "name", "origin", "rotation", "scale", "type", "uuid"))
BLOCKBENCH_OUTLINER_GROUP_AUTHOR_METADATA_FIELDS = frozenset(("color", "isOpen", "locked", "uuid"))
BLOCKBENCH_OUTLINER_GROUP_UNSUPPORTED_FIELDS = {
    "binding": "Group binding behavior is not represented by strict GLB.",
    "export": "Selective export behavior is not represented by the offline strict route.",
    "locator": "Locator/attachment data needs explicit BlendLib socket mapping and is not emitted.",
    "visibility": "Group visibility behavior is not represented by strict GLB nodes.",
}
BLOCKBENCH_ELEMENT_FIELDS = frozenset(("faces", "from", "inflate", "name", "origin", "rotation", "to", "type", "uuid"))
BLOCKBENCH_ELEMENT_AUTHOR_METADATA_FIELDS = frozenset(("color", "locked", "name", "uuid"))
BLOCKBENCH_ELEMENT_UNSUPPORTED_FIELDS = {
    "autouv": "Automatic UV generation is not emitted; supply explicit face UV rectangles.",
    "mirror_uv": "Element-wide mirrored UV behavior is not represented by the strict converter.",
    "rescale": "Rotation rescale behavior is not represented by strict cuboid geometry.",
    "shade": "Per-element shade behavior is not part of the strict material profile.",
    "visibility": "Element visibility behavior is not represented by strict GLB.",
}
BLOCKBENCH_FACE_FIELDS = frozenset(("rotation", "texture", "uv"))
BLOCKBENCH_FACE_UNSUPPORTED_FIELDS = {
    "cullface": "Per-face cullface behavior is not represented by the strict one-material route.",
    "tint": "Per-face tint behavior is not represented by the strict one-material route.",
}
BLOCKBENCH_TEXTURE_FIELDS = frozenset(("name", "path", "source"))
BLOCKBENCH_TEXTURE_AUTHOR_METADATA_FIELDS = frozenset(("folder", "id", "name", "namespace", "uuid", "visible"))
BLOCKBENCH_TEXTURE_UNSUPPORTED_FIELDS = {
    "frame_order": "Animated texture frame ordering is not represented by a strict static PNG material.",
    "frame_time": "Animated texture timing is not represented by a strict static PNG material.",
    "layers_enabled": "Texture layers are not represented by the strict one-texture route.",
    "particle": "Particle texture behavior is not represented by a strict model material.",
    "render_mode": "Texture render-mode behavior is not represented by the strict material profile.",
}
BLOCKBENCH_RESOLUTION_FIELDS = frozenset(("height", "width"))
BLOCKBENCH_ANIMATION_FIELDS = frozenset(("animators", "name"))
BLOCKBENCH_ANIMATION_AUTHOR_METADATA_FIELDS = frozenset(("uuid",))
BLOCKBENCH_ANIMATION_UNSUPPORTED_FIELDS = {
    "animation_length": "Explicit animation length/hold semantics are not emitted into a strict descriptor state graph.",
    "blend_weight": "Controller blend weighting is not represented by a strict GLB clip.",
    "loop": "Blockbench loop semantics belong in an explicit BlendLib descriptor state, not a raw GLB clip.",
    "markers": "Timeline markers/custom events are not emitted by the strict GLB converter.",
    "override": "Animation override behavior is not represented by a strict GLB clip.",
    "particle_effects": "Particle events are not emitted by the strict GLB converter.",
    "sound_effects": "Sound events are not emitted by the strict GLB converter.",
    "timeline": "Timeline/custom events are not emitted by the strict GLB converter.",
}
BLOCKBENCH_ANIMATOR_FIELDS = frozenset(("keyframes", "name", "type"))
BLOCKBENCH_ANIMATOR_AUTHOR_METADATA_FIELDS = frozenset(("uuid",))
BLOCKBENCH_ANIMATOR_UNSUPPORTED_FIELDS = {
    "effects": "Animator effect behavior is not emitted by the strict GLB converter.",
    "locator": "Locator animation needs explicit socket/event mapping and is not emitted.",
}
BLOCKBENCH_KEYFRAME_FIELDS = frozenset(("channel", "data_points", "interpolation", "time", "uuid"))
BLOCKBENCH_KEYFRAME_AUTHOR_METADATA_FIELDS = frozenset(("uuid",))
BLOCKBENCH_KEYFRAME_UNSUPPORTED_FIELDS = {
    "bezier_left_time": "Bezier timing is not represented by strict LINEAR or STEP interpolation.",
    "bezier_left_value": "Bezier tangent data is not represented by strict LINEAR or STEP interpolation.",
    "bezier_right_time": "Bezier timing is not represented by strict LINEAR or STEP interpolation.",
    "bezier_right_value": "Bezier tangent data is not represented by strict LINEAR or STEP interpolation.",
    "easing": "Custom easing is not represented by strict LINEAR or STEP interpolation.",
}
BLOCKBENCH_DATA_POINT_FIELDS = frozenset(("x", "y", "z"))
BLOCKBENCH_DATA_POINT_UNSUPPORTED_FIELDS = {
    "effect": "Effect payloads are not strict GLB transform vectors.",
    "script": "Script payloads are not strict GLB transform vectors.",
}


def source_field_location(location: str, field_name: str) -> str:
    if SIMPLE_SOURCE_FIELD.fullmatch(field_name):
        return f"{location}.{field_name}"
    return f"{location}[{field_name!r}]"


def source_index_location(location: str, index: int) -> str:
    return f"{location}[{index}]"


def source_mapping_location(location: str, key: str) -> str:
    return f"{location}[{key!r}]"


def validate_object_fields(
        value: Any,
        location: str,
        issues: Issues,
        *,
        supported: frozenset[str],
        author_metadata: frozenset[str] = frozenset(),
        known_unsupported: dict[str, str] | None = None,
        object_name: str) -> dict[str, Any] | None:
    """Classify every field in one supported source-dialect object.

    Known unrepresentable fields are distinct from unknown fields in the report,
    so ``--allow-lossy`` never disguises either as an accepted mapping.
    """
    if not isinstance(value, dict):
        issues.error("BLX8-INPUT-OBJECT", location, f"Expected a {object_name} object.")
        return None
    unsupported = known_unsupported or {}
    for field_name in sorted(value):
        field_location = source_field_location(location, field_name)
        if field_name in supported:
            continue
        if field_name in author_metadata:
            issues.ignored_author_metadata(
                field_location,
                "Recognized authoring/editor metadata has no strict GLB or descriptor runtime semantic and is not emitted.")
            continue
        if field_name in unsupported:
            issues.unsupported("BLX8-UNSUPPORTED-KNOWN-FIELD", field_location, unsupported[field_name])
            continue
        issues.unsupported(
            "BLX8-UNSUPPORTED-UNKNOWN-FIELD",
            field_location,
            f"Unknown {object_name} field {field_name!r} cannot be proven representable by the strict converter.")
    return value


def tuple3(value: Any, location: str, issues: Issues, default: Sequence[float] = (0.0, 0.0, 0.0)) -> tuple[float, float, float]:
    if value is None:
        return (float(default[0]), float(default[1]), float(default[2]))
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        issues.error("BLX8-INPUT-VEC3", location, "Expected an array of exactly three finite numbers.")
        return (float(default[0]), float(default[1]), float(default[2]))
    values: list[float] = []
    for index, part in enumerate(value):
        try:
            number = float(part)
        except (TypeError, ValueError, OverflowError):
            issues.error("BLX8-INPUT-VEC3", f"{location}[{index}]", "Expected a finite number.")
            number = float(default[index])
        if not math.isfinite(number):
            issues.error("BLX8-INPUT-NONFINITE", f"{location}[{index}]", "NaN and infinity are rejected.")
            number = float(default[index])
        elif abs(number) > MAX_ABSOLUTE_NUMBER:
            issues.error(
                "BLX8-INPUT-RANGE",
                f"{location}[{index}]",
                f"Number magnitude must not exceed {MAX_ABSOLUTE_NUMBER:g} for bounded strict GLB output.")
            number = float(default[index])
        values.append(number)
    return (values[0], values[1], values[2])


def finite_number(value: Any, location: str, issues: Issues, default: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        issues.error("BLX8-INPUT-NUMBER", location, "Expected a finite number.")
        return default
    if not math.isfinite(number):
        issues.error("BLX8-INPUT-NONFINITE", location, "NaN and infinity are rejected.")
        return default
    if abs(number) > MAX_ABSOLUTE_NUMBER:
        issues.error(
            "BLX8-INPUT-RANGE",
            location,
            f"Number magnitude must not exceed {MAX_ABSOLUTE_NUMBER:g} for bounded strict GLB output.")
        return default
    return number


def string(value: Any, location: str, issues: Issues, default: str = "") -> str:
    if not isinstance(value, str) or not value:
        issues.error("BLX8-INPUT-STRING", location, "Expected a non-empty string.")
        return default
    return value


def normalized_name(value: str, location: str, issues: Issues) -> str:
    candidate = value.strip()
    if not candidate or len(candidate) > 120 or any(ord(character) < 32 for character in candidate):
        issues.error("BLX8-NAME", location, "Name must be non-empty, printable, and at most 120 characters.")
        return "invalid"
    return candidate


def safe_relative_path(value: str, location: str) -> PurePosixPath:
    if not value or chr(0) in value or "\\" in value:
        raise ConversionError(f"{location}: empty, NUL, or backslash path is forbidden")
    parsed = urlparse(value)
    if parsed.scheme or parsed.netloc or value.startswith("//"):
        raise ConversionError(f"{location}: network and URI-like paths are forbidden")
    candidate = PurePosixPath(value)
    if candidate.is_absolute() or ".." in candidate.parts or "." in candidate.parts:
        raise ConversionError(f"{location}: path traversal or absolute path is forbidden")
    return candidate


def safe_child(root: Path, relative: str, location: str) -> Path:
    child = safe_relative_path(relative, location)
    resolved_root = root.resolve()
    # Keep the lexical destination after proving its resolved target stays
    # inside root.  Publication checks must still see and reject an existing
    # destination symlink/reparse point instead of silently following it.
    candidate = resolved_root / Path(*child.parts)
    resolved_child = candidate.resolve()
    try:
        resolved_child.relative_to(resolved_root)
    except ValueError as error:
        raise ConversionError(f"{location}: target escapes its root") from error
    return candidate


@dataclass
class InputReadBudget:
    """Preflight and actual-read budget for local authoring inputs."""

    maximum_bytes: int
    planned_bytes: int = 0
    actual_bytes: int = 0
    planned_paths: set[Path] = field(default_factory=set)

    def preflight(self, path: Path, per_file_limit: int, location: str) -> None:
        try:
            status = path.stat()
        except OSError as error:
            raise ConversionError(f"{location}: cannot stat input {path}: {error}") from error
        if not stat.S_ISREG(status.st_mode):
            raise ConversionError(f"{location}: input must be a regular file: {path}")
        if status.st_size > per_file_limit:
            raise ConversionError(f"{location}: {path} exceeds {per_file_limit} byte limit")
        resolved = path.resolve()
        if resolved in self.planned_paths:
            return
        if self.planned_bytes + status.st_size > self.maximum_bytes:
            raise ConversionError(
                f"{location}: cumulative authoring input size exceeds {self.maximum_bytes} byte limit")
        self.planned_paths.add(resolved)
        self.planned_bytes += status.st_size

    def consume(self, byte_count: int, location: str) -> None:
        if byte_count < 0:
            raise ValueError("byte_count must not be negative")
        if self.actual_bytes + byte_count > self.maximum_bytes:
            raise ConversionError(
                f"{location}: cumulative authoring input reads exceed {self.maximum_bytes} byte limit")
        self.actual_bytes += byte_count


def read_bounded_bytes(path: Path, maximum_bytes: int, location: str, budget: InputReadBudget | None = None) -> bytes:
    if maximum_bytes <= 0:
        raise ValueError("maximum_bytes must be positive")
    chunks: list[bytes] = []
    total = 0
    with path.open("rb") as source:
        while True:
            chunk = source.read(min(64 * 1024, maximum_bytes - total + 1))
            if not chunk:
                return b"".join(chunks)
            total += len(chunk)
            if total > maximum_bytes:
                raise ConversionError(f"{location}: {path} exceeds {maximum_bytes} byte limit")
            if budget is not None:
                budget.consume(len(chunk), location)
            chunks.append(chunk)


def read_json(path: Path, source_root: Path, issues: Issues, budget: InputReadBudget) -> dict[str, Any]:
    try:
        path.resolve().relative_to(source_root.resolve())
    except ValueError as error:
        raise ConversionError(f"input: {path} escapes --source-root") from error
    if not path.is_file():
        raise ConversionError(f"input: not a regular file: {path}")
    try:
        contents = read_bounded_bytes(path, MAX_JSON_BYTES, "input", budget).decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise ConversionError(f"input: {path} is not valid UTF-8") from error
    try:
        document = json.loads(contents)
    except json.JSONDecodeError as error:
        raise ConversionError(f"input: malformed JSON at line {error.lineno}, column {error.colno}") from error
    if not isinstance(document, dict):
        issues.error("BLX8-INPUT-ROOT", "$", "Top-level JSON must be an object.")
        return {}
    return document


def detect_dialect(document: dict[str, Any]) -> str:
    if isinstance(document.get("minecraft:geometry"), list):
        return "geckolib"
    if isinstance(document.get("bones"), list):
        return "geckolib"
    if any(isinstance(key, str) and key.startswith("geometry.") for key in document):
        return "geckolib"
    if isinstance(document.get("elements"), list) or isinstance(document.get("outliner"), list):
        return "blockbench"
    # Animation exports do not necessarily carry their model geometry. The native containers
    # remain distinguishable without guessing from a file name or importing either application.
    if isinstance(document.get("animations"), list):
        return "blockbench"
    if isinstance(document.get("animations"), dict):
        return "geckolib"
    raise ConversionError("input: unsupported JSON shape; expected Blockbench project JSON or GeckoLib geometry/model JSON")


def validate_gecko_root(document: dict[str, Any], issues: Issues) -> None:
    dynamic_geometry_fields = frozenset(key for key in document if key.startswith("geometry."))
    validate_object_fields(
        document,
        "$",
        issues,
        supported=GECKO_ROOT_FIELDS | dynamic_geometry_fields,
        author_metadata=GECKO_ROOT_AUTHOR_METADATA_FIELDS,
        known_unsupported={**GECKO_ROOT_UNSUPPORTED_FIELDS, **GECKO_GEOMETRY_UNSUPPORTED_FIELDS},
        object_name="GeckoLib root")
    for field_name in sorted(dynamic_geometry_fields):
        if not isinstance(document[field_name], dict):
            issues.error("BLX8-GEO-GEOMETRY", source_field_location("$", field_name), "Expected a geometry object.")
    textures = document.get("textures")
    if textures is not None:
        if not isinstance(textures, list):
            issues.error("BLX8-TEXTURE", "$.textures", "Expected an array of local texture strings or objects.")
        else:
            for texture_index, texture in enumerate(textures):
                texture_location = source_index_location("$.textures", texture_index)
                if isinstance(texture, str):
                    continue
                validate_object_fields(
                    texture,
                    texture_location,
                    issues,
                    supported=GECKO_TEXTURE_FIELDS,
                    author_metadata=GECKO_TEXTURE_AUTHOR_METADATA_FIELDS,
                    known_unsupported=GECKO_TEXTURE_UNSUPPORTED_FIELDS,
                    object_name="Gecko texture")


def extract_gecko_geometry(document: dict[str, Any]) -> tuple[dict[str, Any], str, bool]:
    geometry = document.get("minecraft:geometry")
    if isinstance(geometry, list):
        if len(geometry) != 1 or not isinstance(geometry[0], dict):
            raise ConversionError("geckolib: exactly one geometry object is supported per invocation")
        return geometry[0], source_index_location(source_field_location("$", "minecraft:geometry"), 0), False
    if isinstance(document.get("bones"), list):
        return document, "$", True
    candidates = [value for key, value in document.items() if isinstance(key, str) and key.startswith("geometry.")]
    if len(candidates) != 1 or not isinstance(candidates[0], dict):
        raise ConversionError("geckolib: exactly one geometry.* object is supported per invocation")
    geometry_key = next(key for key in document if key.startswith("geometry."))
    return candidates[0], source_field_location("$", geometry_key), False


def validate_gecko_cube_uv(value: Any, location: str, issues: Issues) -> None:
    if isinstance(value, (list, tuple)):
        if len(value) != 2:
            issues.error("BLX8-UV", location, "Gecko box UV must contain exactly [u, v].")
        return
    if not isinstance(value, dict):
        issues.error("BLX8-UV", location, "Expected a Gecko [u, v] box UV or named face UV object.")
        return
    for face_name in sorted(value):
        face_location = source_field_location(location, face_name)
        if face_name not in CARDINAL_CUBE_FACES:
            issues.unsupported(
                "BLX8-UNSUPPORTED-UNKNOWN-FIELD",
                face_location,
                f"Unknown Gecko cube UV face {face_name!r} cannot be represented by strict cuboid geometry.")
            continue
        face_value = value[face_name]
        if isinstance(face_value, dict):
            validate_object_fields(
                face_value,
                face_location,
                issues,
                supported=GECKO_UV_FACE_FIELDS,
                known_unsupported=GECKO_UV_FACE_UNSUPPORTED_FIELDS,
                object_name="Gecko cube UV face")
        elif not isinstance(face_value, (list, tuple)):
            issues.error("BLX8-UV", face_location, "Expected a face UV rectangle or UV object.")


def validate_gecko_geometry(geometry: dict[str, Any], location: str, root_container: bool, issues: Issues) -> None:
    # A direct top-level ``bones`` document has already had its root object
    # classified by validate_gecko_root.  Validate its nested geometry layers
    # without duplicating every root issue in the report.
    if not root_container:
        validate_object_fields(
            geometry,
            location,
            issues,
            supported=GECKO_GEOMETRY_FIELDS,
            author_metadata=GECKO_GEOMETRY_AUTHOR_METADATA_FIELDS,
            known_unsupported=GECKO_GEOMETRY_UNSUPPORTED_FIELDS,
            object_name="Gecko geometry")
    description = geometry.get("description")
    if description is not None:
        validate_object_fields(
            description,
            source_field_location(location, "description"),
            issues,
            supported=GECKO_DESCRIPTION_FIELDS,
            author_metadata=GECKO_DESCRIPTION_AUTHOR_METADATA_FIELDS,
            known_unsupported=GECKO_DESCRIPTION_UNSUPPORTED_FIELDS,
            object_name="Gecko geometry description")
    raw_bones = geometry.get("bones")
    if isinstance(raw_bones, list):
        for bone_index, raw_bone in enumerate(raw_bones):
            bone_location = source_index_location(source_field_location(location, "bones"), bone_index)
            checked_bone = validate_object_fields(
                raw_bone,
                bone_location,
                issues,
                supported=GECKO_BONE_FIELDS,
                author_metadata=GECKO_BONE_AUTHOR_METADATA_FIELDS,
                known_unsupported=GECKO_BONE_UNSUPPORTED_FIELDS,
                object_name="Gecko bone")
            if checked_bone is None:
                continue
            if "parent" in checked_bone and not isinstance(checked_bone["parent"], str):
                issues.error("BLX8-GEO-PARENT", source_field_location(bone_location, "parent"), "Bone parent must be a string.")
            for field_name, message in (
                    ("mirror", "Bone-wide UV mirroring is not representable without changing individual cube UV semantics."),
                    ("never_render", "Bone visibility behavior is not represented by a strict GLB node."),
                    ("reset", "Bone reset behavior is controller/runtime behavior, not a strict GLB transform.")):
                if field_name not in checked_bone:
                    continue
                value = checked_bone[field_name]
                if not isinstance(value, bool):
                    issues.error("BLX8-INPUT-BOOLEAN", source_field_location(bone_location, field_name), "Expected a boolean.")
                elif value:
                    issues.unsupported("BLX8-UNSUPPORTED-KNOWN-FIELD", source_field_location(bone_location, field_name), message)
            if "locators" in checked_bone:
                locators = checked_bone["locators"]
                if not isinstance(locators, dict):
                    issues.error("BLX8-GEO-LOCATORS", source_field_location(bone_location, "locators"), "Expected a locator object.")
                elif locators:
                    issues.unsupported(
                        "BLX8-UNSUPPORTED-KNOWN-FIELD",
                        source_field_location(bone_location, "locators"),
                        "Locator/attachment data needs an explicit BlendLib socket mapping and is not emitted by this converter.")
            raw_cubes = checked_bone.get("cubes")
            if not isinstance(raw_cubes, list):
                continue
            for cube_index, raw_cube in enumerate(raw_cubes):
                cube_location = source_index_location(source_field_location(bone_location, "cubes"), cube_index)
                checked_cube = validate_object_fields(
                    raw_cube,
                    cube_location,
                    issues,
                    supported=GECKO_CUBE_FIELDS,
                    author_metadata=GECKO_CUBE_AUTHOR_METADATA_FIELDS,
                    known_unsupported=GECKO_CUBE_UNSUPPORTED_FIELDS,
                    object_name="Gecko cube")
                if checked_cube is not None:
                    if "mirror" in checked_cube and not isinstance(checked_cube["mirror"], bool):
                        issues.error(
                            "BLX8-INPUT-BOOLEAN",
                            source_field_location(cube_location, "mirror"),
                            "Gecko cube mirror must be a JSON boolean.")
                    if "uv" in checked_cube:
                        validate_gecko_cube_uv(checked_cube["uv"], source_field_location(cube_location, "uv"), issues)


def parse_geckolib(document: dict[str, Any], issues: Issues) -> SourceModel:
    validate_gecko_root(document, issues)
    geometry, geometry_location, root_container = extract_gecko_geometry(document)
    validate_gecko_geometry(geometry, geometry_location, root_container, issues)
    description = geometry.get("description", {})
    if not isinstance(description, dict):
        issues.error("BLX8-GEO-DESCRIPTION", source_field_location(geometry_location, "description"), "Expected an object.")
        description = {}
    texture_size = (
        finite_number(description.get("texture_width", 64), source_field_location(source_field_location(geometry_location, "description"), "texture_width"), issues, 64.0),
        finite_number(description.get("texture_height", 64), source_field_location(source_field_location(geometry_location, "description"), "texture_height"), issues, 64.0),
    )
    if not (1.0 <= texture_size[0] <= MAX_TEXTURE_DIMENSION and 1.0 <= texture_size[1] <= MAX_TEXTURE_DIMENSION):
        issues.error(
            "BLX8-GEO-TEXTURE-SIZE",
            source_field_location(geometry_location, "description"),
            f"Texture dimensions must be in [1, {MAX_TEXTURE_DIMENSION:g}].")
        texture_size = (64.0, 64.0)
    raw_bones = geometry.get("bones")
    if not isinstance(raw_bones, list):
        issues.error("BLX8-GEO-BONES", source_field_location(geometry_location, "bones"), "Expected an array.")
        raw_bones = []
    if len(raw_bones) > MAX_BONES:
        issues.error("BLX8-LIMIT-BONES", source_field_location(geometry_location, "bones"), f"At most {MAX_BONES} bones are allowed.")
        raw_bones = raw_bones[:MAX_BONES]
    bones: list[SourceBone] = []
    cubes: list[SourceCube] = []
    seen_names: set[str] = set()
    for index, raw_bone in enumerate(raw_bones):
        location = source_index_location(source_field_location(geometry_location, "bones"), index)
        if not isinstance(raw_bone, dict):
            issues.error("BLX8-GEO-BONE", location, "Expected an object.")
            continue
        name = normalized_name(string(raw_bone.get("name"), source_field_location(location, "name"), issues), source_field_location(location, "name"), issues)
        if name in seen_names:
            issues.error("BLX8-GEO-DUPLICATE-BONE", source_field_location(location, "name"), f"Duplicate bone name: {name}")
        seen_names.add(name)
        parent_raw = raw_bone.get("parent")
        parent = parent_raw if isinstance(parent_raw, str) and parent_raw else None
        bones.append(
            SourceBone(
                name=name,
                parent=parent,
                pivot=tuple3(raw_bone.get("pivot"), source_field_location(location, "pivot"), issues),
                rotation=tuple3(raw_bone.get("rotation"), source_field_location(location, "rotation"), issues),
                scale=tuple3(raw_bone.get("scale"), source_field_location(location, "scale"), issues, (1.0, 1.0, 1.0)),
                location=location,
            )
        )
        raw_cubes = raw_bone.get("cubes", [])
        if not isinstance(raw_cubes, list):
            issues.error("BLX8-GEO-CUBES", source_field_location(location, "cubes"), "Expected an array.")
            continue
        for cube_index, raw_cube in enumerate(raw_cubes):
            cube_location = source_index_location(source_field_location(location, "cubes"), cube_index)
            if not isinstance(raw_cube, dict):
                issues.error("BLX8-GEO-CUBE", cube_location, "Expected an object.")
                continue
            if "mesh" in raw_cube or "poly_mesh" in raw_cube:
                continue
            raw_mirror = raw_cube.get("mirror", False)
            # validate_gecko_geometry has already rejected a non-boolean input.
            # Do not use bool(...) here: strings such as "false" are truthy in
            # Python and would silently change cube winding/UV mirror semantics.
            mirror = raw_mirror if isinstance(raw_mirror, bool) else False
            cubes.append(
                SourceCube(
                    name=f"{name}_cube_{cube_index}",
                    bone=name,
                    origin=tuple3(raw_cube.get("origin"), source_field_location(cube_location, "origin"), issues),
                    size=tuple3(raw_cube.get("size"), source_field_location(cube_location, "size"), issues),
                    pivot=tuple3(raw_cube.get("pivot"), source_field_location(cube_location, "pivot"), issues),
                    rotation=tuple3(raw_cube.get("rotation"), source_field_location(cube_location, "rotation"), issues),
                    uv=raw_cube.get("uv", [0, 0]),
                    inflate=finite_number(raw_cube.get("inflate", 0), source_field_location(cube_location, "inflate"), issues),
                    mirror=mirror,
                )
            )
    textures = extract_texture_candidates(document, geometry, issues, "$", geometry_location)
    animations = parse_gecko_animations(document, issues, root_validated=True)
    return SourceModel("geckolib", bones, cubes, animations, textures, texture_size)


def validate_blockbench_meta(value: Any, location: str, issues: Issues) -> None:
    metadata = validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_META_FIELDS,
        author_metadata=BLOCKBENCH_META_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_META_UNSUPPORTED_FIELDS,
        object_name="Blockbench meta")
    if metadata is not None and metadata.get("box_uv") is True:
        issues.unsupported(
            "BLX8-UNSUPPORTED-KNOWN-FIELD",
            source_field_location(location, "box_uv"),
            "Blockbench automatic box UV behavior is not emitted; supply explicit per-face UV rectangles.")


def validate_blockbench_face(value: Any, location: str, issues: Issues) -> None:
    validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_FACE_FIELDS,
        known_unsupported=BLOCKBENCH_FACE_UNSUPPORTED_FIELDS,
        object_name="Blockbench cube face")


def validate_blockbench_element(value: Any, location: str, issues: Issues) -> None:
    element = validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_ELEMENT_FIELDS,
        author_metadata=BLOCKBENCH_ELEMENT_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_ELEMENT_UNSUPPORTED_FIELDS,
        object_name="Blockbench element")
    if element is None:
        return
    element_type = element.get("type")
    if element_type not in (None, "cube"):
        issues.unsupported(
            "BLX8-UNSUPPORTED-ELEMENT",
            source_field_location(location, "type"),
            "Only Blockbench cuboid elements are supported by the strict converter.")
    faces = element.get("faces")
    if not isinstance(faces, dict):
        return
    for face_name in sorted(faces):
        face_location = source_field_location(source_field_location(location, "faces"), face_name)
        if face_name not in CARDINAL_CUBE_FACES:
            issues.unsupported(
                "BLX8-UNSUPPORTED-UNKNOWN-FIELD",
                face_location,
                f"Unknown Blockbench cube face {face_name!r} cannot be represented by strict cuboid geometry.")
            continue
        validate_blockbench_face(faces[face_name], face_location, issues)


def validate_blockbench_texture(value: Any, location: str, issues: Issues) -> None:
    if isinstance(value, str):
        return
    texture = validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_TEXTURE_FIELDS,
        author_metadata=BLOCKBENCH_TEXTURE_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_TEXTURE_UNSUPPORTED_FIELDS,
        object_name="Blockbench texture")
    if texture is None:
        return
    source_key = "path" if isinstance(texture.get("path"), str) else "source" if isinstance(texture.get("source"), str) else "name"
    if source_key not in texture:
        issues.error("BLX8-TEXTURE", location, "Texture object must declare one local path/source/name string.")


def validate_blockbench_document(document: dict[str, Any], issues: Issues) -> None:
    validate_object_fields(
        document,
        "$",
        issues,
        supported=BLOCKBENCH_ROOT_FIELDS,
        author_metadata=BLOCKBENCH_ROOT_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_ROOT_UNSUPPORTED_FIELDS,
        object_name="Blockbench root")
    if "meta" in document:
        validate_blockbench_meta(document["meta"], source_field_location("$", "meta"), issues)
    if "resolution" in document:
        validate_object_fields(
            document["resolution"],
            source_field_location("$", "resolution"),
            issues,
            supported=BLOCKBENCH_RESOLUTION_FIELDS,
            object_name="Blockbench resolution")
    textures = document.get("textures")
    if textures is not None and not isinstance(textures, list):
        issues.error("BLX8-TEXTURE", "$.textures", "Expected an array of local texture strings or objects.")
    elif isinstance(textures, list):
        for texture_index, texture in enumerate(textures):
            validate_blockbench_texture(texture, source_index_location(source_field_location("$", "textures"), texture_index), issues)


def blockbench_texture_reference_aliases(value: Any, index: int, candidate: str) -> tuple[set[int], set[str]]:
    """Return documented Blockbench aliases for one emitted source texture.

    Native ``.bbmodel`` faces normally use the numeric array index, while some
    exporters use the string id/UUID/name form.  They are equivalent only when
    they all name the one source PNG carried by the strict material route.
    """
    numeric = {index}
    string = {str(index), f"#{index}", candidate}
    if isinstance(value, str):
        string.add(value)
    elif isinstance(value, dict):
        for key in ("id", "uuid", "name", "path", "source"):
            alias = value.get(key)
            if isinstance(alias, str) and alias:
                string.add(alias)
    return numeric, string


def validate_blockbench_face_texture_assignments(
        document: dict[str, Any], texture_candidates: Sequence[str], issues: Issues) -> None:
    """Prove that Blockbench face texture references survive the one-PNG route.

    A sole source texture can be preserved by one GLB material.  Multiple (or
    no) source candidates cannot preserve a face-level assignment, so each
    actual ``faces.<side>.texture`` is reported at its source path rather than
    silently collapsed to the first candidate.
    """
    raw_textures = document.get("textures", [])
    numeric_aliases: set[int] = set()
    string_aliases: set[str] = set()
    if len(texture_candidates) == 1 and isinstance(raw_textures, list):
        candidate = texture_candidates[0]
        for texture_index, texture in enumerate(raw_textures):
            source = texture if isinstance(texture, str) else (
                texture.get("path", texture.get("source", texture.get("name"))) if isinstance(texture, dict) else None)
            if source == candidate:
                numeric, string = blockbench_texture_reference_aliases(texture, texture_index, candidate)
                numeric_aliases.update(numeric)
                string_aliases.update(string)
    elements = document.get("elements", [])
    if not isinstance(elements, list):
        return
    for element_index, element in enumerate(elements):
        if not isinstance(element, dict) or not isinstance(element.get("faces"), dict):
            continue
        faces_location = source_field_location(source_index_location("$.elements", element_index), "faces")
        for face_name, face in element["faces"].items():
            if not isinstance(face, dict) or "texture" not in face:
                continue
            location = source_field_location(source_field_location(faces_location, str(face_name)), "texture")
            if len(texture_candidates) != 1:
                issues.unsupported(
                    "BLX8-UNSUPPORTED-FACE-TEXTURE",
                    location,
                    "Per-face texture assignment cannot be preserved because the strict route requires exactly one external source PNG.")
                continue
            reference = face["texture"]
            valid_numeric = isinstance(reference, int) and not isinstance(reference, bool) and reference in numeric_aliases
            valid_string = isinstance(reference, str) and reference in string_aliases
            if not valid_numeric and not valid_string:
                issues.error(
                    "BLX8-TEXTURE-REFERENCE",
                    location,
                    "Face texture reference does not name the sole emitted Blockbench texture.")


def blockbench_outliner_attachments(outliner: list[Any], issues: Issues) -> tuple[dict[str, str], list[SourceBone]]:
    attachments: dict[str, str] = {}
    bones: list[SourceBone] = []
    names: set[str] = set()
    limit_reached = False

    def visit(entries: list[Any], parent: str | None, trail: str, depth: int) -> None:
        nonlocal limit_reached
        if limit_reached:
            return
        if depth > MAX_HIERARCHY_DEPTH:
            issues.error("BLX8-LIMIT-DEPTH", trail, f"Hierarchy exceeds {MAX_HIERARCHY_DEPTH} levels.")
            return
        for position, item in enumerate(entries):
            location = f"{trail}[{position}]"
            if isinstance(item, str):
                attachments[item] = parent or "root"
                continue
            if not isinstance(item, dict):
                issues.error("BLX8-BB-OUTLINER", location, "Expected a UUID string or group object.")
                continue
            validate_object_fields(
                item,
                location,
                issues,
                supported=BLOCKBENCH_OUTLINER_GROUP_FIELDS,
                author_metadata=BLOCKBENCH_OUTLINER_GROUP_AUTHOR_METADATA_FIELDS,
                known_unsupported=BLOCKBENCH_OUTLINER_GROUP_UNSUPPORTED_FIELDS,
                object_name="Blockbench outliner group")
            if item.get("type") not in (None, "group"):
                issues.unsupported(
                    "BLX8-UNSUPPORTED-OUTLINER",
                    source_field_location(location, "type"),
                    "Only Blockbench groups and cuboid element references are supported.")
                continue
            if len(bones) >= MAX_BONES:
                issues.error("BLX8-LIMIT-BONES", location, f"At most {MAX_BONES} bones are allowed.")
                limit_reached = True
                return
            name = normalized_name(string(item.get("name"), f"{location}.name", issues), f"{location}.name", issues)
            if name in names:
                issues.error("BLX8-BB-DUPLICATE-BONE", f"{location}.name", f"Duplicate group name: {name}")
            names.add(name)
            bones.append(
                SourceBone(
                    name=name,
                    parent=parent,
                    pivot=tuple3(item.get("origin"), f"{location}.origin", issues),
                    rotation=tuple3(item.get("rotation"), f"{location}.rotation", issues),
                    scale=tuple3(item.get("scale"), f"{location}.scale", issues, (1.0, 1.0, 1.0)),
                    location=location,
                )
            )
            children = item.get("children", [])
            if not isinstance(children, list):
                issues.error("BLX8-BB-CHILDREN", f"{location}.children", "Expected an array.")
                continue
            visit(children, name, f"{location}.children", depth + 1)

    visit(outliner, None, "outliner", 0)
    if not bones:
        bones.append(SourceBone("root", None, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), (1.0, 1.0, 1.0), "$.outliner"))
    return attachments, bones


def parse_blockbench(document: dict[str, Any], issues: Issues) -> SourceModel:
    validate_blockbench_document(document, issues)
    raw_outliner = document.get("outliner", [])
    if not isinstance(raw_outliner, list):
        issues.error("BLX8-BB-OUTLINER", "$.outliner", "Expected an array.")
        raw_outliner = []
    attachments, bones = blockbench_outliner_attachments(raw_outliner, issues)
    elements = document.get("elements", [])
    if not isinstance(elements, list):
        issues.error("BLX8-BB-ELEMENTS", "$.elements", "Expected an array.")
        elements = []
    cubes: list[SourceCube] = []
    for index, element in enumerate(elements):
        location = source_index_location("$.elements", index)
        if not isinstance(element, dict):
            issues.error("BLX8-BB-ELEMENT", location, "Expected an object.")
            continue
        validate_blockbench_element(element, location, issues)
        if element.get("type") not in (None, "cube"):
            continue
        element_id = element.get("uuid")
        bone = attachments.get(element_id, "root") if isinstance(element_id, str) else "root"
        raw_from = tuple3(element.get("from"), f"{location}.from", issues)
        raw_to = tuple3(element.get("to"), f"{location}.to", issues)
        size = (raw_to[0] - raw_from[0], raw_to[1] - raw_from[1], raw_to[2] - raw_from[2])
        if any(component == 0 for component in size):
            issues.error("BLX8-BB-CUBE-SIZE", location, "Cuboid dimensions must be non-zero.")
        cubes.append(
            SourceCube(
                name=normalized_name(str(element.get("name", f"cube_{index}")), f"{location}.name", issues),
                bone=bone,
                origin=raw_from,
                size=size,
                pivot=tuple3(element.get("origin"), f"{location}.origin", issues),
                rotation=tuple3(element.get("rotation"), f"{location}.rotation", issues),
                uv=element.get("faces", {}),
                inflate=finite_number(element.get("inflate", 0.0), f"{location}.inflate", issues),
                mirror=False,
            )
        )
    resolution = document.get("resolution", {})
    if not isinstance(resolution, dict):
        resolution = {}
    texture_size = (
        finite_number(resolution.get("width", 64), "$.resolution.width", issues, 64.0),
        finite_number(resolution.get("height", 64), "$.resolution.height", issues, 64.0),
    )
    if not (1.0 <= texture_size[0] <= MAX_TEXTURE_DIMENSION and 1.0 <= texture_size[1] <= MAX_TEXTURE_DIMENSION):
        issues.error(
            "BLX8-BB-TEXTURE-SIZE",
            "$.resolution",
            f"Texture dimensions must be in [1, {MAX_TEXTURE_DIMENSION:g}].")
        texture_size = (64.0, 64.0)
    textures = extract_texture_candidates(document, document, issues)
    validate_blockbench_face_texture_assignments(document, textures, issues)
    animations = parse_blockbench_animations(document, issues, root_validated=True)
    return SourceModel("blockbench", bones, cubes, animations, textures, texture_size)


def extract_texture_candidates(
        document: dict[str, Any],
        geometry: dict[str, Any],
        issues: Issues,
        document_location: str = "$",
        geometry_location: str = "$") -> list[str]:
    candidates: list[tuple[str, str]] = []
    raw_textures = document.get("textures", [])
    if isinstance(raw_textures, list):
        for index, texture in enumerate(raw_textures):
            location = source_index_location(source_field_location(document_location, "textures"), index)
            if isinstance(texture, str):
                candidates.append((texture, location))
            elif isinstance(texture, dict):
                source = texture.get("path", texture.get("source", texture.get("name")))
                if isinstance(source, str):
                    source_key = "path" if isinstance(texture.get("path"), str) else "source" if isinstance(texture.get("source"), str) else "name"
                    candidates.append((source, source_field_location(location, source_key)))
                else:
                    issues.error("BLX8-TEXTURE", location, "Expected a texture source string.")
            else:
                issues.error("BLX8-TEXTURE", location, "Expected a string or object.")
    for key in ("texture", "texture_path"):
        if key not in geometry:
            continue
        value = geometry.get(key)
        if isinstance(value, str):
            candidates.append((value, source_field_location(geometry_location, key)))
        else:
            issues.error(
                "BLX8-TEXTURE",
                source_field_location(geometry_location, key),
                "Expected a non-empty local texture path string.")
    unique: list[str] = []
    for candidate, location in candidates:
        if candidate.startswith("data:"):
            issues.unsupported("BLX8-UNSUPPORTED-EMBEDDED-TEXTURE", location, "Embedded image data is not accepted; provide an external PNG.")
            continue
        try:
            safe_relative_path(candidate, location)
        except ConversionError as error:
            issues.error("BLX8-UNSAFE-TEXTURE-PATH", location, str(error))
            continue
        if candidate not in unique:
            unique.append(candidate)
    if len(unique) > MAX_EXTERNAL_TEXTURE_INPUTS:
        issues.error(
            "BLX8-LIMIT-TEXTURE-INPUTS",
            source_field_location(document_location, "textures"),
            f"At most {MAX_EXTERNAL_TEXTURE_INPUTS} external texture candidates are allowed.")
        unique = unique[:MAX_EXTERNAL_TEXTURE_INPUTS]
    return unique


def parse_vector(value: Any, location: str, issues: Issues, size: int) -> tuple[float, ...] | None:
    if not isinstance(value, (list, tuple)) or len(value) != size:
        issues.error("BLX8-INPUT-ANIMATION-VALUE", location, f"Expected a numeric array of length {size}.")
        return None
    values: list[float] = []
    for index, part in enumerate(value):
        values.append(finite_number(part, f"{location}[{index}]", issues))
    return tuple(values)


def blockbench_channel_path(channel: str) -> tuple[str, int] | None:
    mapping = {
        "position": ("translation", 3),
        "rotation": ("rotation", 3),
        "scale": ("scale", 3),
    }
    return mapping.get(channel)


def validate_blockbench_keyframe(value: Any, location: str, issues: Issues) -> None:
    keyframe = validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_KEYFRAME_FIELDS,
        author_metadata=BLOCKBENCH_KEYFRAME_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_KEYFRAME_UNSUPPORTED_FIELDS,
        object_name="Blockbench animation keyframe")
    if keyframe is None:
        return
    points = keyframe.get("data_points")
    if isinstance(points, list):
        for point_index, point in enumerate(points):
            validate_object_fields(
                point,
                source_index_location(source_field_location(location, "data_points"), point_index),
                issues,
                supported=BLOCKBENCH_DATA_POINT_FIELDS,
                known_unsupported=BLOCKBENCH_DATA_POINT_UNSUPPORTED_FIELDS,
                object_name="Blockbench keyframe data point")


def validate_blockbench_animator(value: Any, location: str, issues: Issues) -> None:
    animator = validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_ANIMATOR_FIELDS,
        author_metadata=BLOCKBENCH_ANIMATOR_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_ANIMATOR_UNSUPPORTED_FIELDS,
        object_name="Blockbench animation animator")
    if animator is None:
        return
    animator_type = animator.get("type")
    if animator_type not in (None, "bone"):
        issues.unsupported(
            "BLX8-UNSUPPORTED-ANIMATOR",
            source_field_location(location, "type"),
            "Only named bone animators can map to strict GLB node channels.")
    keyframes = animator.get("keyframes")
    if isinstance(keyframes, list):
        for key_index, keyframe in enumerate(keyframes):
            validate_blockbench_keyframe(
                keyframe,
                source_index_location(source_field_location(location, "keyframes"), key_index),
                issues)


def validate_blockbench_animation(value: Any, location: str, issues: Issues) -> None:
    animation = validate_object_fields(
        value,
        location,
        issues,
        supported=BLOCKBENCH_ANIMATION_FIELDS,
        author_metadata=BLOCKBENCH_ANIMATION_AUTHOR_METADATA_FIELDS,
        known_unsupported=BLOCKBENCH_ANIMATION_UNSUPPORTED_FIELDS,
        object_name="Blockbench animation")
    if animation is None:
        return
    animators = animation.get("animators")
    if isinstance(animators, dict):
        for animator_id, animator in sorted(animators.items(), key=lambda item: str(item[0])):
            validate_blockbench_animator(
                animator,
                source_mapping_location(source_field_location(location, "animators"), str(animator_id)),
                issues)


def parse_blockbench_animations(
        document: dict[str, Any], issues: Issues, root_validated: bool = False) -> list[SourceAnimation]:
    if not root_validated:
        validate_blockbench_document(document, issues)
    raw_animations = document.get("animations", [])
    if raw_animations is None:
        return []
    if not isinstance(raw_animations, list):
        issues.error("BLX8-BB-ANIMATIONS", "$.animations", "Expected an array.")
        return []
    animations: list[SourceAnimation] = []
    for animation_index, raw_animation in enumerate(raw_animations):
        location = source_index_location("$.animations", animation_index)
        if not isinstance(raw_animation, dict):
            issues.error("BLX8-BB-ANIMATION", location, "Expected an object.")
            continue
        validate_blockbench_animation(raw_animation, location, issues)
        name = normalized_name(string(raw_animation.get("name"), source_field_location(location, "name"), issues), source_field_location(location, "name"), issues)
        animation = SourceAnimation(name)
        animators = raw_animation.get("animators", {})
        if not isinstance(animators, dict):
            issues.error("BLX8-BB-ANIMATORS", source_field_location(location, "animators"), "Expected an object.")
            continue
        for animator_id, animator in sorted(animators.items(), key=lambda item: str(item[0])):
            animator_location = source_mapping_location(source_field_location(location, "animators"), str(animator_id))
            if not isinstance(animator, dict):
                issues.error("BLX8-BB-ANIMATOR", animator_location, "Expected an object.")
                continue
            if animator.get("type") not in (None, "bone"):
                continue
            bone = animator.get("name")
            if not isinstance(bone, str) or not bone:
                issues.unsupported(
                    "BLX8-UNSUPPORTED-ANIMATOR",
                    source_field_location(animator_location, "name"),
                    "Animator without a named bone cannot be converted.")
                continue
            keyframes = animator.get("keyframes", [])
            if not isinstance(keyframes, list):
                issues.error("BLX8-BB-KEYFRAMES", source_field_location(animator_location, "keyframes"), "Expected an array.")
                continue
            for key_index, keyframe in enumerate(keyframes):
                key_location = source_index_location(source_field_location(animator_location, "keyframes"), key_index)
                if not isinstance(keyframe, dict):
                    issues.error("BLX8-BB-KEYFRAME", key_location, "Expected an object.")
                    continue
                channel = keyframe.get("channel")
                definition = blockbench_channel_path(channel) if isinstance(channel, str) else None
                if definition is None:
                    issues.unsupported(
                        "BLX8-UNSUPPORTED-ANIMATION-CHANNEL",
                        source_field_location(key_location, "channel"),
                        "Only position, rotation, and scale channels are supported.")
                    continue
                destination, width = definition
                interpolation = str(keyframe.get("interpolation", "linear")).lower()
                if interpolation not in ("linear", "step"):
                    issues.unsupported(
                        "BLX8-UNSUPPORTED-INTERPOLATION",
                        source_field_location(key_location, "interpolation"),
                        "Bezier, Catmull-Rom, and expression interpolation are not representable in strict GLB.")
                    continue
                points = keyframe.get("data_points", [])
                if not isinstance(points, list) or len(points) != 1:
                    issues.unsupported(
                        "BLX8-UNSUPPORTED-KEYFRAME",
                        source_field_location(key_location, "data_points"),
                        "A keyframe must have exactly one numeric data point.")
                    continue
                point = points[0]
                if not isinstance(point, dict):
                    issues.error("BLX8-BB-KEYFRAME", source_index_location(source_field_location(key_location, "data_points"), 0), "Keyframe data point must be an object.")
                    continue
                coordinates = [point.get(axis) for axis in ("x", "y", "z")]
                vector = parse_vector(coordinates, source_index_location(source_field_location(key_location, "data_points"), 0), issues, width)
                if vector is None:
                    continue
                time = finite_number(keyframe.get("time"), source_field_location(key_location, "time"), issues)
                if time < 0:
                    issues.error("BLX8-ANIMATION-TIME", source_field_location(key_location, "time"), "Animation time cannot be negative.")
                    continue
                animation.channels.setdefault((bone, destination), []).append(AnimationKey(time, vector, interpolation.upper(), key_location))
        animations.append(animation)
    return animations


def parse_gecko_track(
        track: Any,
        track_location: str,
        issues: Issues) -> list[AnimationKey]:
    key_values: list[tuple[Any, Any, str]] = []
    if isinstance(track, list):
        key_values.append((0.0, track, track_location))
    elif isinstance(track, dict):
        sortable: list[tuple[float, Any, Any, str]] = []
        for time_key, key_value in track.items():
            key_location = source_mapping_location(track_location, str(time_key))
            try:
                sortable.append((float(time_key), time_key, key_value, key_location))
            except (TypeError, ValueError):
                issues.error("BLX8-ANIMATION-TIME", key_location, "Animation key time must be numeric.")
        key_values = [(time_key, key_value, key_location) for _sort_time, time_key, key_value, key_location in sorted(sortable, key=lambda item: item[0])]
    else:
        issues.error("BLX8-INPUT-ANIMATION-VALUE", track_location, "Track must be a vector or time-keyed object.")
        return []

    result: list[AnimationKey] = []
    for time_value, raw_value, key_location in key_values:
        time = finite_number(time_value, key_location, issues)
        if time < 0:
            issues.error("BLX8-ANIMATION-TIME", key_location, "Animation time cannot be negative.")
            continue
        interpolation = "linear"
        value = raw_value
        if isinstance(raw_value, dict):
            keyframe = validate_object_fields(
                raw_value,
                key_location,
                issues,
                supported=GECKO_KEYFRAME_FIELDS,
                known_unsupported=GECKO_KEYFRAME_UNSUPPORTED_FIELDS,
                object_name="Gecko animation keyframe")
            if keyframe is None or "vector" not in keyframe:
                issues.error("BLX8-INPUT-ANIMATION-VALUE", source_field_location(key_location, "vector"), "Keyframe must declare a numeric vector.")
                continue
            interpolation = str(keyframe.get("lerp_mode", "linear")).lower()
            value = keyframe["vector"]
        if interpolation not in ("linear", "step"):
            issues.unsupported(
                "BLX8-UNSUPPORTED-INTERPOLATION",
                source_field_location(key_location, "lerp_mode"),
                "Only linear and step interpolation can be represented.")
            continue
        vector = parse_vector(value, source_field_location(key_location, "vector") if isinstance(raw_value, dict) else key_location, issues, 3)
        if vector is not None:
            result.append(AnimationKey(time, vector, interpolation.upper(), key_location))
    return result


def parse_gecko_animations(
        document: dict[str, Any], issues: Issues, root_validated: bool = False) -> list[SourceAnimation]:
    if not root_validated:
        validate_gecko_root(document, issues)
    raw = document.get("animations", {})
    if raw is None:
        return []
    if not isinstance(raw, dict):
        issues.error("BLX8-GEO-ANIMATIONS", "$.animations", "Expected an object.")
        return []
    animations: list[SourceAnimation] = []
    for name, animation_value in sorted(raw.items(), key=lambda item: str(item[0])):
        location = source_mapping_location("$.animations", str(name))
        if not isinstance(animation_value, dict):
            issues.error("BLX8-GEO-ANIMATION", location, "Expected an object.")
            continue
        validate_object_fields(
            animation_value,
            location,
            issues,
            supported=GECKO_ANIMATION_FIELDS,
            author_metadata=GECKO_ANIMATION_AUTHOR_METADATA_FIELDS,
            known_unsupported=GECKO_ANIMATION_UNSUPPORTED_FIELDS,
            object_name="Gecko animation")
        animation = SourceAnimation(normalized_name(str(name), location, issues))
        raw_bones = animation_value.get("bones", {})
        if not isinstance(raw_bones, dict):
            issues.error("BLX8-GEO-ANIMATION-BONES", source_field_location(location, "bones"), "Expected an object.")
            continue
        for bone, tracks in sorted(raw_bones.items(), key=lambda item: str(item[0])):
            bone_location = source_mapping_location(source_field_location(location, "bones"), str(bone))
            if not isinstance(tracks, dict):
                issues.error("BLX8-GEO-ANIMATION-TRACK", bone_location, "Expected an object.")
                continue
            validate_object_fields(
                tracks,
                bone_location,
                issues,
                supported=GECKO_ANIMATION_BONE_FIELDS,
                known_unsupported=GECKO_ANIMATION_BONE_UNSUPPORTED_FIELDS,
                object_name="Gecko animation bone")
            for source_name, destination in (("position", "translation"), ("rotation", "rotation"), ("scale", "scale")):
                track = tracks.get(source_name)
                if track is None:
                    continue
                track_location = source_field_location(bone_location, source_name)
                keys = parse_gecko_track(track, track_location, issues)
                if keys:
                    animation.channels.setdefault((str(bone), destination), []).extend(keys)
        animations.append(animation)
    return animations


def quaternion_from_euler_degrees(rotation: Sequence[float]) -> tuple[float, float, float, float]:
    """Return the deterministic XYZ intrinsic rotation quaternion used by glTF nodes."""
    x, y, z = (math.radians(component) * 0.5 for component in rotation)
    cx, sx = math.cos(x), math.sin(x)
    cy, sy = math.cos(y), math.sin(y)
    cz, sz = math.cos(z), math.sin(z)
    return (
        sx * cy * cz + cx * sy * sz,
        cx * sy * cz - sx * cy * sz,
        cx * cy * sz + sx * sy * cz,
        cx * cy * cz - sx * sy * sz,
    )


def rotate_vector(vector: Sequence[float], rotation: Sequence[float]) -> tuple[float, float, float]:
    qx, qy, qz, qw = rotation
    vx, vy, vz = vector
    ix = qw * vx + qy * vz - qz * vy
    iy = qw * vy + qz * vx - qx * vz
    iz = qw * vz + qx * vy - qy * vx
    iw = -qx * vx - qy * vy - qz * vz
    return (
        ix * qw + iw * -qx + iy * -qz - iz * -qy,
        iy * qw + iw * -qy + iz * -qx - ix * -qz,
        iz * qw + iw * -qz + ix * -qy - iy * -qx,
    )


def subtract(left: Sequence[float], right: Sequence[float]) -> tuple[float, float, float]:
    return (left[0] - right[0], left[1] - right[1], left[2] - right[2])


def add(left: Sequence[float], right: Sequence[float]) -> tuple[float, float, float]:
    return (left[0] + right[0], left[1] + right[1], left[2] + right[2])


def multiply(vector: Sequence[float], scalar: float) -> tuple[float, float, float]:
    return (vector[0] * scalar, vector[1] * scalar, vector[2] * scalar)


def normalize(vector: Sequence[float]) -> tuple[float, float, float]:
    length = math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
    if length == 0:
        return (0.0, 1.0, 0.0)
    return (vector[0] / length, vector[1] / length, vector[2] / length)


def rotate_uv(corners: list[tuple[float, float]], degrees: Any, location: str, issues: Issues) -> list[tuple[float, float]]:
    if degrees is None:
        return corners
    try:
        rotation_number = float(degrees)
    except (TypeError, ValueError, OverflowError):
        issues.error("BLX8-INPUT-NUMBER", location, "UV rotation must be a finite number.")
        return corners
    if not math.isfinite(rotation_number) or abs(rotation_number) > MAX_ABSOLUTE_NUMBER:
        issues.error(
            "BLX8-INPUT-RANGE",
            location,
            f"UV rotation magnitude must not exceed {MAX_ABSOLUTE_NUMBER:g} degrees.")
        return corners
    if not rotation_number.is_integer():
        issues.unsupported("BLX8-UNSUPPORTED-UV-ROTATION", location, "UV rotation must be an integral multiple of 90 degrees.")
        return corners
    rotation = int(rotation_number)
    if rotation % 90 != 0:
        issues.unsupported("BLX8-UNSUPPORTED-UV-ROTATION", location, "UV rotation must be a multiple of 90 degrees.")
        return corners
    turns = (rotation // 90) % 4
    return corners[turns:] + corners[:turns]


def normalize_uv_rectangle(value: Any, texture_size: tuple[float, float], location: str, issues: Issues, rotation: Any = None) -> list[tuple[float, float]]:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        issues.error("BLX8-UV", location, "Expected a four-number UV rectangle.")
        return [(0.0, 0.0)] * 4
    u0 = finite_number(value[0], f"{location}[0]", issues)
    v0 = finite_number(value[1], f"{location}[1]", issues)
    u1 = finite_number(value[2], f"{location}[2]", issues)
    v1 = finite_number(value[3], f"{location}[3]", issues)
    width, height = texture_size
    corners = [(u0 / width, v1 / height), (u1 / width, v1 / height), (u1 / width, v0 / height), (u0 / width, v0 / height)]
    return rotate_uv(corners, rotation, location, issues)


def gecko_box_uv(origin: Sequence[float], size: Sequence[float], uv: Sequence[float], face: str, texture_size: tuple[float, float]) -> list[tuple[float, float]]:
    """Approximate the standard Bedrock box atlas layout without generating a second UV set."""
    u, v = uv
    x = abs(size[0])
    y = abs(size[1])
    z = abs(size[2])
    rectangles = {
        "north": (u + z + x, v + z, u + z + x + x, v + z + y),
        "south": (u + z, v + z, u + z + x, v + z + y),
        "east": (u, v + z, u + z, v + z + y),
        "west": (u + z + x + x, v + z, u + z + x + x + z, v + z + y),
        "up": (u + z, v, u + z + x, v + z),
        "down": (u + z + x, v, u + z + x + x, v + z),
    }
    rectangle = rectangles[face]
    width, height = texture_size
    return [
        (rectangle[0] / width, rectangle[3] / height),
        (rectangle[2] / width, rectangle[3] / height),
        (rectangle[2] / width, rectangle[1] / height),
        (rectangle[0] / width, rectangle[1] / height),
    ]


def cube_face_uv(cube: SourceCube, face: str, texture_size: tuple[float, float], location: str, issues: Issues) -> list[tuple[float, float]]:
    if isinstance(cube.uv, dict):
        value = cube.uv.get(face)
        if isinstance(value, dict):
            raw_uv = value.get("uv")
            # Gecko/Bedrock geometry sometimes expresses a named face as
            # ``uv: [u, v], uv_size: [width, height]`` instead of the
            # Blockbench-style four-corner rectangle.  Expand it explicitly;
            # a malformed pair remains an error rather than becoming a
            # silently degenerate texture mapping.
            if isinstance(raw_uv, (list, tuple)) and len(raw_uv) == 2 and isinstance(value.get("uv_size"), (list, tuple)) and len(value["uv_size"]) == 2:
                u = finite_number(raw_uv[0], f"{location}.{face}.uv[0]", issues)
                v = finite_number(raw_uv[1], f"{location}.{face}.uv[1]", issues)
                width = finite_number(value["uv_size"][0], f"{location}.{face}.uv_size[0]", issues)
                height = finite_number(value["uv_size"][1], f"{location}.{face}.uv_size[1]", issues)
                return normalize_uv_rectangle((u, v, u + width, v + height), texture_size, f"{location}.{face}.uv", issues, value.get("rotation"))
            return normalize_uv_rectangle(raw_uv, texture_size, f"{location}.{face}.uv", issues, value.get("rotation"))
        if isinstance(value, (list, tuple)):
            return normalize_uv_rectangle(value, texture_size, f"{location}.{face}", issues)
        if cube.uv and all(isinstance(item, (int, float)) for item in cube.uv.values()):
            issues.unsupported("BLX8-UNSUPPORTED-UV", location, "Named cuboid faces require a uv rectangle object.")
            return [(0.0, 0.0)] * 4
        issues.error("BLX8-UV", f"{location}.{face}", "Missing UV rectangle for cuboid face.")
        return [(0.0, 0.0)] * 4
    if isinstance(cube.uv, (list, tuple)) and len(cube.uv) == 2:
        raw_uv = (
            finite_number(cube.uv[0], f"{location}[0]", issues),
            finite_number(cube.uv[1], f"{location}[1]", issues),
        )
        return gecko_box_uv(cube.origin, cube.size, raw_uv, face, texture_size)
    issues.error("BLX8-UV", location, "Expected a GeckoLib [u, v] box UV or Blockbench face map.")
    return [(0.0, 0.0)] * 4


def append_cube(geometry: PrimitiveGeometry, cube: SourceCube, bone: SourceBone, texture_size: tuple[float, float], cube_index: int, issues: Issues) -> None:
    if any(component == 0 for component in cube.size):
        issues.error("BLX8-CUBE-SIZE", cube.name, "Cuboid dimensions must be non-zero.")
        return
    if len(geometry.positions) // 3 + 24 > MAX_VERTICES:
        issues.error("BLX8-LIMIT-VERTICES", cube.name, f"Model exceeds {MAX_VERTICES} generated vertices.")
        return
    lower = (
        min(cube.origin[0], cube.origin[0] + cube.size[0]) - cube.inflate,
        min(cube.origin[1], cube.origin[1] + cube.size[1]) - cube.inflate,
        min(cube.origin[2], cube.origin[2] + cube.size[2]) - cube.inflate,
    )
    upper = (
        max(cube.origin[0], cube.origin[0] + cube.size[0]) + cube.inflate,
        max(cube.origin[1], cube.origin[1] + cube.size[1]) + cube.inflate,
        max(cube.origin[2], cube.origin[2] + cube.size[2]) + cube.inflate,
    )
    x0, y0, z0 = lower
    x1, y1, z1 = upper
    faces: dict[str, tuple[tuple[float, float, float], list[tuple[float, float, float]]]] = {
        "north": ((0.0, 0.0, -1.0), [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)]),
        "south": ((0.0, 0.0, 1.0), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]),
        "east": ((1.0, 0.0, 0.0), [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)]),
        "west": ((-1.0, 0.0, 0.0), [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)]),
        "up": ((0.0, 1.0, 0.0), [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)]),
        "down": ((0.0, -1.0, 0.0), [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)]),
    }
    rotation = quaternion_from_euler_degrees(cube.rotation)
    local_pivot = subtract(cube.pivot, bone.pivot)
    for face_name in ("north", "south", "east", "west", "up", "down"):
        normal, vertices = faces[face_name]
        uvs = cube_face_uv(cube, face_name, texture_size, f"cubes[{cube_index}].uv", issues)
        start = len(geometry.positions) // 3
        for vertex, uv in zip(vertices, uvs):
            local = subtract(vertex, bone.pivot)
            rotated = add(rotate_vector(subtract(local, local_pivot), rotation), local_pivot)
            geometry.positions.extend(rotated)
            geometry.normals.extend(normalize(rotate_vector(normal, rotation)))
            geometry.uvs.extend(uv)
        if cube.mirror:
            geometry.indices.extend((start, start + 2, start + 1, start, start + 3, start + 2))
        else:
            geometry.indices.extend((start, start + 1, start + 2, start, start + 2, start + 3))


def validate_strict_v1_scale(scale: Sequence[float], location: str, issues: Issues) -> None:
    """Match frozen core Transform/AnimationChannel validation before GLB output.

    Scale cannot be made safe by ``--allow-lossy``: dropping or passing through
    an invalid rest/animated scale would produce a strict-v1 GLB the core rejects.
    The emitted values are checked after IEEE-754 float32 conversion because GLB
    stores FLOAT accessors and the Java core validates floats.
    """
    float_values: list[float] = []
    for index, component in enumerate(scale):
        component_location = source_index_location(location, index)
        try:
            encoded = struct.pack("<f", float(component))
            converted = struct.unpack("<f", encoded)[0]
        except (OverflowError, struct.error, TypeError, ValueError):
            issues.error("BLX8-STRICT-SCALE-FINITE", component_location, "Strict v1 scale must be finite after FLOAT conversion.")
            continue
        if not math.isfinite(converted):
            issues.error("BLX8-STRICT-SCALE-FINITE", component_location, "Strict v1 scale must be finite after FLOAT conversion.")
            continue
        if converted <= 0.0:
            issues.error(
                "BLX8-STRICT-SCALE-POSITIVE",
                component_location,
                "Strict v1 node scale must be positive; negative or zero scale must be baked before conversion.")
        float_values.append(converted)
    if len(float_values) != 3 or any(value <= 0.0 for value in float_values):
        return
    minimum = min(float_values)
    maximum = max(float_values)
    if maximum - minimum > max(1.0e-6, maximum * 1.0e-5):
        issues.error(
            "BLX8-STRICT-SCALE-UNIFORM",
            location,
            "Strict v1 node scale must be uniform; non-uniform scale must be baked before conversion.")


def validate_model(model: SourceModel, issues: Issues) -> None:
    if not model.bones:
        issues.error("BLX8-MODEL-BONES", "bones", "At least one bone or implicit root is required.")
    if not model.cubes:
        issues.error("BLX8-MODEL-CUBES", "cubes", "At least one cuboid is required.")
    if len(model.cubes) > MAX_CUBES:
        issues.error("BLX8-LIMIT-CUBES", "cubes", f"At most {MAX_CUBES} cuboids are allowed.")
    if len(model.bones) > MAX_BONES:
        issues.error("BLX8-LIMIT-BONES", "bones", f"At most {MAX_BONES} bones are allowed.")
    names = {bone.name for bone in model.bones}
    for bone in model.bones:
        if bone.parent is not None and bone.parent not in names:
            issues.error("BLX8-BONE-PARENT", bone.name, f"Unknown parent bone: {bone.parent}")
        validate_strict_v1_scale(bone.scale, source_field_location(bone.location, "scale"), issues)
    for cube in model.cubes:
        if cube.bone not in names:
            issues.error("BLX8-CUBE-BONE", cube.name, f"Cube references unknown bone: {cube.bone}")
    if len(model.textures) == 0:
        issues.error("BLX8-TEXTURE-REQUIRED", "textures", "A strict output requires one external PNG texture.")
    if len(model.textures) > 1:
        issues.unsupported("BLX8-UNSUPPORTED-MULTI-TEXTURE", "textures", "This deterministic converter emits one material; split the model or choose one texture.")
    if len(model.animations) > MAX_CLIPS:
        issues.error("BLX8-LIMIT-CLIPS", "animations", f"At most {MAX_CLIPS} animation clips are allowed.")
    animation_names: set[str] = set()
    total_keyframe_samples = 0
    for animation in model.animations:
        if animation.name in animation_names:
            issues.error("BLX8-ANIMATION-DUPLICATE", animation.name, "Animation names must be unique.")
        animation_names.add(animation.name)
        if not animation.channels:
            issues.unsupported(
                "BLX8-UNSUPPORTED-EMPTY-ANIMATION",
                animation.name,
                "An animation with no supported channels cannot be represented as a strict GLB clip.")
        for (bone, path), keys in animation.channels.items():
            if bone not in names:
                issues.error("BLX8-ANIMATION-BONE", animation.name, f"Animation targets unknown bone: {bone}")
            if len(keys) > MAX_ANIMATION_KEYS:
                issues.error("BLX8-LIMIT-ANIMATION-KEYS", animation.name, f"A channel has more than {MAX_ANIMATION_KEYS} keys.")
            total_keyframe_samples += len(keys)
            previous = -1.0
            modes = {key.interpolation for key in keys}
            if len(modes) > 1:
                issues.unsupported("BLX8-MIXED-INTERPOLATION", animation.name, "A target path with mixed interpolation cannot be represented without changing key semantics.")
            for key in sorted(keys, key=lambda item: item.time):
                if key.time > MAX_CLIP_DURATION_SECONDS:
                    issues.error(
                        "BLX8-LIMIT-CLIP-DURATION",
                        animation.name,
                        f"Animation times must not exceed {MAX_CLIP_DURATION_SECONDS:g} seconds.")
                    break
                if key.time <= previous:
                    issues.error("BLX8-ANIMATION-TIME", animation.name, "Animation times must be strictly increasing per target path.")
                    break
                previous = key.time
                if path == "scale":
                    validate_strict_v1_scale(key.value, key.location, issues)
    if total_keyframe_samples > MAX_KEYFRAME_SAMPLES:
        issues.error(
            "BLX8-LIMIT-KEYFRAME-SAMPLES",
            "animations",
            f"At most {MAX_KEYFRAME_SAMPLES} total keyframe samples are allowed.")


def geometry_by_bone(model: SourceModel, issues: Issues) -> dict[str, PrimitiveGeometry]:
    lookup = {bone.name: bone for bone in model.bones}
    result = {bone.name: PrimitiveGeometry() for bone in model.bones}
    for cube_index, cube in enumerate(model.cubes):
        if cube.bone in lookup:
            append_cube(result[cube.bone], cube, lookup[cube.bone], model.texture_size, cube_index, issues)
    return result


class BinaryBuilder:
    def __init__(self) -> None:
        self.data = bytearray()

    def append(self, payload: bytes) -> tuple[int, int]:
        while len(self.data) % 4:
            self.data.append(0)
        offset = len(self.data)
        self.data.extend(payload)
        return offset, len(payload)


def floats_payload(values: Sequence[float]) -> bytes:
    return struct.pack("<" + "f" * len(values), *values)


def index_payload(values: Sequence[int]) -> tuple[bytes, int]:
    if any(value < 0 for value in values):
        raise ConversionError("generated index is negative")
    if max(values, default=0) <= 65535:
        return struct.pack("<" + "H" * len(values), *values), 5123
    return struct.pack("<" + "I" * len(values), *values), 5125


def min_max_vec3(values: Sequence[float]) -> tuple[list[float], list[float]]:
    triples = list(zip(values[0::3], values[1::3], values[2::3]))
    return (
        [min(component[index] for component in triples) for index in range(3)],
        [max(component[index] for component in triples) for index in range(3)],
    )


def append_accessor(document: dict[str, Any], binary: BinaryBuilder, payload: bytes, component_type: int, count: int, accessor_type: str, minimum: list[float] | None = None, maximum: list[float] | None = None) -> int:
    offset, length = binary.append(payload)
    buffer_view = {
        "buffer": 0,
        "byteLength": length,
        "byteOffset": offset,
    }
    document.setdefault("bufferViews", []).append(buffer_view)
    accessor: dict[str, Any] = {
        "bufferView": len(document["bufferViews"]) - 1,
        "componentType": component_type,
        "count": count,
        "type": accessor_type,
    }
    if minimum is not None:
        accessor["min"] = minimum
    if maximum is not None:
        accessor["max"] = maximum
    document.setdefault("accessors", []).append(accessor)
    return len(document["accessors"]) - 1


def validate_hierarchy(bones: Sequence[SourceBone], issues: Issues) -> None:
    parents = {bone.name: bone.parent for bone in bones}
    for name in parents:
        seen: set[str] = set()
        current: str | None = name
        depth = 0
        while current is not None:
            if current in seen:
                issues.error("BLX8-BONE-CYCLE", name, "Bone hierarchy contains a cycle.")
                break
            seen.add(current)
            current = parents.get(current)
            depth += 1
            if depth > MAX_HIERARCHY_DEPTH:
                issues.error("BLX8-LIMIT-DEPTH", name, f"Bone hierarchy exceeds {MAX_HIERARCHY_DEPTH} levels.")
                break


def gltf_animation(model: SourceModel, document: dict[str, Any], binary: BinaryBuilder, node_ids: dict[str, int], issues: Issues) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for animation in sorted(model.animations, key=lambda item: item.name):
        samplers: list[dict[str, Any]] = []
        channels: list[dict[str, Any]] = []
        for (bone, path), source_keys in sorted(animation.channels.items(), key=lambda item: (item[0][0], item[0][1])):
            if bone not in node_ids or not source_keys:
                continue
            keys = sorted(source_keys, key=lambda item: item.time)
            times = [key.time for key in keys]
            output: list[float] = []
            output_type = "VEC3"
            if path == "rotation":
                output_type = "VEC4"
                for key in keys:
                    output.extend(quaternion_from_euler_degrees(key.value))
            else:
                for key in keys:
                    output.extend(key.value)
            input_accessor = append_accessor(
                document,
                binary,
                floats_payload(times),
                5126,
                len(times),
                "SCALAR",
                [min(times)],
                [max(times)],
            )
            output_accessor = append_accessor(
                document,
                binary,
                floats_payload(output),
                5126,
                len(keys),
                output_type,
            )
            interpolation = keys[0].interpolation
            if any(key.interpolation != interpolation for key in keys):
                # validate_model records a fail-closed issue unless the user selected explicit lossy mode.
                interpolation = "LINEAR"
            samplers.append(
                {
                    "input": input_accessor,
                    "interpolation": interpolation,
                    "output": output_accessor,
                }
            )
            channels.append(
                {
                    "sampler": len(samplers) - 1,
                    "target": {"node": node_ids[bone], "path": path},
                }
            )
        if channels:
            result.append({"name": animation.name, "samplers": samplers, "channels": channels})
    return result


def build_glb(model: SourceModel, issues: Issues) -> bytes:
    geometry = geometry_by_bone(model, issues)
    validate_hierarchy(model.bones, issues)
    if issues.has_errors():
        raise ConversionError("model validation failed before GLB emission")
    document: dict[str, Any] = {
        "asset": {"generator": "BlendLib X8 offline converter", "version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": []}],
        "nodes": [],
        "meshes": [],
        "materials": [{"name": "Surface"}],
        "accessors": [],
        "bufferViews": [],
        "buffers": [{"byteLength": 0}],
    }
    binary = BinaryBuilder()
    node_ids: dict[str, int] = {}
    bone_lookup = {bone.name: bone for bone in model.bones}
    for bone in model.bones:
        parent_pivot = bone_lookup[bone.parent].pivot if bone.parent else (0.0, 0.0, 0.0)
        node = {
            "name": bone.name,
            "translation": list(subtract(bone.pivot, parent_pivot)),
            "rotation": list(quaternion_from_euler_degrees(bone.rotation)),
            "scale": list(bone.scale),
        }
        bone_geometry = geometry[bone.name]
        if bone_geometry.indices:
            position_minimum, position_maximum = min_max_vec3(bone_geometry.positions)
            position_accessor = append_accessor(
                document,
                binary,
                floats_payload(bone_geometry.positions),
                5126,
                len(bone_geometry.positions) // 3,
                "VEC3",
                position_minimum,
                position_maximum,
            )
            normal_accessor = append_accessor(
                document,
                binary,
                floats_payload(bone_geometry.normals),
                5126,
                len(bone_geometry.normals) // 3,
                "VEC3",
            )
            uv_accessor = append_accessor(
                document,
                binary,
                floats_payload(bone_geometry.uvs),
                5126,
                len(bone_geometry.uvs) // 2,
                "VEC2",
            )
            indices, component_type = index_payload(bone_geometry.indices)
            index_accessor = append_accessor(
                document,
                binary,
                indices,
                component_type,
                len(bone_geometry.indices),
                "SCALAR",
            )
            mesh = {
                "name": f"{bone.name}_mesh",
                "primitives": [
                    {
                        "attributes": {
                            "NORMAL": normal_accessor,
                            "POSITION": position_accessor,
                            "TEXCOORD_0": uv_accessor,
                        },
                        "indices": index_accessor,
                        "material": 0,
                        "mode": 4,
                    }
                ],
            }
            document["meshes"].append(mesh)
            node["mesh"] = len(document["meshes"]) - 1
        document["nodes"].append(node)
        node_ids[bone.name] = len(document["nodes"]) - 1
    for bone in model.bones:
        node = document["nodes"][node_ids[bone.name]]
        if bone.parent is None:
            document["scenes"][0]["nodes"].append(node_ids[bone.name])
        else:
            document["nodes"][node_ids[bone.parent]].setdefault("children", []).append(node_ids[bone.name])
    animations = gltf_animation(model, document, binary, node_ids, issues)
    if animations:
        document["animations"] = animations
    if issues.has_errors():
        raise ConversionError("model validation failed while encoding animations")
    document["buffers"][0]["byteLength"] = len(binary.data)
    json_chunk = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    while len(json_chunk) % 4:
        json_chunk += b" "
    while len(binary.data) % 4:
        binary.data.append(0)
    total_length = 12 + 8 + len(json_chunk) + 8 + len(binary.data)
    if total_length > MAX_GLB_BYTES:
        issues.error(
            "BLX8-LIMIT-GLB-BYTES",
            "output",
            f"Generated strict GLB exceeds the {MAX_GLB_BYTES} byte runtime limit.")
        raise ConversionError("generated strict GLB exceeds the runtime byte limit")
    return (
        struct.pack("<4sII", b"glTF", 2, total_length)
        + struct.pack("<I4s", len(json_chunk), b"JSON")
        + json_chunk
        + struct.pack("<I4s", len(binary.data), b"BIN\x00")
        + bytes(binary.data)
    )


def validate_namespace(namespace: str) -> str:
    if not re.fullmatch(r"[a-z0-9._-]+", namespace):
        raise ConversionError("namespace: expected lowercase [a-z0-9._-]+")
    return namespace


def validate_model_path(model: str) -> str:
    path = safe_relative_path(model, "model")
    normalized = path.as_posix()
    if not IDENTIFIER_COMPONENT.fullmatch(normalized) or normalized.endswith(".json") or normalized.endswith(".glb"):
        raise ConversionError("model: expected a lowercase BlendLib resource path without an extension")
    return normalized


def validate_units_per_block(value: Any) -> float:
    try:
        units = float(value)
    except (TypeError, ValueError, OverflowError) as error:
        raise ConversionError("--source-units-per-block must be a finite positive number") from error
    if not math.isfinite(units) or units <= 0.0 or units > MAX_ABSOLUTE_NUMBER:
        raise ConversionError("--source-units-per-block must be a finite positive number within the converter numeric bound")
    return units


def read_texture(source_root: Path, texture: str, budget: InputReadBudget) -> tuple[Path, bytes]:
    relative = safe_relative_path(texture, "texture")
    if relative.suffix.lower() != ".png":
        raise ConversionError("texture: strict output accepts external PNG files only")
    path = safe_child(source_root, relative.as_posix(), "texture")
    budget.preflight(path, MAX_TEXTURE_BYTES, "texture")
    payload = read_bounded_bytes(path, MAX_TEXTURE_BYTES, "texture", budget)
    header = payload[:33]
    if (len(header) != 33
            or header[:8] != PNG_SIGNATURE
            or header[8:12] != b"\x00\x00\x00\r"
            or header[12:16] != b"IHDR"):
        raise ConversionError("texture: strict output requires a PNG signature and IHDR header")
    width, height = struct.unpack(">II", header[16:24])
    if width == 0 or height == 0 or width > int(MAX_TEXTURE_DIMENSION) or height > int(MAX_TEXTURE_DIMENSION):
        raise ConversionError(f"texture: dimensions must be in [1, {MAX_TEXTURE_DIMENSION:g}]")
    return path, payload


def is_link_or_reparse_point(path: Path, status: os.stat_result | None = None) -> bool:
    state = status if status is not None else path.lstat()
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return path.is_symlink() or bool(getattr(state, "st_file_attributes", 0) & reparse_flag)


def ensure_safe_output_parent(destination: Path, output_base: Path, location: str, create: bool) -> None:
    base = output_base.resolve()
    try:
        relative_parent = destination.parent.relative_to(base)
    except ValueError as error:
        raise ConversionError(f"{location}: destination parent escapes the approved output base") from error
    current = base
    for part in relative_parent.parts:
        current = current / part
        try:
            status = current.lstat()
        except FileNotFoundError:
            if not create:
                return
            try:
                current.mkdir()
            except FileExistsError:
                pass
            except OSError as error:
                raise ConversionError(f"{location}: cannot create output parent {current}: {error}") from error
            try:
                status = current.lstat()
            except OSError as error:
                raise ConversionError(f"{location}: cannot inspect created output parent {current}: {error}") from error
        except OSError as error:
            raise ConversionError(f"{location}: cannot inspect output parent {current}: {error}") from error
        if is_link_or_reparse_point(current, status):
            raise ConversionError(f"{location}: output parent may not be a symlink or reparse point: {current}")
        if not stat.S_ISDIR(status.st_mode):
            raise ConversionError(f"{location}: output parent must be a directory: {current}")


def verify_output_target(destination: Path, output_base: Path, force: bool, location: str, create_parent: bool) -> None:
    ensure_safe_output_parent(destination, output_base, location, create_parent)
    try:
        status = destination.lstat()
    except FileNotFoundError:
        return
    except OSError as error:
        raise ConversionError(f"{location}: cannot inspect destination {destination}: {error}") from error
    if is_link_or_reparse_point(destination, status):
        raise ConversionError(f"{location}: destination may not be a symlink or reparse point: {destination}")
    if not stat.S_ISREG(status.st_mode):
        raise ConversionError(f"{location}: destination exists but is not a regular file: {destination}")
    if not force:
        raise ConversionError(f"{location}: destination already exists; pass --force to replace this regular file: {destination}")


def preflight_output_targets(
        output_paths: Sequence[tuple[Path, str]], output_base: Path, force: bool) -> None:
    for destination, location in output_paths:
        verify_output_target(destination, output_base, force, location, create_parent=False)


def atomic_write_bytes(destination: Path, payload: bytes, output_base: Path, force: bool, location: str) -> None:
    # Check once before creating the sibling temporary file, then again after
    # it is fsynced and immediately before publication.  The no-force route
    # uses a hard link so a newly-created target cannot be silently replaced
    # between that final check and publication.
    verify_output_target(destination, output_base, force, location, create_parent=True)
    handle = tempfile.NamedTemporaryFile(prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent, delete=False)
    temporary = Path(handle.name)
    try:
        with handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        verify_output_target(destination, output_base, force, location, create_parent=False)
        if force:
            os.replace(temporary, destination)
        else:
            try:
                os.link(temporary, destination)
            except FileExistsError as error:
                raise ConversionError(f"{location}: destination appeared during publication; no file was replaced: {destination}") from error
            temporary.unlink()
    finally:
        if temporary.exists():
            temporary.unlink()


def reject_output_collisions(output_paths: Sequence[Path], source_paths: Sequence[Path]) -> None:
    normalized_outputs = [path.resolve() for path in output_paths]
    if len(normalized_outputs) != len(set(normalized_outputs)):
        raise ConversionError("output: report, descriptor, GLB, and texture destinations must be distinct")
    normalized_sources = {path.resolve() for path in source_paths}
    collisions = [path for path in normalized_outputs if path in normalized_sources]
    if collisions:
        raise ConversionError(f"output: destination would overwrite authoring input: {collisions}")


def atomic_write_json(destination: Path, document: dict[str, Any], output_base: Path, force: bool, location: str) -> None:
    encoded = (json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    atomic_write_bytes(destination, encoded, output_base, force, location)


def descriptor(namespace: str, model_path: str, texture_stem: str, units_per_block: float) -> dict[str, Any]:
    return {
        "extensions": {},
        "extensions_required": [],
        "extensions_used": [],
        "format_version": 1,
        "materials": {
            "Surface": {
                "base_color": f"{namespace}:textures/blendlib/{texture_stem}__surface.png",
                "double_sided": False,
                "emissive": False,
                "mode": "opaque",
            }
        },
        "mesh": f"{namespace}:models3d/{model_path}.glb",
        "profile": "blendlib:rigid_v1",
        "units_per_block": units_per_block,
    }


def report_document(
        status: str,
        args: argparse.Namespace,
        model: SourceModel | None,
        issues: Issues,
        texture_manifest: list[dict[str, str]],
        units_per_block: float | None) -> dict[str, Any]:
    return {
        "converter": "blendlib-x8-offline-converter",
        "dialect": model.dialect if model else None,
        "issues": issues.as_json(),
        "model": args.model,
        "namespace": args.namespace,
        "status": status,
        "texture_manifest": texture_manifest,
        "unit_mapping": {
            "coordinate_policy": "Source cuboid coordinates, pivots, node translations, and animation translation keys are emitted numerically unchanged; no hidden axis or scale compensation is applied.",
            "minecraft_blocks_per_emitted_unit": 1.0 / units_per_block if units_per_block else None,
            "source_units_per_block": units_per_block,
            "target_descriptor_units_per_block": units_per_block,
        },
        "version": 1,
    }


def reject_blockbench_geometry_in_animation_input(document: dict[str, Any], issues: Issues) -> None:
    """Do not silently discard a combined Blockbench model passed as --animation."""
    if "outliner" in document:
        outliner = document["outliner"]
        if not isinstance(outliner, list):
            issues.error("BLX8-BB-OUTLINER", "$.outliner", "Expected an array.")
        elif outliner:
            blockbench_outliner_attachments(outliner, issues)
            issues.unsupported(
                "BLX8-UNSUPPORTED-ANIMATION-INPUT-GEOMETRY",
                "$.outliner",
                "A separate --animation input contributes animation only; its model hierarchy is not emitted.")
    if "elements" in document:
        elements = document["elements"]
        if not isinstance(elements, list):
            issues.error("BLX8-BB-ELEMENTS", "$.elements", "Expected an array.")
        else:
            for index, element in enumerate(elements):
                location = source_index_location("$.elements", index)
                if not isinstance(element, dict):
                    issues.error("BLX8-BB-ELEMENT", location, "Expected an object.")
                    continue
                validate_blockbench_element(element, location, issues)
            if elements:
                issues.unsupported(
                    "BLX8-UNSUPPORTED-ANIMATION-INPUT-GEOMETRY",
                    "$.elements",
                    "A separate --animation input contributes animation only; its cuboid geometry is not emitted.")


def reject_gecko_geometry_in_animation_input(document: dict[str, Any], issues: Issues) -> None:
    """Classify every embedded Gecko geometry object before omitting it."""
    raw_geometry = document.get("minecraft:geometry")
    if isinstance(raw_geometry, list):
        for index, geometry in enumerate(raw_geometry):
            location = source_index_location("$.minecraft:geometry", index)
            if not isinstance(geometry, dict):
                issues.error("BLX8-GEO-GEOMETRY", location, "Expected a geometry object.")
                continue
            validate_gecko_geometry(geometry, location, False, issues)
        if raw_geometry:
            issues.unsupported(
                "BLX8-UNSUPPORTED-ANIMATION-INPUT-GEOMETRY",
                "$.minecraft:geometry",
                "A separate --animation input contributes animation only; its Gecko geometry is not emitted.")
    elif "minecraft:geometry" in document:
        issues.error("BLX8-GEO-GEOMETRY", "$.minecraft:geometry", "Expected an array of geometry objects.")
    if "bones" in document:
        if isinstance(document["bones"], list):
            validate_gecko_geometry(document, "$", True, issues)
            if document["bones"]:
                issues.unsupported(
                    "BLX8-UNSUPPORTED-ANIMATION-INPUT-GEOMETRY",
                    "$.bones",
                    "A separate --animation input contributes animation only; its Gecko geometry is not emitted.")
        else:
            issues.error("BLX8-GEO-BONES", "$.bones", "Expected an array.")
    for field_name in sorted(key for key in document if key.startswith("geometry.")):
        geometry = document[field_name]
        location = source_field_location("$", field_name)
        if isinstance(geometry, dict):
            validate_gecko_geometry(geometry, location, False, issues)
        else:
            issues.error("BLX8-GEO-GEOMETRY", location, "Expected a geometry object.")
        issues.unsupported(
            "BLX8-UNSUPPORTED-ANIMATION-INPUT-GEOMETRY",
            location,
            "A separate --animation input contributes animation only; its Gecko geometry is not emitted.")


def parse_animation_input(path: Path, source_root: Path, issues: Issues, budget: InputReadBudget) -> list[SourceAnimation]:
    document = read_json(path, source_root, issues, budget)
    dialect = detect_dialect(document)
    if dialect == "blockbench":
        validate_blockbench_document(document, issues)
        reject_blockbench_geometry_in_animation_input(document, issues)
        return parse_blockbench_animations(document, issues, root_validated=True)
    validate_gecko_root(document, issues)
    reject_gecko_geometry_in_animation_input(document, issues)
    return parse_gecko_animations(document, issues, root_validated=True)


def add_animations(
        model: SourceModel,
        inputs: Sequence[Path],
        source_root: Path,
        issues: Issues,
        budget: InputReadBudget) -> None:
    existing = {animation.name for animation in model.animations}
    for path in inputs:
        for animation in parse_animation_input(path, source_root, issues, budget):
            if animation.name in existing:
                issues.error("BLX8-ANIMATION-DUPLICATE", animation.name, "Animation name is defined by more than one input.")
            else:
                model.animations.append(animation)
                existing.add(animation.name)


def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(
        prog="blendlib-model-converter",
        description="Offline-only Blockbench/GeckoLib JSON to strict BlendLib GLB 2.0 converter.",
    )
    command.add_argument("--input", required=True, help="Primary JSON input relative to --source-root.")
    command.add_argument("--animation", action="append", default=[], help="Optional animation JSON input relative to --source-root; may repeat.")
    command.add_argument("--texture", action="append", default=[], help="External PNG override relative to --source-root; may repeat.")
    command.add_argument("--source-root", default=".", help="Explicit local root containing all input paths; defaults to the current directory.")
    command.add_argument("--out", required=True, help="Relative output directory beneath the current directory.")
    command.add_argument("--namespace", required=True, help="Lowercase resource namespace for the emitted pack.")
    command.add_argument("--model", required=True, help="Lowercase resource path without .json or .glb.")
    command.add_argument("--source-units-per-block", default="16", help="Positive source cuboid units per Minecraft block; Blockbench/GeckoLib cuboid exports conventionally use 16.")
    command.add_argument("--texture-mode", choices=("copy", "reference"), default="copy", help="Copy the source PNG atomically, or emit only its external copy manifest.")
    command.add_argument("--report", default="conversion-report.json", help="Relative report path beneath --out.")
    command.add_argument("--allow-lossy", action="store_true", help="Permit explicitly reported unsupported source constructs to be omitted; default is fail closed.")
    command.add_argument("--force", action="store_true", help="Replace existing regular output files after output safety checks; the default refuses every existing target.")
    return command


def validate_requested_input_counts(args: argparse.Namespace) -> None:
    if len(args.animation) > MAX_EXTERNAL_ANIMATION_INPUTS:
        raise ConversionError(
            f"--animation: at most {MAX_EXTERNAL_ANIMATION_INPUTS} external animation inputs are allowed")
    if len(args.texture) > MAX_EXTERNAL_TEXTURE_INPUTS:
        raise ConversionError(
            f"--texture: at most {MAX_EXTERNAL_TEXTURE_INPUTS} external texture inputs are allowed")


def preflight_requested_inputs(
        input_path: Path,
        animation_paths: Sequence[Path],
        texture_paths: Sequence[Path],
        budget: InputReadBudget) -> None:
    budget.preflight(input_path, MAX_JSON_BYTES, "--input")
    for animation_path in animation_paths:
        budget.preflight(animation_path, MAX_JSON_BYTES, "--animation")
    for texture_path in texture_paths:
        budget.preflight(texture_path, MAX_TEXTURE_BYTES, "--texture")


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    issues = Issues(args.allow_lossy)
    model: SourceModel | None = None
    texture_manifest: list[dict[str, str]] = []
    output_base = Path.cwd().resolve()
    output_root: Path | None = None
    report_path: Path | None = None
    units_per_block: float | None = None
    try:
        validate_requested_input_counts(args)
        source_root = Path(args.source_root).resolve()
        if not source_root.is_dir():
            raise ConversionError("--source-root must name an existing local directory")
        output_root = safe_child(output_base, args.out, "--out")
        report_path = safe_child(output_root, args.report, "--report")
        namespace = validate_namespace(args.namespace)
        model_path = validate_model_path(args.model)
        units_per_block = validate_units_per_block(args.source_units_per_block)
        texture_stem = model_path.replace("/", "_")
        descriptor_path = safe_child(output_root, f"assets/{namespace}/blend_models/{model_path}.json", "descriptor destination")
        glb_path = safe_child(output_root, f"assets/{namespace}/models3d/{model_path}.glb", "GLB destination")
        input_path = safe_child(source_root, args.input, "--input")
        animation_paths = [safe_child(source_root, raw, "--animation") for raw in args.animation]
        explicit_texture_paths = [safe_child(source_root, raw, "--texture") for raw in args.texture]
        input_budget = InputReadBudget(MAX_CUMULATIVE_INPUT_BYTES)
        try:
            reject_output_collisions([report_path, descriptor_path, glb_path], [input_path, *animation_paths, *explicit_texture_paths])
            preflight_output_targets(
                [(report_path, "report destination"), (descriptor_path, "descriptor destination"), (glb_path, "GLB destination")],
                output_base,
                args.force)
        except ConversionError:
            # No failure report may be published if its own target (or any base
            # target) is already unsafe/replacement-protected.
            report_path = None
            raise
        preflight_requested_inputs(input_path, animation_paths, explicit_texture_paths, input_budget)
        document = read_json(input_path, source_root, issues, input_budget)
        dialect = detect_dialect(document)
        model = parse_blockbench(document, issues) if dialect == "blockbench" else parse_geckolib(document, issues)
        add_animations(model, animation_paths, source_root, issues, input_budget)
        if args.texture:
            model.textures = list(args.texture)
        validate_model(model, issues)
        validate_hierarchy(model.bones, issues)
        if issues.has_errors():
            raise ConversionError("conversion rejected; see the structured report")
        texture_destination_relative = f"assets/{namespace}/textures/blendlib/{texture_stem}__surface.png"
        if model.textures:
            texture_source_paths = [safe_child(source_root, texture, "texture") for texture in model.textures]
            for texture_source_path in texture_source_paths:
                input_budget.preflight(texture_source_path, MAX_TEXTURE_BYTES, "texture")
            texture_destination = safe_child(output_root, texture_destination_relative, "texture destination")
        else:
            texture_destination = None
            texture_source_paths = []
        try:
            output_paths = [report_path, descriptor_path, glb_path]
            if texture_destination is not None:
                output_paths.append(texture_destination)
            source_paths = [input_path, *animation_paths, *texture_source_paths]
            reject_output_collisions(output_paths, source_paths)
            named_output_paths = [
                (report_path, "report destination"),
                (descriptor_path, "descriptor destination"),
                (glb_path, "GLB destination"),
            ]
            if texture_destination is not None:
                named_output_paths.append((texture_destination, "texture destination"))
            preflight_output_targets(named_output_paths, output_base, args.force)
        except ConversionError:
            # Keep replacement rejection visible on stderr; do not create a
            # report as a side effect of an unsafe output plan.
            report_path = None
            raise
        if model.textures:
            source_texture, texture_payload = read_texture(source_root, model.textures[0], input_budget)
            texture_manifest.append(
                {
                    "destination": texture_destination_relative,
                    "mode": args.texture_mode,
                    "source": model.textures[0],
                }
            )
        else:
            source_texture = None
            texture_payload = None
        if issues.has_errors():
            raise ConversionError("conversion rejected; see the structured report")
        glb = build_glb(model, issues)
        if issues.has_errors():
            raise ConversionError("conversion rejected; see the structured report")
        atomic_write_bytes(glb_path, glb, output_base, args.force, "GLB destination")
        atomic_write_json(
            descriptor_path,
            descriptor(namespace, model_path, texture_stem, units_per_block),
            output_base,
            args.force,
            "descriptor destination")
        if source_texture is not None and texture_destination is not None and args.texture_mode == "copy":
            if texture_payload is None:
                raise ConversionError("texture: validated payload is missing")
            atomic_write_bytes(texture_destination, texture_payload, output_base, args.force, "texture destination")
        status = "converted-with-lossy-omissions" if issues.has_lossy_omissions() else "converted"
        atomic_write_json(
            report_path,
            report_document(status, args, model, issues, texture_manifest, units_per_block),
            output_base,
            args.force,
            "report destination")
        return 0
    except (ConversionError, OSError) as error:
        issues.error("BLX8-CONVERSION-FAILED", "$", str(error))
        if report_path is not None:
            try:
                atomic_write_json(
                    report_path,
                    report_document("failed", args, model, issues, texture_manifest, units_per_block),
                    output_base,
                    args.force,
                    "report destination")
            except OSError as report_error:
                print(f"unable to write conversion report: {report_error}", file=sys.stderr)
            except ConversionError as report_error:
                print(f"unable to write conversion report: {report_error}", file=sys.stderr)
        print(f"conversion rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
