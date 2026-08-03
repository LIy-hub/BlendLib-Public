# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""BlendLib X5 authoring-only Blender toolchain.

This module intentionally contains no Minecraft, socket, HTTP, or runtime
asset-loader integration.  It turns Blender-side facts into a bounded,
versioned authoring sidecar and asset report, then emits only the existing
strict-v1 GLB/descriptor/PNG runtime surface.  The pure helpers are kept free
of :mod:`bpy` so the deterministic, safety, and protocol tests run with the
Python standard library alone.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import math
import os
import re
import shutil
import tempfile
from decimal import Decimal
from pathlib import Path
from types import MappingProxyType
from typing import Any, BinaryIO, Callable, Iterable, Mapping, NamedTuple, Sequence
from weakref import ReferenceType, ref


AUTHORING_SIDECAR_FORMAT = "blendlib-x5-authoring-sidecar-v1"
ASSET_REPORT_FORMAT = "blendlib-x5-asset-report-v1"
DEV_REFRESH_FORMAT = "blendlib-x5-dev-refresh-v1"
AUTHORING_SCHEMA_VERSION = "1.0.0"
IDLE_DEBOUNCE_MILLIS = 1_000
MAX_AUTHORING_METADATA_ENTRIES = 64
MAX_AUTHORING_METADATA_TOTAL_ENTRIES = 4_096
MAX_AUTHORING_METADATA_TEXT = 256
MAX_REPORT_BYTES = 512 * 1024
MAX_RUNTIME_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_PREVIEW_FLAGS = 6
MAX_BATCH_ITEMS = 256
MAX_MAPPING_ITEMS = 4_096
MAX_RUNTIME_RESOURCE_ROOTS = 16
MAX_SNAPSHOT_DEPTH = 32
MAX_SNAPSHOT_ITEMS = 131_072
MAX_SNAPSHOT_TEXT = 4_096
MAX_SIGNED_64 = (1 << 63) - 1
BATCH_MANIFEST_FORMAT = "blendlib-x5-batch-manifest-v1"
DEFAULT_RUNTIME_RESOURCE_ROOTS = ("src/main/resources", "build/resources/main")
_MAPPING_INPUT_FIELDS = ("objects", "collections", "actions", "materials", "markers")
_MAPPING_PROXY_TYPE = type(MappingProxyType({}))
_FROZEN_SNAPSHOT_PROVENANCE = object()

RESOURCE_TOKEN = re.compile(r"^[a-z0-9._/-]+$")
NAMESPACE_TOKEN = re.compile(r"^[a-z0-9._-]+$")
SESSION_TOKEN = re.compile(r"^[A-Za-z0-9._-]{16,128}$")
LOD_COLLECTION = re.compile(r"^lod[_ -]?(\d+)$", re.IGNORECASE)
COLLISION_COLLECTION = re.compile(r"^(collision|collider)(?:[_ -].*)?$", re.IGNORECASE)
EVENT_MARKER = re.compile(r"^(?:event[.:]|blendlib_event[.:])([a-z0-9._/-]+)$", re.IGNORECASE)


class X5ToolingError(RuntimeError):
    """Bounded X5 tooling failure with a stable diagnostic code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class _MetadataCollectionError(X5ToolingError):
    """Internal metadata failure carrying only a safe object location."""

    def __init__(self, code: str, message: str, location: str) -> None:
        super().__init__(code, message)
        self.location = location


class _FrozenSnapshot(Mapping[str, Any]):
    """Deeply immutable snapshot consumed exactly once under bounded X5 rules."""

    __slots__ = ("_values", "_diagnostics", "__weakref__")
    __hash__ = object.__hash__

    def __init__(
        self,
        snapshot: Mapping[str, Any],
        diagnostics: Sequence[ToolingDiagnostic],
        *,
        _provenance: object | None = None,
    ) -> None:
        if _provenance is not _FROZEN_SNAPSHOT_PROVENANCE:
            raise X5ToolingError(
                "BLENDLIB-X5-SNAPSHOT-001",
                "Frozen snapshots can only be created by the bounded preflight factory.",
            )
        if type(snapshot) is not _MAPPING_PROXY_TYPE or type(diagnostics) is not tuple:
            raise X5ToolingError(
                "BLENDLIB-X5-SNAPSHOT-001",
                "Frozen snapshot representation is not the exact immutable contract.",
            )
        object.__setattr__(self, "_values", snapshot)
        object.__setattr__(self, "_diagnostics", diagnostics)

    def __setattr__(self, name: str, value: Any) -> None:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen snapshot state cannot be modified.")

    def __getitem__(self, key: str) -> Any:
        return self._values[key]

    def __iter__(self):
        return iter(self._values)

    def __len__(self) -> int:
        return len(self._values)

    def __eq__(self, other: object) -> bool:
        return self is other

    @property
    def diagnostics(self) -> tuple[ToolingDiagnostic, ...]:
        return self._diagnostics

    def __copy__(self) -> "_FrozenSnapshot":
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen snapshot artifacts cannot be copied.")

    def __deepcopy__(self, memo: dict[int, Any]) -> "_FrozenSnapshot":
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen snapshot artifacts cannot be copied.")

    def __reduce_ex__(self, protocol: int) -> object:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen snapshot artifacts cannot be serialized.")


@dataclasses.dataclass(frozen=True)
class ToolingDiagnostic:
    """A deterministic authoring diagnostic, never a runtime error-code claim."""

    severity: str
    code: str
    location: str
    message: str
    remediation: str

    def __post_init__(self) -> None:
        if self.severity not in {"ERROR", "WARN", "INFO"}:
            raise ValueError("X5 diagnostic severity is invalid")
        if not self.code.startswith("BLENDLIB-X5-"):
            raise ValueError("X5 diagnostics must use the tooling-only BLENDLIB-X5 prefix")
        if len(self.location) > 512 or len(self.message) > 1_024 or len(self.remediation) > 1_024:
            raise ValueError("X5 diagnostic text exceeds its bounded contract")

    def to_json(self) -> dict[str, str]:
        return {
            "code": self.code,
            "location": self.location,
            "message": self.message,
            "remediation": self.remediation,
            "severity": self.severity,
        }


_DiagnosticRecord = tuple[str, str, str, str, str]


class _TrustedSnapshotState(NamedTuple):
    snapshot_ref: ReferenceType[Any]
    generation: object
    values: Mapping[str, Any]
    exposed_diagnostics: tuple[ToolingDiagnostic, ...]
    diagnostic_records: tuple[_DiagnosticRecord, ...]
    first_error_record: _DiagnosticRecord | None


_TRUSTED_SNAPSHOT_STATES: dict[int, _TrustedSnapshotState] = {}


@dataclasses.dataclass(frozen=True)
class PreflightResult:
    diagnostics: tuple[ToolingDiagnostic, ...]
    snapshot: Mapping[str, Any]

    @property
    def ok(self) -> bool:
        return all(item.severity != "ERROR" for item in self.diagnostics)

    def report(self) -> dict[str, Any]:
        return {
            "diagnostics": [item.to_json() for item in self.diagnostics],
            "format": "blendlib-x5-preflight-v1",
            "ok": self.ok,
        }


@dataclasses.dataclass(frozen=True)
class BatchExportItem:
    """One deterministic batch entry; output identity is namespace/model id."""

    namespace: str
    model_id: str
    profile: str
    collection_name: str | None

    @property
    def output_key(self) -> str:
        return f"{self.namespace}:assets/{self.namespace}/models3d/{self.model_id}.glb"


@dataclasses.dataclass(frozen=True)
class PreviewState:
    model: bool = False
    bones: bool = False
    sockets: bool = False
    normals: bool = False
    materials: bool = False
    animation_timeline: bool = False


@dataclasses.dataclass(frozen=True)
class RefreshMessage:
    session_token: str
    generation: int
    artifact_hashes: Mapping[str, str]
    model_key: str

    def __post_init__(self) -> None:
        _require_session_token(self.session_token)
        _strict_integer(
            self.generation,
            "refresh generation",
            "BLENDLIB-X5-REFRESH-003",
            minimum=0,
            maximum=MAX_SIGNED_64,
        )
        _require_resource_id(self.model_key, "model key")
        if not isinstance(self.artifact_hashes, Mapping) or not self.artifact_hashes:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-003", "Refresh must identify at least one artifact hash.")
        if len(self.artifact_hashes) > MAX_MAPPING_ITEMS:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-001", "Refresh artifact map exceeds its bounded contract.")
        for relative, digest in self.artifact_hashes.items():
            safe_relative_path(relative, "refresh artifact path")
            if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise X5ToolingError("BLENDLIB-X5-REFRESH-003", "Refresh artifact hashes must be lowercase SHA-256.")

    def to_payload(self) -> dict[str, Any]:
        return {
            "artifact_hashes": dict(sorted(self.artifact_hashes.items())),
            "format": DEV_REFRESH_FORMAT,
            "generation": self.generation,
            "model_key": self.model_key,
            "session_token": self.session_token,
        }

    @classmethod
    def from_payload(cls, value: object) -> "RefreshMessage":
        if not isinstance(value, dict) or value.get("format") != DEV_REFRESH_FORMAT:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-001", "Malformed or unsupported dev-refresh message.")
        expected_fields = {"artifact_hashes", "format", "generation", "model_key", "session_token"}
        if set(value) != expected_fields:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-001", "Dev-refresh message has unknown or missing fields.")
        artifact_hashes = value.get("artifact_hashes")
        if not isinstance(artifact_hashes, dict) or not all(
            isinstance(path, str) and isinstance(digest, str)
            for path, digest in artifact_hashes.items()
        ):
            raise X5ToolingError("BLENDLIB-X5-REFRESH-001", "Refresh artifact hashes must be a string map.")
        try:
            return cls(
                session_token=_text(value.get("session_token"), "session token"),
                generation=_integer(value.get("generation"), "generation"),
                artifact_hashes=artifact_hashes,
                model_key=_text(value.get("model_key"), "model key"),
            )
        except X5ToolingError as error:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-001", "Dev-refresh message failed strict field validation.") from error


@dataclasses.dataclass
class _PreparedExport:
    options: Any
    result: dict[str, Any]
    outputs: dict[str, bytes]
    stage_root: Path


def canonical_json_bytes(value: Any) -> bytes:
    """Return cross-language canonical JSON with plain finite decimal numbers."""

    return _canonical_json_text(_canonical_value(value)).encode("utf-8")


def _bounded_canonical_json_bytes(
    value: Any,
    maximum_bytes: int,
    code: str,
    description: str,
) -> bytes:
    """Serialize canonical UTF-8 incrementally and stop before exceeding the cap."""

    payload = bytearray()
    try:
        for fragment in _canonical_json_fragments(value):
            encoded = fragment.encode("utf-8")
            if len(payload) + len(encoded) > maximum_bytes:
                raise X5ToolingError(code, f"{description} exceeds the 512 KiB authoring JSON limit.")
            payload.extend(encoded)
    except UnicodeError as error:
        raise X5ToolingError(code, f"{description} is not valid canonical UTF-8.") from error
    return bytes(payload)


def pretty_json_bytes(value: Any) -> bytes:
    return (json.dumps(_canonical_value(value), ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode(
        "utf-8"
    )


def asset_report_bytes(value: Mapping[str, Any]) -> bytes:
    """Return the published canonical report bytes under the inclusive 512 KiB cap."""

    return _bounded_canonical_json_bytes(
        value, MAX_REPORT_BYTES, "BLENDLIB-X5-REPORT-002", "Authoring report"
    )


def refresh_message_bytes(message: RefreshMessage) -> bytes:
    """Return canonical refresh bytes under the writer/watcher 512 KiB cap."""

    return _bounded_canonical_json_bytes(
        message.to_payload(), MAX_REPORT_BYTES, "BLENDLIB-X5-REFRESH-001", "Dev-refresh message"
    )


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _bounded_regular_file(
    path: Path,
    maximum_bytes: int,
    code: str,
    description: str,
    allowed_roots: Sequence[Path] | None = None,
) -> tuple[Path, int]:
    """Resolve one regular file beneath an explicit root before trusting its size."""

    try:
        requested = Path(path)
        roots = tuple(Path(root).resolve(strict=True) for root in (allowed_roots or (requested.parent,)))
        resolved = requested.resolve(strict=True)
        if not resolved.is_file() or not any(resolved == root or resolved.is_relative_to(root) for root in roots):
            raise OSError("not a regular file beneath an authorized root")
        declared_size = resolved.stat().st_size
        if declared_size < 0 or declared_size > maximum_bytes:
            raise X5ToolingError(code, f"{description} exceeds its bounded size.")
        return resolved, declared_size
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(code, f"{description} cannot be read as a regular file beneath its authorized root.") from error


def sha256_file(path: Path, *, allowed_roots: Sequence[Path] | None = None) -> str:
    """Hash one stable regular runtime artifact with a fixed buffer and hard byte cap."""

    try:
        resolved, declared_size = _bounded_regular_file(
            path,
            MAX_RUNTIME_ARTIFACT_BYTES,
            "BLENDLIB-X5-REFRESH-005",
            "Refresh artifact",
            allowed_roots,
        )
        digest = hashlib.sha256()
        total = 0
        with resolved.open("rb") as stream:
            for block in iter(lambda: stream.read(8 * 1024), b""):
                total += len(block)
                if total > declared_size or total > MAX_RUNTIME_ARTIFACT_BYTES:
                    raise OSError("runtime artifact grew while being hashed")
                digest.update(block)
        if total != declared_size:
            raise OSError("runtime artifact shrank while being hashed")
        return digest.hexdigest()
    except OSError as error:
        raise X5ToolingError("BLENDLIB-X5-REFRESH-005", "Refresh artifact cannot be hashed within its bounded size.") from error


def _read_bounded_stream(
    stream: BinaryIO,
    declared_size: int,
    maximum_bytes: int,
    code: str,
    description: str,
) -> bytes:
    """Read one stat-sized payload and reject shrink/growth without an unbounded allocation."""

    if declared_size < 0 or declared_size > maximum_bytes:
        raise X5ToolingError(code, f"{description} exceeds its bounded size.")
    payload = bytearray()
    total = 0
    while total < declared_size:
        requested = min(8 * 1024, declared_size - total)
        block = stream.read(requested)
        if not block or len(block) > requested:
            raise X5ToolingError(code, f"{description} changed while being read or exceeds its bounded size.")
        payload.extend(block)
        total += len(block)
        if total > maximum_bytes:
            raise X5ToolingError(code, f"{description} changed while being read or exceeds its bounded size.")
    if stream.read(1):
        raise X5ToolingError(code, f"{description} changed while being read or exceeds its bounded size.")
    return bytes(payload)


def _read_bounded_file(
    path: Path,
    maximum_bytes: int,
    code: str,
    description: str,
    *,
    allowed_roots: Sequence[Path] | None = None,
) -> bytes:
    """Stat and read a regular file with a hard allocation ceiling and growth detection."""

    try:
        resolved, declared_size = _bounded_regular_file(path, maximum_bytes, code, description, allowed_roots)
        with resolved.open("rb") as stream:
            return _read_bounded_stream(stream, declared_size, maximum_bytes, code, description)
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(code, f"{description} cannot be read as a regular file.") from error


def safe_relative_path(raw: str, label: str = "path") -> str:
    """Accept a portable project-relative path and reject URI/host-path escape forms."""

    if not isinstance(raw, str) or not raw or raw != raw.strip():
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must be a non-empty, untrimmed relative path.")
    if "\\" in raw or ":" in raw or "\x00" in raw or raw.startswith(("/", "~")) or re.match(r"^[A-Za-z]:", raw):
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must not use an absolute or host-specific path.")
    lowered = raw.lower()
    if "://" in raw or lowered.startswith(("file:", "http:", "https:", "ftp:")):
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must not be a file or network URI.")
    parts = raw.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must not contain empty, dot, or parent segments.")
    return "/".join(parts)


def resolve_under(root: Path, relative: str, label: str = "path") -> Path:
    safe = safe_relative_path(relative, label)
    root = root.resolve()
    target = (root / Path(*safe.split("/"))).resolve()
    try:
        target.relative_to(root)
    except ValueError as error:  # Defensive against symlink escape.
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} escapes the configured root.") from error
    return target


def require_non_runtime_output(
    project_root: Path,
    runtime_resource_root: str,
    output_relative: str,
    label: str,
    runtime_resource_roots: Sequence[str] = (),
) -> str:
    """Reject authoring output inside any actual default/configured runtime tree."""

    root = project_root.resolve()
    resource_roots = tuple(
        resolve_under(root, relative, "runtime resource root")
        for relative in _runtime_resource_roots(runtime_resource_root, runtime_resource_roots)
    )
    safe = safe_relative_path(output_relative, label)
    target = resolve_under(root, safe, label)
    for resource_root in resource_roots:
        try:
            target.relative_to(resource_root)
        except ValueError:
            continue
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-003",
            f"{label} must remain outside every configured runtime resource tree.",
        )
    return safe


def _runtime_resource_roots(
    configured_root: str,
    explicit_roots: Sequence[str] = (),
) -> tuple[str, ...]:
    """Return Java-parity runtime roots in deterministic, bounded order."""

    roots: list[str] = []
    for raw in (configured_root, *DEFAULT_RUNTIME_RESOURCE_ROOTS, *explicit_roots):
        safe = safe_relative_path(raw, "runtime resource root")
        if safe not in roots:
            roots.append(safe)
    if len(roots) > MAX_RUNTIME_RESOURCE_ROOTS:
        raise X5ToolingError("BLENDLIB-X5-PATH-003", "Runtime resource root set exceeds its bounded contract.")
    return tuple(roots)


def plan_batch(items: Sequence[BatchExportItem]) -> tuple[BatchExportItem, ...]:
    """Sort batch work independently of UI order and reject duplicate logical/output identities."""

    diagnostics: list[ToolingDiagnostic] = []
    ordered = tuple(sorted(items, key=lambda item: (item.namespace, item.model_id, item.collection_name or "")))
    seen_models: set[tuple[str, str]] = set()
    seen_outputs: set[str] = set()
    for item in ordered:
        try:
            _require_namespace(item.namespace)
            _require_resource_token(item.model_id, "model id")
        except X5ToolingError as error:
            diagnostics.append(_diagnostic("ERROR", error.code, f"batch:{item.model_id}", error.message, "Use a canonical resource id."))
            continue
        identity = (item.namespace, item.model_id)
        if identity in seen_models:
            diagnostics.append(_diagnostic(
                "ERROR", "BLENDLIB-X5-BATCH-001", f"batch:{item.namespace}:{item.model_id}",
                "Duplicate batch model id.", "Assign each batch item a unique namespace/model id."
            ))
        seen_models.add(identity)
        if item.output_key in seen_outputs:
            diagnostics.append(_diagnostic(
                "ERROR", "BLENDLIB-X5-BATCH-002", f"batch:{item.namespace}:{item.model_id}",
                "Two batch items resolve to the same output artifact.", "Change one model id or output root."
            ))
        seen_outputs.add(item.output_key)
        if item.profile not in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
            diagnostics.append(_diagnostic(
                "ERROR", "BLENDLIB-X5-BATCH-003", f"batch:{item.namespace}:{item.model_id}/profile",
                "Batch profile is not a strict v1 profile.", "Use blendlib:rigid_v1 or blendlib:skinned_v1."
            ))
    if any(item.severity == "ERROR" for item in diagnostics):
        raise X5ToolingError("BLENDLIB-X5-BATCH-001", _render_diagnostics(diagnostics))
    return ordered


def preflight_snapshot(snapshot: Mapping[str, Any]) -> PreflightResult:
    """Validate a normalized Blender snapshot before an X5 export writes anything.

    The snapshot format is intentionally small and mock-friendly.  Blender code converts
    scene data into it, while the tests supply dictionaries directly.
    """

    diagnostics: list[ToolingDiagnostic] = []
    frozen_snapshot = _freeze_mapping_snapshot(snapshot, diagnostics)
    _check_output_identity(frozen_snapshot, diagnostics)
    objects = frozen_snapshot["objects"]
    if not objects:
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-SCENE-001", "scene", "No export objects were collected.", "Select one non-empty export collection."))
    root_count = frozen_snapshot.get("root_count")
    if not _is_strict_integer(root_count, minimum=0, maximum=MAX_MAPPING_ITEMS) or root_count != 1:
        diagnostics.append(_diagnostic(
            "ERROR", "BLENDLIB-X5-SCENE-002", "scene/root", "Exactly one export root is required.",
            "Parent export objects beneath one explicit Empty or Armature root."
        ))
    names: set[str] = set()
    for obj in sorted((item for item in objects if isinstance(item, Mapping)), key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(obj.get("name"), "<unnamed>")
        location = f"object:{name}"
        if not name or name in names:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-SCENE-003", location, "Object names must be unique and non-empty.", "Rename the object."))
        names.add(name)
        _check_transform(obj, location, diagnostics)
        object_type = _string_or(obj.get("type"), "")
        if object_type == "MESH":
            _check_mesh(obj, location, diagnostics)
        elif object_type == "ARMATURE":
            _check_armature(obj, location, diagnostics)
        elif object_type not in {"EMPTY", ""}:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-SCENE-004", location, f"Unsupported authoring object type '{object_type}'.", "Bake or remove it before export."))
    try:
        _collect_authoring_metadata(objects)
    except _MetadataCollectionError as error:
        diagnostics.append(_diagnostic(
            "ERROR",
            error.code,
            error.location,
            error.message,
            "Keep BlendLib metadata identities unique, scalar, bounded, and non-secret.",
        ))
    materials = frozen_snapshot["materials"]
    actions = frozen_snapshot["actions"]
    collections = frozen_snapshot["collections"]
    markers = frozen_snapshot["markers"]
    _check_materials(materials, diagnostics)
    _check_actions(actions, diagnostics)
    _check_collections(collections, diagnostics)
    _check_markers(markers, diagnostics)
    if _mapping_output_count(objects, collections, actions, materials, markers) > MAX_MAPPING_ITEMS:
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-MAPPING-002",
            "scene/mapping",
            "Total emitted authoring mapping exceeds 4096 items.",
            "Reduce sockets, collections, Actions, materials, or visual-event markers.",
        ))
    _check_units_and_coordinates(frozen_snapshot, diagnostics)
    ordered = tuple(sorted(diagnostics, key=_diagnostic_sort_key))
    return PreflightResult(
        ordered,
        _new_frozen_snapshot(frozen_snapshot._values, ordered, trusted=True),
    )


def build_authoring_sidecar(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    """Map Blender-only semantics into a bounded, explicitly non-runtime sidecar."""

    frozen_snapshot = _sidecar_snapshot(snapshot)
    namespace = _require_namespace(_text(frozen_snapshot.get("namespace"), "namespace"))
    model_id = _require_resource_token(_text(frozen_snapshot.get("model_id"), "model id"), "model id")
    profile = _text(frozen_snapshot.get("profile"), "profile")
    if profile not in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
        raise X5ToolingError("BLENDLIB-X5-SIDECAR-001", "Sidecar profile must name an existing strict v1 profile.")

    objects = [item for item in frozen_snapshot["objects"] if isinstance(item, Mapping)]
    collections = [item for item in frozen_snapshot["collections"] if isinstance(item, Mapping)]
    actions = [item for item in frozen_snapshot["actions"] if isinstance(item, Mapping)]
    materials = [item for item in frozen_snapshot["materials"] if isinstance(item, Mapping)]
    markers = [item for item in frozen_snapshot["markers"] if isinstance(item, Mapping)]
    if _mapping_output_count(objects, collections, actions, materials, markers) > MAX_MAPPING_ITEMS:
        raise X5ToolingError("BLENDLIB-X5-MAPPING-002", "Total emitted authoring mapping exceeds 4096 items.")

    sockets = []
    metadata = _collect_authoring_metadata(objects)
    for obj in sorted(objects, key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(obj.get("name"), "")
        if obj.get("type") == "EMPTY":
            key = _authoring_key(_string_or(obj.get("socket_key"), name), "socket")
            sockets.append({"key": f"{namespace}:{key}", "source_object": name})

    groups: list[dict[str, Any]] = []
    lod_levels: list[dict[str, Any]] = []
    collisions: list[dict[str, Any]] = []
    for collection in sorted(collections, key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(collection.get("name"), "")
        lod_match = LOD_COLLECTION.fullmatch(name)
        if lod_match:
            level = _lod_level(lod_match)
            if level is None:
                raise X5ToolingError("BLENDLIB-X5-LOD-002", "LOD level exceeds the signed 64-bit integer contract.")
            lod_levels.append({
                "level": level,
                "source_collection": name,
                "triangle_count": _bounded_nonnegative_integer(collection.get("triangle_count"), 0),
            })
        elif COLLISION_COLLECTION.fullmatch(name):
            collisions.append({
                "authoring_only": True,
                "objects": sorted(_string_or(value, "") for value in _list(collection.get("objects")) if _string_or(value, "")),
                "read_only": True,
                "runtime_authority": "never",
                "source_collection": name,
            })
        else:
            groups.append({"source_collection": name, "variant_key": _authoring_key(name, "group")})

    animation_clips = []
    for action in sorted(actions, key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(action.get("name"), "")
        animation_clips.append({
            "clip": name,
            "frame_end": _bounded_number(action.get("frame_end"), 0.0),
            "frame_start": _bounded_number(action.get("frame_start"), 0.0),
            "source_action": name,
        })

    material_definitions = []
    for material in sorted(materials, key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(material.get("name"), "")
        material_definitions.append({
            "authoring_material": name,
            "descriptor_mode": _string_or(material.get("mode"), "opaque"),
            "source_material": name,
        })

    visual_events = []
    for marker in sorted(markers, key=lambda item: (_bounded_number(item.get("frame"), 0.0), _string_or(item.get("name"), ""))):
        name = _string_or(marker.get("name"), "")
        event_match = EVENT_MARKER.fullmatch(name)
        if event_match:
            visual_events.append({
                "event": f"{namespace}:{event_match.group(1).lower()}",
                "frame": _bounded_number(marker.get("frame"), 0.0),
                "presentation_only": True,
                "source_marker": name,
            })

    return {
        "authoring_metadata": dict(sorted(metadata.items())),
        "format": AUTHORING_SIDECAR_FORMAT,
        "mapping": {
            "action_animation_clips": animation_clips,
            "collection_groups_variants": groups,
            "collision_references": collisions,
            "empty_sockets": sockets,
            "lod_levels": sorted(lod_levels, key=lambda item: (item["level"], item["source_collection"])),
            "material_definitions": material_definitions,
            "timeline_visual_events": visual_events,
        },
        "model": {"model_id": model_id, "namespace": namespace, "profile": profile},
        "runtime_boundary": {
            "collision_references_are_authoring_only": True,
            "descriptor_extensions_are_not_used": True,
            "runtime_reads_blend_fbx_obj": False,
            "visual_events_are_presentation_only": True,
        },
        "schema_version": AUTHORING_SCHEMA_VERSION,
    }


def build_asset_report(
    *,
    snapshot: Mapping[str, Any],
    sidecar: Mapping[str, Any],
    validation: Mapping[str, Any],
    artifacts: Mapping[str, bytes],
    diagnostics: Sequence[ToolingDiagnostic],
) -> dict[str, Any]:
    """Create a machine-readable X5 report without source-machine paths or secrets."""

    if len(diagnostics) > MAX_MAPPING_ITEMS or len(artifacts) > MAX_MAPPING_ITEMS:
        raise X5ToolingError("BLENDLIB-X5-REPORT-002", "Authoring report exceeds its bounded collection contract.")

    mapping = sidecar.get("mapping", {}) if isinstance(sidecar, Mapping) else {}
    objects = [item for item in _list(snapshot.get("objects")) if isinstance(item, Mapping)]
    bones = sum(len(_list(item.get("bones"))) for item in objects if item.get("type") == "ARMATURE")
    weight_vertices = sum(
        1
        for item in objects
        if item.get("type") == "MESH"
        for weights in _list(item.get("weights"))
        if _list(weights)
    )
    index_count = _strict_integer(
        validation.get("index_count"),
        "validation index count",
        "BLENDLIB-X5-REPORT-002",
        minimum=0,
        maximum=MAX_SIGNED_64,
    )
    vertex_count = _strict_integer(
        validation.get("vertex_count"),
        "validation vertex count",
        "BLENDLIB-X5-REPORT-002",
        minimum=0,
        maximum=MAX_SIGNED_64,
    )
    triangles = index_count // 3
    ordered_diagnostics = sorted(diagnostics, key=_diagnostic_sort_key)
    performance_warnings = [item.to_json() for item in ordered_diagnostics if item.severity == "WARN"]
    artifact_hashes = {safe_relative_path(path, "artifact path"): sha256_bytes(payload) for path, payload in sorted(artifacts.items())}
    return {
        "artifacts": artifact_hashes,
        "counts": {
            "animation_clips": len(_list(mapping.get("action_animation_clips"))),
            "bones": bones,
            "collision_references": len(_list(mapping.get("collision_references"))),
            "events": len(_list(mapping.get("timeline_visual_events"))),
            "lod_levels": len(_list(mapping.get("lod_levels"))),
            "material_slots": len(_list(validation.get("material_names"))),
            "triangles": triangles,
            "vertex_weight_records": weight_vertices,
            "vertices": vertex_count,
        },
        "diagnostics": [item.to_json() for item in ordered_diagnostics],
        "format": ASSET_REPORT_FORMAT,
        "model": sidecar.get("model", {}),
        "performance_warnings": performance_warnings,
        "schema_version": AUTHORING_SCHEMA_VERSION,
        "sidecar_sha256": sha256_bytes(canonical_json_bytes(sidecar)),
    }


def atomic_write_bundle(
    root: Path,
    outputs: Mapping[str, bytes],
    *,
    replace_func: Callable[[str | os.PathLike[str], str | os.PathLike[str]], None] = os.replace,
) -> None:
    """Commit a staged multi-file bundle with rollback on a replacement failure.

    There is no claim that Windows offers a cross-file filesystem transaction.
    This routine instead stages every byte first, moves prior files to a private
    backup directory, and restores all affected targets if any replacement
    fails.  A rollback failure preserves the backup for explicit recovery.
    """

    root = root.resolve()
    if not outputs:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "An export bundle must contain at least one file.")
    normalized: dict[str, bytes] = {}
    for relative, payload in outputs.items():
        safe = safe_relative_path(relative, "bundle output path")
        if safe in normalized:
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"Duplicate export target: {safe}")
        if not isinstance(payload, bytes):
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"Bundle payload is not bytes: {safe}")
        normalized[safe] = payload

    root.mkdir(parents=True, exist_ok=True)
    stage_root: Path | None = None
    backup_root: Path | None = None
    moved_backups: list[tuple[str, Path, Path]] = []
    installed: list[tuple[Path, bool]] = []
    preserve_backup = False
    try:
        stage_root = Path(tempfile.mkdtemp(prefix=".blendlib-x5-stage-", dir=root))
        backup_root = Path(tempfile.mkdtemp(prefix=".blendlib-x5-backup-", dir=root))
        targets = {relative: resolve_under(root, relative, "bundle output path") for relative in sorted(normalized)}
        for relative, payload in normalized.items():
            staged = resolve_under(stage_root, relative, "staged output path")
            staged.parent.mkdir(parents=True, exist_ok=True)
            with staged.open("wb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
        for relative in sorted(normalized):
            target = targets[relative]
            backup = resolve_under(backup_root, relative, "backup output path")
            existed = target.exists()
            if existed:
                backup.parent.mkdir(parents=True, exist_ok=True)
                replace_func(target, backup)
                moved_backups.append((relative, target, backup))
            target.parent.mkdir(parents=True, exist_ok=True)
            replace_func(resolve_under(stage_root, relative, "staged output path"), target)
            installed.append((target, existed))
    except Exception as error:  # Roll back only the known, scoped output targets.
        cleanup_errors: list[str] = []
        restore_errors: list[str] = []
        for target, existed in reversed(installed):
            if existed:
                continue
            try:
                if target.exists():
                    target.unlink()
            except Exception:  # pragma: no cover - host filesystem failure.
                cleanup_errors.append(target.relative_to(root).as_posix())
        for relative, target, backup in reversed(moved_backups):
            if not backup.exists():
                continue
            try:
                replace_func(backup, target)
            except Exception:  # pragma: no cover - exercised through fault injection.
                restore_errors.append(relative)
        rollback_errors = cleanup_errors + restore_errors
        if rollback_errors:
            preserve_backup = True
            affected = ", ".join(sorted(set(rollback_errors))[:3])
            if restore_errors:
                detail = (
                    "Rollback restore incomplete for " + affected
                    + "; prior bytes remain recoverable in project-relative backup directory " + backup_root.name + "."
                )
            else:
                detail = (
                    "Rollback restored prior files but could not remove new targets " + affected
                    + "; project-relative backup directory " + backup_root.name + " was preserved for recovery audit."
                )
        else:
            detail = "Export replacement failed; all affected targets were restored from staging backups."
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-002", detail) from error
    finally:
        if stage_root is not None:
            shutil.rmtree(stage_root, ignore_errors=True)
        if backup_root is not None and not preserve_backup:
            shutil.rmtree(backup_root, ignore_errors=True)


class RefreshReceiver:
    """Small client-binding seam that verifies local artifacts before accepting refresh.

    The game-side integration is deliberately *not* implemented here.  A future
    client binding creates this receiver with the already-authorized local project
    root and calls :meth:`receive` from its own lifecycle.  No network endpoint,
    background thread, or polling loop belongs to this add-on/tooling seam.
    """

    def __init__(self, session_token: str, artifact_root: Path) -> None:
        _require_session_token(session_token)
        self._session_token = session_token
        self._artifact_root = artifact_root.resolve()
        self._last_generation = -1

    @property
    def last_generation(self) -> int:
        return self._last_generation

    def receive(self, message: RefreshMessage) -> bool:
        if message.session_token != self._session_token:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-002", "Refresh message belongs to another development session.")
        if message.generation <= self._last_generation:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-004", "Refresh message is stale or duplicated.")
        for relative, expected_hash in sorted(message.artifact_hashes.items()):
            artifact = resolve_under(self._artifact_root, relative, "refresh artifact path")
            if not artifact.is_file():
                raise X5ToolingError("BLENDLIB-X5-REFRESH-005", "Refresh artifact is missing under the authorized project root.")
            if sha256_file(artifact, allowed_roots=(self._artifact_root,)) != expected_hash:
                raise X5ToolingError("BLENDLIB-X5-REFRESH-005", "Refresh artifact hash does not match the staged message.")
        self._last_generation = message.generation
        return True


class DebouncedRefreshAdapter:
    """Caller-driven one-second idle debounce; it owns no polling thread or network listener."""

    def __init__(self, receiver: RefreshReceiver, idle_millis: int = IDLE_DEBOUNCE_MILLIS) -> None:
        _strict_integer(
            idle_millis,
            "refresh idle milliseconds",
            "BLENDLIB-X5-REFRESH-003",
            minimum=0,
            maximum=MAX_SIGNED_64,
        )
        if idle_millis != IDLE_DEBOUNCE_MILLIS:
            raise ValueError("X5 refresh debounce is fixed at one second")
        self._receiver = receiver
        self._idle_millis = idle_millis
        self._pending: RefreshMessage | None = None
        self._first_seen_millis: int | None = None

    def offer(self, message: RefreshMessage, now_millis: int) -> bool:
        _strict_integer(
            now_millis, "refresh clock", "BLENDLIB-X5-REFRESH-003", minimum=0, maximum=MAX_SIGNED_64
        )
        if self._pending != message:
            self._pending = message
            self._first_seen_millis = now_millis
            return False
        return self.tick(now_millis)

    def tick(self, now_millis: int) -> bool:
        _strict_integer(
            now_millis, "refresh clock", "BLENDLIB-X5-REFRESH-003", minimum=0, maximum=MAX_SIGNED_64
        )
        if self._pending is None or self._first_seen_millis is None:
            return False
        if now_millis - self._first_seen_millis < self._idle_millis:
            return False
        pending = self._pending
        self._pending = None
        self._first_seen_millis = None
        return self._receiver.receive(pending)


class FilesystemRefreshWatcher:
    """A bounded, caller-polled file adapter; platform integration owns scheduling."""

    def __init__(
        self,
        artifact_root: Path,
        message_relative_path: str,
        adapter: DebouncedRefreshAdapter,
        *,
        runtime_resource_root: str = "src/main/resources",
        runtime_resource_roots: Sequence[str] = (),
    ) -> None:
        self._artifact_root = artifact_root.resolve()
        self._message_relative_path = require_non_runtime_output(
            self._artifact_root,
            runtime_resource_root,
            message_relative_path,
            "dev refresh path",
            runtime_resource_roots,
        )
        self._runtime_resource_root = runtime_resource_root
        self._runtime_resource_roots = tuple(runtime_resource_roots)
        self._adapter = adapter
        self._last_digest: str | None = None

    def poll_once(self, now_millis: int) -> bool:
        safe_message = require_non_runtime_output(
            self._artifact_root,
            self._runtime_resource_root,
            self._message_relative_path,
            "dev refresh path",
            self._runtime_resource_roots,
        )
        message_file = resolve_under(self._artifact_root, safe_message, "dev refresh path")
        if not message_file.is_file():
            return self._adapter.tick(now_millis)
        raw = _read_bounded_file(
            message_file,
            MAX_REPORT_BYTES,
            "BLENDLIB-X5-REFRESH-001",
            "Dev-refresh message",
            allowed_roots=(self._artifact_root,),
        )
        digest = sha256_bytes(raw)
        if digest == self._last_digest:
            return self._adapter.tick(now_millis)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise X5ToolingError("BLENDLIB-X5-REFRESH-001", "Dev-refresh message is not UTF-8 JSON.") from error
        message = RefreshMessage.from_payload(payload)
        self._last_digest = digest
        return self._adapter.offer(message, now_millis)


def write_refresh_message(
    root: Path,
    relative_path: str,
    message: RefreshMessage,
    *,
    runtime_resource_root: str,
    runtime_resource_roots: Sequence[str] = (),
) -> None:
    payload = refresh_message_bytes(message)
    safe = require_non_runtime_output(
        root, runtime_resource_root, relative_path, "dev refresh path", runtime_resource_roots
    )
    atomic_write_bundle(root, {safe: payload})


def load_batch_manifest(project_root: Path, relative_path: str) -> tuple[BatchExportItem, ...]:
    """Load the bounded, project-relative batch manifest used by CLI and UI export.

    A manifest is deliberately authoring-only input.  It is not copied into any
    runtime asset and it cannot name host paths or vary the one strict-v1 output
    contract.
    """

    manifest_path = resolve_under(project_root, relative_path, "batch manifest")
    raw = _read_bounded_file(
        manifest_path,
        MAX_REPORT_BYTES,
        "BLENDLIB-X5-BATCH-005",
        "Batch manifest",
        allowed_roots=(project_root,),
    )
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise X5ToolingError("BLENDLIB-X5-BATCH-005", "Batch manifest must be UTF-8 JSON.") from error
    if not isinstance(value, dict) or set(value) != {"format", "items"} or value.get("format") != BATCH_MANIFEST_FORMAT:
        raise X5ToolingError("BLENDLIB-X5-BATCH-005", "Batch manifest has an unsupported schema.")
    raw_items = value.get("items")
    if not isinstance(raw_items, list) or not raw_items or len(raw_items) > MAX_BATCH_ITEMS:
        raise X5ToolingError("BLENDLIB-X5-BATCH-005", "Batch manifest must contain 1-256 items.")
    items: list[BatchExportItem] = []
    for index, item in enumerate(raw_items):
        if not isinstance(item, dict) or set(item) != {"collection_name", "model_id", "namespace", "profile"}:
            raise X5ToolingError("BLENDLIB-X5-BATCH-005", f"Batch item {index} has unknown or missing fields.")
        collection_name = item.get("collection_name")
        if collection_name is not None and (not isinstance(collection_name, str) or not collection_name.strip()):
            raise X5ToolingError("BLENDLIB-X5-BATCH-005", f"Batch item {index} collection name is invalid.")
        items.append(BatchExportItem(
            namespace=_text(item.get("namespace"), f"batch item {index} namespace"),
            model_id=_text(item.get("model_id"), f"batch item {index} model id"),
            profile=_text(item.get("profile"), f"batch item {index} profile"),
            collection_name=collection_name,
        ))
    return plan_batch(items)


def build_preview_command(state: PreviewState) -> dict[str, Any]:
    """Return the bounded state applied by the Blender viewport preview controller."""

    flags = {
        "animation_timeline": state.animation_timeline,
        "bones": state.bones,
        "materials": state.materials,
        "model": state.model,
        "normals": state.normals,
        "sockets": state.sockets,
    }
    if len(flags) != MAX_PREVIEW_FLAGS:  # Guard future accidental unbounded controls.
        raise AssertionError("Preview control contract changed without an X5 protocol update")
    return {"format": "blendlib-x5-preview-command-v1", "flags": flags}


def apply_blender_preview(blender: Any, context: Any, options: Any, state: PreviewState) -> dict[str, Any]:
    """Apply reversible authoring-only viewport/debug state to the export collection."""

    _restore_blender_preview()
    command = build_preview_command(state)
    if not any(command["flags"].values()):
        return {
            "active": False,
            "format": "blendlib-x5-preview-state-v1",
            "flags": command["flags"],
            "selected_objects": 0,
            "view3d_spaces": 0,
        }

    exporter = _legacy_exporter()
    collection = exporter._select_collection(options.collection_name)
    objects, _ = exporter._collect_export_objects(collection)
    actions = exporter._discover_action_objects(objects)
    scene = context.scene
    spaces = _view3d_spaces(blender)
    baseline = _capture_blender_preview_baseline(context, objects, spaces)
    globals()["_X5_PREVIEW_BASELINE"] = baseline

    selected = 0
    if state.model:
        for obj in objects:
            obj.hide_viewport = False
            obj.hide_set(False)
            obj.select_set(True)
            selected += 1
        active = next((obj for obj in objects if obj.type == "MESH"), objects[0])
        context.view_layer.objects.active = active
        _frame_selected_in_viewports(blender, spaces)

    bones = 0
    if state.bones:
        for obj in objects:
            if obj.type == "ARMATURE":
                obj.show_in_front = True
                obj.data.show_names = True
                obj.data.display_type = "OCTAHEDRAL"
                bones += len(obj.data.bones)

    sockets = 0
    if state.sockets:
        for obj in objects:
            if obj.type == "EMPTY":
                obj.show_in_front = True
                obj.show_name = True
                obj.empty_display_type = "ARROWS"
                sockets += 1

    normal_meshes = 0
    if state.normals:
        for obj in objects:
            if obj.type == "MESH":
                obj.show_wire = True
                obj.show_all_edges = True
                normal_meshes += 1
        for space in spaces:
            overlay = space.overlay
            if hasattr(overlay, "show_face_normals"):
                overlay.show_face_normals = True

    material_names: list[str] = []
    if state.materials:
        for space in spaces:
            space.shading.type = "MATERIAL"
        material_names = sorted({
            slot.material.name
            for obj in objects
            if obj.type == "MESH"
            for slot in obj.material_slots
            if slot.material is not None
        })
        material_mesh = next((obj for obj in objects if obj.type == "MESH" and obj.material_slots), None)
        if material_mesh is not None:
            material_mesh.active_material_index = 0
            context.view_layer.objects.active = material_mesh

    timeline_range: list[int] = []
    if state.animation_timeline and actions:
        frame_start = math.floor(min(float(action.frame_range[0]) for action in actions))
        frame_end = math.ceil(max(float(action.frame_range[1]) for action in actions))
        scene.frame_start = frame_start
        scene.frame_end = max(frame_start, frame_end)
        scene.frame_set(frame_start)
        timeline_range = [frame_start, max(frame_start, frame_end)]

    return {
        "active": True,
        "bones": bones,
        "format": "blendlib-x5-preview-state-v1",
        "flags": command["flags"],
        "material_names": material_names,
        "normal_meshes": normal_meshes,
        "selected_objects": selected,
        "sockets": sockets,
        "timeline_range": timeline_range,
        "view3d_spaces": len(spaces),
    }


def _view3d_spaces(blender: Any) -> list[Any]:
    spaces: list[Any] = []
    for window in getattr(blender.context.window_manager, "windows", ()):
        screen = getattr(window, "screen", None)
        for area in getattr(screen, "areas", ()) if screen is not None else ():
            if area.type == "VIEW_3D":
                spaces.append(area.spaces.active)
    return spaces


def _capture_blender_preview_baseline(context: Any, objects: Sequence[Any], spaces: Sequence[Any]) -> dict[str, Any]:
    object_states = []
    for obj in objects:
        state: dict[str, Any] = {
            "active_material_index": getattr(obj, "active_material_index", 0),
            "empty_display_type": getattr(obj, "empty_display_type", None),
            "hide": obj.hide_get(),
            "hide_viewport": obj.hide_viewport,
            "object": obj,
            "selected": obj.select_get(),
            "show_all_edges": getattr(obj, "show_all_edges", False),
            "show_in_front": obj.show_in_front,
            "show_name": obj.show_name,
            "show_wire": getattr(obj, "show_wire", False),
        }
        if obj.type == "ARMATURE":
            state["armature_display_type"] = obj.data.display_type
            state["armature_show_names"] = obj.data.show_names
        object_states.append(state)
    space_states = []
    for space in spaces:
        space_states.append({
            "face_normals": getattr(space.overlay, "show_face_normals", None),
            "shading": space.shading.type,
            "space": space,
        })
    scene = context.scene
    return {
        "active_object": context.view_layer.objects.active,
        "frame_current": scene.frame_current,
        "frame_end": scene.frame_end,
        "frame_start": scene.frame_start,
        "objects": object_states,
        "scene": scene,
        "spaces": space_states,
        "view_layer": context.view_layer,
    }


def _restore_blender_preview() -> None:
    baseline = globals().pop("_X5_PREVIEW_BASELINE", None)
    if not baseline:
        return
    for state in baseline["objects"]:
        obj = state["object"]
        try:
            obj.hide_viewport = state["hide_viewport"]
            obj.hide_set(state["hide"])
            obj.select_set(state["selected"])
            obj.show_in_front = state["show_in_front"]
            obj.show_name = state["show_name"]
            if hasattr(obj, "show_wire"):
                obj.show_wire = state["show_wire"]
                obj.show_all_edges = state["show_all_edges"]
            if state["empty_display_type"] is not None:
                obj.empty_display_type = state["empty_display_type"]
            if hasattr(obj, "active_material_index"):
                obj.active_material_index = state["active_material_index"]
            if obj.type == "ARMATURE":
                obj.data.display_type = state["armature_display_type"]
                obj.data.show_names = state["armature_show_names"]
        except (ReferenceError, RuntimeError):
            continue
    for state in baseline["spaces"]:
        try:
            space = state["space"]
            space.shading.type = state["shading"]
            if state["face_normals"] is not None:
                space.overlay.show_face_normals = state["face_normals"]
        except (ReferenceError, RuntimeError):
            continue
    try:
        scene = baseline["scene"]
        scene.frame_start = baseline["frame_start"]
        scene.frame_end = baseline["frame_end"]
        scene.frame_set(baseline["frame_current"])
        baseline["view_layer"].objects.active = baseline["active_object"]
    except (ReferenceError, RuntimeError):
        pass


def _frame_selected_in_viewports(blender: Any, spaces: Sequence[Any]) -> None:
    for window in getattr(blender.context.window_manager, "windows", ()):
        screen = getattr(window, "screen", None)
        for area in getattr(screen, "areas", ()) if screen is not None else ():
            if area.type != "VIEW_3D" or area.spaces.active not in spaces:
                continue
            region = next((item for item in area.regions if item.type == "WINDOW"), None)
            if region is None:
                continue
            try:
                with blender.context.temp_override(window=window, area=area, region=region):
                    blender.ops.view3d.view_selected(use_all_regions=False)
            except RuntimeError:
                continue


def preflight_blender(options: Any) -> PreflightResult:
    """Collect Blender facts and run all pure X5 validation before staging export output."""

    exporter = _legacy_exporter()
    blender = exporter._require_blender()
    collection = exporter._select_collection(options.collection_name)
    objects, ignored_warnings = exporter._collect_export_objects(collection)
    snapshot = _snapshot_from_blender(blender, collection, objects, options)
    result = preflight_snapshot(snapshot)
    diagnostics = list(result.diagnostics)
    try:
        exporter._validate_source_objects(objects, options.profile)
        exporter._discover_actions(objects)
    except exporter.ExportError as error:
        diagnostics.append(_diagnostic(
            "ERROR", "BLENDLIB-X5-PREFLIGHT-LEGACY", "scene/strict-v1",
            f"Strict-v1 source validation failed with {error.code}.",
            "Resolve the strict-v1 exporter diagnostic before retrying."
        ))
    diagnostics.extend(
        _diagnostic("WARN", "BLENDLIB-X5-SCENE-IGNORED", "scene", warning, "Keep non-runtime objects outside the export collection.")
        for warning in sorted(ignored_warnings)
    )
    ordered = tuple(sorted(diagnostics, key=_diagnostic_sort_key))
    snapshot = result.snapshot
    if isinstance(snapshot, _FrozenSnapshot):
        state = _trusted_snapshot_state(snapshot)
        if state is None:
            raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Blender preflight snapshot provenance was lost.")
        snapshot = _new_frozen_snapshot(state.values, ordered, trusted=True)
    return PreflightResult(ordered, snapshot)


def x5_export_open_blend(options: Any) -> dict[str, Any]:
    """One-click X5 export: preflight, stage, atomically publish, then optionally refresh."""

    prepared = _prepare_x5_export(options)
    try:
        atomic_write_bundle(options.project_root, prepared.outputs)
    finally:
        shutil.rmtree(prepared.stage_root, ignore_errors=True)
    return prepared.result


def run_x5_cli(options: Any) -> dict[str, Any] | list[dict[str, Any]]:
    """Open the requested blend once, then run one deterministic X5 export or batch.

    This mirrors the legacy command-line seam without changing it.  The caller
    still supplies only Blender arguments after ``--``; all X5 publication goes
    through the staging/rollback path above.
    """

    exporter = _legacy_exporter()
    blender = exporter._require_blender()
    blender.ops.wm.open_mainfile(filepath=str(options.blend_path))
    manifest_path = getattr(options, "batch_manifest_path", None)
    if manifest_path is None:
        return x5_export_open_blend(options)
    try:
        relative_manifest = manifest_path.resolve().relative_to(options.project_root.resolve()).as_posix()
    except ValueError as error:
        raise X5ToolingError("BLENDLIB-X5-PATH-001", "Batch manifest must remain under the project root.") from error
    return x5_batch_export_open_blend(options, load_batch_manifest(options.project_root, relative_manifest))


def x5_batch_export_open_blend(options: Any, items: Sequence[BatchExportItem]) -> list[dict[str, Any]]:
    """Stage every batch item after all preflights, then publish one atomic bundle."""

    ordered = plan_batch(items)
    prepared: list[_PreparedExport] = []
    try:
        for item in ordered:
            item_options = dataclasses.replace(
                options,
                namespace=item.namespace,
                model_id=item.model_id,
                profile=item.profile,
                collection_name=item.collection_name,
            )
            preflight = preflight_blender(item_options)
            if not preflight.ok:
                raise X5ToolingError("BLENDLIB-X5-BATCH-004", _render_diagnostics(preflight.diagnostics))
            prepared.append(_prepare_x5_export(item_options, preflight=preflight))
        outputs: dict[str, bytes] = {}
        for prepared_item in prepared:
            for relative, payload in prepared_item.outputs.items():
                if relative in outputs:
                    raise X5ToolingError("BLENDLIB-X5-BATCH-002", f"Batch outputs conflict at {relative}.")
                outputs[relative] = payload
        atomic_write_bundle(options.project_root, outputs)
        return [item.result for item in prepared]
    finally:
        for prepared_item in prepared:
            shutil.rmtree(prepared_item.stage_root, ignore_errors=True)


def _prepare_x5_export(options: Any, *, preflight: PreflightResult | None = None) -> _PreparedExport:
    preflight = preflight or preflight_blender(options)
    if not preflight.ok:
        raise X5ToolingError("BLENDLIB-X5-PREFLIGHT-001", _render_diagnostics(preflight.diagnostics))
    _require_project_relative_options(options)
    sidecar = build_authoring_sidecar(preflight.snapshot)
    sidecar_payload = _bounded_canonical_json_bytes(
        sidecar,
        MAX_REPORT_BYTES,
        "BLENDLIB-X5-SIDECAR-002",
        "Authoring sidecar",
    )
    output_root = safe_relative_path(options.output_resource_root, "output resource root")
    authoring_root = safe_relative_path(options.authoring_output_root, "authoring output root")
    sidecar_relative = require_non_runtime_output(
        options.project_root,
        output_root,
        f"{authoring_root}/{options.namespace}/{options.model_id}.blendlib-authoring.json",
        "authoring sidecar path",
    )
    report_relative = require_non_runtime_output(
        options.project_root,
        output_root,
        f"{authoring_root}/{options.namespace}/{options.model_id}.asset-report.json",
        "authoring report path",
    )
    expected_artifacts = {
        f"{output_root}/assets/{options.namespace}/blend_models/{options.model_id}.json": b"",
        f"{output_root}/assets/{options.namespace}/models3d/{options.model_id}.glb": b"",
        sidecar_relative: sidecar_payload,
    }
    for material in preflight.snapshot.get("materials", ()):
        if not isinstance(material, Mapping):
            continue
        material_name = _string_or(material.get("name"), "")
        texture_name = f"{_legacy_path_slug(options.model_id)}__{_legacy_path_slug(material_name)}.png"
        expected_artifacts[
            f"{output_root}/assets/{options.namespace}/textures/blendlib/{texture_name}"
        ] = b""
    report_preview = build_asset_report(
        snapshot=preflight.snapshot,
        sidecar=sidecar,
        validation={
            "index_count": 9_223_372_036_854_775_807,
            "material_names": tuple(expected_artifacts),
            "vertex_count": 9_223_372_036_854_775_807,
        },
        artifacts=expected_artifacts,
        diagnostics=preflight.diagnostics,
    )
    asset_report_bytes(report_preview)
    exporter = _legacy_exporter()
    texture_source_roots = exporter._authorized_texture_roots(options)
    publication_root = Path(options.project_root).resolve()
    try:
        publication_root.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Project root cannot be prepared for staged export.") from error
    stage_root = Path(tempfile.mkdtemp(prefix=".blendlib-x5-export-", dir=publication_root))
    stage_options = dataclasses.replace(
        options,
        project_root=stage_root,
        report_path=None,
        dev_refresh_path=None,
        texture_source_roots=texture_source_roots,
    )
    try:
        legacy = exporter.export_open_blend(stage_options)
        staged_assets = stage_root / Path(*output_root.split("/")) / "assets" / options.namespace
        if not staged_assets.is_dir():
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Staged strict-v1 export did not produce an assets root.")
        outputs: dict[str, bytes] = {}
        for staged_file in sorted(path for path in staged_assets.rglob("*") if path.is_file()):
            relative = staged_file.relative_to(stage_root).as_posix()
            outputs[relative] = _read_bounded_file(
                staged_file,
                MAX_RUNTIME_ARTIFACT_BYTES,
                "BLENDLIB-X5-ATOMIC-001",
                "Staged runtime artifact",
                allowed_roots=(stage_root,),
            )
        artifact_paths = {
            "descriptor": f"{output_root}/assets/{options.namespace}/blend_models/{options.model_id}.json",
            "glb": f"{output_root}/assets/{options.namespace}/models3d/{options.model_id}.glb",
        }
        outputs[sidecar_relative] = sidecar_payload
        report = build_asset_report(
            snapshot=preflight.snapshot,
            sidecar=sidecar,
            validation=legacy["validation"],
            artifacts=outputs,
            diagnostics=preflight.diagnostics,
        )
        outputs[report_relative] = asset_report_bytes(report)
        clean_result = {
            "authoring_report": report_relative,
            "authoring_sidecar": sidecar_relative,
            "format": "blendlib-x5-export-result-v1",
            "mesh": artifact_paths["glb"],
            "model_key": f"{options.namespace}:{options.model_id}",
            "report_sha256": sha256_bytes(canonical_json_bytes(report)),
            "sidecar_sha256": sha256_bytes(sidecar_payload),
            "strict_v1_validation": legacy["validation"],
        }
        if options.report_path is not None:
            try:
                report_relative_path = options.report_path.resolve().relative_to(options.project_root.resolve()).as_posix()
            except ValueError as error:
                raise X5ToolingError("BLENDLIB-X5-PATH-001", "Explicit report output must be under project root.") from error
            safe_report = require_non_runtime_output(
                options.project_root, output_root, report_relative_path, "explicit report path"
            )
            outputs[safe_report] = asset_report_bytes(clean_result)
        if getattr(options, "dev_refresh_path", None) is not None:
            relative_refresh = options.dev_refresh_path.resolve().relative_to(options.project_root.resolve()).as_posix()
            message = RefreshMessage(
                session_token=_text(getattr(options, "dev_session_token", None), "dev session token"),
                generation=_integer(getattr(options, "dev_generation", None), "dev generation"),
                artifact_hashes={relative: sha256_bytes(payload) for relative, payload in sorted(outputs.items())},
                model_key=clean_result["model_key"],
            )
            safe_refresh = require_non_runtime_output(
                options.project_root, output_root, relative_refresh, "dev refresh path"
            )
            outputs[safe_refresh] = refresh_message_bytes(message)
            clean_result["dev_refresh"] = safe_refresh
        return _PreparedExport(options, clean_result, outputs, stage_root)
    except Exception:
        shutil.rmtree(stage_root, ignore_errors=True)
        raise


def _snapshot_from_blender(blender: Any, collection: Any, objects: Sequence[Any], options: Any) -> dict[str, Any]:
    if len(objects) > MAX_MAPPING_ITEMS:
        raise X5ToolingError("BLENDLIB-X5-MAPPING-001", "Export object mapping exceeds 4096 items.")
    object_set = set(objects)
    allowed_texture_roots = _legacy_exporter()._authorized_texture_roots(options)
    normalized_objects: list[dict[str, Any]] = []
    materials: dict[str, dict[str, Any]] = {}
    for obj in sorted(objects, key=lambda value: value.name):
        entry: dict[str, Any] = {
            "custom_properties": _blender_custom_properties(obj),
            "name": obj.name,
            "scale": [float(value) for value in obj.scale],
            "type": obj.type,
        }
        if obj.type == "MESH":
            entry.update({
                "face_vertex_counts": [len(polygon.vertices) for polygon in obj.data.polygons],
                "material_names": [slot.material.name if slot.material is not None else "" for slot in obj.material_slots],
                "normals": [[float(component) for component in vertex.normal] for vertex in obj.data.vertices],
                "uv0": obj.data.uv_layers.active is not None and len(obj.data.uv_layers.active.data) > 0,
                "weights": [[float(group.weight) for group in vertex.groups] for vertex in obj.data.vertices],
            })
            for slot in obj.material_slots:
                material = slot.material
                if material is None or material.name in materials:
                    continue
                if len(materials) >= MAX_MAPPING_ITEMS:
                    raise X5ToolingError("BLENDLIB-X5-MAPPING-001", "Material mapping exceeds 4096 items.")
                image_info: dict[str, Any] = {"name": material.name, "mode": "opaque"}
                try:
                    image = _legacy_exporter()._material_texture_source(material)
                    image_info.update(_texture_source_snapshot(image, allowed_texture_roots))
                except Exception as error:
                    image_info.update({"external": False, "packed": True, "source": "", "error": str(error)})
                materials[material.name] = image_info
        if obj.type == "ARMATURE":
            entry["bones"] = [
                {"name": bone.name, "parent": bone.parent.name if bone.parent is not None else None}
                for bone in obj.data.bones
            ]
        normalized_objects.append(entry)
    all_collections = _blender_collections(collection)
    actions: list[dict[str, Any]] = []
    for action in _legacy_exporter()._discover_action_objects(objects):
        frame_start, frame_end = action.frame_range
        actions.append({"frame_end": float(frame_end), "frame_start": float(frame_start), "name": action.name})
    if len(blender.context.scene.timeline_markers) > MAX_MAPPING_ITEMS:
        raise X5ToolingError("BLENDLIB-X5-MAPPING-001", "Timeline marker mapping exceeds 4096 items.")
    markers = [
        {"frame": float(marker.frame), "name": marker.name}
        for marker in sorted(blender.context.scene.timeline_markers, key=lambda value: (value.frame, value.name))
    ]
    roots = [obj for obj in objects if obj.parent not in object_set]
    return {
        "actions": actions,
        "collections": all_collections,
        "coordinate_transform": "minecraft_x=blender_x;minecraft_y=blender_z;minecraft_z=-blender_y",
        "markers": markers,
        "materials": list(materials.values()),
        "model_id": options.model_id,
        "namespace": options.namespace,
        "objects": normalized_objects,
        "output_resource_root": options.output_resource_root,
        "profile": options.profile,
        "root_count": len(roots),
        "units_per_block": float(blender.context.scene.unit_settings.scale_length or 1.0),
    }


def _texture_source_snapshot(image: Path, allowed_roots: Sequence[Path]) -> dict[str, Any]:
    """Resolve a Blender image once and retain only facts needed by bounded preflight."""

    resolved_image = image.resolve()
    resolved_roots = tuple(root.resolve() for root in allowed_roots)
    return {
        "external": True,
        "packed": False,
        "source": str(resolved_image),
        "source_exists": resolved_image.exists(),
        "source_regular": resolved_image.is_file(),
        "source_within_allowed_root": any(
            resolved_image == allowed_root or resolved_image.is_relative_to(allowed_root)
            for allowed_root in resolved_roots
        ),
        "suffix": resolved_image.suffix.lower(),
    }


def _blender_collections(root: Any) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []

    def walk(collection: Any) -> None:
        if len(values) >= MAX_MAPPING_ITEMS:
            raise X5ToolingError("BLENDLIB-X5-MAPPING-001", "Collection mapping exceeds 4096 items.")
        if len(collection.objects) > MAX_MAPPING_ITEMS:
            raise X5ToolingError("BLENDLIB-X5-MAPPING-001", "Collection object mapping exceeds 4096 items.")
        values.append({
            "name": collection.name,
            "objects": sorted(object_.name for object_ in collection.objects),
            "triangle_count": sum(len(polygon.vertices) - 2 for object_ in collection.all_objects if object_.type == "MESH" for polygon in object_.data.polygons),
        })
        for child in sorted(collection.children, key=lambda value: value.name):
            walk(child)

    walk(root)
    return values


def _blender_custom_properties(obj: Any) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for key, value in obj.items():
        if key.startswith("blendlib_"):
            values[key] = value
    return values


def register_blender_ui(blender: Any) -> None:
    """Register X5 panels/operators.  Viewport visual proof remains a manual evidence item."""

    if globals().get("_REGISTERED_X5_CLASSES"):
        return

    class BLENDLIB_OT_x5_preflight(blender.types.Operator):
        bl_idname = "blendlib.x5_preflight"
        bl_label = "Preflight BlendLib X5"

        def execute(self, context: Any) -> set[str]:
            try:
                result = preflight_blender(_ui_options(context, blender))
                context.scene.blendlib_x5_last_status = "PASS" if result.ok else "BLOCKED"
                for diagnostic in result.diagnostics:
                    self.report({"ERROR" if diagnostic.severity == "ERROR" else "WARNING"}, f"{diagnostic.code}: {diagnostic.message}")
                return {"FINISHED"} if result.ok else {"CANCELLED"}
            except Exception as error:
                context.scene.blendlib_x5_last_status = "ERROR"
                self.report({"ERROR"}, str(error))
                return {"CANCELLED"}

    class BLENDLIB_OT_x5_export(blender.types.Operator):
        bl_idname = "blendlib.x5_export"
        bl_label = "Preflight and Export X5"

        def execute(self, context: Any) -> set[str]:
            try:
                result = x5_export_open_blend(_ui_options(context, blender))
                context.scene.blendlib_x5_last_status = "EXPORTED " + result["mesh"]
                self.report({"INFO"}, context.scene.blendlib_x5_last_status)
                return {"FINISHED"}
            except Exception as error:
                context.scene.blendlib_x5_last_status = "BLOCKED"
                self.report({"ERROR"}, str(error))
                return {"CANCELLED"}

    class BLENDLIB_OT_x5_dev_refresh(blender.types.Operator):
        bl_idname = "blendlib.x5_dev_refresh"
        bl_label = "Export and Dev Refresh"

        def execute(self, context: Any) -> set[str]:
            return BLENDLIB_OT_x5_export.execute(self, context)

    class BLENDLIB_OT_x5_batch_export(blender.types.Operator):
        bl_idname = "blendlib.x5_batch_export"
        bl_label = "Preflight and Batch Export X5"

        def execute(self, context: Any) -> set[str]:
            try:
                options = _ui_options(context, blender)
                manifest = context.scene.blendlib_x5_batch_manifest.strip()
                if not manifest:
                    raise X5ToolingError("BLENDLIB-X5-BATCH-005", "Set a project-relative X5 batch manifest first.")
                result = x5_batch_export_open_blend(options, load_batch_manifest(options.project_root, manifest))
                context.scene.blendlib_x5_last_status = f"EXPORTED {len(result)} batch item(s)"
                self.report({"INFO"}, context.scene.blendlib_x5_last_status)
                return {"FINISHED"}
            except Exception as error:
                context.scene.blendlib_x5_last_status = "BLOCKED"
                self.report({"ERROR"}, str(error))
                return {"CANCELLED"}

    class BLENDLIB_OT_x5_preview(blender.types.Operator):
        bl_idname = "blendlib.x5_preview"
        bl_label = "Apply Viewport Preview/Debug"

        def execute(self, context: Any) -> set[str]:
            try:
                scene = context.scene
                state = apply_blender_preview(blender, context, _ui_options(context, blender), PreviewState(
                    model=scene.blendlib_x5_preview_model,
                    bones=scene.blendlib_x5_preview_bones,
                    sockets=scene.blendlib_x5_preview_sockets,
                    normals=scene.blendlib_x5_preview_normals,
                    materials=scene.blendlib_x5_preview_materials,
                    animation_timeline=scene.blendlib_x5_preview_timeline,
                ))
                scene.blendlib_x5_preview_state = canonical_json_bytes(state).decode("utf-8")
                scene.blendlib_x5_last_status = "PREVIEW ACTIVE" if state["active"] else "PREVIEW RESTORED"
                self.report({"INFO"}, "X5 viewport state applied; interactive visual evidence remains manual.")
                return {"FINISHED"}
            except Exception as error:
                _restore_blender_preview()
                context.scene.blendlib_x5_last_status = "PREVIEW BLOCKED"
                self.report({"ERROR"}, str(error))
                return {"CANCELLED"}

    class VIEW3D_PT_blendlib_x5(blender.types.Panel):
        bl_label = "BlendLib X5 Toolchain"
        bl_idname = "VIEW3D_PT_blendlib_x5"
        bl_space_type = "VIEW_3D"
        bl_region_type = "UI"
        bl_category = "BlendLib"

        def draw(self, context: Any) -> None:
            layout = self.layout
            scene = context.scene
            layout.prop(scene, "blendlib_x5_authoring_output_root")
            layout.operator(BLENDLIB_OT_x5_preflight.bl_idname, icon="CHECKMARK")
            layout.operator(BLENDLIB_OT_x5_export.bl_idname, icon="EXPORT")
            layout.prop(scene, "blendlib_x5_batch_manifest")
            layout.operator(BLENDLIB_OT_x5_batch_export.bl_idname, icon="EXPORT")
            layout.prop(scene, "blendlib_x5_dev_refresh_file")
            layout.prop(scene, "blendlib_x5_dev_session_token")
            layout.prop(scene, "blendlib_x5_dev_generation")
            layout.operator(BLENDLIB_OT_x5_dev_refresh.bl_idname, icon="FILE_REFRESH")
            layout.separator()
            for property_name in (
                "blendlib_x5_preview_model", "blendlib_x5_preview_bones", "blendlib_x5_preview_sockets",
                "blendlib_x5_preview_normals", "blendlib_x5_preview_materials", "blendlib_x5_preview_timeline",
            ):
                layout.prop(scene, property_name)
            layout.operator(BLENDLIB_OT_x5_preview.bl_idname, icon="HIDE_OFF")
            layout.label(text=scene.blendlib_x5_last_status)

    classes = (
        BLENDLIB_OT_x5_preflight,
        BLENDLIB_OT_x5_export,
        BLENDLIB_OT_x5_batch_export,
        BLENDLIB_OT_x5_dev_refresh,
        BLENDLIB_OT_x5_preview,
        VIEW3D_PT_blendlib_x5,
    )
    for cls in classes:
        blender.utils.register_class(cls)
    string_property = blender.props.StringProperty
    blender.types.Scene.blendlib_x5_authoring_output_root = string_property(name="Authoring Output Root", default="build/blendlib-authoring")
    blender.types.Scene.blendlib_x5_batch_manifest = string_property(name="Batch Manifest", default="")
    blender.types.Scene.blendlib_x5_dev_refresh_file = string_property(name="Dev Refresh File", default="")
    blender.types.Scene.blendlib_x5_dev_session_token = string_property(name="Dev Session Token", default="")
    blender.types.Scene.blendlib_x5_dev_generation = blender.props.IntProperty(name="Dev Generation", default=1, min=0)
    blender.types.Scene.blendlib_x5_last_status = string_property(name="X5 Status", default="Ready")
    blender.types.Scene.blendlib_x5_preview_state = string_property(name="X5 Preview State", default="")
    for property_name, display_name in (
        ("blendlib_x5_preview_model", "Model Preview"), ("blendlib_x5_preview_bones", "Bones"),
        ("blendlib_x5_preview_sockets", "Sockets"),
        ("blendlib_x5_preview_normals", "Normals"), ("blendlib_x5_preview_materials", "Materials"),
        ("blendlib_x5_preview_timeline", "Animation Timeline"),
    ):
        setattr(blender.types.Scene, property_name, blender.props.BoolProperty(name=display_name, default=False))
    globals()["_REGISTERED_X5_CLASSES"] = classes


def unregister_blender_ui(blender: Any) -> None:
    _restore_blender_preview()
    for property_name in (
        "blendlib_x5_authoring_output_root", "blendlib_x5_batch_manifest", "blendlib_x5_dev_refresh_file", "blendlib_x5_dev_session_token",
        "blendlib_x5_dev_generation", "blendlib_x5_last_status", "blendlib_x5_preview_state", "blendlib_x5_preview_model",
        "blendlib_x5_preview_bones", "blendlib_x5_preview_sockets",
        "blendlib_x5_preview_normals", "blendlib_x5_preview_materials", "blendlib_x5_preview_timeline",
    ):
        if hasattr(blender.types.Scene, property_name):
            delattr(blender.types.Scene, property_name)
    for cls in reversed(globals().get("_REGISTERED_X5_CLASSES", ())):
        blender.utils.unregister_class(cls)
    globals().pop("_REGISTERED_X5_CLASSES", None)


def _ui_options(context: Any, blender: Any) -> Any:
    exporter = _legacy_exporter()
    scene = context.scene
    project_root = Path(scene.blendlib_project_root).expanduser().resolve()
    refresh_file = scene.blendlib_x5_dev_refresh_file.strip()
    return exporter.ExportOptions(
        blend_path=Path(blender.data.filepath).resolve(),
        project_root=project_root,
        namespace=scene.blendlib_namespace,
        model_id=scene.blendlib_model_id,
        profile=scene.blendlib_profile,
        collection_name=scene.blendlib_collection.name if scene.blendlib_collection else None,
        output_resource_root=scene.blendlib_output_resource_root,
        report_path=None,
        authoring_output_root=scene.blendlib_x5_authoring_output_root,
        dev_refresh_path=resolve_under(project_root, refresh_file, "dev refresh path") if refresh_file else None,
        dev_session_token=scene.blendlib_x5_dev_session_token or None,
        dev_generation=scene.blendlib_x5_dev_generation,
    )


def _check_output_identity(snapshot: Mapping[str, Any], diagnostics: list[ToolingDiagnostic]) -> None:
    for key, label in (("namespace", "namespace"), ("model_id", "model id")):
        try:
            if key == "namespace":
                _require_namespace(_text(snapshot.get(key), label))
            else:
                _require_resource_token(_text(snapshot.get(key), label), label)
        except X5ToolingError as error:
            diagnostics.append(_diagnostic("ERROR", error.code, key, error.message, "Use canonical lower-case resource tokens."))
    if snapshot.get("profile") not in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
        diagnostics.append(_diagnostic(
            "ERROR", "BLENDLIB-X5-PROFILE-001", "profile", "X5 requires an existing strict-v1 profile.",
            "Use blendlib:rigid_v1 or blendlib:skinned_v1."
        ))
    try:
        safe_relative_path(_text(snapshot.get("output_resource_root"), "output resource root"), "output resource root")
    except X5ToolingError as error:
        diagnostics.append(_diagnostic("ERROR", error.code, "output_resource_root", error.message, "Use a project-relative output directory."))


def _check_transform(obj: Mapping[str, Any], location: str, diagnostics: list[ToolingDiagnostic]) -> None:
    scale = _list(obj.get("scale"))
    if len(scale) != 3 or not all(_finite_number(value) for value in scale):
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TRANSFORM-001", f"{location}/scale", "Scale must contain three finite values.", "Apply or bake object transforms."))
        return
    numeric = [float(value) for value in scale]
    if min(numeric) <= 0 or max(numeric) - min(numeric) > 1.0e-5:
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TRANSFORM-002", f"{location}/scale", "Negative or non-uniform scale is unsafe for strict-v1 export.", "Apply scale before export."))


def _check_mesh(obj: Mapping[str, Any], location: str, diagnostics: list[ToolingDiagnostic]) -> None:
    faces = _list(obj.get("face_vertex_counts"))
    if not faces or any(type(value) is not int or value != 3 for value in faces):
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-MESH-001", f"{location}/topology", "All source faces must already be triangles.", "Triangulate and apply topology before export."))
    if obj.get("uv0") is not True:
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-MESH-002", f"{location}/uv0", "Mesh is missing UV0.", "Create one non-empty UV map."))
    normals = _list(obj.get("normals"))
    if not normals or any(not _valid_normal(normal) for normal in normals):
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-MESH-003", f"{location}/normals", "Mesh normals must be finite non-zero vectors.", "Recalculate normals before export."))
    material_names = _list(obj.get("material_names"))
    if not material_names or any(not isinstance(value, str) or not value.strip() for value in material_names):
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-MATERIAL-001", f"{location}/materials", "Every mesh needs named material slots.", "Assign named external-PNG materials."))
    for index, weights in enumerate(_list(obj.get("weights"))):
        values = _list(weights)
        if not values:
            continue
        if len(values) > 4 or any(not _finite_number(value) or float(value) < 0.0 for value in values):
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-WEIGHT-001", f"{location}/vertex:{index}/weights", "Weights must contain at most four finite non-negative influences.", "Normalize and prune vertex weights."))
        elif not math.isclose(sum(float(value) for value in values), 1.0, abs_tol=1.0e-5, rel_tol=0.0):
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-WEIGHT-002", f"{location}/vertex:{index}/weights", "Vertex weights must sum to one.", "Normalize vertex weights."))


def _check_armature(obj: Mapping[str, Any], location: str, diagnostics: list[ToolingDiagnostic]) -> None:
    bones = [item for item in _list(obj.get("bones")) if isinstance(item, Mapping)]
    names = {_string_or(bone.get("name"), "") for bone in bones}
    if not bones or "" in names or len(names) != len(bones):
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-ARMATURE-001", f"{location}/bones", "Armature bone names must be unique and non-empty.", "Rename or remove duplicate bones."))
        return
    parents = {name: _string_or(bone.get("parent"), "") for name, bone in ((_string_or(item.get("name"), ""), item) for item in bones)}
    for name, parent in sorted(parents.items()):
        if parent and parent not in names:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-ARMATURE-002", f"{location}/bone:{name}", "Bone parent is not part of the exported armature.", "Repair the bone hierarchy."))
    for name in sorted(names):
        seen: set[str] = set()
        cursor = name
        while cursor:
            if cursor in seen:
                diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-ARMATURE-003", f"{location}/bone:{name}", "Bone hierarchy contains a cycle.", "Remove the cyclic parenting relationship."))
                break
            seen.add(cursor)
            cursor = parents.get(cursor, "")


def _check_materials(materials: Sequence[Any], diagnostics: list[ToolingDiagnostic]) -> None:
    names: set[str] = set()
    for material in sorted((item for item in materials if isinstance(item, Mapping)), key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(material.get("name"), "")
        location = f"material:{name or '<unnamed>'}"
        if not name or name in names:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-MATERIAL-002", location, "Material names must be unique and non-empty.", "Rename the material."))
        names.add(name)
        if material.get("packed"):
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TEXTURE-001", f"{location}/image", "Packed images are forbidden for runtime export.", "Save one external PNG and reconnect the material."))
        if material.get("external") is not True or _string_or(material.get("suffix"), "").lower() != ".png":
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TEXTURE-002", f"{location}/image", "Material needs one external PNG base-color image.", "Use a non-packed .png image."))
        if material.get("source_exists") is not True or material.get("source_regular") is not True:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TEXTURE-004", f"{location}/image", "External PNG source is missing or is not a regular file.", "Select an existing regular PNG file."))
        if material.get("source_within_allowed_root") is not True:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TEXTURE-005", f"{location}/image", "External PNG resolves outside the blend directory and authorized project root.", "Move the PNG beneath the blend directory or project root and reconnect it."))
        source = _string_or(material.get("source"), "")
        lowered = source.lower()
        if lowered.startswith(("file:", "http:", "https:", "ftp:")) or "://" in source:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-TEXTURE-003", f"{location}/image", "File/network URI image sources are unsafe.", "Use a local external PNG source."))


def _check_actions(actions: Sequence[Any], diagnostics: list[ToolingDiagnostic]) -> None:
    names: set[str] = set()
    for action in sorted((item for item in actions if isinstance(item, Mapping)), key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(action.get("name"), "")
        location = f"action:{name or '<unnamed>'}"
        if not name or name in names or any(ord(character) < 32 for character in name):
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-ACTION-001", location, "Action names must be unique printable names.", "Rename the Action."))
        names.add(name)
        start = action.get("frame_start")
        end = action.get("frame_end")
        if not _finite_number(start) or not _finite_number(end) or float(end) < float(start):
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-ACTION-002", f"{location}/range", "Action frame range must be finite and ordered.", "Set a valid action start/end range."))


def _check_collections(collections: Sequence[Any], diagnostics: list[ToolingDiagnostic]) -> None:
    seen_lods: set[int] = set()
    for collection in sorted((item for item in collections if isinstance(item, Mapping)), key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(collection.get("name"), "")
        lod_match = LOD_COLLECTION.fullmatch(name)
        if lod_match:
            level = _lod_level(lod_match)
            if level is None:
                diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-LOD-002", f"collection:{name}", "LOD level is outside the signed 64-bit integer contract.", "Use a non-negative LOD level within the Java long range."))
                continue
            if level in seen_lods:
                diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-LOD-001", f"collection:{name}", "LOD level is duplicated.", "Use one collection for each LOD level."))
            seen_lods.add(level)
            triangles = collection.get("triangle_count", 0)
            if not _is_strict_integer(triangles, minimum=0, maximum=MAX_SIGNED_64):
                diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-LOD-002", f"collection:{name}", "LOD triangle count is invalid.", "Triangulate the LOD mesh."))
            elif triangles > 100_000:
                diagnostics.append(_diagnostic("WARN", "BLENDLIB-X5-LOD-003", f"collection:{name}", "LOD exceeds the X5 performance warning budget.", "Reduce triangles or document the intended budget."))
        if COLLISION_COLLECTION.fullmatch(name) and not _list(collection.get("objects")):
            diagnostics.append(_diagnostic("WARN", "BLENDLIB-X5-COLLISION-001", f"collection:{name}", "Collision reference collection is empty.", "Add reference objects or remove the unused collection."))


def _check_markers(markers: Sequence[Any], diagnostics: list[ToolingDiagnostic]) -> None:
    events: set[tuple[float, str]] = set()
    for marker in sorted((item for item in markers if isinstance(item, Mapping)), key=lambda item: (_bounded_number(item.get("frame"), 0.0), _string_or(item.get("name"), ""))):
        name = _string_or(marker.get("name"), "")
        match = EVENT_MARKER.fullmatch(name)
        if not match:
            continue
        frame = marker.get("frame")
        if not _finite_number(frame) or float(frame) < 0:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-EVENT-001", f"marker:{name}", "Visual-event marker frame is invalid.", "Use a non-negative finite timeline frame."))
            continue
        identity = (float(frame), match.group(1).lower())
        if identity in events:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-EVENT-002", f"marker:{name}", "Duplicate visual event at the same frame.", "Rename or move one marker."))
        events.add(identity)


def _check_units_and_coordinates(snapshot: Mapping[str, Any], diagnostics: list[ToolingDiagnostic]) -> None:
    units = snapshot.get("units_per_block")
    if not _finite_number(units) or not math.isclose(float(units), 1.0, abs_tol=1.0e-5, rel_tol=0.0):
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-COORD-001", "scene/units", "One Blender unit must equal one Minecraft block.", "Set scene units/asset scale to 1.0 and apply transforms."))
    expected = "minecraft_x=blender_x;minecraft_y=blender_z;minecraft_z=-blender_y"
    if snapshot.get("coordinate_transform") != expected:
        diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-COORD-002", "scene/coordinates", "X5 requires the single canonical Blender-to-Minecraft transform.", "Use the exporter coordinate preset; do not add renderer compensation."))


def _collect_authoring_metadata(objects: Sequence[Any]) -> dict[str, Any]:
    """Collect a globally unique, bounded canonical metadata map without value disclosure."""

    metadata: dict[str, Any] = {}
    object_identities: dict[str, str] = {}
    for obj in sorted((item for item in objects if isinstance(item, Mapping)), key=lambda item: _string_or(item.get("name"), "")):
        source_name = _string_or(obj.get("name"), "<unnamed>")
        location = f"object:{source_name}/custom_properties"
        try:
            object_metadata = _bounded_metadata(obj.get("custom_properties", {}))
            if not object_metadata:
                continue
            object_key = _metadata_object_key(source_name)
        except X5ToolingError as error:
            raise _MetadataCollectionError(error.code, error.message, location) from error

        prior_source = object_identities.setdefault(object_key, source_name)
        if prior_source != source_name:
            raise _MetadataCollectionError(
                "BLENDLIB-X5-METADATA-001",
                "Different source objects collide after metadata identity normalization.",
                location,
            )
        for key, value in object_metadata.items():
            canonical_key = f"object.{object_key}.{key}"
            if canonical_key in metadata:
                raise _MetadataCollectionError(
                    "BLENDLIB-X5-METADATA-001",
                    "Authoring metadata entries collide after canonical normalization.",
                    location,
                )
            if len(metadata) >= MAX_AUTHORING_METADATA_TOTAL_ENTRIES:
                raise _MetadataCollectionError(
                    "BLENDLIB-X5-METADATA-001",
                    "Authoring metadata has more than 4096 canonical entries.",
                    location,
                )
            metadata[canonical_key] = value
    return metadata


def _bounded_metadata(value: Any) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        return {}
    metadata: dict[str, Any] = {}
    for key, item in sorted(value.items()):
        if not isinstance(key, str) or not key.startswith("blendlib_"):
            continue
        if len(metadata) >= MAX_AUTHORING_METADATA_ENTRIES:
            raise X5ToolingError("BLENDLIB-X5-METADATA-001", "Authoring metadata has more than 64 BlendLib entries.")
        if _looks_secret(key):
            raise X5ToolingError("BLENDLIB-X5-METADATA-002", "Authoring metadata keys must not identify secret-bearing fields.")
        normalized_key = _authoring_key(key.removeprefix("blendlib_"), "metadata")
        if normalized_key in metadata:
            raise X5ToolingError("BLENDLIB-X5-METADATA-001", "Authoring metadata keys collide after normalization.")
        if isinstance(item, bool):
            metadata[normalized_key] = item
        elif type(item) in {int, float, Decimal} and _finite_number(item):
            metadata[normalized_key] = _canonical_number(float(item))
        elif isinstance(item, str) and len(item) <= MAX_AUTHORING_METADATA_TEXT and not _looks_secret(item):
            metadata[normalized_key] = item
        else:
            raise X5ToolingError("BLENDLIB-X5-METADATA-002", "Authoring metadata values must be bounded scalar values without secrets.")
    return metadata


def _canonical_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, bool)):
        return value
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise X5ToolingError("BLENDLIB-X5-CANONICAL-001", "Canonical JSON cannot contain NaN or Infinity.")
        return _canonical_number(value)
    if isinstance(value, Mapping):
        return {str(key): _canonical_value(item) for key, item in sorted(value.items(), key=lambda item: str(item[0]))}
    if isinstance(value, (list, tuple)):
        return [_canonical_value(item) for item in value]
    raise X5ToolingError("BLENDLIB-X5-CANONICAL-001", f"Canonical JSON cannot encode {type(value).__name__}.")


def _canonical_json_text(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise X5ToolingError("BLENDLIB-X5-CANONICAL-001", "Canonical JSON cannot contain NaN or Infinity.")
        decimal = Decimal(str(value))
        if decimal.is_zero():
            return "0"
        rendered = format(decimal, "f")
        if "." in rendered:
            rendered = rendered.rstrip("0").rstrip(".")
        return rendered
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, Mapping):
        return "{" + ",".join(
            f"{json.dumps(str(key), ensure_ascii=False)}:{_canonical_json_text(item)}"
            for key, item in sorted(value.items(), key=lambda item: str(item[0]))
        ) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_canonical_json_text(item) for item in value) + "]"
    raise X5ToolingError("BLENDLIB-X5-CANONICAL-001", f"Canonical JSON cannot encode {type(value).__name__}.")


def _canonical_json_fragments(value: Any) -> Iterable[str]:
    """Yield canonical JSON without constructing one unbounded aggregate string."""

    if value is None or isinstance(value, (bool, int, float)):
        yield _canonical_json_text(value)
        return
    if isinstance(value, str):
        yield '"'
        escaped: list[str] = []
        escaped_length = 0
        for character in value:
            item = json.dumps(character, ensure_ascii=False)[1:-1]
            if escaped and escaped_length + len(item) > 1_024:
                yield "".join(escaped)
                escaped = []
                escaped_length = 0
            escaped.append(item)
            escaped_length += len(item)
        if escaped:
            yield "".join(escaped)
        yield '"'
        return
    if isinstance(value, Mapping):
        yield "{"
        first = True
        for key, item in sorted(value.items(), key=lambda entry: str(entry[0])):
            if not first:
                yield ","
            first = False
            yield from _canonical_json_fragments(str(key))
            yield ":"
            yield from _canonical_json_fragments(item)
        yield "}"
        return
    if isinstance(value, (list, tuple)):
        yield "["
        for index, item in enumerate(value):
            if index:
                yield ","
            yield from _canonical_json_fragments(item)
        yield "]"
        return
    raise X5ToolingError("BLENDLIB-X5-CANONICAL-001", f"Canonical JSON cannot encode {type(value).__name__}.")


def _canonical_number(value: float) -> float | int:
    if value == 0:
        return 0
    return int(value) if value.is_integer() else value


def _legacy_exporter() -> Any:
    # Imported lazily so stdlib tests have no bpy dependency.  Blender's
    # extension loader imports this package relatively, while headless scripts
    # add ``blender-addon`` directly to sys.path.
    try:
        from . import blendlib_exporter as exporter  # type: ignore
    except ImportError:
        import blendlib_exporter as exporter  # type: ignore

    return exporter


def _require_project_relative_options(options: Any) -> None:
    _require_namespace(options.namespace)
    _require_resource_token(options.model_id, "model id")
    resource_root = safe_relative_path(options.output_resource_root, "output resource root")
    require_non_runtime_output(
        Path(options.project_root), resource_root, options.authoring_output_root, "authoring output root"
    )
    for attribute, label in (("report_path", "explicit report path"), ("dev_refresh_path", "dev refresh path")):
        value = getattr(options, attribute, None)
        if value is None:
            continue
        try:
            relative = Path(value).resolve().relative_to(Path(options.project_root).resolve()).as_posix()
        except ValueError as error:
            raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must remain under project root.") from error
        require_non_runtime_output(Path(options.project_root), resource_root, relative, label)


def _require_resource_token(value: str, label: str) -> str:
    if not isinstance(value, str) or not value or not RESOURCE_TOKEN.fullmatch(value) or ".." in value or value.startswith("/") or value.endswith("/") or "//" in value:
        raise X5ToolingError("BLENDLIB-X5-PATH-002", f"Invalid {label}; use canonical [a-z0-9._/-] tokens without traversal.")
    return value


def _require_namespace(value: str) -> str:
    if not isinstance(value, str) or not value or not NAMESPACE_TOKEN.fullmatch(value):
        raise X5ToolingError("BLENDLIB-X5-PATH-002", "Invalid namespace; use canonical [a-z0-9._-] tokens.")
    return value


def _require_resource_id(value: str, label: str) -> str:
    if not isinstance(value, str) or value.count(":") != 1:
        raise X5ToolingError("BLENDLIB-X5-PATH-002", f"Invalid {label}; use namespace:path.")
    namespace, path = value.split(":", 1)
    _require_namespace(namespace)
    _require_resource_token(path, label)
    return value


def _require_session_token(value: str) -> str:
    if not isinstance(value, str) or not SESSION_TOKEN.fullmatch(value):
        raise X5ToolingError("BLENDLIB-X5-REFRESH-003", "Session token must be 16-128 ASCII token characters.")
    return value


def _diagnostic(severity: str, code: str, location: str, message: str, remediation: str) -> ToolingDiagnostic:
    return ToolingDiagnostic(severity, code, location[:512], message[:1024], remediation[:1024])


def _diagnostic_sort_key(item: ToolingDiagnostic) -> tuple[int, str, str, str]:
    return ({"ERROR": 0, "WARN": 1, "INFO": 2}[item.severity], item.code, item.location, item.message)


def _render_diagnostics(diagnostics: Iterable[ToolingDiagnostic]) -> str:
    maximum_characters = 16_384
    rendered: list[str] = []
    total = 0
    for item in sorted(diagnostics, key=_diagnostic_sort_key):
        fragment = f"{item.code}@{item.location}: {item.message}"
        separator = 3 if rendered else 0
        if total + separator + len(fragment) > maximum_characters:
            rendered.append("BLENDLIB-X5-DIAGNOSTIC-001: additional bounded diagnostics omitted")
            break
        rendered.append(fragment)
        total += separator + len(fragment)
    return " | ".join(rendered)


def _new_frozen_snapshot(
    values: Mapping[str, Any],
    diagnostics: Sequence[ToolingDiagnostic],
    *,
    trusted: bool,
) -> _FrozenSnapshot:
    if type(values) is not _MAPPING_PROXY_TYPE:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen snapshot factory requires the exact immutable representation.",
        )
    diagnostic_records = _canonical_diagnostic_records(diagnostics)
    diagnostic_tuple = _diagnostics_from_records(diagnostic_records)
    snapshot = _FrozenSnapshot(
        values,
        diagnostic_tuple,
        _provenance=_FROZEN_SNAPSHOT_PROVENANCE,
    )
    if trusted:
        identity = id(snapshot)
        generation = object()

        def release_snapshot(
            snapshot_ref: ReferenceType[Any],
            *,
            registered_identity: int = identity,
            registered_generation: object = generation,
        ) -> None:
            _release_trusted_snapshot(
                registered_identity,
                registered_generation,
                snapshot_ref,
            )

        snapshot_ref = ref(snapshot, release_snapshot)
        _TRUSTED_SNAPSHOT_STATES[identity] = _TrustedSnapshotState(
            snapshot_ref,
            generation,
            values,
            diagnostic_tuple,
            diagnostic_records,
            min(
                (record for record in diagnostic_records if record[0] == "ERROR"),
                key=_diagnostic_record_sort_key,
                default=None,
            ),
        )
    return snapshot


def _canonical_diagnostic_records(
    diagnostics: Sequence[ToolingDiagnostic],
) -> tuple[_DiagnosticRecord, ...]:
    records: list[_DiagnosticRecord] = []
    try:
        for item in diagnostics:
            if type(item) is not ToolingDiagnostic:
                raise ValueError("diagnostic type")
            record = (
                item.severity,
                item.code,
                item.location,
                item.message,
                item.remediation,
            )
            if any(type(field) is not str for field in record):
                raise ValueError("diagnostic field type")
            ToolingDiagnostic(*record)
            records.append(record)
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen snapshot diagnostics do not satisfy the immutable value contract.",
        ) from error
    return tuple(records)


def _diagnostics_from_records(
    records: Sequence[_DiagnosticRecord],
) -> tuple[ToolingDiagnostic, ...]:
    return tuple(ToolingDiagnostic(*record) for record in records)


def _diagnostic_record_sort_key(record: _DiagnosticRecord) -> tuple[int, str, str, str]:
    return ({"ERROR": 0, "WARN": 1, "INFO": 2}[record[0]], record[1], record[2], record[3])


def _release_trusted_snapshot(
    identity: int,
    generation: object,
    snapshot_ref: ReferenceType[Any],
) -> None:
    state = _TRUSTED_SNAPSHOT_STATES.get(identity)
    if (
        state is not None
        and state.generation is generation
        and state.snapshot_ref is snapshot_ref
    ):
        del _TRUSTED_SNAPSHOT_STATES[identity]


def _trusted_snapshot_state(
    snapshot: object,
) -> _TrustedSnapshotState | None:
    if type(snapshot) is not _FrozenSnapshot:
        return None
    try:
        state = _TRUSTED_SNAPSHOT_STATES.get(id(snapshot))
        if state is None or state.snapshot_ref() is not snapshot:
            return None
        if (
            snapshot._values is not state.values
            or snapshot._diagnostics is not state.exposed_diagnostics
        ):
            return None
        return state
    except Exception:
        return None


def _list(value: Any) -> list[Any]:
    return list(value) if isinstance(value, (list, tuple)) else []


def _freeze_mapping_snapshot(
    snapshot: Mapping[str, Any],
    diagnostics: list[ToolingDiagnostic],
) -> _FrozenSnapshot:
    if isinstance(snapshot, _FrozenSnapshot):
        state = _trusted_snapshot_state(snapshot)
        if state is not None:
            diagnostics.extend(_diagnostics_from_records(state.diagnostic_records))
            return snapshot
        _append_snapshot_diagnostic("scene", diagnostics)
        empty = MappingProxyType({field: () for field in _MAPPING_INPUT_FIELDS})
        return _new_frozen_snapshot(empty, diagnostics, trusted=False)
    if not isinstance(snapshot, Mapping):
        _append_snapshot_diagnostic("scene", diagnostics)
        empty = MappingProxyType({field: () for field in _MAPPING_INPUT_FIELDS})
        return _new_frozen_snapshot(empty, diagnostics, trusted=False)
    frozen_values: dict[str, Any] = {}
    try:
        source_items = iter(snapshot.items())
        for index, pair in enumerate(source_items):
            if index >= 256:
                _append_snapshot_diagnostic("scene", diagnostics)
                break
            key, value = pair
            if not isinstance(key, str) or len(key) > MAX_SNAPSHOT_TEXT:
                _append_snapshot_diagnostic("scene", diagnostics)
                continue
            frozen_values[key] = value
    except Exception:
        _append_snapshot_diagnostic("scene", diagnostics)
    mapping_diagnostics: list[ToolingDiagnostic] = []
    for location in _MAPPING_INPUT_FIELDS:
        frozen_values[location] = _consume_mapping_iterable(
            frozen_values.get(location), location, mapping_diagnostics
        )
    diagnostics.extend(mapping_diagnostics)
    budget = [0]
    deep_values = _freeze_snapshot_value(frozen_values, "scene", diagnostics, 0, budget, set())
    if not isinstance(deep_values, Mapping):
        deep_values = MappingProxyType({field: () for field in _MAPPING_INPUT_FIELDS})
    return _new_frozen_snapshot(deep_values, diagnostics, trusted=False)


def _snapshot_diagnostic(location: str) -> ToolingDiagnostic:
    return _diagnostic(
        "ERROR",
        "BLENDLIB-X5-SNAPSHOT-001",
        location,
        "Authoring snapshot exceeds the bounded mapping/list/scalar contract.",
        "Use finite acyclic JSON-like authoring values within the X5 snapshot limits.",
    )


def _append_snapshot_diagnostic(
    location: str,
    diagnostics: list[ToolingDiagnostic],
) -> None:
    if not any(item.code == "BLENDLIB-X5-SNAPSHOT-001" for item in diagnostics):
        diagnostics.append(_snapshot_diagnostic(location))


def _freeze_snapshot_value(
    value: Any,
    location: str,
    diagnostics: list[ToolingDiagnostic],
    depth: int,
    budget: list[int],
    active: set[int],
) -> Any:
    """Copy one JSON-like value into immutable containers without retaining aliases."""

    if depth > MAX_SNAPSHOT_DEPTH or budget[0] >= MAX_SNAPSHOT_ITEMS:
        _append_snapshot_diagnostic(location, diagnostics)
        return None
    budget[0] += 1
    if value is None or type(value) in {bool, int, float, Decimal}:
        return value
    if isinstance(value, str):
        if len(value) <= MAX_SNAPSHOT_TEXT:
            return value
        _append_snapshot_diagnostic(location, diagnostics)
        return None
    if isinstance(value, Mapping):
        identity = id(value)
        if identity in active:
            _append_snapshot_diagnostic(location, diagnostics)
            return MappingProxyType({})
        active.add(identity)
        copied: dict[str, Any] = {}
        try:
            for index, pair in enumerate(value.items()):
                if index >= MAX_SNAPSHOT_ITEMS or budget[0] >= MAX_SNAPSHOT_ITEMS:
                    _append_snapshot_diagnostic(location, diagnostics)
                    break
                key, item = pair
                if not isinstance(key, str) or len(key) > MAX_SNAPSHOT_TEXT:
                    _append_snapshot_diagnostic(location, diagnostics)
                    continue
                copied[key] = _freeze_snapshot_value(
                    item, f"{location}/{key}"[:512], diagnostics, depth + 1, budget, active
                )
        except Exception:
            _append_snapshot_diagnostic(location, diagnostics)
        finally:
            active.discard(identity)
        return MappingProxyType(copied)
    if isinstance(value, (list, tuple)):
        identity = id(value)
        if identity in active:
            _append_snapshot_diagnostic(location, diagnostics)
            return ()
        active.add(identity)
        copied_items: list[Any] = []
        try:
            for index, item in enumerate(value):
                if index >= MAX_SNAPSHOT_ITEMS or budget[0] >= MAX_SNAPSHOT_ITEMS:
                    _append_snapshot_diagnostic(location, diagnostics)
                    break
                copied_items.append(_freeze_snapshot_value(
                    item, f"{location}/{index}"[:512], diagnostics, depth + 1, budget, active
                ))
        except Exception:
            _append_snapshot_diagnostic(location, diagnostics)
        finally:
            active.discard(identity)
        return tuple(copied_items)
    _append_snapshot_diagnostic(location, diagnostics)
    return None


def _consume_mapping_iterable(
    value: Any,
    location: str,
    diagnostics: list[ToolingDiagnostic],
) -> tuple[Any, ...]:
    if value is None:
        return ()
    try:
        iterator = iter(value)
    except Exception:
        _append_mapping_iterator_diagnostic(location, diagnostics)
        return ()

    items: list[Any] = []
    while True:
        try:
            item = next(iterator)
        except StopIteration:
            return tuple(items)
        except Exception:
            _append_mapping_iterator_diagnostic(location, diagnostics)
            return tuple(items)
        if len(items) == MAX_MAPPING_ITEMS:
            diagnostics.append(_diagnostic(
                "ERROR",
                "BLENDLIB-X5-MAPPING-001",
                f"scene/{location}",
                f"{location.capitalize()} mapping exceeds 4096 items.",
                "Reduce the authoring mapping to the bounded X5/Java contract.",
            ))
            return tuple(items)
        items.append(item)


def _append_mapping_iterator_diagnostic(
    location: str,
    diagnostics: list[ToolingDiagnostic],
) -> None:
    diagnostics.append(_diagnostic(
        "ERROR",
        "BLENDLIB-X5-MAPPING-003",
        f"scene/{location}",
        f"{location.capitalize()} mapping could not be consumed safely.",
        "Provide a finite iterable that yields authoring mapping entries without failing.",
    ))


def _sidecar_snapshot(snapshot: Mapping[str, Any]) -> _FrozenSnapshot:
    if isinstance(snapshot, _FrozenSnapshot):
        state = _trusted_snapshot_state(snapshot)
        if state is None:
            raise X5ToolingError(
                "BLENDLIB-X5-SNAPSHOT-001",
                "Frozen snapshot provenance or immutable state is invalid.",
            )
        frozen = snapshot
    else:
        frozen = preflight_snapshot(snapshot).snapshot
        state = _trusted_snapshot_state(frozen)
        if state is None:
            raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Authoring snapshot was not frozen safely.")
    if state.first_error_record is not None:
        first = ToolingDiagnostic(*state.first_error_record)
        raise X5ToolingError(first.code, first.message)
    return frozen


def _mapping_output_count(
    objects: Sequence[Any],
    collections: Sequence[Any],
    actions: Sequence[Any],
    materials: Sequence[Any],
    markers: Sequence[Any],
) -> int:
    sockets = sum(1 for item in objects if isinstance(item, Mapping) and item.get("type") == "EMPTY")
    collection_items = sum(1 for item in collections if isinstance(item, Mapping))
    action_items = sum(1 for item in actions if isinstance(item, Mapping))
    material_items = sum(1 for item in materials if isinstance(item, Mapping))
    event_items = sum(
        1
        for item in markers
        if isinstance(item, Mapping) and EVENT_MARKER.fullmatch(_string_or(item.get("name"), ""))
    )
    return sockets + collection_items + action_items + material_items + event_items


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise X5ToolingError("BLENDLIB-X5-PATH-002", f"{label} must be a non-empty string.")
    return value


def _integer(value: Any, label: str) -> int:
    return _strict_integer(
        value,
        label,
        "BLENDLIB-X5-REFRESH-003",
        minimum=0,
        maximum=MAX_SIGNED_64,
    )


def _is_strict_integer(value: Any, *, minimum: int, maximum: int) -> bool:
    return type(value) is int and minimum <= value <= maximum


def _strict_integer(
    value: Any,
    label: str,
    code: str,
    *,
    minimum: int,
    maximum: int,
) -> int:
    if not _is_strict_integer(value, minimum=minimum, maximum=maximum):
        raise X5ToolingError(code, f"{label} must be an integer within its bounded range.")
    return value


def _string_or(value: Any, fallback: str) -> str:
    return value if isinstance(value, str) else fallback


def _finite_number(value: Any) -> bool:
    """Recognize supported finite numbers without leaking conversion failures."""

    if type(value) is int and value.bit_length() > 1_024:
        return False
    if type(value) not in {int, float, Decimal}:
        return False
    try:
        if isinstance(value, Decimal) and not value.is_finite():
            return False
        return math.isfinite(float(value))
    except (ArithmeticError, ValueError, TypeError):
        return False


def _valid_normal(value: Any) -> bool:
    values = _list(value)
    return len(values) == 3 and all(_finite_number(item) for item in values) and sum(float(item) ** 2 for item in values) > 1.0e-12


def _bounded_number(value: Any, fallback: float) -> float | int:
    if not _finite_number(value):
        return fallback
    return _canonical_number(float(value))


def _bounded_nonnegative_integer(value: Any, fallback: int) -> int:
    return value if _is_strict_integer(value, minimum=0, maximum=MAX_SIGNED_64) else fallback


def _lod_level(match: re.Match[str]) -> int | None:
    try:
        level = int(match.group(1))
    except (ValueError, OverflowError):
        return None
    return level if _is_strict_integer(level, minimum=0, maximum=MAX_SIGNED_64) else None


def _authoring_key(value: str, label: str) -> str:
    candidate = value.lower().replace(" ", "_")
    candidate = re.sub(r"[^a-z0-9._/-]", "_", candidate)
    if not candidate or len(candidate) > 128 or ".." in candidate or "//" in candidate:
        raise X5ToolingError("BLENDLIB-X5-METADATA-001", f"Invalid {label} key for authoring sidecar.")
    return candidate


def _legacy_path_slug(value: str) -> str:
    """Mirror the strict P2 texture filename slug without importing Blender code."""

    return re.sub(r"[^a-z0-9._-]", "_", value.lower().replace("/", "_"))


def _metadata_object_key(value: str) -> str:
    """Create the unambiguous object component used by object.<object>.<metadata>."""

    if _looks_secret(value):
        raise X5ToolingError("BLENDLIB-X5-METADATA-002", "Authoring metadata object keys must not identify secret-bearing fields.")
    return _authoring_key(value, "object").replace(".", "_").replace("/", "_")


def _looks_secret(value: str) -> bool:
    lowered = value.lower()
    return any(
        token in lowered
        for token in (
            "password", "passwd", "secret", "token", "credential", "apikey", "api_key", "api-key", "api.key",
            "private key", "private_key", "private-key", "private.key"
        )
    )
