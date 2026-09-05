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
import stat
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
SKIN_WEIGHT_EPSILON = 1.0e-5
BATCH_MANIFEST_FORMAT = "blendlib-x5-batch-manifest-v1"
DEFAULT_RUNTIME_RESOURCE_ROOTS = ("src/main/resources", "build/resources/main")
# The X5 publication seam still crosses Blender and strict-v1 legacy pathname
# APIs.  It deliberately does not opt into Win32 ``\\?\\`` extended paths.
# On the supported legacy Win32 surface, a file spelling may use 259 visible
# UTF-16 code units (MAX_PATH includes its terminating NUL), while
# CreateDirectoryW rejects a directory spelling at 248 visible units so a
# later legacy file leaf still has room.  Keep the two limits distinct: a
# file-safe leaf does not imply that its intermediate directory was creatable.
WINDOWS_LEGACY_FILE_MAX_UTF16_UNITS = 259
WINDOWS_LEGACY_DIRECTORY_MAX_UTF16_UNITS = 247
_PRIVATE_DIRECTORY_RANDOM_BYTES = 16
_PRIVATE_DIRECTORY_RANDOM_HEX_CHARS = _PRIVATE_DIRECTORY_RANDOM_BYTES * 2
_LEGACY_EXPORT_STAGE_PREFIX = ".blendlib-x5-export-"
_ATOMIC_STAGE_PREFIX = ".blendlib-x5-stage-"
_ATOMIC_BACKUP_PREFIX = ".blendlib-x5-backup-"
_MAPPING_INPUT_FIELDS = ("objects", "collections", "actions", "materials", "markers")
_MAPPING_PROXY_TYPE = type(MappingProxyType({}))
_PATH_TYPE = type(Path())
_FROZEN_SNAPSHOT_PROVENANCE = object()
# The atomic writer must not let a monkey-patched path primitive become a
# publication authority. These references are deliberately captured before
# test hooks are installed; hooks below can request a deterministic fault, but
# never choose the pathname an actual filesystem move will resolve.
_TRUSTED_OS_RMDIR = os.rmdir

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
    integrity_digest: bytes
    authority_values: Mapping[str, Any]
    authority_integrity_digest: bytes
    exposed_diagnostics: tuple[ToolingDiagnostic, ...]
    diagnostic_records: tuple[_DiagnosticRecord, ...]
    first_error_record: _DiagnosticRecord | None


_TRUSTED_SNAPSHOT_STATES: dict[int, _TrustedSnapshotState] = {}


class _TrustedPreflightState(NamedTuple):
    result_ref: ReferenceType[Any]
    generation: object
    snapshot_ref: ReferenceType[Any]
    snapshot_generation: object


_TRUSTED_PREFLIGHT_STATES: dict[int, _TrustedPreflightState] = {}


@dataclasses.dataclass(frozen=True)
class PreflightResult:
    diagnostics: tuple[ToolingDiagnostic, ...]
    snapshot: Mapping[str, Any]

    @property
    def ok(self) -> bool:
        _, snapshot_state = _trusted_preflight_snapshot_state(self)
        return snapshot_state.first_error_record is None

    def report(self) -> dict[str, Any]:
        _, snapshot_state = _trusted_preflight_snapshot_state(self)
        return {
            "diagnostics": [item.to_json() for item in _diagnostics_from_records(snapshot_state.diagnostic_records)],
            "format": "blendlib-x5-preflight-v1",
            "ok": snapshot_state.first_error_record is None,
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
    claims: tuple[_CanonicalArtifactClaim, ...] = ()
    root_binding: _AtomicDirectoryBinding | None = None
    bundle_bindings: _AtomicBundleBindings | None = None


@dataclasses.dataclass(frozen=True)
class _ArtifactClaim:
    """One immutable publication target in a pre-stage X5 artifact graph."""

    relative: str
    identity: str
    kind: str
    owner: str
    payload_group: str


@dataclasses.dataclass(frozen=True)
class _AtomicObjectIdentity:
    """One opaque no-follow filesystem-object identity.

    Windows identities always come from no-follow ``FileIdInfo`` handles and
    contain their complete volume serial/file-id pair.  POSIX keeps its own
    descriptor/stat ``st_dev``/``st_ino`` pair under a distinct namespace.
    Keeping the provenance explicit prevents a Blender/CPython-specific
    ``st_dev`` encoding from ever being compared to a Win32 volume serial.
    """

    namespace: str
    primary: int
    secondary: int | bytes


@dataclasses.dataclass(frozen=True)
class _AtomicWindowsFileInformation:
    """The no-follow identity and size obtained from one live Win32 handle."""

    attributes: int
    identity: _AtomicObjectIdentity
    size: int


@dataclasses.dataclass(frozen=True)
class _AtomicDirectoryBinding:
    """One existing physical directory in an approved target-parent chain."""

    path: Path
    identity: _AtomicObjectIdentity


@dataclasses.dataclass(frozen=True)
class _AtomicBundleTarget:
    """One physical output binding held from preflight through replacement."""

    relative: str
    identity: str
    target: Path
    directory_chain: tuple[_AtomicDirectoryBinding, ...]


@dataclasses.dataclass(frozen=True)
class _AtomicBundleBindings:
    """The private physical target set approved for one generic atomic bundle."""

    root: Path
    root_identity: str
    targets: tuple[_AtomicBundleTarget, ...]


@dataclasses.dataclass(frozen=True)
class _AtomicRootRoute:
    """An approval-time root anchor plus suffix created only by this transaction."""

    root: Path
    anchor: _AtomicDirectoryBinding
    missing_parts: tuple[str, ...]


@dataclasses.dataclass
class _AtomicDirectoryLease:
    """One transaction-lifetime, no-follow directory anchor.

    Windows does not expose dir_fd through the standard library. Its branch
    therefore keeps a directory CreateFileW handle open without
    FILE_SHARE_DELETE; POSIX retains a no-follow directory descriptor. Both
    branches bind the opened object to its platform-native identity before it
    is used as a parent for an atomic publication operation.
    """

    binding: _AtomicDirectoryBinding
    fd: int | None = None
    windows_handle: int | None = None
    parent: _AtomicDirectoryLease | None = None
    entry_name: str | None = None
    closed: bool = False

    @property
    def path(self) -> Path:
        return self.binding.path


@dataclasses.dataclass
class _AtomicLeafBinding:
    """One regular file bound to one transaction parent and content authority.

    Windows leaves that may be moved, restored, or deleted retain their exact
    handle until the transaction is committed or rollback authority is no
    longer available.  The handle deliberately denies external WRITE and
    DELETE sharing, while ``expected_digest`` makes same-file in-place writes
    fail closed even if a hostile handle predates this transaction.
    """

    relative: str
    parent: _AtomicDirectoryLease
    name: str
    identity: _AtomicObjectIdentity
    expected_digest: str | None = None
    windows_handle: int | None = None
    closed: bool = False

    @property
    def path(self) -> Path:
        return self.parent.path / self.name


@dataclasses.dataclass(frozen=True)
class _AtomicLeafLocation:
    """A named destination recorded for rollback without file-handle authority."""

    relative: str
    parent: _AtomicDirectoryLease
    name: str
    identity: _AtomicObjectIdentity


@dataclasses.dataclass(frozen=True)
class _AtomicFaultEvent:
    """A test-only fault boundary which intentionally carries no path authority."""

    phase: str
    relative: str


class _AtomicLeaseGraph:
    """Own every directory object the transaction traverses until completion."""

    def __init__(self) -> None:
        self._by_identity: dict[_AtomicObjectIdentity, _AtomicDirectoryLease] = {}
        self._ordered: list[_AtomicDirectoryLease] = []

    def acquire(
        self,
        binding: _AtomicDirectoryBinding,
        label: str,
    ) -> _AtomicDirectoryLease:
        key = binding.identity
        existing = self._by_identity.get(key)
        if existing is not None:
            _validate_atomic_directory_lease(existing, label)
            if existing.binding.identity != binding.identity:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} changed physical identity while its transaction lease was active.",
                )
            return existing
        lease = _open_atomic_directory_lease(binding, label)
        self._by_identity[key] = lease
        self._ordered.append(lease)
        return lease

    def acquire_child(
        self,
        parent: _AtomicDirectoryLease,
        name: str,
        binding: _AtomicDirectoryBinding,
        label: str,
    ) -> _AtomicDirectoryLease:
        """Acquire a child through an already-held parent lease."""

        key = binding.identity
        existing = self._by_identity.get(key)
        if existing is not None:
            _validate_atomic_directory_lease(existing, label)
            return existing
        lease = _open_atomic_directory_lease(binding, label, parent=parent, entry_name=name)
        self._by_identity[key] = lease
        self._ordered.append(lease)
        return lease

    def close_all(self) -> None:
        for lease in reversed(self._ordered):
            _close_atomic_directory_lease(lease)



@dataclasses.dataclass(frozen=True)
class _FrozenLegacyOptions:
    """The exact legacy-export inputs approved by an X5 artifact graph.

    This intentionally does not retain the caller's mutable option object.
    The legacy exporter needs only attribute access, so a small local frozen
    value keeps the published set independent from Python-side mutation after
    preflight.
    """

    blend_path: Path
    project_root: Path
    namespace: str
    model_id: str
    profile: str
    collection_name: str | None
    output_resource_root: str
    report_path: Path | None
    authoring_output_root: str
    dev_refresh_path: Path | None
    dev_session_token: str | None
    dev_generation: int | None
    batch_manifest_path: Path | None
    texture_source_roots: tuple[Path, ...]


@dataclasses.dataclass(frozen=True)
class _FrozenExportPlan:
    """A private, identity-registered plan reused from preflight through staging.

    The public Python object is intentionally not the authority.  The registry
    below owns the exact approved snapshot, options, claims, and sidecar bytes,
    so mutation, copying, or a forged object cannot change the approved output
    set between graph validation and private legacy export.
    """

    snapshot: _FrozenSnapshot
    options: Any
    claims: tuple[_ArtifactClaim, ...]
    sidecar_payload: bytes
    default_report_relative: str
    explicit_report_relative: str | None
    refresh_relative: str | None
    runtime_relatives: tuple[str, ...]

    def __copy__(self) -> "_FrozenExportPlan":
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plans cannot be copied.")

    def __deepcopy__(self, memo: dict[int, Any]) -> "_FrozenExportPlan":
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plans cannot be copied.")

    def __reduce_ex__(self, protocol: int) -> object:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plans cannot be serialized.")


class _CanonicalLegacyOptions(NamedTuple):
    """Plain private option values used by every post-approval operation."""

    blend_path: str
    project_root: str
    namespace: str
    model_id: str
    profile: str
    collection_name: str | None
    output_resource_root: str
    report_path: str | None
    authoring_output_root: str
    dev_refresh_path: str | None
    dev_session_token: str | None
    dev_generation: int | None
    batch_manifest_path: str | None
    texture_source_roots: tuple[str, ...]


class _CanonicalArtifactClaim(NamedTuple):
    """Plain private role/path binding for one approved publication target."""

    relative: str
    identity: str
    kind: str
    owner: str
    payload_group: str


class _CanonicalPublicationRecord(NamedTuple):
    """One complete approved-output record used for final publication checks."""

    owner: str
    kind: str
    payload_group: str
    relative: str
    identity: str
    payload: bytes


class _TrustedExportPlanState(NamedTuple):
    plan_ref: ReferenceType[Any]
    generation: object
    snapshot_ref: ReferenceType[Any]
    snapshot_generation: object
    presented_options: _FrozenLegacyOptions
    presented_option_fields: tuple[Any, ...]
    options: _CanonicalLegacyOptions
    presented_claims: tuple[_ArtifactClaim, ...]
    presented_claim_fields: tuple[tuple[str, str, str, str, str], ...]
    claims: tuple[_CanonicalArtifactClaim, ...]
    presented_sidecar_payload: bytes
    sidecar_payload: bytes
    presented_default_report_relative: str
    default_report_relative: str
    presented_explicit_report_relative: str | None
    explicit_report_relative: str | None
    presented_refresh_relative: str | None
    refresh_relative: str | None
    presented_runtime_relatives: tuple[str, ...]
    runtime_relatives: tuple[str, ...]
    root_route: _AtomicRootRoute
    bundle_bindings: _AtomicBundleBindings


_TRUSTED_EXPORT_PLAN_STATES: dict[int, _TrustedExportPlanState] = {}


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


def _filesystem_identity(path: Path) -> str:
    """Return the conservative Windows filesystem identity of a resolved target.

    ``Path.resolve`` normalizes existing symlink/junction components while
    retaining a non-existent suffix.  Case-folding is intentional: X5's
    supported authoring workflow targets Windows filesystems, where differently
    cased spellings can address one publication target.
    """

    try:
        return str(path.resolve()).replace("\\", "/").casefold()
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-004",
            "Artifact graph paths cannot be resolved safely before publication.",
        ) from error


def _artifact_claim(
    project_root: Path,
    relative: str,
    kind: str,
    owner: str,
    payload_group: str,
) -> _ArtifactClaim:
    safe = safe_relative_path(relative, f"{kind} artifact path")
    target = resolve_under(project_root, safe, f"{kind} artifact path")
    return _ArtifactClaim(safe, _filesystem_identity(target), kind, owner, payload_group)


def _private_directory_budget_name(prefix: str) -> str:
    """Return the exact longest private-directory spelling used by X5."""

    return prefix + ("f" * _PRIVATE_DIRECTORY_RANDOM_HEX_CHARS)


def _windows_utf16_path_units(path: Path, label: str) -> int:
    """Count an ordinary Win32 pathname in UTF-16 code units, not code points."""

    try:
        return len(os.fspath(path).encode("utf-16-le")) // 2
    except UnicodeEncodeError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-005",
            f"Windows legacy publication path budget cannot encode {label}; choose a shorter project root or model id.",
        ) from error


def _reject_windows_extended_project_root(path: Path) -> None:
    """Keep the explicit no-extended-path X5 contract at the input boundary."""

    if os.name != "nt":
        return
    rendered = os.fspath(path)
    if rendered.startswith(("\\\\?\\", "\\\\.\\")):
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-005",
            "Windows extended or device project-root prefixes are unsupported; choose a shorter normal project root.",
        )


def _windows_directory_prefixes(root: Path) -> tuple[Path, ...]:
    """Return every lexical directory prefix that a root route can create."""

    if not root.is_absolute():
        raise X5ToolingError("BLENDLIB-X5-PATH-004", "Windows publication root is not absolute.")
    current = Path(root.anchor)
    prefixes: list[Path] = []
    for part in root.parts[1:]:
        current = current / part
        prefixes.append(current)
    if not prefixes or prefixes[-1] != root:
        prefixes.append(root)
    return tuple(prefixes)


def _windows_relative_parent_directories(root: Path, relative: str) -> tuple[Path, ...]:
    """Return every nested parent X5 can create below one publication root."""

    current = root
    directories: list[Path] = []
    for part in Path(*relative.split("/")).parts[:-1]:
        current = current / part
        directories.append(current)
    return tuple(directories)


def _windows_physical_parent_directories(root: Path, target: Path) -> tuple[Path, ...]:
    """Return the physical output parents used by the handle-bound writer."""

    try:
        relative_parent = target.parent.relative_to(root)
    except ValueError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-004",
            "Windows publication target escaped its resolved project root during path budgeting.",
        ) from error
    current = root
    directories: list[Path] = []
    for part in relative_parent.parts:
        current = current / part
        directories.append(current)
    return tuple(directories)


def _legacy_raw_glb_relative(relative: str) -> str | None:
    """Return the strict-v1 private raw-GLB scratch leaf for one GLB output."""

    suffix = Path(*relative.split("/"))
    if suffix.suffix.lower() != ".glb":
        return None
    return suffix.with_suffix(".raw.glb").as_posix()


def _require_windows_legacy_path_budget(
    project_root: Path,
    artifact_relatives: Iterable[str],
    label: str,
    *,
    include_legacy_export_stage: bool,
) -> None:
    """Reject unrepresentable Windows legacy publication paths before mutation.

    Blender and the retained strict-v1 compatibility exporter still receive
    ordinary Win32 path spellings.  X5 does not claim extended-path support,
    so this checks every file leaf and every directory that publication may
    create: the project-root route, public parents, private stage/backup
    roots, nested texture parents, and strict-v1's transient ``.raw.glb``.
    The diagnostic intentionally names only an approved relative artifact or
    a logical scope, never the host path.
    """

    if os.name != "nt":
        return
    try:
        root = Path(project_root).expanduser().resolve()
    except (OSError, TypeError, ValueError) as error:
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-004",
            f"{label} project root cannot be resolved for the Windows publication path budget.",
        ) from error

    stage_scopes: list[tuple[str, str]] = []
    if include_legacy_export_stage:
        stage_scopes.append(("private legacy export stage", _LEGACY_EXPORT_STAGE_PREFIX))
    stage_scopes.extend((
        ("private atomic staging directory", _ATOMIC_STAGE_PREFIX),
        ("private atomic backup directory", _ATOMIC_BACKUP_PREFIX),
    ))

    def require_directory(path: Path, scope: str, relative: str | None = None) -> None:
        if _windows_utf16_path_units(path, scope) <= WINDOWS_LEGACY_DIRECTORY_MAX_UTF16_UNITS:
            return
        subject = scope if relative is None else f"{relative} in {scope}"
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-005",
            "Windows legacy directory path budget is exceeded for "
            f"{subject}; choose a shorter project root or model id.",
        )

    def require_file(path: Path, scope: str, relative: str) -> None:
        if _windows_utf16_path_units(path, scope) <= WINDOWS_LEGACY_FILE_MAX_UTF16_UNITS:
            return
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-005",
            "Windows legacy file path budget is exceeded for "
            f"{relative} in {scope}; choose a shorter project root or model id.",
        )

    for directory in _windows_directory_prefixes(root):
        require_directory(directory, "project-root directory")
    private_roots: list[tuple[str, Path]] = []
    for scope, prefix in stage_scopes:
        private_root = root / _private_directory_budget_name(prefix)
        require_directory(private_root, scope)
        private_roots.append((scope, private_root))

    normalized = tuple(sorted({
        safe_relative_path(relative, f"{label} artifact path")
        for relative in artifact_relatives
    }))
    for relative in normalized:
        suffix = Path(*relative.split("/"))
        public_target = resolve_under(root, relative, f"{label} artifact path")
        for directory in _windows_physical_parent_directories(root, public_target):
            require_directory(directory, "public target parent", relative)
        require_file(public_target, "public target", relative)
        for scope, private_root in private_roots:
            for directory in _windows_relative_parent_directories(private_root, relative):
                require_directory(directory, scope, relative)
            require_file(private_root / suffix, scope, relative)
        if include_legacy_export_stage:
            raw_relative = _legacy_raw_glb_relative(relative)
            if raw_relative is not None:
                raw_suffix = Path(*raw_relative.split("/"))
                raw_root = next(
                    private_root
                    for scope, private_root in private_roots
                    if scope == "private legacy export stage"
                )
                for directory in _windows_relative_parent_directories(raw_root, raw_relative):
                    require_directory(directory, "private legacy export stage", raw_relative)
                require_file(raw_root / raw_suffix, "private legacy raw GLB", raw_relative)


def _relative_project_path(project_root: Path, value: Any, label: str) -> str:
    """Resolve a Path-like option under the project root without creating it."""

    try:
        candidate = Path(value).expanduser()
    except (TypeError, ValueError) as error:
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must be a project-relative path.") from error
    root = project_root.resolve()
    if not candidate.is_absolute():
        candidate = root / candidate
    try:
        target = candidate.resolve()
        relative = target.relative_to(root).as_posix()
    except (OSError, ValueError) as error:
        raise X5ToolingError("BLENDLIB-X5-PATH-001", f"{label} must remain under the project root.") from error
    return safe_relative_path(relative, label)


def _artifact_graph_error(
    code: str,
    first: _ArtifactClaim,
    second: _ArtifactClaim,
    detail: str,
) -> X5ToolingError:
    return X5ToolingError(
        code,
        "Artifact graph conflict between "
        f"{first.owner} {first.kind} ({first.relative}) and "
        f"{second.owner} {second.kind} ({second.relative}): {detail}",
    )


def _report_deduplication_is_safe(first: _ArtifactClaim, second: _ArtifactClaim) -> bool:
    return (
        {first.kind, second.kind} == {"default-report", "explicit-report"}
        and first.owner == second.owner
        and first.payload_group == second.payload_group
    )


def _identity_contains(parent: str, child: str) -> bool:
    return child.startswith(parent.rstrip("/") + "/")


def _resolved_directory_chain(path: Path, *, code: str, label: str) -> Path:
    """Resolve ``path`` and require its nearest existing ancestor to be a directory.

    ``Path.exists`` follows links and therefore cannot distinguish a missing
    suffix below an existing regular file from an ordinary not-yet-created
    directory.  Resolve aliases first, then inspect the resolved chain with
    ``lstat`` so planning never defers a file-versus-directory conflict to a
    later ``mkdir``.  The first existing node is sufficient: reaching it means
    all farther ancestors were traversable directories during resolution.
    """

    try:
        resolved = path.resolve(strict=False)
    except OSError as error:
        raise X5ToolingError(code, f"{label} cannot be resolved safely before publication.") from error

    candidate = resolved
    while True:
        try:
            mode = os.lstat(candidate).st_mode
        except FileNotFoundError:
            parent = candidate.parent
            if parent == candidate:
                raise X5ToolingError(code, f"{label} has no existing directory ancestor.")
            candidate = parent
            continue
        except OSError as error:
            raise X5ToolingError(code, f"{label} cannot be inspected safely before publication.") from error
        if not stat.S_ISDIR(mode):
            raise X5ToolingError(code, f"{label} has an existing non-directory ancestor.")
        return resolved


def _validate_artifact_graph(
    project_root: Path,
    claims: Sequence[_ArtifactClaim | _CanonicalArtifactClaim],
    *,
    conflict_code: str,
) -> tuple[_ArtifactClaim, ...]:
    """Reject all publication target conflicts before any mkdir/stage/export.

    This validates both identical filesystem identities and file-versus-
    directory relationships.  The sole allowed duplicate is the default and
    explicit asset-report path of one export, because those paths receive the
    exact same canonical report bytes.
    """

    if not claims:
        raise X5ToolingError(conflict_code, "Artifact graph must contain at least one publication target.")
    root = _resolved_directory_chain(
        project_root,
        code=conflict_code,
        label="Artifact graph project root",
    )

    # The identity recorded in a trusted plan is an approval-time binding, not
    # a cacheable path fact. Existing symlink/junction components can be
    # retargeted after planning; recompute every identity now and fail closed
    # if even one approved relative path resolves somewhere else. Collision and
    # parent checks must use those live identities rather than stale plan data.
    live_claims: list[_ArtifactClaim] = []
    for claim in claims:
        target = resolve_under(root, claim.relative, f"{claim.kind} artifact path")
        current_identity = _filesystem_identity(target)
        if current_identity != claim.identity:
            raise X5ToolingError(
                conflict_code,
                "Artifact graph "
                f"{claim.owner} {claim.kind} ({claim.relative}) changed resolved identity after approval.",
            )
        live_claims.append(_ArtifactClaim(
            claim.relative,
            current_identity,
            claim.kind,
            claim.owner,
            claim.payload_group,
        ))

    unique: list[_ArtifactClaim] = []
    by_identity: dict[str, _ArtifactClaim] = {}
    for claim in sorted(live_claims, key=lambda item: (item.identity, item.owner, item.kind, item.relative)):
        prior = by_identity.get(claim.identity)
        if prior is None:
            by_identity[claim.identity] = claim
            unique.append(claim)
            continue
        if _report_deduplication_is_safe(prior, claim):
            continue
        raise _artifact_graph_error(
            conflict_code,
            prior,
            claim,
            "both claims resolve to the same filesystem target",
        )

    ordered = tuple(sorted(unique, key=lambda item: (item.identity, item.owner, item.kind, item.relative)))
    for index, first in enumerate(ordered):
        for second in ordered[index + 1 :]:
            if _identity_contains(first.identity, second.identity) or _identity_contains(second.identity, first.identity):
                raise _artifact_graph_error(
                    conflict_code,
                    first,
                    second,
                    "one output must be a file while the other requires it to be a directory",
                )

    for claim in ordered:
        target = resolve_under(root, claim.relative, f"{claim.kind} artifact path")
        try:
            target_mode = os.lstat(target).st_mode
        except FileNotFoundError:
            target_mode = None
        except OSError as error:
            raise X5ToolingError(
                conflict_code,
                f"Artifact graph {claim.owner} {claim.kind} ({claim.relative}) cannot be inspected safely.",
            ) from error
        if target_mode is not None and not stat.S_ISREG(target_mode):
            raise X5ToolingError(
                conflict_code,
                f"Artifact graph {claim.owner} {claim.kind} ({claim.relative}) resolves to an existing directory or non-regular file.",
            )
        parent = _resolved_directory_chain(
            target.parent,
            code=conflict_code,
            label=f"Artifact graph {claim.owner} {claim.kind} ({claim.relative})",
        )
        try:
            parent.relative_to(root)
        except ValueError as error:
            raise X5ToolingError(conflict_code, "Artifact graph escaped the configured project root.") from error
    return ordered


def plan_batch(items: Sequence[BatchExportItem]) -> tuple[BatchExportItem, ...]:
    """Sort batch work and reject duplicate logical identities before Blender work.

    The GLB name alone is not a sufficient publication identity: sidecars,
    reports, refresh messages, descriptor parents, and canonicalized texture
    filenames are all checked later as one resolved artifact graph.
    """

    diagnostics: list[ToolingDiagnostic] = []
    ordered = tuple(sorted(items, key=lambda item: (item.namespace, item.model_id, item.collection_name or "")))
    seen_models: set[tuple[str, str]] = set()
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

    # A frozen snapshot already carries one canonical diagnostic authority.  Re-running
    # preflight would merge that authority with a second validation pass and can emit
    # duplicate report records, so reject it in constant time before any check runs.
    if isinstance(snapshot, _FrozenSnapshot):
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen snapshots cannot be preflighted again; use their bound PreflightResult.",
        )

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
    profile = _string_or(frozen_snapshot.get("profile"), "")
    named_objects: dict[str, Mapping[str, Any]] = {}
    for item in objects:
        if not isinstance(item, Mapping):
            continue
        item_name = _string_or(item.get("name"), "")
        if item_name and item_name not in named_objects:
            named_objects[item_name] = item

    names: set[str] = set()
    mesh_count = 0
    for obj in sorted((item for item in objects if isinstance(item, Mapping)), key=lambda item: _string_or(item.get("name"), "")):
        name = _string_or(obj.get("name"), "<unnamed>")
        location = f"object:{name}"
        if not name or name in names:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-SCENE-003", location, "Object names must be unique and non-empty.", "Rename the object."))
        names.add(name)
        _check_transform(obj, location, diagnostics)
        object_type = _string_or(obj.get("type"), "")
        if object_type == "MESH":
            mesh_count += 1
            _check_mesh(obj, location, profile, named_objects, diagnostics)
        elif object_type == "ARMATURE":
            _check_armature(obj, location, diagnostics)
        elif object_type not in {"EMPTY", ""}:
            diagnostics.append(_diagnostic("ERROR", "BLENDLIB-X5-SCENE-004", location, f"Unsupported authoring object type '{object_type}'.", "Bake or remove it before export."))
    if profile == "blendlib:skinned_v1" and mesh_count == 0:
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-ARMATURE-004",
            "scene/skin_binding",
            "Skinned profile requires at least one Armature-bound mesh.",
            "Add a skinned mesh with exactly one exported Armature target.",
        ))
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
    return _new_preflight_result(
        _new_frozen_snapshot(frozen_snapshot._values, ordered, trusted=True)
    )


def build_authoring_sidecar(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    """Map Blender-only semantics into a bounded, explicitly non-runtime sidecar."""

    frozen_snapshot = _sidecar_snapshot(snapshot)
    values = _snapshot_authority_values(frozen_snapshot)
    namespace = _require_namespace(_text(values.get("namespace"), "namespace"))
    model_id = _require_resource_token(_text(values.get("model_id"), "model id"), "model id")
    profile = _text(values.get("profile"), "profile")
    if profile not in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
        raise X5ToolingError("BLENDLIB-X5-SIDECAR-001", "Sidecar profile must name an existing strict v1 profile.")

    objects = [item for item in values["objects"] if isinstance(item, Mapping)]
    collections = [item for item in values["collections"] if isinstance(item, Mapping)]
    actions = [item for item in values["actions"] if isinstance(item, Mapping)]
    materials = [item for item in values["materials"] if isinstance(item, Mapping)]
    markers = [item for item in values["markers"] if isinstance(item, Mapping)]
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

    # ``diagnostics`` is retained as a source-compatible presentation argument,
    # but reports are always reconstructed from the snapshot authority record.
    frozen_snapshot = _sidecar_snapshot(snapshot)
    snapshot_state = _trusted_snapshot_state(frozen_snapshot)
    if snapshot_state is None:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Authoring report snapshot provenance was lost.")
    authoritative_values = _snapshot_authority_values(frozen_snapshot)
    authoritative_diagnostics = _diagnostics_from_records(snapshot_state.diagnostic_records)
    if len(authoritative_diagnostics) > MAX_MAPPING_ITEMS or len(artifacts) > MAX_MAPPING_ITEMS:
        raise X5ToolingError("BLENDLIB-X5-REPORT-002", "Authoring report exceeds its bounded collection contract.")

    mapping = sidecar.get("mapping", {}) if isinstance(sidecar, Mapping) else {}
    objects = [item for item in _list(authoritative_values.get("objects")) if isinstance(item, Mapping)]
    bones = sum(len(_list(item.get("bones"))) for item in objects if item.get("type") == "ARMATURE")
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
    weight_vertices = (
        vertex_count
        if authoritative_values.get("profile") == "blendlib:skinned_v1"
        else 0
    )
    triangles = index_count // 3
    ordered_diagnostics = sorted(authoritative_diagnostics, key=_diagnostic_sort_key)
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


def _atomic_posix_identity(metadata: os.stat_result) -> _AtomicObjectIdentity:
    """Return the POSIX identity namespace for a descriptor/stat object.

    This helper must never be used for a Windows approval or lease: Blender's
    bundled Python is free to encode ``st_dev`` differently from CPython.
    """

    return _AtomicObjectIdentity("posix", int(metadata.st_dev), int(metadata.st_ino))


def _atomic_directory_chain(
    root: Path,
    parent: Path,
) -> tuple[_AtomicDirectoryBinding, ...]:
    """Capture existing directory objects from root through one target parent.

    ``Path.resolve`` spelling alone cannot detect an ordinary directory removed
    and recreated at the same name.  Atomic publication keeps a lightweight
    device/inode binding for every existing resolved directory it will traverse
    later.  The target itself may be missing, so only directories are bound.
    """

    try:
        relative = parent.relative_to(root)
    except ValueError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic bundle target escaped the configured project root.",
        ) from error
    bindings: list[_AtomicDirectoryBinding] = []
    candidate = root
    for part in (None, *relative.parts):
        if part is not None:
            candidate = candidate / part
        try:
            metadata = os.lstat(candidate)
        except FileNotFoundError:
            # All remaining descendants are necessarily absent below this
            # missing segment.  They will be bound by the explicit controlled
            # directory-creation revalidation in ``atomic_write_bundle``.
            break
        except OSError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle target directory cannot be inspected safely.",
            ) from error
        if not stat.S_ISDIR(metadata.st_mode):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle target has an existing non-directory ancestor.",
            )
        if os.name == "nt":
            bindings.append(_atomic_existing_directory_binding(candidate, "Atomic bundle target directory"))
        else:
            bindings.append(_AtomicDirectoryBinding(candidate, _atomic_posix_identity(metadata)))
    return tuple(bindings)


def _atomic_directory_chain_matches(
    approved: tuple[_AtomicDirectoryBinding, ...],
    live: tuple[_AtomicDirectoryBinding, ...],
    *,
    permit_controlled_extension: bool,
) -> bool:
    """Compare physical parent bindings, optionally extending controlled mkdirs."""

    if len(live) < len(approved):
        return False
    if any(
        actual.path != expected.path
        or actual.identity != expected.identity
        for expected, actual in zip(approved, live)
    ):
        return False
    return permit_controlled_extension or len(live) == len(approved)


def _atomic_existing_directory_binding(path: Path, label: str) -> _AtomicDirectoryBinding:
    """Bind one existing, resolved directory before giving it a transaction lease."""

    try:
        physical = path.resolve(strict=True)
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be resolved and inspected safely.",
        ) from error
    if os.name == "nt":
        # Approval may be repeated while a transaction lease is already live.
        # Use an all-share no-follow identity probe here; `_open_atomic_...`
        # later upgrades the approved identity to the no-delete-share lease.
        handle = _atomic_windows_open_directory_probe_handle(physical, label)
        try:
            information = _atomic_windows_file_information(handle, label)
            if not information.attributes & 0x00000010 or information.attributes & 0x00000400:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} is not an existing non-reparse directory.",
                )
            return _AtomicDirectoryBinding(
                physical,
                information.identity,
            )
        finally:
            _atomic_windows_close_handle(handle)
    try:
        metadata = os.lstat(physical)
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be resolved and inspected safely.",
        ) from error
    if not stat.S_ISDIR(metadata.st_mode):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} is not an existing directory.",
        )
    return _AtomicDirectoryBinding(physical, _atomic_posix_identity(metadata))


def _atomic_lstat_child(
    parent: _AtomicDirectoryLease,
    name: str,
    label: str,
) -> os.stat_result:
    """Inspect one direct child through its already-leased parent object."""

    _validate_atomic_directory_lease(parent, label)
    try:
        if os.name == "nt":
            return os.lstat(parent.path / name)
        if parent.fd is None or os.stat not in os.supports_dir_fd:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} has no capability-safe POSIX parent descriptor.",
            )
        return os.stat(name, dir_fd=parent.fd, follow_symlinks=False)
    except FileNotFoundError:
        raise
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be inspected through its transaction lease.",
        ) from error


def _atomic_child_directory_binding(
    parent: _AtomicDirectoryLease,
    name: str,
    label: str,
) -> _AtomicDirectoryBinding:
    """Bind a direct child directory without re-resolving its parent path."""

    if os.name == "nt":
        _validate_atomic_named_directory_lease(parent, label)
        return _atomic_existing_directory_binding(parent.path / name, label)
    try:
        metadata = _atomic_lstat_child(parent, name, label)
    except FileNotFoundError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} disappeared before its transaction lease was acquired.",
        ) from error
    if not stat.S_ISDIR(metadata.st_mode):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} is not an existing directory.",
        )
    return _AtomicDirectoryBinding(parent.path / name, _atomic_posix_identity(metadata))


def _atomic_create_child_directory(
    graph: _AtomicLeaseGraph,
    parent: _AtomicDirectoryLease,
    name: str,
    label: str,
) -> _AtomicDirectoryLease:
    """Create and immediately lease one private or public child directory."""

    _validate_atomic_named_directory_lease(parent, label)
    try:
        if os.name == "nt":
            (parent.path / name).mkdir()
        else:
            if parent.fd is None or os.mkdir not in os.supports_dir_fd:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} cannot create a directory with a capability-safe POSIX parent descriptor.",
                )
            os.mkdir(name, mode=0o700, dir_fd=parent.fd)
    except FileExistsError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} unexpectedly already exists in a transaction directory.",
        ) from error
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be created safely in a transaction directory.",
        ) from error
    binding = _atomic_child_directory_binding(parent, name, label)
    return graph.acquire_child(parent, name, binding, label)


def _atomic_allocate_private_directory(
    graph: _AtomicLeaseGraph,
    parent: _AtomicDirectoryLease,
    prefix: str,
    label: str,
) -> _AtomicDirectoryLease:
    """Allocate a random private child and lease it before any bytes are written."""

    _validate_atomic_named_directory_lease(parent, label)
    for _ in range(128):
        name = f"{prefix}{os.urandom(_PRIVATE_DIRECTORY_RANDOM_BYTES).hex()}"
        try:
            if os.name == "nt":
                (parent.path / name).mkdir()
            else:
                if parent.fd is None or os.mkdir not in os.supports_dir_fd:
                    raise X5ToolingError(
                        "BLENDLIB-X5-ATOMIC-001",
                        f"{label} cannot allocate through a capability-safe POSIX parent descriptor.",
                    )
                os.mkdir(name, mode=0o700, dir_fd=parent.fd)
        except FileExistsError:
            continue
        except X5ToolingError:
            raise
        except OSError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} cannot be allocated safely in the project root.",
            ) from error
        binding = _atomic_child_directory_binding(parent, name, label)
        return graph.acquire_child(parent, name, binding, label)
    raise X5ToolingError(
        "BLENDLIB-X5-ATOMIC-001",
        f"{label} could not allocate a unique private directory.",
    )


def _atomic_existing_child_lease(
    graph: _AtomicLeaseGraph,
    parent: _AtomicDirectoryLease,
    name: str,
    label: str,
) -> _AtomicDirectoryLease:
    """Acquire one existing child directory through an already-held parent."""

    binding = _atomic_child_directory_binding(parent, name, label)
    return graph.acquire_child(parent, name, binding, label)


def _atomic_existing_or_create_child_lease(
    graph: _AtomicLeaseGraph,
    parent: _AtomicDirectoryLease,
    name: str,
    label: str,
) -> _AtomicDirectoryLease:
    """Lease a direct child, creating it only through the parent lease when absent."""

    try:
        if os.name == "nt":
            # The path probe is only for absence detection.  Its identity is
            # deliberately discarded; the binding below is handle-native.
            _atomic_lstat_child(parent, name, label)
        binding = _atomic_child_directory_binding(parent, name, label)
    except FileNotFoundError:
        return _atomic_create_child_directory(graph, parent, name, label)
    return graph.acquire_child(parent, name, binding, label)


def _atomic_lease_directory_path(
    graph: _AtomicLeaseGraph,
    directory: Path,
    label: str,
    *,
    create_missing: bool,
) -> _AtomicDirectoryLease:
    """Walk an absolute directory through no-follow parent leases.

    This is the only path-to-directory bridge used by a transaction.  Once the
    first filesystem root is opened, every remaining component is inspected,
    opened, and (when requested) created through its already-held parent.  In
    particular, publication never resumes a pathname walk after a lease exists.
    """

    try:
        physical = Path(directory).resolve(strict=False)
    except (OSError, ValueError) as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be resolved safely for a transaction lease.",
        ) from error
    if not physical.is_absolute():
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} is not an absolute directory path.")

    # Opening a filesystem volume root with DELETE access is denied on common
    # Windows installations.  Start at the closest already-existing directory
    # instead, then use only child handles/dirfds for every missing suffix.
    candidate = physical
    missing: list[str] = []
    while True:
        try:
            metadata = os.lstat(candidate)
        except FileNotFoundError:
            if not create_missing:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} is missing before its transaction lease was acquired.",
                )
            if candidate.parent == candidate:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} has no existing directory anchor.",
                )
            missing.append(candidate.name)
            candidate = candidate.parent
            continue
        except OSError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} cannot be inspected safely for a transaction lease.",
            ) from error
        if not stat.S_ISDIR(metadata.st_mode):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} has an existing non-directory ancestor.",
            )
        break

    current = graph.acquire(
        _atomic_existing_directory_binding(candidate, f"{label} existing directory anchor"),
        f"{label} existing directory anchor",
    )
    for part in reversed(missing):
        current = _atomic_existing_or_create_child_lease(
            graph,
            current,
            part,
            f"{label} directory {part}",
        )
    return current


def _atomic_capture_root_route(
    directory: Path,
    label: str,
    *,
    code: str = "BLENDLIB-X5-PATH-004",
) -> _AtomicRootRoute:
    """Freeze the nearest existing root ancestor before plan approval.

    A missing project root is legal, but a later transaction may create only
    the exact suffix that was missing at approval.  If another process creates
    or substitutes any component first, opening this saved anchor followed by
    exclusive child creation fails rather than adopting the new pathname tree.
    """

    try:
        physical = Path(directory).resolve(strict=False)
    except (OSError, ValueError) as error:
        raise X5ToolingError(
            code,
            f"{label} cannot be resolved safely before plan approval.",
        ) from error
    if not physical.is_absolute():
        raise X5ToolingError(code, f"{label} is not an absolute project directory.")
    candidate = physical
    missing: list[str] = []
    while True:
        try:
            metadata = os.lstat(candidate)
        except FileNotFoundError:
            if candidate.parent == candidate:
                raise X5ToolingError(
                    code,
                    f"{label} has no existing directory anchor.",
                )
            missing.append(candidate.name)
            candidate = candidate.parent
            continue
        except OSError as error:
            raise X5ToolingError(
                code,
                f"{label} cannot be inspected safely before plan approval.",
            ) from error
        if not stat.S_ISDIR(metadata.st_mode):
            raise X5ToolingError(
                code,
                f"{label} has an existing non-directory ancestor.",
            )
        return _AtomicRootRoute(
            physical,
            _atomic_existing_directory_binding(candidate, f"{label} existing directory anchor"),
            tuple(reversed(missing)),
        )


def _atomic_acquire_approved_root_route(
    graph: _AtomicLeaseGraph,
    route: _AtomicRootRoute,
    label: str,
) -> _AtomicDirectoryLease:
    """Open the plan-approved root anchor and create only its approved suffix."""

    current = graph.acquire(route.anchor, f"{label} approved anchor")
    for part in route.missing_parts:
        current = _atomic_create_child_directory(graph, current, part, f"{label} directory {part}")
    if current.path != route.root:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} approved root route no longer reaches its planned directory.",
        )
    return current


def _atomic_rebase_bundle_bindings_after_root_creation(
    bindings: _AtomicBundleBindings,
    root_binding: _AtomicDirectoryBinding,
    label: str,
) -> _AtomicBundleBindings:
    """Insert a controlled newly-created root into otherwise empty plan chains."""

    rebased: list[_AtomicBundleTarget] = []
    for target in bindings.targets:
        if target.directory_chain:
            first = target.directory_chain[0]
            if first != root_binding:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} has a root binding different from the approved transaction root.",
                )
            rebased.append(target)
            continue
        try:
            target.target.parent.relative_to(root_binding.path)
        except ValueError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} target escaped the newly created project root.",
            ) from error
        rebased.append(dataclasses.replace(target, directory_chain=(root_binding,)))
    return _AtomicBundleBindings(root_binding.path, bindings.root_identity, tuple(rebased))


def _atomic_merge_bundle_bindings(
    bindings: Sequence[_AtomicBundleBindings],
    root_binding: _AtomicDirectoryBinding,
    label: str,
) -> _AtomicBundleBindings:
    """Merge already-approved per-item targets without upgrading any binding."""

    if not bindings:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no approved bundle bindings.")
    root_identity = bindings[0].root_identity
    targets: dict[str, _AtomicBundleTarget] = {}
    for binding_set in bindings:
        if binding_set.root != root_binding.path or binding_set.root_identity != root_identity:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} does not share one approved project-root binding.",
            )
        for target in binding_set.targets:
            if target.relative in targets:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} contains duplicate approved target {target.relative}.",
                )
            if not target.directory_chain or target.directory_chain[0] != root_binding:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} target {target.relative} is not rooted in the approved transaction root.",
                )
            targets[target.relative] = target
    return _AtomicBundleBindings(
        root_binding.path,
        root_identity,
        tuple(sorted(targets.values(), key=lambda item: item.relative)),
    )


def _validate_atomic_named_directory_lease(lease: _AtomicDirectoryLease, label: str) -> None:
    """Require both the opened object and its approved parent entry to remain current."""

    _validate_atomic_directory_lease(lease, label)
    if os.name == "nt":
        # A full lease intentionally denies DELETE sharing, so revalidation
        # uses a read-attributes probe whose share mask still admits the live
        # lease.  Both identities are Win32 handle identities; never compare
        # this against Python's version-dependent ``st_dev`` encoding.
        candidate = lease.binding.path if lease.parent is None else lease.parent.path / (lease.entry_name or "")
        if lease.parent is not None and lease.entry_name is None:
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no approved parent entry.")
        handle = _atomic_windows_open_directory_probe_handle(candidate, label)
        try:
            information = _atomic_windows_file_information(handle, label)
            if (
                not information.attributes & 0x00000010
                or information.attributes & 0x00000400
                or information.identity != lease.binding.identity
            ):
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} changed named directory identity while the transaction was active.",
                )
        finally:
            _atomic_windows_close_handle(handle)
        return
    try:
        if lease.parent is None:
            metadata = os.lstat(lease.binding.path)
        else:
            if lease.entry_name is None:
                raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no approved parent entry.")
            metadata = _atomic_lstat_child(lease.parent, lease.entry_name, label)
    except X5ToolingError:
        raise
    except FileNotFoundError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} disappeared from its approved parent while the transaction was active.",
        ) from error
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be revalidated through its approved parent.",
        ) from error
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or _atomic_posix_identity(metadata) != lease.binding.identity
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} changed named directory identity while the transaction was active.",
        )


def _atomic_public_parent(
    graph: _AtomicLeaseGraph,
    root: _AtomicDirectoryLease,
    target: _AtomicBundleTarget,
    label: str,
    controlled_children: dict[tuple[int, str], _AtomicDirectoryLease],
) -> _AtomicDirectoryLease:
    """Lease/create the pre-approved *physical* output parent chain.

    Artifact planning permits an in-root alias only when it resolves beneath
    the approved project root.  The mutation path must therefore follow the
    frozen physical chain rather than reopening the user spelling (which might
    have been retargeted after staging).  A caller revalidates that spelling at
    each public boundary; this function then performs all operations by handle.
    """

    if not target.directory_chain:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no approved root directory chain.")
    first = target.directory_chain[0]
    if (
        first.identity != root.binding.identity
        or first.path != root.binding.path
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} no longer begins at the leased project root.",
        )

    parent = root
    for binding in target.directory_chain[1:]:
        if binding.path.parent != parent.path:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} has a non-contiguous approved physical directory chain.",
            )
        current = _atomic_existing_child_lease(graph, parent, binding.path.name, label)
        if current.binding != binding:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} changed physical directory identity after approval.",
            )
        parent = current

    try:
        suffix = target.target.parent.relative_to(parent.path)
    except ValueError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} escaped the leased physical project tree.",
        ) from error
    for part in suffix.parts:
        key = (id(parent), part)
        existing = controlled_children.get(key)
        if existing is not None:
            _validate_atomic_named_directory_lease(existing, f"{label} directory {part}")
            parent = existing
            continue
        # This suffix did not exist when the immutable plan was approved.
        # Only this transaction may create it; accepting a concurrently
        # pre-created directory would silently replace the approved baseline.
        parent = _atomic_create_child_directory(
            graph,
            parent,
            part,
            f"{label} directory {part}",
        )
        controlled_children[key] = parent
    return parent


def _atomic_expected_private_parent(
    graph: _AtomicLeaseGraph,
    root: _AtomicDirectoryLease,
    relative: str,
    directories: list[_AtomicDirectoryLease],
    parents: dict[str, _AtomicDirectoryLease],
    label: str,
) -> _AtomicDirectoryLease:
    """Walk an already-created private tree without ever recreating a component."""

    parent = root
    prefix: list[str] = []
    for part in safe_relative_path(relative, label).split("/")[:-1]:
        prefix.append(part)
        key = "/".join(prefix)
        existing = parents.get(key)
        if existing is None:
            parent = _atomic_existing_child_lease(
                graph,
                parent,
                part,
                f"{label} directory {key}",
            )
            parents[key] = parent
            directories.append(parent)
        else:
            parent = existing
            _validate_atomic_named_directory_lease(parent, f"{label} directory {key}")
    return parent


def _atomic_windows_file_information(handle: int, label: str) -> _AtomicWindowsFileInformation:
    """Read one complete Windows handle identity without resolving its path.

    ``BY_HANDLE_FILE_INFORMATION.dwVolumeSerialNumber`` is only 32 bits and
    cannot be compared to the version-dependent Windows ``st_dev`` exposed by
    Python runtimes.  ``FileIdInfo`` supplies the authoritative 64-bit volume
    serial plus 128-bit file id for all X5 Windows bindings and leases.
    """

    import ctypes
    from ctypes import wintypes

    class _ByHandleFileInformation(ctypes.Structure):
        _fields_ = [
            ("attributes", wintypes.DWORD),
            ("creation_time", wintypes.FILETIME),
            ("last_access_time", wintypes.FILETIME),
            ("last_write_time", wintypes.FILETIME),
            ("volume_serial", wintypes.DWORD),
            ("size_high", wintypes.DWORD),
            ("size_low", wintypes.DWORD),
            ("number_of_links", wintypes.DWORD),
            ("file_index_high", wintypes.DWORD),
            ("file_index_low", wintypes.DWORD),
        ]

    class _FileId128(ctypes.Structure):
        _fields_ = [("identifier", ctypes.c_ubyte * 16)]

    class _FileIdInfo(ctypes.Structure):
        _fields_ = [
            ("volume_serial_number", ctypes.c_ulonglong),
            ("file_id", _FileId128),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    get_information = kernel32.GetFileInformationByHandle
    get_information.argtypes = [wintypes.HANDLE, ctypes.POINTER(_ByHandleFileInformation)]
    get_information.restype = wintypes.BOOL
    information = _ByHandleFileInformation()
    if not get_information(handle, ctypes.byref(information)):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} handle identity cannot be inspected safely (WinError {ctypes.get_last_error()}).",
        )
    get_information_ex = kernel32.GetFileInformationByHandleEx
    get_information_ex.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    get_information_ex.restype = wintypes.BOOL
    file_id_information = _FileIdInfo()
    file_id_info = 18
    if not get_information_ex(
        handle,
        file_id_info,
        ctypes.byref(file_id_information),
        ctypes.sizeof(file_id_information),
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} complete handle identity cannot be inspected safely (WinError {ctypes.get_last_error()}).",
        )
    size = (int(information.size_high) << 32) | int(information.size_low)
    return _AtomicWindowsFileInformation(
        int(information.attributes),
        _AtomicObjectIdentity(
            "windows",
            int(file_id_information.volume_serial_number),
            bytes(file_id_information.file_id.identifier),
        ),
        size,
    )


def _atomic_windows_close_handle(handle: int) -> None:
    """Close one best-effort Windows transaction handle."""

    import ctypes
    from ctypes import wintypes

    close_handle = ctypes.WinDLL("kernel32", use_last_error=True).CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    close_handle(handle)


def _atomic_windows_open_directory_handle(path: Path, label: str) -> int:
    """Open a no-delete-share handle that pins one directory entry on Windows."""

    import ctypes
    from ctypes import wintypes

    generic_read = 0x80000000
    delete = 0x00010000
    file_share_read = 0x00000001
    file_share_write = 0x00000002
    open_existing = 3
    file_flag_backup_semantics = 0x02000000
    file_flag_open_reparse_point = 0x00200000
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    handle = create_file(
        str(path),
        generic_read | delete,
        file_share_read | file_share_write,
        None,
        open_existing,
        file_flag_backup_semantics | file_flag_open_reparse_point,
        None,
    )
    invalid_handle = ctypes.c_void_p(-1).value
    if handle == invalid_handle:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot acquire a no-follow transaction lease (WinError {ctypes.get_last_error()}).",
        )
    return int(handle)


def _atomic_windows_open_directory_probe_handle(path: Path, label: str) -> int:
    """Open a no-follow read-attributes probe compatible with a live lease."""

    import ctypes
    from ctypes import wintypes

    file_read_attributes = 0x00000080
    file_share_read = 0x00000001
    file_share_write = 0x00000002
    file_share_delete = 0x00000004
    open_existing = 3
    file_flag_backup_semantics = 0x02000000
    file_flag_open_reparse_point = 0x00200000
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    handle = create_file(
        str(path),
        file_read_attributes,
        file_share_read | file_share_write | file_share_delete,
        None,
        open_existing,
        file_flag_backup_semantics | file_flag_open_reparse_point,
        None,
    )
    invalid_handle = ctypes.c_void_p(-1).value
    if handle == invalid_handle:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be revalidated through its no-follow directory handle (WinError {ctypes.get_last_error()}).",
        )
    return int(handle)


def _atomic_windows_open_regular_content_handle(
    path: Path,
    label: str,
    *,
    create_new: bool,
    writable: bool,
) -> int:
    """Open one exact transaction leaf and reject external write/delete sharing.

    This is deliberately stronger than a move-only source handle.  A stage
    remains locked from creation through install; an old public target remains
    locked from authoritative digest capture through backup/restore; and an
    installed public target retains that same lock until commit or rollback is
    settled.  If a pre-existing writer prevents this share contract, opening
    the handle fails before the first public mutation.
    """

    import ctypes
    from ctypes import wintypes

    generic_read = 0x80000000
    generic_write = 0x40000000
    delete = 0x00010000
    file_share_read = 0x00000001
    create_new_disposition = 1
    open_existing = 3
    file_attribute_normal = 0x00000080
    file_flag_open_reparse_point = 0x00200000
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    desired_access = generic_read | delete
    if writable:
        desired_access |= generic_write
    handle = create_file(
        str(path),
        desired_access,
        file_share_read,
        None,
        create_new_disposition if create_new else open_existing,
        file_attribute_normal | file_flag_open_reparse_point,
        None,
    )
    invalid_handle = ctypes.c_void_p(-1).value
    if handle == invalid_handle:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot acquire an exact regular-file content handle (WinError {ctypes.get_last_error()}).",
        )
    return int(handle)


def _atomic_windows_open_regular_handle(path: Path, label: str) -> int:
    """Open one existing exact leaf for a transaction move/delete lifecycle."""

    return _atomic_windows_open_regular_content_handle(
        path,
        label,
        create_new=False,
        writable=False,
    )


def _atomic_windows_open_regular_read_handle(path: Path, label: str) -> int:
    """Open one exact regular leaf for bounded reads without following a reparse point."""

    import ctypes
    from ctypes import wintypes

    generic_read = 0x80000000
    file_read_attributes = 0x00000080
    file_share_read = 0x00000001
    file_share_write = 0x00000002
    open_existing = 3
    file_flag_open_reparse_point = 0x00200000
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    handle = create_file(
        str(path),
        generic_read | file_read_attributes,
        file_share_read | file_share_write,
        None,
        open_existing,
        file_flag_open_reparse_point,
        None,
    )
    invalid_handle = ctypes.c_void_p(-1).value
    if handle == invalid_handle:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot acquire an exact regular-file read handle (WinError {ctypes.get_last_error()}).",
        )
    return int(handle)


def _atomic_windows_write_payload(handle: int, payload: bytes, label: str) -> None:
    """Write and flush bytes through a retained Windows transaction handle."""

    import ctypes
    from ctypes import wintypes

    write_file = ctypes.WinDLL("kernel32", use_last_error=True).WriteFile
    write_file.argtypes = [
        wintypes.HANDLE,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
        ctypes.c_void_p,
    ]
    write_file.restype = wintypes.BOOL
    flush = ctypes.WinDLL("kernel32", use_last_error=True).FlushFileBuffers
    flush.argtypes = [wintypes.HANDLE]
    flush.restype = wintypes.BOOL
    for offset in range(0, len(payload), 8 * 1024):
        chunk = payload[offset : offset + 8 * 1024]
        buffer = ctypes.create_string_buffer(chunk)
        written = wintypes.DWORD(0)
        if not write_file(handle, buffer, len(chunk), ctypes.byref(written), None) or written.value != len(chunk):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} cannot be written through its retained transaction handle (WinError {ctypes.get_last_error()}).",
            )
    if not flush(handle):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be flushed through its retained transaction handle (WinError {ctypes.get_last_error()}).",
        )


def _atomic_windows_digest_handle(handle: int, label: str) -> str:
    """Stream a SHA-256 through one exact Windows handle without reopening it."""

    import ctypes
    from ctypes import wintypes

    class _LargeInteger(ctypes.Structure):
        _fields_ = [("quad_part", ctypes.c_longlong)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    get_size = kernel32.GetFileSizeEx
    get_size.argtypes = [wintypes.HANDLE, ctypes.POINTER(_LargeInteger)]
    get_size.restype = wintypes.BOOL
    set_pointer = kernel32.SetFilePointerEx
    set_pointer.argtypes = [
        wintypes.HANDLE,
        _LargeInteger,
        ctypes.POINTER(_LargeInteger),
        wintypes.DWORD,
    ]
    set_pointer.restype = wintypes.BOOL
    read_file = kernel32.ReadFile
    read_file.argtypes = [
        wintypes.HANDLE,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
        ctypes.c_void_p,
    ]
    read_file.restype = wintypes.BOOL
    size_before = _LargeInteger()
    if not get_size(handle, ctypes.byref(size_before)) or size_before.quad_part < 0:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} size cannot be inspected through its retained transaction handle (WinError {ctypes.get_last_error()}).",
        )
    if not set_pointer(handle, _LargeInteger(0), None, 0):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be rewound through its retained transaction handle (WinError {ctypes.get_last_error()}).",
        )
    digest = hashlib.sha256()
    remaining = int(size_before.quad_part)
    while remaining:
        request = min(8 * 1024, remaining)
        buffer = ctypes.create_string_buffer(request)
        received = wintypes.DWORD(0)
        if not read_file(handle, buffer, request, ctypes.byref(received), None) or received.value != request:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} changed while its retained transaction handle was being hashed.",
            )
        digest.update(buffer.raw[:received.value])
        remaining -= int(received.value)
    size_after = _LargeInteger()
    if not get_size(handle, ctypes.byref(size_after)) or size_after.quad_part != size_before.quad_part:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} changed while its retained transaction handle was being hashed.",
        )
    return digest.hexdigest()


def _atomic_validate_windows_leaf_handle(leaf: _AtomicLeafBinding, label: str) -> None:
    """Verify one retained leaf's exact identity and approved content bytes."""

    if leaf.closed or leaf.windows_handle is None:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} retained regular-file transaction handle was closed too early.",
        )
    information = _atomic_windows_file_information(leaf.windows_handle, label)
    if (
        information.attributes & 0x00000010
        or information.attributes & 0x00000400
        or information.identity != leaf.identity
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} retained regular-file handle no longer denotes its approved non-reparse object.",
        )
    if leaf.expected_digest is not None and _atomic_windows_digest_handle(leaf.windows_handle, label) != leaf.expected_digest:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} content changed while its retained transaction handle was active.",
        )


def _atomic_close_leaf_handle(leaf: _AtomicLeafBinding) -> None:
    """Release one leaf handle only after no automatic move/restore remains."""

    if leaf.closed:
        return
    leaf.closed = True
    if os.name == "nt" and leaf.windows_handle is not None:
        _atomic_windows_close_handle(leaf.windows_handle)
    leaf.windows_handle = None


def _atomic_windows_rename_handle(
    handle: int,
    destination_parent: _AtomicDirectoryLease,
    destination_name: str,
    replace: bool,
    label: str,
) -> None:
    """Move one source handle to a destination held stable by a live directory lease.

    ``SetFileInformationByHandle(FileRenameInfo)`` rejects a non-NULL
    ``RootDirectory`` on supported Windows versions (despite the historical
    structure documentation suggesting otherwise).  The destination therefore
    remains an absolute spelling, but every directory in that spelling is a
    transaction-lifetime no-delete-share lease and was revalidated immediately
    before this call.  Windows cannot rename, replace, or retarget that parent
    chain while its handles remain open; the source object itself is still
    renamed by its exact handle, not by a pathname operation.
    """

    import ctypes
    from ctypes import wintypes

    class _FileRenameInformation(ctypes.Structure):
        _fields_ = [
            ("replace_if_exists", wintypes.BYTE),
            ("root_directory", wintypes.HANDLE),
            ("file_name_length", wintypes.DWORD),
            ("file_name", wintypes.WCHAR * 1),
        ]

    if destination_parent.windows_handle is None:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} has no Windows destination-directory transaction lease.",
        )
    destination = destination_parent.path / destination_name
    encoded = str(destination).encode("utf-16-le")
    # The trailing C array needs room for the terminator even though
    # ``file_name_length`` deliberately excludes it.  The API accepts the
    # complete allocated buffer, including that terminator.
    buffer_size = ctypes.sizeof(_FileRenameInformation) + len(encoded) + ctypes.sizeof(wintypes.WCHAR)
    payload = ctypes.create_string_buffer(buffer_size)
    information = ctypes.cast(payload, ctypes.POINTER(_FileRenameInformation))
    information.contents.replace_if_exists = 1 if replace else 0
    information.contents.root_directory = wintypes.HANDLE(0)
    information.contents.file_name_length = len(encoded)
    ctypes.memmove(ctypes.addressof(payload) + _FileRenameInformation.file_name.offset, encoded, len(encoded))

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    set_information.restype = wintypes.BOOL
    file_rename_information = 3
    if not set_information(handle, file_rename_information, payload, buffer_size):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-002",
            f"{label} handle-anchored replacement failed (WinError {ctypes.get_last_error()}).",
        )


def _atomic_windows_delete_handle(handle: int, label: str) -> None:
    """Mark one exact regular file for delete by handle rather than pathname."""

    import ctypes
    from ctypes import wintypes

    class _FileDispositionInformation(ctypes.Structure):
        _fields_ = [("delete_file", wintypes.BYTE)]

    payload = _FileDispositionInformation(1)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    set_information.restype = wintypes.BOOL
    file_disposition_information = 4
    if not set_information(handle, file_disposition_information, ctypes.byref(payload), ctypes.sizeof(payload)):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-002",
            f"{label} handle-anchored delete failed (WinError {ctypes.get_last_error()}).",
        )


def _atomic_exact_source_publication_available() -> bool:
    """Return whether this host can install/delete verified sources by exact identity.

    Windows uses ``SetFileInformationByHandle`` on an already verified source
    handle while the complete destination parent chain is no-delete-share
    leased.  POSIX ``renameat``/``renameat2`` still re-resolve a source name;
    a dirfd and post-check cannot turn that into exact-object authority, so X5
    intentionally exposes no POSIX publication capability until a complete
    exact-source move *and* rollback/delete primitive is implemented.
    """

    return os.name == "nt"


def _require_atomic_exact_source_publication_capability() -> None:
    """Fail before stage/root/public mutation when exact-source publication is absent."""

    if not _atomic_exact_source_publication_available():
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic publication is unavailable on this POSIX runtime because the verified source file "
            "cannot be moved by exact handle identity; pathname rename fallbacks are disabled before staging.",
        )


def _open_atomic_directory_lease(
    binding: _AtomicDirectoryBinding,
    label: str,
    *,
    parent: _AtomicDirectoryLease | None = None,
    entry_name: str | None = None,
) -> _AtomicDirectoryLease:
    """Acquire and bind one directory lease without following its final reparse point."""

    if os.name == "nt":
        if parent is not None:
            if entry_name is None:
                raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no child entry name.")
            _validate_atomic_named_directory_lease(parent, f"{label} parent")
        handle = _atomic_windows_open_directory_handle(binding.path, label)
        try:
            information = _atomic_windows_file_information(handle, label)
            if not information.attributes & 0x00000010 or information.attributes & 0x00000400:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} is not the approved non-reparse directory object.",
                )
            if information.identity != binding.identity:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} changed identity before its transaction lease was acquired.",
                )
            return _AtomicDirectoryLease(binding, windows_handle=handle, parent=parent, entry_name=entry_name)
        except Exception:
            _atomic_windows_close_handle(handle)
            raise

    try:
        if parent is None:
            before = os.lstat(binding.path)
        else:
            if entry_name is None:
                raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no child entry name.")
            before = _atomic_lstat_child(parent, entry_name, label)
    except X5ToolingError:
        raise
    except FileNotFoundError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} disappeared before its transaction lease was acquired.",
        ) from error
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} disappeared before its transaction lease was acquired.",
        ) from error
    if (
        not stat.S_ISDIR(before.st_mode)
        or _atomic_posix_identity(before) != binding.identity
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} changed identity before its transaction lease was acquired.",
        )
    required_flags = ("O_DIRECTORY", "O_NOFOLLOW")
    if any(not hasattr(os, flag) for flag in required_flags) or not os.supports_dir_fd:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "This platform cannot provide no-follow directory-handle publication safety.",
        )
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    try:
        if parent is None:
            descriptor = os.open(binding.path, flags)
        else:
            if parent.fd is None or entry_name is None:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} has no POSIX parent descriptor for a child transaction lease.",
                )
            descriptor = os.open(entry_name, flags, dir_fd=parent.fd)
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot acquire a no-follow directory transaction lease.",
        ) from error
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or _atomic_posix_identity(metadata) != binding.identity
        ):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} is not the approved directory object after lease acquisition.",
            )
        return _AtomicDirectoryLease(binding, fd=descriptor, parent=parent, entry_name=entry_name)
    except Exception:
        os.close(descriptor)
        raise


def _validate_atomic_directory_lease(lease: _AtomicDirectoryLease, label: str) -> None:
    """Require an open transaction lease to still denote its allocation-time object."""

    if lease.closed:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} transaction lease was closed before the operation completed.",
        )
    if os.name == "nt":
        if lease.windows_handle is None:
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no Windows transaction lease.")
        information = _atomic_windows_file_information(lease.windows_handle, label)
        if (
            not information.attributes & 0x00000010
            or information.attributes & 0x00000400
            or information.identity != lease.binding.identity
        ):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} transaction lease no longer denotes its approved directory object.",
            )
        return
    if lease.fd is None:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no POSIX transaction lease.")
    try:
        metadata = os.fstat(lease.fd)
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} transaction lease cannot be inspected safely.",
        ) from error
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or _atomic_posix_identity(metadata) != lease.binding.identity
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} transaction lease no longer denotes its approved directory object.",
        )


def _close_atomic_directory_lease(lease: _AtomicDirectoryLease) -> None:
    """Close one lease exactly once, even when an earlier failure is in flight."""

    if lease.closed:
        return
    lease.closed = True
    if os.name == "nt":
        if lease.windows_handle is not None:
            _atomic_windows_close_handle(lease.windows_handle)
        return
    if lease.fd is not None:
        try:
            os.close(lease.fd)
        except OSError:
            pass


def _atomic_private_child_directory(
    graph: _AtomicLeaseGraph,
    parent: _AtomicDirectoryLease,
    name: str,
    label: str,
    directories: list[_AtomicDirectoryLease],
) -> _AtomicDirectoryLease:
    """Create and lease one expected private directory, rejecting surprise entries."""

    lease = _atomic_create_child_directory(graph, parent, name, label)
    directories.append(lease)
    return lease


def _atomic_private_parent(
    graph: _AtomicLeaseGraph,
    root_lease: _AtomicDirectoryLease,
    relative: str,
    label: str,
    directories: list[_AtomicDirectoryLease],
    parents: dict[str, _AtomicDirectoryLease],
) -> _AtomicDirectoryLease:
    """Return a leased private parent for one safe output relative path."""

    parent = root_lease
    prefix: list[str] = []
    for part in safe_relative_path(relative, label).split("/")[:-1]:
        prefix.append(part)
        key = "/".join(prefix)
        existing = parents.get(key)
        if existing is None:
            parent = _atomic_private_child_directory(
                graph,
                parent,
                part,
                f"{label} directory {key}",
                directories,
            )
            parents[key] = parent
        else:
            parent = existing
            _validate_atomic_directory_lease(parent, f"{label} directory {key}")
    return parent


def _atomic_existing_regular_leaf(
    parent: _AtomicDirectoryLease,
    name: str,
    relative: str,
    label: str,
    *,
    lock_contents: bool = False,
) -> _AtomicLeafBinding | None:
    """Return a no-follow regular leaf binding, optionally retaining its content lock.

    The locking form is used for every generic bundle stage/backup/public
    leaf.  It cannot coexist with a pre-existing writer/deleter, so that race
    fails before a public target changes.  The non-locking form remains for
    legacy-stage inventory, where the compatibility exporter itself owns the
    temporary pathname until X5 reads its approved bytes.
    """

    _validate_atomic_directory_lease(parent, label)
    try:
        metadata = _atomic_lstat_child(parent, name, label)
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(metadata.st_mode):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} is not an existing regular file.",
        )
    if os.name == "nt":
        handle: int | None = None
        try:
            if lock_contents:
                handle = _atomic_windows_open_regular_content_handle(
                    parent.path / name,
                    label,
                    create_new=False,
                    writable=False,
                )
            else:
                handle = _atomic_windows_open_regular_read_handle(parent.path / name, label)
            information = _atomic_windows_file_information(handle, label)
            if information.attributes & 0x00000010 or information.attributes & 0x00000400:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} is not an existing non-reparse regular file.",
                )
            if lock_contents:
                digest = _atomic_windows_digest_handle(handle, label)
                leaf = _AtomicLeafBinding(relative, parent, name, information.identity, digest, handle)
                handle = None
                return leaf
            return _AtomicLeafBinding(relative, parent, name, information.identity)
        finally:
            if handle is not None:
                _atomic_windows_close_handle(handle)
    return _AtomicLeafBinding(relative, parent, name, _atomic_posix_identity(metadata))


def _atomic_create_private_leaf(
    parent: _AtomicDirectoryLease,
    name: str,
    relative: str,
    payload: bytes,
    label: str,
) -> _AtomicLeafBinding:
    """Create one new private regular file through its leased parent."""

    _validate_atomic_named_directory_lease(parent, label)
    if os.name == "nt":
        handle: int | None = None
        try:
            handle = _atomic_windows_open_regular_content_handle(
                parent.path / name,
                label,
                create_new=True,
                writable=True,
            )
            information = _atomic_windows_file_information(handle, label)
            if information.attributes & 0x00000010 or information.attributes & 0x00000400:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} is not a new non-reparse regular file.",
                )
            _atomic_windows_write_payload(handle, payload, label)
            expected_digest = sha256_bytes(payload)
            if _atomic_windows_digest_handle(handle, label) != expected_digest:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"{label} content changed while its retained transaction handle was active.",
                )
            leaf = _AtomicLeafBinding(relative, parent, name, information.identity, expected_digest, handle)
            handle = None
            return leaf
        except FileExistsError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} unexpectedly already exists in a private transaction directory.",
            ) from error
        finally:
            if handle is not None:
                _atomic_windows_close_handle(handle)
    try:
        if parent.fd is None or os.open not in os.supports_dir_fd:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} cannot create a file with a capability-safe POSIX parent descriptor.",
            )
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
        descriptor = os.open(name, flags, 0o600, dir_fd=parent.fd)
        stream = os.fdopen(descriptor, "wb")
        with stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} unexpectedly already exists in a private transaction directory.",
        ) from error
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be created as a new private file.",
        ) from error
    return _atomic_require_regular_leaf(parent, name, relative, label)


def _atomic_read_private_leaf(
    parent: _AtomicDirectoryLease,
    name: str,
    relative: str,
    maximum_bytes: int,
    code: str,
    label: str,
    tracked_leaves: list[_AtomicLeafBinding],
) -> tuple[_AtomicLeafBinding, bytes]:
    """Open and bounded-read one exact private leaf without a fresh path walk.

    The legacy strict-v1 exporter must receive a stage pathname, but no output
    of that legacy call becomes authoritative until this helper has reopened
    the expected leaf through the stage's transaction lease and verified its
    regular-file identity.  Windows retains a no-delete-share file handle;
    POSIX uses ``openat`` with ``O_NOFOLLOW`` beneath the leased directory fd.
    """

    _validate_atomic_named_directory_lease(parent, label)
    leaf = _atomic_require_regular_leaf(parent, name, relative, label)
    _atomic_track_private_leaf(tracked_leaves, leaf, label)
    if os.name == "nt":
        handle = _atomic_windows_open_regular_read_handle(leaf.path, label)
        descriptor: int | None = None
        try:
            information = _atomic_windows_file_information(handle, label)
            if (
                information.attributes & 0x00000010
                or information.attributes & 0x00000400
                or information.identity != leaf.identity
            ):
                raise X5ToolingError(
                    code,
                    f"{label} changed regular-file identity before its bounded read.",
                )
            import msvcrt

            descriptor = msvcrt.open_osfhandle(handle, os.O_RDONLY | getattr(os, "O_BINARY", 0))
            handle = None  # Ownership transferred to the CRT descriptor.
            stream = os.fdopen(descriptor, "rb")
            descriptor = None
            with stream:
                metadata = os.fstat(stream.fileno())
                if not stat.S_ISREG(metadata.st_mode):
                    raise X5ToolingError(code, f"{label} is not a regular file during its bounded read.")
                return leaf, _read_bounded_stream(stream, metadata.st_size, maximum_bytes, code, label)
        except X5ToolingError:
            raise
        except OSError as error:
            raise X5ToolingError(code, f"{label} cannot be read through its Windows transaction handle.") from error
        finally:
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except OSError:
                    pass
            if handle is not None:
                _atomic_windows_close_handle(handle)

    if parent.fd is None or os.open not in os.supports_dir_fd or not hasattr(os, "O_NOFOLLOW"):
        raise X5ToolingError(
            code,
            f"{label} cannot be read through a capability-safe POSIX parent descriptor.",
        )
    flags = os.O_RDONLY | os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    try:
        descriptor = os.open(name, flags, dir_fd=parent.fd)
    except OSError as error:
        raise X5ToolingError(code, f"{label} cannot be opened through its POSIX transaction lease.") from error
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or _atomic_posix_identity(metadata) != leaf.identity
        ):
            raise X5ToolingError(code, f"{label} changed regular-file identity before its bounded read.")
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = -1
            return leaf, _read_bounded_stream(stream, metadata.st_size, maximum_bytes, code, label)
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(code, f"{label} cannot be read through its POSIX transaction lease.") from error
    finally:
        if descriptor >= 0:
            try:
                os.close(descriptor)
            except OSError:
                pass


def _atomic_track_private_leaf(
    leaves: list[_AtomicLeafBinding],
    leaf: _AtomicLeafBinding,
    label: str,
) -> None:
    """Record one cleanup authority before a bounded read can fail.

    A later identity change must never replace an already-recorded authority:
    the cleanup pass will see the old binding fail to revalidate and retain the
    complete stage for recovery.
    """

    for existing in leaves:
        if existing.relative != leaf.relative:
            continue
        if existing.identity != leaf.identity:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} changed tracked regular-file identity while staging was active.",
            )
        return
    leaves.append(leaf)


def _atomic_inventory_private_outputs_for_cleanup(
    graph: _AtomicLeaseGraph,
    root: _AtomicDirectoryLease,
    expected_relatives: Sequence[str],
    directories: list[_AtomicDirectoryLease],
    parents: dict[str, _AtomicDirectoryLease],
    leaves: list[_AtomicLeafBinding],
    label: str,
) -> None:
    """Best-effort same-lease inventory after a legacy/export-read failure.

    This only extends the exact cleanup set with already-approved regular
    leaves.  Missing entries are harmless; a reparse point, non-directory
    parent, identity drift, or unexpected entry causes the normal tracked
    cleaner to retain the stage rather than using a second pathname walk.
    """

    _validate_atomic_named_directory_lease(root, label)
    for relative in sorted(safe_relative_path(item, f"{label} expected output") for item in expected_relatives):
        try:
            parent = _atomic_expected_private_parent(
                graph,
                root,
                relative,
                directories,
                parents,
                label,
            )
        except FileNotFoundError:
            continue
        name = relative.rsplit("/", 1)[-1]
        leaf = _atomic_existing_regular_leaf(
            parent,
            name,
            relative,
            f"{label} tracked output {relative}",
        )
        if leaf is not None:
            _atomic_track_private_leaf(leaves, leaf, label)


def _atomic_collect_private_outputs(
    graph: _AtomicLeaseGraph,
    root: _AtomicDirectoryLease,
    expected_relatives: Sequence[str],
    directories: list[_AtomicDirectoryLease],
    parents: dict[str, _AtomicDirectoryLease],
    leaves: list[_AtomicLeafBinding],
    label: str,
) -> dict[str, bytes]:
    """Read exactly the approved legacy-stage leaves and reject every extra entry.

    The exporter is allowed to write only a normal stage spelling for legacy
    compatibility.  Traversal back out of that stage is nevertheless entirely
    lease anchored: no ``rglob``, resolution, or recursive cleanup obtains
    authority over an exporter-controlled pathname.
    """

    _validate_atomic_named_directory_lease(root, label)
    normalized = tuple(sorted(safe_relative_path(relative, f"{label} expected output") for relative in expected_relatives))
    if len(set(normalized)) != len(normalized):
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has duplicate expected output names.")

    if not any(directory is root for directory in directories):
        directories.append(root)
    outputs: dict[str, bytes] = {}
    for relative in normalized:
        parent = _atomic_expected_private_parent(
            graph,
            root,
            relative,
            directories,
            parents,
            label,
        )
        name = relative.rsplit("/", 1)[-1]
        leaf, payload = _atomic_read_private_leaf(
            parent,
            name,
            relative,
            MAX_RUNTIME_ARTIFACT_BYTES,
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} output {relative}",
            leaves,
        )
        outputs[relative] = payload

    unique_directories: list[_AtomicDirectoryLease] = []
    seen_directories: set[int] = set()
    for directory in directories:
        if id(directory) not in seen_directories:
            seen_directories.add(id(directory))
            unique_directories.append(directory)
    known_children: dict[int, set[str]] = {id(directory): set() for directory in unique_directories}
    for leaf in leaves:
        known_children.setdefault(id(leaf.parent), set()).add(leaf.name)
    by_path = {directory.path: directory for directory in unique_directories}
    for directory in unique_directories:
        parent = by_path.get(directory.path.parent)
        if parent is not None:
            known_children[id(parent)].add(directory.path.name)
    for directory in unique_directories:
        entries = _atomic_directory_entries(directory, f"{label} directory {directory.path.name}")
        unexpected = set(entries) - known_children.get(id(directory), set())
        if unexpected:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} contains unapproved staged entries.",
            )
    return outputs


def _atomic_require_regular_leaf(
    parent: _AtomicDirectoryLease,
    name: str,
    relative: str,
    label: str,
    expected: _AtomicLeafBinding | None = None,
) -> _AtomicLeafBinding:
    """Bind one current regular file and, when supplied, require its exact identity."""

    if expected is not None and os.name == "nt" and expected.windows_handle is not None:
        _atomic_validate_windows_leaf_handle(expected, label)
        return expected
    leaf = _atomic_existing_regular_leaf(parent, name, relative, label)
    if leaf is None:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} disappeared before its anchored operation.")
    if expected is not None and leaf.identity != expected.identity:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} changed regular-file identity before its anchored operation.",
        )
    return leaf


def _atomic_validate_leaf_binding(leaf: _AtomicLeafBinding, label: str) -> bool:
    """Validate one live leaf without replacing a retained handle by pathname."""

    if os.name == "nt" and leaf.windows_handle is not None:
        _atomic_validate_windows_leaf_handle(leaf, label)
        return True
    current = _atomic_existing_regular_leaf(leaf.parent, leaf.name, leaf.relative, label)
    if current is None:
        return False
    if current.identity != leaf.identity:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} changed regular-file identity before its anchored operation.",
        )
    return True


def _atomic_move_leaf(
    source: _AtomicLeafBinding,
    destination_parent: _AtomicDirectoryLease,
    destination_name: str,
    destination_relative: str,
    *,
    replace: bool,
    label: str,
) -> _AtomicLeafBinding:
    """Move a retained exact source handle without reopening its source pathname."""

    _require_atomic_exact_source_publication_capability()
    _validate_atomic_named_directory_lease(source.parent, f"{label} source parent")
    _validate_atomic_named_directory_lease(destination_parent, f"{label} destination parent")
    _atomic_validate_leaf_binding(source, f"{label} source")
    existing_destination = _atomic_existing_regular_leaf(
        destination_parent,
        destination_name,
        destination_relative,
        f"{label} destination",
    )
    if existing_destination is not None and not replace:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-002",
            f"{label} destination unexpectedly already exists.",
        )
    if os.name != "nt":
        # A test may force the predicate true, but no POSIX implementation is
        # allowed to silently recover the unsafe source-name rename path.
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic publication has no exact-source POSIX move implementation.",
        )
    if source.windows_handle is None or source.closed:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} source has no retained Windows content handle.",
        )
    _atomic_windows_rename_handle(source.windows_handle, destination_parent, destination_name, replace, label)
    # Transfer, rather than close/reopen, the exact source authority.  The
    # moved-from binding remains only as a stale private-cleanup record and
    # cannot accidentally delete the new public name.
    moved = _AtomicLeafBinding(
        destination_relative,
        destination_parent,
        destination_name,
        source.identity,
        source.expected_digest,
        source.windows_handle,
    )
    source.windows_handle = None
    source.closed = True
    return moved


def _atomic_delete_leaf(leaf: _AtomicLeafBinding, label: str) -> bool:
    """Delete only a retained exact file after its identity/content revalidation."""

    _require_atomic_exact_source_publication_capability()
    if os.name != "nt":
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic publication has no exact-source POSIX delete implementation.",
        )
    if leaf.windows_handle is None:
        current = _atomic_existing_regular_leaf(leaf.parent, leaf.name, leaf.relative, label)
        if current is None:
            return False
        if current.identity != leaf.identity:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"{label} changed regular-file identity before anchored deletion.",
            )
        handle = _atomic_windows_open_regular_handle(leaf.path, label)
        leaf.windows_handle = handle
        leaf.closed = False
    try:
        _atomic_validate_windows_leaf_handle(leaf, label)
        _atomic_windows_delete_handle(leaf.windows_handle, label)
    except Exception:
        raise
    else:
        # Windows releases a delete-pending file's directory entry only when
        # the final handle closes.  This is safe now: caller has reached a
        # final cleanup/rollback removal decision for this exact leaf.
        _atomic_close_leaf_handle(leaf)
    return True


def _atomic_directory_entries(lease: _AtomicDirectoryLease, label: str) -> tuple[str, ...]:
    """List one still-leased directory without recursively traversing unknown entries."""

    _validate_atomic_directory_lease(lease, label)
    try:
        if os.name == "nt":
            return tuple(sorted(item.name for item in lease.path.iterdir()))
        if lease.fd is None:
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no POSIX directory descriptor.")
        return tuple(sorted(os.listdir(lease.fd)))
    except X5ToolingError:
        raise
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} cannot be listed safely before cleanup.",
        ) from error


def _atomic_remove_empty_private_directory(lease: _AtomicDirectoryLease, label: str) -> None:
    """Remove only an empty private directory while its exact parent lease is live."""

    _validate_atomic_named_directory_lease(lease, label)
    if _atomic_directory_entries(lease, label):
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} is not empty for anchored cleanup.")
    if os.name == "nt":
        if lease.windows_handle is None:
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", f"{label} has no Windows directory handle.")
        # The lease was opened with DELETE access and without FILE_SHARE_DELETE.
        # Disposition is therefore bound to this exact directory object rather
        # than a spelling that could be recreated after close.
        _atomic_windows_delete_handle(lease.windows_handle, label)
        _close_atomic_directory_lease(lease)
        return
    if lease.parent is None or lease.entry_name is None or lease.parent.fd is None:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            f"{label} has no POSIX parent descriptor for anchored directory cleanup.",
        )
    _validate_atomic_named_directory_lease(lease.parent, f"{label} parent")
    try:
        _TRUSTED_OS_RMDIR(lease.entry_name, dir_fd=lease.parent.fd)
    except OSError as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-002",
            f"{label} anchored empty-directory cleanup failed.",
        ) from error
    _close_atomic_directory_lease(lease)


def _atomic_cleanup_private_tree(
    leaves: Sequence[_AtomicLeafBinding],
    directories: Sequence[_AtomicDirectoryLease],
    label: str,
) -> str | None:
    """Delete only tracked leaves, then non-recursively remove known empty directories.

    In particular this intentionally does not call shutil.rmtree. Any unexpected
    entry, identity change, or race after a lease release is retained for
    recovery instead of recursively deleting a newly substituted tree.
    """

    try:
        unique_directories: list[_AtomicDirectoryLease] = []
        seen_directories: set[int] = set()
        for directory in directories:
            if id(directory) not in seen_directories:
                seen_directories.add(id(directory))
                unique_directories.append(directory)

        # Fail closed before removing even a known leaf when an unexpected
        # sibling is present.  This avoids turning a cleanup pass into a
        # partial recursive delete if somebody plants a marker beneath a
        # private root while the transaction is active.
        known_children: dict[int, set[str]] = {id(directory): set() for directory in unique_directories}
        for leaf in leaves:
            known_children.setdefault(id(leaf.parent), set()).add(leaf.name)
        by_path = {directory.path: directory for directory in unique_directories}
        for directory in unique_directories:
            if directory.path.parent in by_path:
                known_children[id(by_path[directory.path.parent])].add(directory.path.name)
        for directory in unique_directories:
            entries = _atomic_directory_entries(directory, f"{label} directory {directory.path.name}")
            unexpected = set(entries) - known_children.get(id(directory), set())
            if unexpected:
                return f"{label} retained because {directory.path.name} contains untracked entries."

        # Preflight every known leaf before deleting any of them.  If a later
        # leaf was replaced after binding, retain the entire private tree
        # rather than partially deleting earlier siblings first.
        for leaf in leaves:
            try:
                _atomic_validate_leaf_binding(leaf, f"{label} tracked leaf {leaf.relative}")
            except X5ToolingError as error:
                return f"{label} retained: {error.message}"

        for leaf in leaves:
            _atomic_delete_leaf(leaf, f"{label} tracked leaf {leaf.relative}")
        for directory in reversed(unique_directories):
            entries = _atomic_directory_entries(directory, f"{label} directory {directory.path.name}")
            if entries:
                return f"{label} retained because {directory.path.name} still contains tracked entries."
            try:
                _atomic_remove_empty_private_directory(directory, f"{label} directory {directory.path.name}")
            except X5ToolingError as error:
                return f"{label} retained: {error.message}"
        return None
    except X5ToolingError as error:
        return f"{label} retained: {error.message}"


def _atomic_render_recovery_cleanup_details(details: Iterable[str | None]) -> str:
    """Render anchored private-cleanup facts without exposing host path spellings."""

    rendered = "; ".join(sorted({detail for detail in details if detail is not None}))
    if len(rendered) <= MAX_SNAPSHOT_TEXT:
        return rendered
    suffix = "; additional bounded private cleanup details omitted"
    return rendered[:MAX_SNAPSHOT_TEXT - len(suffix)] + suffix


def _validate_atomic_bundle_targets(
    root: Path,
    relatives: tuple[str, ...],
    *,
    approved: _AtomicBundleBindings | None = None,
    approved_claims: tuple[_CanonicalArtifactClaim, ...] | None = None,
    claim_conflict_code: str = "BLENDLIB-X5-ATOMIC-001",
    permit_controlled_directory_extension: bool = False,
) -> _AtomicBundleBindings:
    """Validate every generic bundle target before this writer creates anything.

    Export-plan publication performs a richer owner/kind graph check before
    staging. This companion protects the reusable atomic writer (and therefore
    the direct dev-refresh writer) at its own side-effect boundary. It resolves
    legal in-root aliases first, then uses ``lstat`` on existing nodes so a
    regular file cannot masquerade as a not-yet-created directory.
    """

    if not relatives:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Atomic bundle must contain publication targets.")
    root = _resolved_directory_chain(
        Path(root),
        code="BLENDLIB-X5-ATOMIC-001",
        label="Atomic bundle project root",
    )
    root_identity = _filesystem_identity(root)
    approved_claim_by_relative: dict[str, _ArtifactClaim] = {}
    if approved_claims is not None:
        if (
            type(approved_claims) is not tuple
            or not approved_claims
            or any(type(claim) is not _CanonicalArtifactClaim for claim in approved_claims)
        ):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle approved claim authority is invalid.",
            )
        # Revalidate every frozen claim immediately before we derive generic
        # target bindings.  Without this bridge, an alias retarget between the
        # X5 graph check and generic writer entry could be adopted as a new
        # baseline by the writer itself.
        live_approved_claims = _validate_artifact_graph(
            root,
            approved_claims,
            conflict_code=claim_conflict_code,
        )
        if set(relatives) != {claim.relative for claim in live_approved_claims}:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle output set diverged from its approved claim graph.",
            )
        approved_claim_by_relative = {claim.relative: claim for claim in live_approved_claims}
    approved_by_relative: dict[str, _AtomicBundleTarget] = {}
    if approved is not None:
        if root_identity != approved.root_identity:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle project root changed resolved identity after approval.",
            )
        approved_by_relative = {target.relative: target for target in approved.targets}
        if tuple(sorted(relatives)) != tuple(sorted(approved_by_relative)):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle target set changed after approval.",
            )

    by_identity: dict[str, _AtomicBundleTarget] = {}
    for relative in sorted(relatives):
        live_target = resolve_under(root, relative, "bundle output path")
        identity = _filesystem_identity(live_target)
        approved_claim = approved_claim_by_relative.get(relative)
        if approved_claim is not None and identity != approved_claim.identity:
            raise X5ToolingError(
                claim_conflict_code,
                f"Atomic bundle target {relative} changed resolved identity after X5 approval.",
            )
        approved_target = approved_by_relative.get(relative)
        if approved_target is not None:
            if identity != approved_target.identity:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"Atomic bundle target {relative} changed resolved identity after approval.",
                )
            # Use the physical, pre-approved path for the eventual replacement.
            # Re-resolving the relative spelling after staging would otherwise
            # let a retargeted symlink/junction redirect a refresh or export
            # into a different tree between validation and os.replace.
            target = approved_target.target
            if _filesystem_identity(target) != approved_target.identity:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    f"Atomic bundle target {relative} changed physical identity after approval.",
                )
        else:
            target = live_target
        try:
            target_mode = os.lstat(target).st_mode
        except FileNotFoundError:
            target_mode = None
        except OSError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"Atomic bundle target {relative} cannot be inspected safely.",
            ) from error
        if target_mode is not None and not stat.S_ISREG(target_mode):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"Atomic bundle target {relative} is not an existing regular file.",
            )
        parent = _resolved_directory_chain(
            target.parent,
            code="BLENDLIB-X5-ATOMIC-001",
            label=f"Atomic bundle target {relative}",
        )
        try:
            parent.relative_to(root)
        except ValueError as error:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Atomic bundle target escaped the configured project root.",
            ) from error
        directory_chain = _atomic_directory_chain(root, parent)
        if approved_target is not None and not _atomic_directory_chain_matches(
            approved_target.directory_chain,
            directory_chain,
            permit_controlled_extension=permit_controlled_directory_extension,
        ):
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"Atomic bundle target {relative} changed physical directory identity after approval.",
            )
        binding = _AtomicBundleTarget(relative, identity, target, directory_chain)
        if identity in by_identity:
            prior = by_identity[identity]
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                f"Atomic bundle targets collide at {prior.relative} and {relative}.",
            )
        by_identity[identity] = binding

    ordered = tuple(sorted(by_identity.items()))
    for index, (first_identity, first) in enumerate(ordered):
        for second_identity, second in ordered[index + 1 :]:
            if _identity_contains(first_identity, second_identity) or _identity_contains(second_identity, first_identity):
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    "Atomic bundle targets conflict as a file and parent directory: "
                    f"{first.relative}, {second.relative}.",
                )
    return _AtomicBundleBindings(
        root=root,
        root_identity=root_identity,
        targets=tuple(sorted(by_identity.values(), key=lambda item: item.relative)),
    )


def atomic_write_bundle(
    root: Path,
    outputs: Mapping[str, bytes],
    *,
    replace_func: Callable[[_AtomicFaultEvent], None] | None = None,
    approved_claims: tuple[_CanonicalArtifactClaim, ...] | None = None,
    claim_conflict_code: str = "BLENDLIB-X5-ATOMIC-001",
    approved_bindings: _AtomicBundleBindings | None = None,
    approved_root_binding: _AtomicDirectoryBinding | None = None,
) -> None:
    """Publish a bundle through leased parents and exact source-file operations.

    The optional callback is deliberately a fault observer for tests. It gets
    only a phase and relative output key, never a source or target pathname;
    all actual moves and deletes remain in this module's anchored helpers.
    """

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
    _require_windows_legacy_path_budget(
        root,
        tuple(normalized),
        "Atomic bundle",
        include_legacy_export_stage=False,
    )

    def fault(phase: str, relative: str) -> None:
        if replace_func is not None:
            replace_func(_AtomicFaultEvent(phase, relative))

    leases = _AtomicLeaseGraph()
    root_lease: _AtomicDirectoryLease | None = None
    if (approved_bindings is None) != (approved_root_binding is None):
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic bundle physical approval is incomplete.",
        )
    try:
        if approved_bindings is not None:
            if (
                type(approved_bindings) is not _AtomicBundleBindings
                or type(approved_root_binding) is not _AtomicDirectoryBinding
            ):
                raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Atomic bundle physical approval is invalid.")
            _require_atomic_exact_source_publication_capability()
            # Do not turn a same-spelling recreated root into the writer's
            # baseline.  Open the approval-time object first; all later child
            # work remains rooted in this verified transaction lease.
            root_lease = leases.acquire(approved_root_binding, "Atomic bundle approved project root")
            root = root_lease.path
            bindings = _validate_atomic_bundle_targets(
                root,
                tuple(normalized),
                approved=approved_bindings,
                approved_claims=approved_claims,
                claim_conflict_code=claim_conflict_code,
            )
        else:
            bindings = _validate_atomic_bundle_targets(
                root,
                tuple(normalized),
                approved_claims=approved_claims,
                claim_conflict_code=claim_conflict_code,
            )
            root = bindings.root
            _require_atomic_exact_source_publication_capability()
    except X5ToolingError as error:
        leases.close_all()
        if (
            approved_claims is not None
            or approved_bindings is not None
            or error.message.startswith("Atomic publication is unavailable on this POSIX runtime")
        ):
            raise
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic bundle targets are unsafe before staging.",
        ) from error
    transaction_bindings = approved_bindings or bindings

    stage_directories: list[_AtomicDirectoryLease] = []
    backup_directories: list[_AtomicDirectoryLease] = []
    stage_leaves: dict[str, _AtomicLeafBinding] = {}
    backup_leaves: list[_AtomicLeafBinding] = []
    moved_backups: list[tuple[str, _AtomicLeafLocation, _AtomicLeafBinding]] = []
    installed: list[tuple[_AtomicLeafBinding, bool]] = []
    retained_leaves: list[_AtomicLeafBinding] = []
    public_parent_leases: dict[str, _AtomicDirectoryLease] = {}
    public_controlled_children: dict[tuple[int, str], _AtomicDirectoryLease] = {}
    public_leases: list[_AtomicDirectoryLease] = []
    stage_lease: _AtomicDirectoryLease | None = None
    backup_lease: _AtomicDirectoryLease | None = None
    preserve_stage = False
    preserve_backup = False
    committed = False
    rollback_failure_message: str | None = None
    rollback_failure_cause: Exception | None = None

    def add_public_lease(lease: _AtomicDirectoryLease) -> None:
        current: _AtomicDirectoryLease | None = lease
        while current is not None:
            if not any(current is item for item in public_leases):
                public_leases.append(current)
            if current is root_lease:
                return
            current = current.parent

    def retain_leaf(leaf: _AtomicLeafBinding) -> None:
        if any(existing is leaf for existing in retained_leaves):
            return
        retained_leaves.append(leaf)

    def transfer_retained_leaf(source: _AtomicLeafBinding, moved: _AtomicLeafBinding) -> None:
        for index, existing in enumerate(retained_leaves):
            if existing is source:
                retained_leaves[index] = moved
                return
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Atomic bundle lost the retained source-handle journal before a move.",
        )

    def require_rollback_identity() -> None:
        """Stop every remaining public write as soon as a named lease drifts."""

        nonlocal preserve_backup, preserve_stage
        try:
            _validate_atomic_bundle_targets(
                root,
                tuple(normalized),
                approved=transaction_bindings,
                approved_claims=approved_claims,
                claim_conflict_code=claim_conflict_code,
                permit_controlled_directory_extension=True,
            )
            for lease in public_leases:
                _validate_atomic_named_directory_lease(lease, "Atomic bundle public directory")
            if stage_lease is not None:
                _validate_atomic_named_directory_lease(stage_lease, "Atomic bundle staging directory")
            if backup_lease is not None:
                _validate_atomic_named_directory_lease(backup_lease, "Atomic bundle backup directory")
            for leaf in tuple(retained_leaves):
                if not leaf.closed:
                    _atomic_validate_leaf_binding(leaf, f"Atomic bundle retained leaf {leaf.relative}")
        except X5ToolingError as error:
            replacement_started = bool(moved_backups or installed)
            if replacement_started:
                # Once a public name has changed, an identity fault means no
                # remaining cleanup action has enough authority to decide
                # which staged or backup bytes are disposable.  Retain both
                # private trees for operator recovery instead of trying to be
                # clever about which one is still useful.
                preserve_backup = True
                preserve_stage = True
            if stage_lease is not None:
                try:
                    _validate_atomic_named_directory_lease(stage_lease, "Atomic bundle staging directory")
                except X5ToolingError:
                    preserve_stage = True
            if backup_lease is not None:
                try:
                    _validate_atomic_named_directory_lease(backup_lease, "Atomic bundle backup directory")
                except X5ToolingError:
                    preserve_backup = True
            if not replacement_started:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    "Atomic target or private-directory identity changed before public replacement; "
                    "publication was not started.",
                ) from error
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-002",
                "Atomic target or private-directory identity changed after replacement began; automatic "
                "rollback was withheld to avoid following an unapproved path.",
            ) from error

    try:
        try:
            if root_lease is None:
                root_lease = _atomic_lease_directory_path(
                    leases,
                    root,
                    "Atomic bundle project root",
                    create_missing=True,
                )
                # A direct generic write may legally create its project root.
                # Convert the approval-time empty root chains to the exact
                # object just created/opened before any strict revalidation;
                # if an already-bound root was replaced meanwhile this helper
                # fails rather than treating the new path object as approved.
                transaction_bindings = _atomic_rebase_bundle_bindings_after_root_creation(
                    transaction_bindings,
                    root_lease.binding,
                    "Atomic bundle project root",
                )
            else:
                _validate_atomic_named_directory_lease(root_lease, "Atomic bundle approved project root")
            _validate_atomic_bundle_targets(
                root,
                tuple(normalized),
                approved=transaction_bindings,
                approved_claims=approved_claims,
                claim_conflict_code=claim_conflict_code,
            )
            add_public_lease(root_lease)
            stage_lease = _atomic_allocate_private_directory(
                leases,
                root_lease,
                _ATOMIC_STAGE_PREFIX,
                "Atomic bundle staging directory",
            )
            stage_directories.append(stage_lease)
            fault("after-stage-allocation", "")
            stage_parents: dict[str, _AtomicDirectoryLease] = {}
            for relative, payload in normalized.items():
                _validate_atomic_named_directory_lease(stage_lease, "Atomic bundle staging directory")
                parent = _atomic_private_parent(
                    leases,
                    stage_lease,
                    relative,
                    "staged output path",
                    stage_directories,
                    stage_parents,
                )
                name = relative.rsplit("/", 1)[-1]
                stage_leaf = _atomic_create_private_leaf(
                    parent,
                    name,
                    relative,
                    payload,
                    f"Staged output {relative}",
                )
                stage_leaves[relative] = stage_leaf
                retain_leaf(stage_leaf)
                _atomic_validate_leaf_binding(stage_leaf, f"Staged output {relative}")

            _validate_atomic_bundle_targets(
                root,
                tuple(normalized),
                approved=transaction_bindings,
                approved_claims=approved_claims,
                claim_conflict_code=claim_conflict_code,
            )
            for binding in transaction_bindings.targets:
                target_parent = _atomic_public_parent(
                    leases,
                    root_lease,
                    binding,
                    f"Atomic bundle output parent for {binding.relative}",
                    public_controlled_children,
                )
                public_parent_leases[binding.relative] = target_parent
                add_public_lease(target_parent)

            backup_lease = _atomic_allocate_private_directory(
                leases,
                root_lease,
                _ATOMIC_BACKUP_PREFIX,
                "Atomic bundle backup directory",
            )
            backup_directories.append(backup_lease)
            fault("after-backup-allocation", "")
            backup_parents: dict[str, _AtomicDirectoryLease] = {}
            # The final private allocation is an externally observable boundary.
            # Re-read the approved relative spellings before the first source
            # handle is opened, even though later moves use frozen physical
            # parents. This keeps alias retargeting fail-closed rather than
            # silently accepting an input namespace change.
            _validate_atomic_bundle_targets(
                root,
                tuple(normalized),
                approved=transaction_bindings,
                approved_claims=approved_claims,
                claim_conflict_code=claim_conflict_code,
                permit_controlled_directory_extension=True,
            )
            require_rollback_identity()

            # Lock every currently published leaf before changing even the
            # first target.  A later bundle member must not discover an
            # incompatible external writer only after an earlier member has
            # been displaced; the content handle itself becomes the
            # authoritative old-byte source for the complete transaction.
            locked_old_leaves: dict[str, _AtomicLeafBinding] = {}
            for binding in transaction_bindings.targets:
                relative = binding.relative
                target_parent = public_parent_leases[relative]
                old_leaf = _atomic_existing_regular_leaf(
                    target_parent,
                    binding.target.name,
                    relative,
                    f"Atomic bundle target {relative}",
                    lock_contents=True,
                )
                if old_leaf is not None:
                    retain_leaf(old_leaf)
                    _atomic_validate_leaf_binding(old_leaf, f"Atomic bundle target {relative}")
                    locked_old_leaves[relative] = old_leaf
            require_rollback_identity()

            for binding in transaction_bindings.targets:
                require_rollback_identity()
                relative = binding.relative
                target_parent = public_parent_leases[relative]
                target_name = binding.target.name
                old_leaf = locked_old_leaves.get(relative)
                if old_leaf is not None:
                    backup_parent = _atomic_private_parent(
                        leases,
                        backup_lease,
                        relative,
                        "backup output path",
                        backup_directories,
                        backup_parents,
                    )
                    fault("before-backup", relative)
                    require_rollback_identity()
                    backed_up = _atomic_move_leaf(
                        old_leaf,
                        backup_parent,
                        target_name,
                        relative,
                        replace=False,
                        label=f"Atomic backup move for {relative}",
                    )
                    transfer_retained_leaf(old_leaf, backed_up)
                    _atomic_validate_leaf_binding(backed_up, f"Atomic backup move for {relative}")
                    original_target = _AtomicLeafLocation(
                        relative,
                        target_parent,
                        target_name,
                        old_leaf.identity,
                    )
                    moved_backups.append((relative, original_target, backed_up))
                    backup_leaves.append(backed_up)
                    fault("after-backup", relative)

                fault("before-install", relative)
                require_rollback_identity()
                published = _atomic_move_leaf(
                    stage_leaves[relative],
                    target_parent,
                    target_name,
                    relative,
                    replace=False,
                    label=f"Atomic staged install for {relative}",
                )
                transfer_retained_leaf(stage_leaves[relative], published)
                _atomic_validate_leaf_binding(published, f"Atomic staged install for {relative}")
                installed.append((published, old_leaf is not None))
                fault("after-install", relative)
        except X5ToolingError as error:
            if not moved_backups and not installed:
                raise
            failure: Exception = error
        except Exception as error:
            if not moved_backups and not installed:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    "Atomic bundle failed before public replacement began.",
                ) from error
            failure = error
        else:
            require_rollback_identity()
            committed = True
            return

        rollback_errors: list[str] = []
        try:
            require_rollback_identity()
            for published, existed in reversed(installed):
                require_rollback_identity()
                # Whether the target was newly created or displaced an older
                # one, remove this exact installed handle first.  Keeping it
                # open would make a backup restore's replacement pathname
                # depend on a second writer/handle; the authoritative old
                # backup remains locked and recoverable until this succeeds.
                fault("before-remove", published.relative)
                _atomic_delete_leaf(published, f"Atomic rollback new target {published.relative}")
                fault("after-remove", published.relative)

            for relative, original_target, backup_leaf in reversed(moved_backups):
                require_rollback_identity()
                current = _atomic_existing_regular_leaf(
                    original_target.parent,
                    original_target.name,
                    relative,
                    f"Atomic rollback target {relative}",
                )
                if current is not None:
                    raise X5ToolingError(
                        "BLENDLIB-X5-ATOMIC-002",
                        "Atomic rollback target remained occupied after exact installed-file removal; automatic rollback was withheld.",
                    ) from failure
                try:
                    fault("before-restore", relative)
                except Exception:
                    # A pathless test observer can request one deterministic
                    # restore failure without changing any resolver authority.
                    # Preserve that leaf's backup, but keep restoring unrelated
                    # already-approved targets whose leases remain valid.
                    rollback_errors.append(relative)
                    continue
                restored = _atomic_move_leaf(
                    backup_leaf,
                    original_target.parent,
                    original_target.name,
                    relative,
                    replace=False,
                    label=f"Atomic rollback restore for {relative}",
                )
                transfer_retained_leaf(backup_leaf, restored)
                _atomic_validate_leaf_binding(restored, f"Atomic rollback restore for {relative}")
                if restored.identity != original_target.identity:
                    raise X5ToolingError(
                        "BLENDLIB-X5-ATOMIC-002",
                        "Atomic rollback restore did not retain the approved prior-file identity.",
                    ) from failure
                try:
                    fault("after-restore", relative)
                except Exception:
                    # The move has already completed and its exact file-id was
                    # checked. An observer failure cannot turn that recovered
                    # target into an unknown source or force a second move.
                    pass
            require_rollback_identity()
        except X5ToolingError as error:
            preserve_backup = True
            if "rollback was withheld" in error.message or "identity changed during rollback" in error.message:
                raise
            rollback_errors.append(error.message)
        except Exception as error:
            preserve_backup = True
            rollback_errors.append(type(error).__name__)

        if rollback_errors:
            preserve_backup = True
            backup_name = backup_lease.path.name if backup_lease is not None else "unavailable"
            rollback_failure_message = (
                "Rollback restoration was incomplete or uncertain; any remaining project-relative backup bytes "
                f"were preserved in {backup_name}."
            )
        else:
            rollback_failure_message = "Export replacement failed; all affected targets were restored from staging backups."
        rollback_failure_cause = failure
    finally:
        stage_cleanup: str | None = None
        backup_cleanup: str | None = None
        if stage_lease is not None and not preserve_stage:
            stage_cleanup = _atomic_cleanup_private_tree(
                tuple(stage_leaves.values()),
                stage_directories,
                "Atomic staging directory",
            )
        if backup_lease is not None and not preserve_backup:
            backup_cleanup = _atomic_cleanup_private_tree(
                tuple(backup_leaves),
                backup_directories,
                "Atomic backup directory",
            )
        # Leaf disposition can only complete once its source handle is closed.
        # Release leaves before their parent directory leases so a committed
        # public target never remains write/delete locked after this call.
        for leaf in reversed(retained_leaves):
            _atomic_close_leaf_handle(leaf)
        leases.close_all()
        if stage_cleanup is not None or backup_cleanup is not None:
            details = _atomic_render_recovery_cleanup_details((stage_cleanup, backup_cleanup))
            if rollback_failure_message is not None:
                rollback_failure_message += " Private transaction cleanup was retained for recovery: " + details
            elif committed:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-002",
                    "Publication completed but private transaction cleanup was retained for recovery: " + details,
                )

    if rollback_failure_message is not None:
        rollback_error = X5ToolingError("BLENDLIB-X5-ATOMIC-002", rollback_failure_message)
        if rollback_failure_cause is None:
            raise rollback_error
        raise rollback_error from rollback_failure_cause


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
    result_snapshot, result_state = _trusted_preflight_snapshot_state(result)
    authority_values = _snapshot_authority_values(result_snapshot)
    diagnostics = list(_diagnostics_from_records(result_state.diagnostic_records))
    if not any(item.severity == "ERROR" for item in diagnostics):
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
    snapshot = _new_frozen_snapshot(authority_values, ordered, trusted=True)
    return _new_preflight_result(snapshot)


def _frozen_option_record(options: _FrozenLegacyOptions) -> tuple[Any, ...]:
    """Return the presentation fields whose object identities are sentinel values."""

    return (
        options.blend_path,
        options.project_root,
        options.namespace,
        options.model_id,
        options.profile,
        options.collection_name,
        options.output_resource_root,
        options.report_path,
        options.authoring_output_root,
        options.dev_refresh_path,
        options.dev_session_token,
        options.dev_generation,
        options.batch_manifest_path,
        options.texture_source_roots,
    )


def _claim_records(claims: Sequence[_ArtifactClaim]) -> tuple[tuple[str, str, str, str, str], ...]:
    return tuple(
        (claim.relative, claim.identity, claim.kind, claim.owner, claim.payload_group)
        for claim in claims
    )


def _canonical_export_options(options: _FrozenLegacyOptions) -> _CanonicalLegacyOptions:
    """Copy one approved option record into exact builtin-only private state."""

    if type(options) is not _FrozenLegacyOptions:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan options are not an exact frozen record.")
    fields = _frozen_option_record(options)
    (
        blend_path,
        project_root,
        namespace,
        model_id,
        profile,
        collection_name,
        output_resource_root,
        report_path,
        authoring_output_root,
        dev_refresh_path,
        dev_session_token,
        dev_generation,
        batch_manifest_path,
        texture_source_roots,
    ) = fields
    if (
        type(blend_path) is not _PATH_TYPE
        or type(project_root) is not _PATH_TYPE
        or type(namespace) is not str
        or type(model_id) is not str
        or type(profile) is not str
        or (collection_name is not None and type(collection_name) is not str)
        or type(output_resource_root) is not str
        or (report_path is not None and type(report_path) is not _PATH_TYPE)
        or type(authoring_output_root) is not str
        or (dev_refresh_path is not None and type(dev_refresh_path) is not _PATH_TYPE)
        or (dev_session_token is not None and type(dev_session_token) is not str)
        or (dev_generation is not None and type(dev_generation) is not int)
        or (batch_manifest_path is not None and type(batch_manifest_path) is not _PATH_TYPE)
        or type(texture_source_roots) is not tuple
        or any(type(root) is not _PATH_TYPE for root in texture_source_roots)
    ):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan options lost their exact builtin field types.")
    return _CanonicalLegacyOptions(
        blend_path=str(blend_path),
        project_root=str(project_root),
        namespace=str(namespace),
        model_id=str(model_id),
        profile=str(profile),
        collection_name=None if collection_name is None else str(collection_name),
        output_resource_root=str(output_resource_root),
        report_path=None if report_path is None else str(report_path),
        authoring_output_root=str(authoring_output_root),
        dev_refresh_path=None if dev_refresh_path is None else str(dev_refresh_path),
        dev_session_token=None if dev_session_token is None else str(dev_session_token),
        dev_generation=dev_generation,
        batch_manifest_path=None if batch_manifest_path is None else str(batch_manifest_path),
        texture_source_roots=tuple(str(root) for root in texture_source_roots),
    )


def _canonical_export_claims(
    claims: Sequence[_ArtifactClaim],
) -> tuple[_CanonicalArtifactClaim, ...]:
    """Copy the presentation graph into exact builtin-only canonical records."""

    if type(claims) is not tuple or not claims:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan claims are not an exact non-empty tuple.")
    canonical: list[_CanonicalArtifactClaim] = []
    for claim in claims:
        if type(claim) is not _ArtifactClaim:
            raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan claims are not exact immutable records.")
        record = (claim.relative, claim.identity, claim.kind, claim.owner, claim.payload_group)
        if any(type(value) is not str for value in record):
            raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan claim fields lost their exact builtin string types.")
        canonical.append(_CanonicalArtifactClaim(*(str(value) for value in record)))
    return tuple(canonical)


def _execution_options_from_canonical(options: _CanonicalLegacyOptions) -> _FrozenLegacyOptions:
    """Materialize Path-only legacy inputs from private canonical plan state."""

    if type(options) is not _CanonicalLegacyOptions or (
        type(options.blend_path) is not str
        or type(options.project_root) is not str
        or type(options.namespace) is not str
        or type(options.model_id) is not str
        or type(options.profile) is not str
        or (options.collection_name is not None and type(options.collection_name) is not str)
        or type(options.output_resource_root) is not str
        or (options.report_path is not None and type(options.report_path) is not str)
        or type(options.authoring_output_root) is not str
        or (options.dev_refresh_path is not None and type(options.dev_refresh_path) is not str)
        or (options.dev_session_token is not None and type(options.dev_session_token) is not str)
        or (options.dev_generation is not None and type(options.dev_generation) is not int)
        or (options.batch_manifest_path is not None and type(options.batch_manifest_path) is not str)
        or type(options.texture_source_roots) is not tuple
        or any(type(root) is not str for root in options.texture_source_roots)
    ):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan canonical options are invalid.")
    return _FrozenLegacyOptions(
        blend_path=Path(options.blend_path),
        project_root=Path(options.project_root),
        namespace=options.namespace,
        model_id=options.model_id,
        profile=options.profile,
        collection_name=options.collection_name,
        output_resource_root=options.output_resource_root,
        report_path=None if options.report_path is None else Path(options.report_path),
        authoring_output_root=options.authoring_output_root,
        dev_refresh_path=None if options.dev_refresh_path is None else Path(options.dev_refresh_path),
        dev_session_token=options.dev_session_token,
        dev_generation=options.dev_generation,
        batch_manifest_path=None if options.batch_manifest_path is None else Path(options.batch_manifest_path),
        texture_source_roots=tuple(Path(root) for root in options.texture_source_roots),
    )


def _validate_canonical_plan_graph(
    claims: tuple[_CanonicalArtifactClaim, ...],
    default_report_relative: str,
    explicit_report_relative: str | None,
    refresh_relative: str | None,
    runtime_relatives: tuple[str, ...],
) -> None:
    """Keep scalar plan fields bound to the complete canonical claim multiset."""

    if (
        type(claims) is not tuple
        or not claims
        or any(
            type(claim) is not _CanonicalArtifactClaim
            or any(
                type(value) is not str
                for value in (claim.relative, claim.identity, claim.kind, claim.owner, claim.payload_group)
            )
            for claim in claims
        )
    ):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical export-plan claims are invalid.")
    if (
        type(default_report_relative) is not str
        or (explicit_report_relative is not None and type(explicit_report_relative) is not str)
        or (refresh_relative is not None and type(refresh_relative) is not str)
        or type(runtime_relatives) is not tuple
        or any(type(relative) is not str for relative in runtime_relatives)
    ):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical export-plan path fields are invalid.")
    by_kind: dict[str, list[_CanonicalArtifactClaim]] = {}
    for claim in claims:
        by_kind.setdefault(claim.kind, []).append(claim)
    sidecars = by_kind.get("sidecar", [])
    default_reports = by_kind.get("default-report", [])
    explicit_reports = by_kind.get("explicit-report", [])
    refreshes = by_kind.get("dev-refresh", [])
    if len(sidecars) != 1 or len(default_reports) != 1:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical export plan is missing a unique sidecar or default report claim.")
    if default_reports[0].relative != default_report_relative:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical default-report binding changed.")
    if (explicit_report_relative is None) != (len(explicit_reports) == 0):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical explicit-report binding changed.")
    if explicit_report_relative is not None and (
        len(explicit_reports) != 1 or explicit_reports[0].relative != explicit_report_relative
    ):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical explicit-report binding changed.")
    if (refresh_relative is None) != (len(refreshes) == 0):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical dev-refresh binding changed.")
    if refresh_relative is not None and (len(refreshes) != 1 or refreshes[0].relative != refresh_relative):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical dev-refresh binding changed.")
    authoring_kinds = {"sidecar", "default-report", "explicit-report", "dev-refresh"}
    runtime_claim_relatives = tuple(sorted(claim.relative for claim in claims if claim.kind not in authoring_kinds))
    if tuple(sorted(runtime_relatives)) != runtime_claim_relatives:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Canonical runtime artifact multiset changed.")


def _publication_record_multiset(
    claims: tuple[_CanonicalArtifactClaim, ...],
    payloads: Mapping[str, bytes],
) -> tuple[_CanonicalPublicationRecord, ...]:
    """Bind every output byte string to its full approved artifact record.

    A default and explicit report may legally be two Windows spellings of the
    same physical file.  The publication map contains one canonical writer
    key (the default-report spelling), while the returned records deliberately
    retain both semantic claim roles and bind them to the same bytes.
    """

    if type(payloads) is not dict:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Prepared publication payload map is invalid.")
    claims_by_identity: dict[str, list[_CanonicalArtifactClaim]] = {}
    for claim in claims:
        claims_by_identity.setdefault(claim.identity, []).append(claim)
    canonical_output_by_identity: dict[str, _CanonicalArtifactClaim] = {}
    for identity, grouped_claims in claims_by_identity.items():
        ordered_claims = tuple(sorted(grouped_claims, key=lambda item: (item.owner, item.kind, item.relative)))
        primary = ordered_claims[0]
        for alias in ordered_claims[1:]:
            if not _report_deduplication_is_safe(primary, alias):
                raise X5ToolingError(
                    "BLENDLIB-X5-SNAPSHOT-001",
                    "Prepared publication claims contain an unapproved filesystem alias.",
                )
        canonical_output_by_identity[identity] = primary
    expected_output_relatives = tuple(sorted(
        claim.relative for claim in canonical_output_by_identity.values()
    ))
    if tuple(sorted(payloads)) != expected_output_relatives:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Prepared outputs diverged from the approved artifact graph.")
    records: list[_CanonicalPublicationRecord] = []
    for claim in claims:
        primary = canonical_output_by_identity[claim.identity]
        payload = payloads.get(primary.relative)
        if type(payload) is not bytes:
            raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Prepared output payload type is invalid.")
        records.append(_CanonicalPublicationRecord(
            claim.owner,
            claim.kind,
            claim.payload_group,
            claim.relative,
            claim.identity,
            payload,
        ))
    return tuple(sorted(records))


def _freeze_export_options(options: Any) -> _FrozenLegacyOptions:
    """Copy only approved export inputs before any staging directory exists."""

    try:
        raw_project_root = Path(getattr(options, "project_root")).expanduser()
        _reject_windows_extended_project_root(raw_project_root)
        project_root = raw_project_root.resolve()
        blend_path = Path(getattr(options, "blend_path")).expanduser().resolve()
    except (OSError, TypeError, ValueError) as error:
        raise X5ToolingError("BLENDLIB-X5-PATH-004", "Export paths cannot be resolved safely before publication.") from error
    namespace = _require_namespace(getattr(options, "namespace", None))
    model_id = _require_resource_token(getattr(options, "model_id", None), "model id")
    profile = getattr(options, "profile", None)
    if profile not in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
        raise X5ToolingError("BLENDLIB-X5-PROFILE-001", "X5 requires an existing strict-v1 profile.")
    collection_name = getattr(options, "collection_name", None)
    if collection_name is not None and (not isinstance(collection_name, str) or not collection_name):
        raise X5ToolingError("BLENDLIB-X5-PATH-002", "Collection name must be a non-empty string when supplied.")
    output_root = safe_relative_path(getattr(options, "output_resource_root", None), "output resource root")
    authoring_root = safe_relative_path(getattr(options, "authoring_output_root", "build/blendlib-authoring"), "authoring output root")
    return _FrozenLegacyOptions(
        blend_path=blend_path,
        project_root=project_root,
        namespace=namespace,
        model_id=model_id,
        profile=profile,
        collection_name=collection_name,
        output_resource_root=output_root,
        report_path=None,
        authoring_output_root=authoring_root,
        dev_refresh_path=None,
        dev_session_token=getattr(options, "dev_session_token", None),
        dev_generation=getattr(options, "dev_generation", None),
        batch_manifest_path=None,
        texture_source_roots=(),
    )


def _strict_v1_artifact_paths(
    options: _FrozenLegacyOptions,
    material_names: Sequence[str],
) -> dict[str, str]:
    """Delegate artifact naming to the strict-v1 exporter without copying it."""

    try:
        from . import blendlib_exporter as naming_exporter  # type: ignore
    except ImportError:
        import blendlib_exporter as naming_exporter  # type: ignore
    helper = naming_exporter.strict_v1_artifact_paths
    try:
        paths = helper(options, material_names)
    except X5ToolingError:
        raise
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-ATOMIC-001",
            "Strict-v1 artifact naming failed before X5 staging.",
        ) from error
    if not isinstance(paths, dict) or not paths:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Strict-v1 artifact naming returned no publication paths.")
    return paths


def _material_names_from_snapshot(snapshot: Mapping[str, Any]) -> tuple[str, ...]:
    names: list[str] = []
    for material in snapshot.get("materials", ()):
        if not isinstance(material, Mapping):
            raise X5ToolingError("BLENDLIB-X5-PREFLIGHT-001", "Preflight material records lost their immutable mapping form.")
        name = material.get("name")
        if not isinstance(name, str) or not name:
            raise X5ToolingError("BLENDLIB-X5-PREFLIGHT-001", "Preflight material names must be non-empty strings.")
        names.append(name)
    return tuple(sorted(names))


def _single_claim(
    claims: Sequence[_ArtifactClaim | _CanonicalArtifactClaim],
    kind: str,
) -> _ArtifactClaim | _CanonicalArtifactClaim:
    matches = tuple(claim for claim in claims if claim.kind == kind)
    if len(matches) != 1:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", f"Approved export plan is missing exactly one {kind} claim.")
    return matches[0]


def _decode_approved_sidecar(payload: bytes) -> Mapping[str, Any]:
    try:
        sidecar = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Approved authoring sidecar bytes are invalid.") from error
    if not isinstance(sidecar, Mapping):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Approved authoring sidecar must be a JSON object.")
    return sidecar


def _build_export_plan(
    options: Any,
    *,
    preflight: PreflightResult | None = None,
    conflict_code: str = "BLENDLIB-X5-PATH-004",
    enforce_windows_path_budget: bool = True,
) -> _FrozenExportPlan:
    """Approve every publication target before stage creation or legacy export.

    The resulting plan is registered by identity and carries no mutable caller
    object.  Single export and batch export both consume this exact plan.
    """

    preflight = preflight or preflight_blender(options)
    snapshot, snapshot_state = _trusted_preflight_snapshot_state(preflight)
    if snapshot_state.first_error_record is not None:
        raise X5ToolingError(
            "BLENDLIB-X5-PREFLIGHT-001",
            _render_diagnostics(_diagnostics_from_records(snapshot_state.diagnostic_records)),
        )
    authority_values = _snapshot_authority_values(snapshot)

    frozen_options = _freeze_export_options(options)
    publication_root = frozen_options.project_root
    require_non_runtime_output(
        publication_root,
        frozen_options.output_resource_root,
        frozen_options.authoring_output_root,
        "authoring output root",
    )
    sidecar = build_authoring_sidecar(snapshot)
    sidecar_payload = _bounded_canonical_json_bytes(
        sidecar,
        MAX_REPORT_BYTES,
        "BLENDLIB-X5-SIDECAR-002",
        "Authoring sidecar",
    )
    owner = f"{frozen_options.namespace}:{frozen_options.model_id}"
    sidecar_relative = require_non_runtime_output(
        publication_root,
        frozen_options.output_resource_root,
        f"{frozen_options.authoring_output_root}/{frozen_options.namespace}/{frozen_options.model_id}.blendlib-authoring.json",
        "authoring sidecar path",
    )
    default_report_relative = require_non_runtime_output(
        publication_root,
        frozen_options.output_resource_root,
        f"{frozen_options.authoring_output_root}/{frozen_options.namespace}/{frozen_options.model_id}.asset-report.json",
        "authoring report path",
    )
    explicit_report_relative: str | None = None
    explicit_report = getattr(options, "report_path", None)
    if explicit_report is not None:
        explicit_report_relative = require_non_runtime_output(
            publication_root,
            frozen_options.output_resource_root,
            _relative_project_path(publication_root, explicit_report, "explicit report path"),
            "explicit report path",
        )

    refresh_relative: str | None = None
    refresh_path = getattr(options, "dev_refresh_path", None)
    if refresh_path is not None:
        refresh_relative = require_non_runtime_output(
            publication_root,
            frozen_options.output_resource_root,
            _relative_project_path(publication_root, refresh_path, "dev refresh path"),
            "dev refresh path",
        )
        RefreshMessage(
            session_token=_text(frozen_options.dev_session_token, "dev session token"),
            generation=_integer(frozen_options.dev_generation, "dev generation"),
            artifact_hashes={sidecar_relative: "0" * 64},
            model_key=owner,
        )

    material_names = _material_names_from_snapshot(authority_values)
    runtime_paths = _strict_v1_artifact_paths(frozen_options, material_names)
    runtime_relatives = tuple(sorted(runtime_paths.values()))

    claims: list[_ArtifactClaim] = [
        _artifact_claim(
            publication_root,
            relative,
            kind,
            owner,
            f"runtime:{owner}:{kind}",
        )
        for kind, relative in sorted(runtime_paths.items())
    ]
    claims.append(_artifact_claim(publication_root, sidecar_relative, "sidecar", owner, f"sidecar:{owner}"))
    claims.append(_artifact_claim(
        publication_root,
        default_report_relative,
        "default-report",
        owner,
        f"asset-report:{owner}",
    ))
    if explicit_report_relative is not None:
        claims.append(_artifact_claim(
            publication_root,
            explicit_report_relative,
            "explicit-report",
            owner,
            f"asset-report:{owner}",
        ))
    if refresh_relative is not None:
        claims.append(_artifact_claim(
            publication_root,
            refresh_relative,
            "dev-refresh",
            owner,
            f"dev-refresh:{owner}",
        ))

    unique_claims = _validate_artifact_graph(
        publication_root,
        claims,
        conflict_code=conflict_code,
    )
    if enforce_windows_path_budget:
        _require_windows_legacy_path_budget(
            publication_root,
            tuple(claim.relative for claim in unique_claims),
            "X5 publication",
            include_legacy_export_stage=True,
        )
    root_route = _atomic_capture_root_route(
        publication_root,
        "X5 publication project root",
        code=conflict_code,
    )
    approved_claims = tuple(sorted(claims, key=lambda item: (item.identity, item.owner, item.kind, item.relative)))
    bundle_bindings = _validate_atomic_bundle_targets(
        publication_root,
        tuple(sorted(claim.relative for claim in unique_claims)),
    )
    expected_artifacts = {relative: b"" for relative in runtime_relatives}
    expected_artifacts[sidecar_relative] = sidecar_payload
    report_preview = build_asset_report(
        snapshot=snapshot,
        sidecar=sidecar,
        validation={
            "index_count": MAX_SIGNED_64,
            "material_names": material_names,
            "vertex_count": MAX_SIGNED_64,
        },
        artifacts=expected_artifacts,
        diagnostics=(),
    )
    asset_report_bytes(report_preview)
    if refresh_relative is not None:
        preview_hashes = {
            claim.relative: "0" * 64
            for claim in unique_claims
            if claim.kind != "dev-refresh"
        }
        refresh_message_bytes(RefreshMessage(
            session_token=_text(frozen_options.dev_session_token, "dev session token"),
            generation=_integer(frozen_options.dev_generation, "dev generation"),
            artifact_hashes=preview_hashes,
            model_key=owner,
        ))
    exporter = _legacy_exporter()
    try:
        texture_roots = tuple(Path(root).resolve() for root in exporter._authorized_texture_roots(options))
    except X5ToolingError:
        raise
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-PATH-004",
            "Texture source roots cannot be frozen safely before publication.",
        ) from error
    if not texture_roots:
        raise X5ToolingError("BLENDLIB-X5-PATH-004", "At least one authorized texture source root is required.")
    frozen_options = dataclasses.replace(frozen_options, texture_source_roots=texture_roots)
    return _new_frozen_export_plan(
        snapshot,
        frozen_options,
        approved_claims,
        sidecar_payload,
        default_report_relative,
        explicit_report_relative,
        refresh_relative,
        runtime_relatives,
        root_route,
        bundle_bindings,
    )


def x5_export_open_blend(options: Any) -> dict[str, Any]:
    """One-click X5 export: approve graph, stage, atomically publish, refresh."""

    plan = _build_export_plan(options)
    prepared = _prepare_x5_export(options, plan=plan)
    _, frozen_options, plan_state = _trusted_export_plan_state(plan)
    if prepared.root_binding is None or prepared.bundle_bindings is None:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Prepared X5 export lost its physical publication approval.")
    _validate_artifact_graph(
        frozen_options.project_root,
        plan_state.claims,
        conflict_code="BLENDLIB-X5-PATH-004",
    )
    atomic_write_bundle(
        frozen_options.project_root,
        prepared.outputs,
        approved_claims=plan_state.claims,
        claim_conflict_code="BLENDLIB-X5-PATH-004",
        approved_bindings=prepared.bundle_bindings,
        approved_root_binding=prepared.root_binding,
    )
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
    """Preflight every item, approve one graph, then stage/publish atomically."""

    ordered = plan_batch(items)
    if not ordered:
        raise X5ToolingError("BLENDLIB-X5-BATCH-001", "Batch manifests must contain at least one export item.")
    preflighted: list[tuple[Any, PreflightResult]] = []
    for item in ordered:
        item_options = dataclasses.replace(
            options,
            namespace=item.namespace,
            model_id=item.model_id,
            profile=item.profile,
            collection_name=item.collection_name,
        )
        preflight = preflight_blender(item_options)
        _, snapshot_state = _trusted_preflight_snapshot_state(preflight)
        if snapshot_state.first_error_record is not None:
            raise X5ToolingError(
                "BLENDLIB-X5-BATCH-004",
                _render_diagnostics(_diagnostics_from_records(snapshot_state.diagnostic_records)),
            )
        preflighted.append((item_options, preflight))

    plans = [
        _build_export_plan(
            item_options,
            preflight=preflight,
            conflict_code="BLENDLIB-X5-BATCH-002",
            enforce_windows_path_budget=False,
        )
        for item_options, preflight in preflighted
    ]
    plan_states = [_trusted_export_plan_state(plan) for plan in plans]
    publication_root = plan_states[0][1].project_root
    if any(state[1].project_root != publication_root for state in plan_states[1:]):
        raise X5ToolingError("BLENDLIB-X5-BATCH-002", "Batch items do not resolve to one project root.")
    batch_claims = _validate_artifact_graph(
        publication_root,
        tuple(claim for _, _, state in plan_states for claim in state.claims),
        conflict_code="BLENDLIB-X5-BATCH-002",
    )
    _require_windows_legacy_path_budget(
        publication_root,
        tuple(claim.relative for claim in batch_claims),
        "X5 batch publication",
        include_legacy_export_stage=True,
    )

    prepared: list[_PreparedExport] = []
    # A legal initially-missing project root is created exactly once by the
    # first approved item.  Every later item must reopen that same physical
    # object, not attempt a second exclusive creation from its own identical
    # plan route (and not adopt a replacement root by pathname).
    batch_root_binding: _AtomicDirectoryBinding | None = None
    for (item_options, preflight), plan in zip(preflighted, plans, strict=True):
        prepared_item = _prepare_x5_export(
            item_options,
            preflight=preflight,
            plan=plan,
            _batch_root_binding=batch_root_binding,
        )
        if prepared_item.root_binding is None:
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Prepared X5 batch has no project-root binding.")
        if batch_root_binding is not None and prepared_item.root_binding != batch_root_binding:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Prepared X5 batch changed its physical project-root binding.",
            )
        batch_root_binding = prepared_item.root_binding
        prepared.append(prepared_item)
    outputs: dict[str, bytes] = {}
    for prepared_item in prepared:
        for relative, payload in prepared_item.outputs.items():
            if relative in outputs:
                raise X5ToolingError("BLENDLIB-X5-BATCH-002", f"Batch outputs conflict at {relative}.")
            outputs[relative] = payload
    authoritative_states = [_trusted_export_plan_state(plan)[2] for plan in plans]
    approved_claims = tuple(claim for state in authoritative_states for claim in state.claims)
    _validate_artifact_graph(
        publication_root,
        approved_claims,
        conflict_code="BLENDLIB-X5-BATCH-002",
    )
    if any(item.root_binding is None or item.bundle_bindings is None for item in prepared):
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Prepared X5 batch lost a physical publication approval.")
    root_binding = prepared[0].root_binding
    if root_binding is None:
        raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Prepared X5 batch has no project-root binding.")
    bundle_bindings = _atomic_merge_bundle_bindings(
        tuple(item.bundle_bindings for item in prepared if item.bundle_bindings is not None),
        root_binding,
        "Prepared X5 batch",
    )
    atomic_write_bundle(
        publication_root,
        outputs,
        approved_claims=approved_claims,
        claim_conflict_code="BLENDLIB-X5-BATCH-002",
        approved_bindings=bundle_bindings,
        approved_root_binding=root_binding,
    )
    return [item.result for item in prepared]


def _prepare_x5_export(
    options: Any,
    *,
    preflight: PreflightResult | None = None,
    plan: _FrozenExportPlan | None = None,
    _batch_root_binding: _AtomicDirectoryBinding | None = None,
) -> _PreparedExport:
    """Run private legacy export only after an immutable plan has been approved."""

    if plan is None:
        plan = _build_export_plan(options, preflight=preflight)
    elif preflight is not None:
        supplied_snapshot, _ = _trusted_preflight_snapshot_state(preflight)
        approved_snapshot, _, _ = _trusted_export_plan_state(plan)
        if supplied_snapshot is not approved_snapshot:
            raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Prepared export received a mismatched preflight and plan.")
    snapshot, frozen_options, plan_state = _trusted_export_plan_state(plan)
    publication_root = frozen_options.project_root
    live_claims = _validate_artifact_graph(
        publication_root,
        plan_state.claims,
        conflict_code="BLENDLIB-X5-PATH-004",
    )
    _require_windows_legacy_path_budget(
        publication_root,
        tuple(claim.relative for claim in live_claims),
        "X5 publication",
        include_legacy_export_stage=True,
    )
    sidecar = _decode_approved_sidecar(plan_state.sidecar_payload)
    sidecar_claim = _single_claim(plan_state.claims, "sidecar")
    default_report_claim = _single_claim(plan_state.claims, "default-report")
    explicit_report_claim = next((claim for claim in plan_state.claims if claim.kind == "explicit-report"), None)
    refresh_claim = next((claim for claim in plan_state.claims if claim.kind == "dev-refresh"), None)
    if (plan_state.explicit_report_relative is None) != (explicit_report_claim is None):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Approved explicit-report claim identity was lost.")
    if (plan_state.refresh_relative is None) != (refresh_claim is None):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Approved dev-refresh claim identity was lost.")
    _require_atomic_exact_source_publication_capability()
    leases = _AtomicLeaseGraph()
    stage_root = publication_root
    stage_directories: list[_AtomicDirectoryLease] = []
    stage_parents: dict[str, _AtomicDirectoryLease] = {}
    stage_leaves: list[_AtomicLeafBinding] = []
    stage_cleanup_attempted = False
    active_bundle_bindings: _AtomicBundleBindings | None = None
    try:
        if _batch_root_binding is None:
            root_lease = _atomic_acquire_approved_root_route(
                leases,
                plan_state.root_route,
                "Private X5 export project root",
            )
        else:
            if type(_batch_root_binding) is not _AtomicDirectoryBinding:
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    "Private X5 batch root continuation is invalid.",
                )
            if (
                _batch_root_binding.path != publication_root
                or (
                    not plan_state.root_route.missing_parts
                    and _batch_root_binding != plan_state.root_route.anchor
                )
            ):
                raise X5ToolingError(
                    "BLENDLIB-X5-ATOMIC-001",
                    "Private X5 batch root continuation diverged from its approved route.",
                )
            root_lease = leases.acquire(
                _batch_root_binding,
                "Private X5 export approved batch project root",
            )
        # Creating a missing project root is a controlled directory extension,
        # never an excuse to keep using the approval-time spelling unchecked.
        _validate_artifact_graph(
            publication_root,
            plan_state.claims,
            conflict_code="BLENDLIB-X5-PATH-004",
        )
        active_bundle_bindings = (
            _atomic_rebase_bundle_bindings_after_root_creation(
                plan_state.bundle_bindings,
                root_lease.binding,
                "Private X5 export project root",
            )
            if plan_state.root_route.missing_parts
            else plan_state.bundle_bindings
        )
        _validate_atomic_bundle_targets(
            root_lease.path,
            tuple(target.relative for target in active_bundle_bindings.targets),
            approved=active_bundle_bindings,
            approved_claims=plan_state.claims,
            claim_conflict_code="BLENDLIB-X5-PATH-004",
        )
        _validate_atomic_named_directory_lease(root_lease, "Private X5 export project root")
        stage_lease = _atomic_allocate_private_directory(
            leases,
            root_lease,
            _LEGACY_EXPORT_STAGE_PREFIX,
            "Private X5 export staging directory",
        )
        stage_root = stage_lease.path
        stage_directories.append(stage_lease)
        # The legacy exporter is a compatibility seam, not a second
        # publication authority.  It receives only its private runtime output
        # root; authoring reports, refresh messages, and manifests remain
        # disabled until X5 has checked and atomically published their bytes.
        stage_options = dataclasses.replace(
            frozen_options,
            project_root=stage_root,
            report_path=None,
            dev_refresh_path=None,
            batch_manifest_path=None,
        )
        exporter = _legacy_exporter()
        legacy = exporter.export_open_blend(stage_options)
        # The public plan object remains attacker-visible while the legacy
        # compatibility seam runs.  Re-check its registry authority before
        # accepting even private staged bytes; a mutated presentation surface
        # must fail before report construction or public publication.
        confirmed_snapshot, confirmed_options, confirmed_state = _trusted_export_plan_state(plan)
        if (
            confirmed_snapshot is not snapshot
            or confirmed_state is not plan_state
            or _canonical_export_options(confirmed_options) != plan_state.options
        ):
            raise X5ToolingError(
                "BLENDLIB-X5-SNAPSHOT-001",
                "Export plan authority changed while the private legacy export was active.",
            )
        if not isinstance(legacy, Mapping) or not isinstance(legacy.get("validation"), Mapping):
            raise X5ToolingError("BLENDLIB-X5-ATOMIC-001", "Staged strict-v1 export did not return validation facts.")
        _validate_atomic_named_directory_lease(root_lease, "Private X5 export project root")
        _validate_atomic_named_directory_lease(stage_lease, "Private X5 export staging directory")
        _validate_atomic_bundle_targets(
            root_lease.path,
            tuple(target.relative for target in active_bundle_bindings.targets),
            approved=active_bundle_bindings,
            approved_claims=plan_state.claims,
            claim_conflict_code="BLENDLIB-X5-PATH-004",
        )
        outputs = _atomic_collect_private_outputs(
            leases,
            stage_lease,
            plan_state.runtime_relatives,
            stage_directories,
            stage_parents,
            stage_leaves,
            "Private X5 export staging directory",
        )
        expected_runtime = set(plan_state.runtime_relatives)
        actual_runtime = set(outputs)
        if actual_runtime != expected_runtime:
            missing = ", ".join(sorted(expected_runtime - actual_runtime)[:3]) or "none"
            unexpected = ", ".join(sorted(actual_runtime - expected_runtime)[:3]) or "none"
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Staged strict-v1 artifact set diverged from the approved plan "
                f"(missing: {missing}; unexpected: {unexpected}).",
            )
        expected_payloads = dict(outputs)
        outputs[sidecar_claim.relative] = plan_state.sidecar_payload
        expected_payloads[sidecar_claim.relative] = plan_state.sidecar_payload
        report = build_asset_report(
            snapshot=snapshot,
            sidecar=sidecar,
            validation=legacy["validation"],
            artifacts=outputs,
            diagnostics=(),
        )
        report_payload = asset_report_bytes(report)
        outputs[default_report_claim.relative] = report_payload
        expected_payloads[default_report_claim.relative] = report_payload
        if explicit_report_claim is not None and explicit_report_claim.identity != default_report_claim.identity:
            outputs[explicit_report_claim.relative] = report_payload
            expected_payloads[explicit_report_claim.relative] = report_payload
        clean_result = {
            "authoring_report": default_report_claim.relative,
            "authoring_sidecar": sidecar_claim.relative,
            "format": "blendlib-x5-export-result-v1",
            "mesh": _single_claim(plan_state.claims, "glb").relative,
            "model_key": f"{frozen_options.namespace}:{frozen_options.model_id}",
            "report_sha256": sha256_bytes(report_payload),
            "sidecar_sha256": sha256_bytes(plan_state.sidecar_payload),
            "strict_v1_validation": legacy["validation"],
        }
        if refresh_claim is not None:
            message = RefreshMessage(
                session_token=_text(frozen_options.dev_session_token, "dev session token"),
                generation=_integer(frozen_options.dev_generation, "dev generation"),
                artifact_hashes={relative: sha256_bytes(payload) for relative, payload in sorted(outputs.items())},
                model_key=clean_result["model_key"],
            )
            refresh_payload = refresh_message_bytes(message)
            outputs[refresh_claim.relative] = refresh_payload
            expected_payloads[refresh_claim.relative] = refresh_payload
            clean_result["dev_refresh"] = refresh_claim.relative
        if _publication_record_multiset(plan_state.claims, outputs) != _publication_record_multiset(
            plan_state.claims,
            expected_payloads,
        ):
            raise X5ToolingError(
                "BLENDLIB-X5-SNAPSHOT-001",
                "Prepared output records diverged from the complete approved artifact graph.",
            )
        stage_cleanup_attempted = True
        cleanup_detail = _atomic_cleanup_private_tree(
            stage_leaves,
            stage_directories,
            "Private X5 export staging directory",
        )
        if cleanup_detail is not None:
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Private X5 export preparation was not published because its private staging cleanup "
                f"was retained for recovery: {cleanup_detail}",
            )
        return _PreparedExport(
            frozen_options,
            clean_result,
            outputs,
            stage_root,
            plan_state.claims,
            root_lease.binding,
            active_bundle_bindings,
        )
    except Exception as failure:
        # Do not close/rebind a legacy stage after an error.  The transaction
        # still owns its original root/parent leases, so it can inventory only
        # approved leaves and remove them while those capabilities remain live.
        # Any inventory or cleanup uncertainty deliberately retains the stage.
        cleanup_detail: str | None = None
        if stage_directories and not stage_cleanup_attempted:
            stage_cleanup_attempted = True
            try:
                _atomic_inventory_private_outputs_for_cleanup(
                    leases,
                    stage_directories[0],
                    plan_state.runtime_relatives,
                    stage_directories,
                    stage_parents,
                    stage_leaves,
                    "Private X5 export staging directory",
                )
            except X5ToolingError:
                pass
            try:
                cleanup_detail = _atomic_cleanup_private_tree(
                    stage_leaves,
                    stage_directories,
                    "Private X5 export staging directory",
                )
            except Exception as cleanup_error:
                cleanup_detail = (
                    "Private X5 export staging directory cleanup failed with "
                    f"{type(cleanup_error).__name__}."
                )
        if cleanup_detail is not None:
            stage_name = stage_directories[0].path.name
            raise X5ToolingError(
                "BLENDLIB-X5-ATOMIC-001",
                "Private X5 export preparation was not published; private staging directory "
                f"{stage_name} was retained for recovery: {cleanup_detail}",
            ) from failure
        raise
    finally:
        leases.close_all()


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
            armature_modifiers = [
                modifier for modifier in obj.modifiers if modifier.type == "ARMATURE"
            ]
            vertex_group_names = {
                int(group.index): group.name for group in obj.vertex_groups
            }
            entry.update({
                "face_vertex_counts": [len(polygon.vertices) for polygon in obj.data.polygons],
                "material_names": [slot.material.name if slot.material is not None else "" for slot in obj.material_slots],
                "normals": [[float(component) for component in vertex.normal] for vertex in obj.data.vertices],
                "uv0": obj.data.uv_layers.active is not None and len(obj.data.uv_layers.active.data) > 0,
                "skin_binding": {
                    "armature_modifiers": [
                        {
                            "name": modifier.name,
                            "target": modifier.object.name if modifier.object is not None else "",
                            "target_bones": [
                                bone.name for bone in modifier.object.data.bones
                            ] if (
                                modifier.object is not None
                                and modifier.object.type == "ARMATURE"
                            ) else [],
                            "target_exported": modifier.object in object_set,
                            "target_type": modifier.object.type if modifier.object is not None else "",
                        }
                        for modifier in armature_modifiers
                    ],
                    "profile": options.profile,
                    "vertex_group_assignments": [
                        [
                            {
                                "name": vertex_group_names.get(int(group.group), ""),
                                "weight": float(group.weight),
                            }
                            for group in vertex.groups
                        ]
                        for vertex in obj.data.vertices
                    ],
                },
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
                _, snapshot_state = _trusted_preflight_snapshot_state(result)
                diagnostics = _diagnostics_from_records(snapshot_state.diagnostic_records)
                passed = snapshot_state.first_error_record is None
                context.scene.blendlib_x5_last_status = "PASS" if passed else "BLOCKED"
                for diagnostic in diagnostics:
                    self.report({"ERROR" if diagnostic.severity == "ERROR" else "WARNING"}, f"{diagnostic.code}: {diagnostic.message}")
                return {"FINISHED"} if passed else {"CANCELLED"}
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


def _check_mesh(
    obj: Mapping[str, Any],
    location: str,
    profile: str,
    named_objects: Mapping[str, Mapping[str, Any]],
    diagnostics: list[ToolingDiagnostic],
) -> None:
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
    if profile in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
        _check_skin_binding(obj, location, profile, named_objects, len(normals), diagnostics)


def _check_skin_binding(
    obj: Mapping[str, Any],
    location: str,
    profile: str,
    named_objects: Mapping[str, Mapping[str, Any]],
    vertex_count: int,
    diagnostics: list[ToolingDiagnostic],
) -> None:
    binding = obj.get("skin_binding")
    if not isinstance(binding, Mapping):
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-ARMATURE-004",
            f"{location}/skin_binding",
            "Mesh skin-binding facts are missing from the normalized snapshot.",
            "Rebuild the snapshot from Blender before running public preflight.",
        ))
        return

    if binding.get("profile") != profile:
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-PROFILE-002",
            f"{location}/skin_binding/profile",
            "Mesh skin-binding facts were captured for a different export profile.",
            "Rebuild preflight facts after selecting the final strict-v1 profile.",
        ))

    modifiers = _list(binding.get("armature_modifiers"))
    if any(not isinstance(item, Mapping) for item in modifiers):
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-ARMATURE-004",
            f"{location}/skin_binding/modifiers",
            "Armature modifier facts do not satisfy the normalized binding contract.",
            "Rebuild the snapshot from Blender and keep modifier facts intact.",
        ))
        return

    if profile == "blendlib:rigid_v1":
        if modifiers:
            diagnostics.append(_diagnostic(
                "ERROR",
                "BLENDLIB-X5-ARMATURE-004",
                f"{location}/skin_binding/modifiers",
                "Rigid profile cannot contain an Armature-modified mesh.",
                "Remove the Armature modifier or select blendlib:skinned_v1.",
            ))
        return

    if len(modifiers) != 1:
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-ARMATURE-004",
            f"{location}/skin_binding/modifiers",
            "Skinned profile requires exactly one Armature modifier per mesh.",
            "Keep one Armature modifier targeting one exported Armature.",
        ))
        return

    modifier = modifiers[0]
    target_name = _string_or(modifier.get("target"), "")
    target = named_objects.get(target_name)
    captured_bones = _list(modifier.get("target_bones"))
    exported_bones = _list(target.get("bones")) if isinstance(target, Mapping) else []
    captured_bone_names = tuple(
        _string_or(item, "") for item in captured_bones
    )
    exported_bone_names = tuple(
        _string_or(item.get("name"), "")
        for item in exported_bones
        if isinstance(item, Mapping)
    )
    if (
        not target_name
        or modifier.get("target_exported") is not True
        or modifier.get("target_type") != "ARMATURE"
        or not isinstance(target, Mapping)
        or target.get("type") != "ARMATURE"
        or not captured_bone_names
        or any(not name for name in captured_bone_names)
        or len(set(captured_bone_names)) != len(captured_bone_names)
        or captured_bone_names != exported_bone_names
    ):
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-ARMATURE-005",
            f"{location}/skin_binding/target",
            "Armature modifier target is unbound, outside the export set, or disagrees with exported bones.",
            "Target the exported Armature whose bone list supplies the runtime skin influences.",
        ))
        return

    assignments = _list(binding.get("vertex_group_assignments"))
    if len(assignments) != vertex_count:
        diagnostics.append(_diagnostic(
            "ERROR",
            "BLENDLIB-X5-WEIGHT-001",
            f"{location}/skin_binding/influences",
            "Skin influence records must match the source mesh vertex count.",
            "Rebuild the normalized snapshot from the current Blender mesh.",
        ))
    target_bones = set(captured_bone_names)
    for index in range(min(len(assignments), vertex_count)):
        raw_assignments = assignments[index]
        if not isinstance(raw_assignments, (list, tuple)):
            raw_values: list[Any] = []
            invalid = True
        else:
            raw_values = list(raw_assignments)
            invalid = False
        weights: list[float] = []
        seen_bones: set[str] = set()
        for assignment in raw_values:
            if not isinstance(assignment, Mapping):
                invalid = True
                continue
            bone_name = _string_or(assignment.get("name"), "")
            if bone_name not in target_bones:
                continue
            weight = assignment.get("weight")
            if bone_name in seen_bones or not _finite_number(weight) or float(weight) < 0.0:
                invalid = True
                continue
            seen_bones.add(bone_name)
            numeric_weight = float(weight)
            if numeric_weight > SKIN_WEIGHT_EPSILON:
                weights.append(numeric_weight)
        influence_location = f"{location}/vertex:{index}/weights"
        if invalid or not weights or len(weights) > 4:
            diagnostics.append(_diagnostic(
                "ERROR",
                "BLENDLIB-X5-WEIGHT-001",
                influence_location,
                "Runtime skin weights must contain one to four unique finite non-negative bone influences.",
                "Keep only target-bone vertex groups, normalize, and prune effective influences.",
            ))
        elif not math.isclose(sum(weights), 1.0, abs_tol=SKIN_WEIGHT_EPSILON, rel_tol=0.0):
            diagnostics.append(_diagnostic(
                "ERROR",
                "BLENDLIB-X5-WEIGHT-002",
                influence_location,
                "Runtime skin weights must sum to one.",
                "Normalize the effective target-bone vertex groups.",
            ))


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
    project_root = Path(options.project_root).resolve()
    require_non_runtime_output(
        project_root, resource_root, options.authoring_output_root, "authoring output root"
    )
    for attribute, label in (("report_path", "explicit report path"), ("dev_refresh_path", "dev refresh path")):
        value = getattr(options, attribute, None)
        if value is None:
            continue
        require_non_runtime_output(project_root, resource_root, _relative_project_path(project_root, value, label), label)


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


def _snapshot_integrity_digest(values: Mapping[str, Any]) -> bytes:
    """Hash the exact frozen JSON-like tree without retaining a mutable backing map.

    ``MappingProxyType`` only prevents ordinary writes through the proxy; Python
    reflection can still reach its backing dictionary.  The trusted registry
    therefore keeps an independent byte digest and recomputes it at every trust
    boundary.  The representation only accepts values emitted by
    ``_freeze_snapshot_value`` and is deliberately order-sensitive so any
    backing-dict replacement/reinsertion fails closed as well.
    """

    digest = hashlib.sha256()
    active: set[int] = set()

    def write_chunk(tag: bytes, payload: bytes = b"") -> None:
        digest.update(tag)
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)

    def write_integer(value: int) -> None:
        magnitude = abs(value)
        payload = magnitude.to_bytes(max(1, (magnitude.bit_length() + 7) // 8), "big")
        write_chunk(b"I+" if value >= 0 else b"I-", payload)

    def visit(value: Any) -> None:
        if value is None:
            write_chunk(b"N")
            return
        if type(value) is bool:
            write_chunk(b"B1" if value else b"B0")
            return
        if type(value) is int:
            write_integer(value)
            return
        if type(value) is float:
            write_chunk(b"F", value.hex().encode("ascii"))
            return
        if type(value) is Decimal:
            decimal_tuple = value.as_tuple()
            write_chunk(b"D", repr(decimal_tuple).encode("ascii"))
            return
        if type(value) is str:
            write_chunk(b"S", value.encode("utf-8"))
            return
        if type(value) is _MAPPING_PROXY_TYPE:
            identity = id(value)
            if identity in active:
                raise ValueError("frozen snapshot mapping cycle")
            active.add(identity)
            try:
                write_chunk(b"M", len(value).to_bytes(8, "big"))
                for key, item in value.items():
                    if type(key) is not str:
                        raise ValueError("frozen snapshot mapping key type")
                    visit(key)
                    visit(item)
            finally:
                active.discard(identity)
            return
        if type(value) is tuple:
            identity = id(value)
            if identity in active:
                raise ValueError("frozen snapshot tuple cycle")
            active.add(identity)
            try:
                write_chunk(b"T", len(value).to_bytes(8, "big"))
                for item in value:
                    visit(item)
            finally:
                active.discard(identity)
            return
        raise ValueError("frozen snapshot value type")

    visit(values)
    return digest.digest()


def _clone_frozen_snapshot_values(values: Mapping[str, Any]) -> Mapping[str, Any]:
    """Deep-copy the exact frozen tree into a separate proxy graph.

    A ``MappingProxyType`` is presentation-immutable only: hostile Python can
    reach its backing dictionary with reflection.  Trusted registry authority
    therefore never shares the public snapshot's proxy graph, and each
    consuming operation receives a fresh detached graph rather than the
    registry's long-lived one.
    """

    active: set[int] = set()

    def clone(value: Any) -> Any:
        if value is None or type(value) in {bool, int, float, Decimal, str}:
            return value
        if type(value) is _MAPPING_PROXY_TYPE:
            identity = id(value)
            if identity in active:
                raise ValueError("frozen snapshot mapping cycle")
            active.add(identity)
            try:
                copied: dict[str, Any] = {}
                for key, item in value.items():
                    if type(key) is not str:
                        raise ValueError("frozen snapshot mapping key type")
                    copied[key] = clone(item)
                return MappingProxyType(copied)
            finally:
                active.discard(identity)
        if type(value) is tuple:
            identity = id(value)
            if identity in active:
                raise ValueError("frozen snapshot tuple cycle")
            active.add(identity)
            try:
                return tuple(clone(item) for item in value)
            finally:
                active.discard(identity)
        raise ValueError("frozen snapshot value type")

    cloned = clone(values)
    if type(cloned) is not _MAPPING_PROXY_TYPE:
        raise ValueError("frozen snapshot root type")
    return cloned


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
    try:
        integrity_digest = _snapshot_integrity_digest(values)
        authority_values = _clone_frozen_snapshot_values(values)
        authority_integrity_digest = _snapshot_integrity_digest(authority_values)
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen snapshot values do not satisfy the immutable value contract.",
        ) from error
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
            integrity_digest,
            authority_values,
            authority_integrity_digest,
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


def _new_preflight_result(snapshot: _FrozenSnapshot) -> PreflightResult:
    snapshot_state = _trusted_snapshot_state(snapshot)
    if snapshot_state is None:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Preflight results require an authoritative frozen snapshot.",
        )
    result = PreflightResult(
        _diagnostics_from_records(snapshot_state.diagnostic_records),
        snapshot,
    )
    identity = id(result)
    generation = object()

    def release_preflight(
        result_ref: ReferenceType[Any],
        *,
        registered_identity: int = identity,
        registered_generation: object = generation,
    ) -> None:
        _release_trusted_preflight(
            registered_identity,
            registered_generation,
            result_ref,
        )

    result_ref = ref(result, release_preflight)
    _TRUSTED_PREFLIGHT_STATES[identity] = _TrustedPreflightState(
        result_ref,
        generation,
        snapshot_state.snapshot_ref,
        snapshot_state.generation,
    )
    return result


def _new_frozen_export_plan(
    snapshot: _FrozenSnapshot,
    options: _FrozenLegacyOptions,
    claims: Sequence[_ArtifactClaim],
    sidecar_payload: bytes,
    default_report_relative: str,
    explicit_report_relative: str | None,
    refresh_relative: str | None,
    runtime_relatives: Sequence[str],
    root_route: _AtomicRootRoute,
    bundle_bindings: _AtomicBundleBindings,
) -> _FrozenExportPlan:
    """Register one exact pre-stage plan for later private staging use."""

    snapshot_state = _trusted_snapshot_state(snapshot)
    if snapshot_state is None or type(options) is not _FrozenLegacyOptions:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plan inputs lack authoritative provenance.")
    if type(claims) is not tuple:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plan claims are not an exact tuple.")
    approved_claims = claims
    canonical_options = _canonical_export_options(options)
    canonical_claims = _canonical_export_claims(approved_claims)
    if (
        type(root_route) is not _AtomicRootRoute
        or type(bundle_bindings) is not _AtomicBundleBindings
        or root_route.root != options.project_root
        or bundle_bindings.root != options.project_root
    ):
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen export plan physical publication approval is invalid.",
        )
    if type(sidecar_payload) is not bytes or not sidecar_payload:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plan sidecar payload is invalid.")
    if (
        type(default_report_relative) is not str
        or (explicit_report_relative is not None and type(explicit_report_relative) is not str)
        or (refresh_relative is not None and type(refresh_relative) is not str)
        or type(runtime_relatives) is not tuple
        or any(type(relative) is not str for relative in runtime_relatives)
    ):
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Frozen export plan path values are invalid.")
    canonical_sidecar_payload = bytes(bytearray(sidecar_payload))
    canonical_default_report_relative = str(default_report_relative)
    canonical_explicit_report_relative = (
        None if explicit_report_relative is None else str(explicit_report_relative)
    )
    canonical_refresh_relative = None if refresh_relative is None else str(refresh_relative)
    canonical_runtime_relatives = tuple(str(relative) for relative in runtime_relatives)
    _validate_canonical_plan_graph(
        canonical_claims,
        canonical_default_report_relative,
        canonical_explicit_report_relative,
        canonical_refresh_relative,
        canonical_runtime_relatives,
    )
    plan = _FrozenExportPlan(
        snapshot,
        options,
        approved_claims,
        sidecar_payload,
        default_report_relative,
        explicit_report_relative,
        refresh_relative,
        tuple(runtime_relatives),
    )
    identity = id(plan)
    generation = object()

    def release_plan(
        plan_ref: ReferenceType[Any],
        *,
        registered_identity: int = identity,
        registered_generation: object = generation,
    ) -> None:
        _release_trusted_export_plan(registered_identity, registered_generation, plan_ref)

    plan_ref = ref(plan, release_plan)
    _TRUSTED_EXPORT_PLAN_STATES[identity] = _TrustedExportPlanState(
        plan_ref,
        generation,
        snapshot_state.snapshot_ref,
        snapshot_state.generation,
        options,
        _frozen_option_record(options),
        canonical_options,
        approved_claims,
        tuple(_claim_records(approved_claims)),
        canonical_claims,
        sidecar_payload,
        canonical_sidecar_payload,
        default_report_relative,
        canonical_default_report_relative,
        explicit_report_relative,
        canonical_explicit_report_relative,
        refresh_relative,
        canonical_refresh_relative,
        tuple(runtime_relatives),
        canonical_runtime_relatives,
        root_route,
        bundle_bindings,
    )
    return plan


def _release_trusted_export_plan(
    identity: int,
    generation: object,
    plan_ref: ReferenceType[Any],
) -> None:
    state = _TRUSTED_EXPORT_PLAN_STATES.get(identity)
    if state is not None and state.generation is generation and state.plan_ref is plan_ref:
        del _TRUSTED_EXPORT_PLAN_STATES[identity]


def _trusted_export_plan_state(
    plan: object,
) -> tuple[_FrozenSnapshot, _FrozenLegacyOptions, _TrustedExportPlanState]:
    """Return registry authority only when every public plan surface is intact."""

    if type(plan) is not _FrozenExportPlan:
        raise X5ToolingError("BLENDLIB-X5-SNAPSHOT-001", "Export plan provenance is invalid.")
    try:
        state = _TRUSTED_EXPORT_PLAN_STATES.get(id(plan))
        if state is None or state.plan_ref() is not plan:
            raise ValueError("unregistered export plan")
        snapshot = state.snapshot_ref()
        snapshot_state = _trusted_snapshot_state(snapshot)
        if (
            snapshot is None
            or plan.snapshot is not snapshot
            or snapshot_state is None
            or snapshot_state.snapshot_ref is not state.snapshot_ref
            or snapshot_state.generation is not state.snapshot_generation
        ):
            raise ValueError("export plan snapshot authority changed")
        if (
            plan.options is not state.presented_options
            or type(state.presented_options) is not _FrozenLegacyOptions
        ):
            raise ValueError("export plan options changed")
        presented_option_fields = _frozen_option_record(state.presented_options)
        if (
            type(state.presented_option_fields) is not tuple
            or len(presented_option_fields) != len(state.presented_option_fields)
            or any(actual is not expected for actual, expected in zip(presented_option_fields, state.presented_option_fields))
            or _canonical_export_options(state.presented_options) != state.options
        ):
            raise ValueError("export plan option fields changed")
        if (
            plan.claims is not state.presented_claims
            or type(state.presented_claims) is not tuple
        ):
            raise ValueError("export plan publication graph changed")
        presented_claim_records = _claim_records(state.presented_claims)
        if (
            type(state.presented_claim_fields) is not tuple
            or len(presented_claim_records) != len(state.presented_claim_fields)
            or any(
                len(actual) != len(expected)
                or any(value is not expected_value for value, expected_value in zip(actual, expected))
                for actual, expected in zip(presented_claim_records, state.presented_claim_fields)
            )
            or _canonical_export_claims(state.presented_claims) != state.claims
        ):
            raise ValueError("export plan claim fields changed")
        if (
            plan.sidecar_payload is not state.presented_sidecar_payload
            or type(plan.sidecar_payload) is not bytes
            or plan.default_report_relative is not state.presented_default_report_relative
            or type(plan.default_report_relative) is not str
            or plan.explicit_report_relative is not state.presented_explicit_report_relative
            or (plan.explicit_report_relative is not None and type(plan.explicit_report_relative) is not str)
            or plan.refresh_relative is not state.presented_refresh_relative
            or (plan.refresh_relative is not None and type(plan.refresh_relative) is not str)
            or plan.runtime_relatives is not state.presented_runtime_relatives
            or type(plan.runtime_relatives) is not tuple
            or any(type(relative) is not str for relative in plan.runtime_relatives)
        ):
            raise ValueError("export plan public scalar fields changed")
        if (
            bytes(bytearray(plan.sidecar_payload)) != state.sidecar_payload
            or str(plan.default_report_relative) != state.default_report_relative
            or (None if plan.explicit_report_relative is None else str(plan.explicit_report_relative))
            != state.explicit_report_relative
            or (None if plan.refresh_relative is None else str(plan.refresh_relative)) != state.refresh_relative
            or tuple(str(relative) for relative in plan.runtime_relatives) != state.runtime_relatives
        ):
            raise ValueError("export plan canonical scalar fields changed")
        _validate_canonical_plan_graph(
            state.claims,
            state.default_report_relative,
            state.explicit_report_relative,
            state.refresh_relative,
            state.runtime_relatives,
        )
        return snapshot, _execution_options_from_canonical(state.options), state
    except X5ToolingError:
        raise
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Export plan provenance or approved publication graph is invalid.",
        ) from error


def _release_trusted_preflight(
    identity: int,
    generation: object,
    result_ref: ReferenceType[Any],
) -> None:
    state = _TRUSTED_PREFLIGHT_STATES.get(identity)
    if (
        state is not None
        and state.generation is generation
        and state.result_ref is result_ref
    ):
        del _TRUSTED_PREFLIGHT_STATES[identity]


def _trusted_preflight_snapshot_state(
    result: object,
) -> tuple[_FrozenSnapshot, _TrustedSnapshotState]:
    if type(result) is not PreflightResult:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Preflight result provenance is invalid.",
        )
    try:
        state = _TRUSTED_PREFLIGHT_STATES.get(id(result))
        if state is None or state.result_ref() is not result:
            raise ValueError("unregistered preflight result")
        snapshot = state.snapshot_ref()
        if snapshot is None or result.snapshot is not snapshot:
            raise ValueError("preflight snapshot identity changed")
        snapshot_state = _trusted_snapshot_state(snapshot)
        if (
            snapshot_state is None
            or snapshot_state.snapshot_ref is not state.snapshot_ref
            or snapshot_state.generation is not state.snapshot_generation
        ):
            raise ValueError("preflight snapshot authority changed")
        return snapshot, snapshot_state
    except X5ToolingError:
        raise
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Preflight result provenance or bound snapshot identity is invalid.",
        ) from error


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
            or _snapshot_integrity_digest(snapshot._values) != state.integrity_digest
            or _snapshot_integrity_digest(state.authority_values) != state.authority_integrity_digest
        ):
            return None
        return state
    except Exception:
        return None


def _snapshot_authority_values(snapshot: _FrozenSnapshot) -> Mapping[str, Any]:
    """Return a fresh private execution view after validating public authority.

    The public ``_FrozenSnapshot`` remains the provenance token used by APIs
    and tests, but export/report code must never consume its proxy graph after
    a one-time digest check.  Re-cloning registry-held authority closes that
    check-to-use window while retaining a final public-digest gate at every
    call site.
    """

    state = _trusted_snapshot_state(snapshot)
    if state is None:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen snapshot provenance or immutable state is invalid.",
        )
    try:
        return _clone_frozen_snapshot_values(state.authority_values)
    except Exception as error:
        raise X5ToolingError(
            "BLENDLIB-X5-SNAPSHOT-001",
            "Frozen snapshot authority cannot be copied safely for consumption.",
        ) from error


def _list(value: Any) -> list[Any]:
    return list(value) if isinstance(value, (list, tuple)) else []


def _freeze_mapping_snapshot(
    snapshot: Mapping[str, Any],
    diagnostics: list[ToolingDiagnostic],
) -> _FrozenSnapshot:
    if isinstance(snapshot, _FrozenSnapshot):
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
            if not isinstance(key, str):
                _append_snapshot_diagnostic("scene", diagnostics)
                continue
            plain_key = key if type(key) is str else str.__str__(key)
            if len(plain_key) > MAX_SNAPSHOT_TEXT:
                _append_snapshot_diagnostic("scene", diagnostics)
                continue
            frozen_values[plain_key] = value
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
        plain_value = value if type(value) is str else str.__str__(value)
        if len(plain_value) <= MAX_SNAPSHOT_TEXT:
            return plain_value
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
                if not isinstance(key, str):
                    _append_snapshot_diagnostic(location, diagnostics)
                    continue
                plain_key = key if type(key) is str else str.__str__(key)
                if len(plain_key) > MAX_SNAPSHOT_TEXT:
                    _append_snapshot_diagnostic(location, diagnostics)
                    continue
                copied[plain_key] = _freeze_snapshot_value(
                    item, f"{location}/{plain_key}"[:512], diagnostics, depth + 1, budget, active
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
