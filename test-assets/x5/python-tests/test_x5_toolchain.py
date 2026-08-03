"""Standard-library X5 contract tests; intentionally outside packaged addon paths."""

from __future__ import annotations

import copy
import gc
import io
import json
import math
import os
import pickle
import subprocess
import sys
import tempfile
import unittest
from decimal import Decimal
from enum import IntEnum
from pathlib import Path
from types import SimpleNamespace


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
ADDON_ROOT = REPOSITORY_ROOT / "blender-addon"
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

import blendlib_x5_toolchain as x5  # noqa: E402
import blendlib_exporter as legacy  # noqa: E402


class TrackingBytesIO(io.BytesIO):
    def __init__(self, payload: bytes) -> None:
        super().__init__(payload)
        self.maximum_request = 0

    def read(self, size: int = -1) -> bytes:
        self.maximum_request = max(self.maximum_request, size)
        return super().read(size)


class X5ToolchainTest(unittest.TestCase):
    def snapshot(self) -> dict:
        return json.loads((Path(__file__).with_name("valid_snapshot.json")).read_text(encoding="utf-8"))

    def test_import_is_bpy_free_and_preflight_is_deterministic(self) -> None:
        self.assertFalse(hasattr(x5, "bpy"))
        first = x5.preflight_snapshot(self.snapshot())
        second = x5.preflight_snapshot(self.snapshot())
        self.assertTrue(first.ok, first.report())
        self.assertEqual(first.report(), second.report())
        self.assertEqual([], [item for item in first.diagnostics if item.severity == "ERROR"])

    def test_authoring_mapping_report_and_paths_are_runtime_separate(self) -> None:
        snapshot = self.snapshot()
        sidecar = x5.build_authoring_sidecar(snapshot)
        self.assertTrue(sidecar["runtime_boundary"]["descriptor_extensions_are_not_used"])
        self.assertEqual("blendlib:heroroot", sidecar["mapping"]["empty_sockets"][0]["key"])
        self.assertEqual("hero_variant", sidecar["mapping"]["collection_groups_variants"][0]["variant_key"])
        self.assertTrue(sidecar["mapping"]["collision_references"][0]["read_only"])
        self.assertEqual(
            ["object.heromesh.tint", "object.heroroot.display_name"],
            sorted(sidecar["authoring_metadata"]),
        )
        report = x5.build_asset_report(
            snapshot=snapshot,
            sidecar=sidecar,
            validation={"index_count": 3, "material_names": ["HeroMaterial"], "vertex_count": 3},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero/model.glb": b"glb"},
            diagnostics=(),
        )
        encoded = x5.canonical_json_bytes(report)
        self.assertEqual(encoded, x5.canonical_json_bytes(report))
        self.assertNotIn(b":\\", encoded)
        self.assertEqual(x5.ASSET_REPORT_FORMAT, report["format"])
        with self.assertRaisesRegex(x5.X5ToolingError, "PATH-001"):
            x5.safe_relative_path("C:/host/path")
        with self.assertRaisesRegex(x5.X5ToolingError, "PATH-001"):
            x5.safe_relative_path("assets/file:stream")

    def test_blender_snapshot_actions_become_sidecar_animation_clips(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            class FakeObject:
                name = "RigidRoot"
                parent = None
                scale = (1.0, 1.0, 1.0)
                type = "EMPTY"
                animation_data = None

                @staticmethod
                def items() -> tuple[()]:
                    return ()

                @staticmethod
                def get(unused_key: str) -> None:
                    return None

            rigid_root = FakeObject()
            action = SimpleNamespace(name="rigid_pulse", frame_range=(1.0, 12.0), fcurves=[])
            rigid_root.animation_data = SimpleNamespace(action=action, nla_tracks=[])
            collection = SimpleNamespace(
                all_objects=[rigid_root], children=[], name="BlendLibExport", objects=[rigid_root]
            )
            blender = SimpleNamespace(
                context=SimpleNamespace(scene=SimpleNamespace(
                    timeline_markers=[], unit_settings=SimpleNamespace(scale_length=1.0)
                )),
                data=SimpleNamespace(actions=[
                    action,
                    SimpleNamespace(name="unrelated_fake_user", frame_range=(0.0, 0.0), fcurves=[]),
                ]),
            )
            options = SimpleNamespace(
                blend_path=root / "source.blend",
                model_id="fixtures/rigid_model",
                namespace="blendlib_showcase",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
            )

            snapshot = x5._snapshot_from_blender(blender, collection, [rigid_root], options)
            sidecar = x5.build_authoring_sidecar(snapshot)

            self.assertEqual(["rigid_pulse"], [item["name"] for item in snapshot["actions"]])
            self.assertEqual(["rigid_pulse"], [item["clip"] for item in sidecar["mapping"]["action_animation_clips"]])

    def test_preflight_reports_mesh_and_metadata_errors_with_stable_codes(self) -> None:
        snapshot = self.snapshot()
        mesh = snapshot["objects"][1]
        mesh["uv0"] = False
        mesh["weights"] = [[0.6, 0.3, 0.2, 0.1, 0.0]]
        mesh["custom_properties"] = {"blendlib_token": "secret-value"}
        snapshot["objects"][2]["bones"][1]["parent"] = "missing"
        result = x5.preflight_snapshot(snapshot)
        codes = [item.code for item in result.diagnostics]
        self.assertFalse(result.ok)
        self.assertIn("BLENDLIB-X5-MESH-002", codes)
        self.assertIn("BLENDLIB-X5-WEIGHT-001", codes)
        self.assertIn("BLENDLIB-X5-METADATA-002", codes)
        self.assertIn("BLENDLIB-X5-ARMATURE-002", codes)

    def test_metadata_rejects_secret_keys_and_values_without_echoing_them(self) -> None:
        for key, value in (
            ("blendlib_password", "ordinary"),
            ("blendlib_note", "credential=TOPSECRET"),
        ):
            snapshot = self.snapshot()
            snapshot["objects"][0]["custom_properties"] = {key: value}

            result = x5.preflight_snapshot(snapshot)
            rendered = json.dumps(result.report(), sort_keys=True)

            self.assertIn("BLENDLIB-X5-METADATA-002", [item.code for item in result.diagnostics])
            self.assertNotIn("TOPSECRET", rendered)
            self.assertNotIn("password", rendered.lower())

    def test_metadata_rejects_object_and_entry_canonical_collisions_without_value_echo(self) -> None:
        snapshot = self.snapshot()
        snapshot["objects"][0]["name"] = "Obj.A"
        snapshot["objects"][0]["custom_properties"] = {"blendlib_tint": "FIRST_PRIVATE_VALUE"}
        snapshot["objects"][1]["name"] = "Obj/A"
        snapshot["objects"][1]["custom_properties"] = {"blendlib_tint": "SECOND_PRIVATE_VALUE"}

        result = x5.preflight_snapshot(snapshot)
        rendered = json.dumps(result.report(), sort_keys=True)
        metadata_errors = [item for item in result.diagnostics if item.code == "BLENDLIB-X5-METADATA-001"]
        self.assertEqual(1, len(metadata_errors), result.report())
        self.assertEqual("object:Obj/A/custom_properties", metadata_errors[0].location)
        self.assertNotIn("FIRST_PRIVATE_VALUE", rendered)
        self.assertNotIn("SECOND_PRIVATE_VALUE", rendered)
        with self.assertRaisesRegex(x5.X5ToolingError, "METADATA-001"):
            x5.build_authoring_sidecar(snapshot)

        same_object = self.snapshot()
        same_object["objects"][0]["custom_properties"] = {"blendlib_tint": "first"}
        duplicate = json.loads(json.dumps(same_object["objects"][0]))
        same_object["objects"].append(duplicate)
        duplicate_result = x5.preflight_snapshot(same_object)
        self.assertIn("BLENDLIB-X5-METADATA-001", [item.code for item in duplicate_result.diagnostics])

        key_collision = self.snapshot()
        key_collision["objects"][0]["custom_properties"] = {
            "blendlib_same key": "first",
            "blendlib_same@key": "second",
        }
        self.assertIn(
            "BLENDLIB-X5-METADATA-001",
            [item.code for item in x5.preflight_snapshot(key_collision).diagnostics],
        )

    def test_metadata_rejects_more_than_4096_canonical_entries_before_sidecar_build(self) -> None:
        snapshot = self.snapshot()
        snapshot["objects"] = [
            {
                "custom_properties": {f"blendlib_key_{entry:02d}": entry for entry in range(64)},
                "name": f"Object_{object_index:02d}",
                "scale": [1.0, 1.0, 1.0],
                "type": "EMPTY",
            }
            for object_index in range(65)
        ]
        result = x5.preflight_snapshot(snapshot)
        self.assertFalse(result.ok)
        self.assertIn("BLENDLIB-X5-METADATA-001", [item.code for item in result.diagnostics])
        with self.assertRaisesRegex(x5.X5ToolingError, "more than 4096"):
            x5.build_authoring_sidecar(snapshot)

    def test_oversize_sidecar_is_rejected_before_staging_or_legacy_export(self) -> None:
        snapshot = self.snapshot()
        snapshot["objects"] = [
            {
                "custom_properties": {
                    f"blendlib_key_{entry:02d}": "x" * x5.MAX_AUTHORING_METADATA_TEXT
                    for entry in range(x5.MAX_AUTHORING_METADATA_ENTRIES)
                },
                "name": f"Object_{object_index:02d}",
                "scale": [1.0, 1.0, 1.0],
                "type": "EMPTY",
            }
            for object_index in range(64)
        ]
        sidecar = x5.build_authoring_sidecar(snapshot)
        self.assertGreater(len(x5.canonical_json_bytes(sidecar)), x5.MAX_REPORT_BYTES)
        preflight = x5.preflight_snapshot(snapshot)
        self.assertTrue(preflight.ok, preflight.report())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            options = SimpleNamespace(
                authoring_output_root="build/blendlib-authoring",
                blend_path=root / "source.blend",
                dev_refresh_path=None,
                model_id="hero",
                namespace="blendlib",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                with self.assertRaisesRegex(x5.X5ToolingError, "SIDECAR-002"):
                    x5._prepare_x5_export(options, preflight=preflight)
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

    def test_sidecar_bounded_canonical_serializer_accepts_exact_512_kib_boundary(self) -> None:
        snapshot = self.snapshot()
        snapshot["objects"] = [
            {
                "custom_properties": {
                    f"blendlib_key_{entry:02d}": ""
                    for entry in range(x5.MAX_AUTHORING_METADATA_ENTRIES)
                },
                "name": f"Object_{object_index:02d}",
                "scale": [1.0, 1.0, 1.0],
                "type": "EMPTY",
            }
            for object_index in range(64)
        ]
        sidecar = x5.build_authoring_sidecar(snapshot)
        metadata = sidecar["authoring_metadata"]
        remaining = x5.MAX_REPORT_BYTES - len(x5.canonical_json_bytes(sidecar))
        self.assertGreaterEqual(remaining, 0)
        for key in sorted(metadata):
            added = min(x5.MAX_AUTHORING_METADATA_TEXT, remaining)
            metadata[key] = "x" * added
            remaining -= added
            if remaining == 0:
                break
        self.assertEqual(0, remaining, "valid bounded scalar capacity could not reach the exact file limit")
        canonical = x5.canonical_json_bytes(sidecar)
        self.assertEqual(x5.MAX_REPORT_BYTES, len(canonical))
        self.assertEqual(
            canonical,
            x5._bounded_canonical_json_bytes(
                sidecar, x5.MAX_REPORT_BYTES, "BLENDLIB-X5-SIDECAR-002", "Authoring sidecar"
            ),
        )
        growable = next(key for key in sorted(metadata) if len(metadata[key]) < x5.MAX_AUTHORING_METADATA_TEXT)
        metadata[growable] += "x"
        with self.assertRaisesRegex(x5.X5ToolingError, "SIDECAR-002"):
            x5._bounded_canonical_json_bytes(
                sidecar, x5.MAX_REPORT_BYTES, "BLENDLIB-X5-SIDECAR-002", "Authoring sidecar"
            )

    def test_preflight_rejects_missing_and_escaped_png_sources(self) -> None:
        snapshot = self.snapshot()
        material = snapshot["materials"][0]
        material["source_exists"] = False
        material["source_regular"] = False
        material["source_within_allowed_root"] = False

        result = x5.preflight_snapshot(snapshot)
        diagnostics = {item.code: item.location for item in result.diagnostics}

        self.assertEqual("material:HeroMaterial/image", diagnostics["BLENDLIB-X5-TEXTURE-004"])
        self.assertEqual("material:HeroMaterial/image", diagnostics["BLENDLIB-X5-TEXTURE-005"])

    def test_texture_snapshot_checks_real_regular_file_and_allowed_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            allowed = parent / "project"
            allowed.mkdir()
            png = allowed / "texture.png"
            png.write_bytes(b"png")

            valid = x5._texture_source_snapshot(png, (allowed,))
            missing = x5._texture_source_snapshot(allowed / "missing.png", (allowed,))
            outside = x5._texture_source_snapshot(parent / "outside.png", (allowed,))

            self.assertTrue(valid["source_exists"])
            self.assertTrue(valid["source_regular"])
            self.assertTrue(valid["source_within_allowed_root"])
            self.assertFalse(missing["source_exists"])
            self.assertFalse(missing["source_regular"])
            self.assertTrue(missing["source_within_allowed_root"])
            self.assertFalse(outside["source_within_allowed_root"])

    def test_cli_freezes_existing_texture_roots_without_requiring_project_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            blend_directory = parent / "source"
            blend_directory.mkdir()
            blend_path = blend_directory / "source.blend"
            blend_path.write_bytes(b"blend")
            project_root = parent / "first-export-project"

            options = legacy.parse_blender_arguments([
                "blender",
                "--",
                "--blend",
                str(blend_path),
                "--project-root",
                str(project_root),
                "--namespace",
                "blendlib",
                "--model-id",
                "hero",
                "--profile",
                "blendlib:rigid_v1",
            ])

            self.assertFalse(project_root.exists())
            self.assertEqual((blend_directory.resolve(),), legacy._authorized_texture_roots(options))

    def test_invalid_preflight_blocks_before_any_staging_or_legacy_export(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "not-created"
            options = SimpleNamespace(
                project_root=root,
                namespace="blendlib",
                model_id="hero",
                output_resource_root="src/main/resources",
                authoring_output_root="build/blendlib-authoring",
            )
            failed = x5.PreflightResult((x5.ToolingDiagnostic(
                "ERROR", "BLENDLIB-X5-SCENE-001", "scene", "invalid", "repair"
            ),), {})
            original_preflight = x5.preflight_blender
            original_exporter = x5._legacy_exporter
            try:
                x5.preflight_blender = lambda unused: failed
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                with self.assertRaisesRegex(x5.X5ToolingError, "PREFLIGHT-001"):
                    x5._prepare_x5_export(options)
            finally:
                x5.preflight_blender = original_preflight
                x5._legacy_exporter = original_exporter
            self.assertFalse(root.exists())

    def test_batch_plan_is_stable_and_rejects_duplicates(self) -> None:
        reversed_items = [
            x5.BatchExportItem("blendlib", "zeta", "blendlib:rigid_v1", None),
            x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", "A"),
        ]
        self.assertEqual(["alpha", "zeta"], [item.model_id for item in x5.plan_batch(reversed_items)])
        with self.assertRaisesRegex(x5.X5ToolingError, "BATCH-001"):
            x5.plan_batch(reversed_items + [x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", "B")])

    def test_atomic_bundle_failure_restores_prior_files_and_leaves_no_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "out").mkdir()
            (root / "out" / "a.txt").write_bytes(b"old-a")
            (root / "out" / "b.txt").write_bytes(b"old-b")

            def fail_second_stage(source: str | os.PathLike[str], target: str | os.PathLike[str]) -> None:
                if Path(target).name == "b.txt" and ".blendlib-x5-stage-" in str(source):
                    raise OSError("injected replacement fault")
                os.replace(source, target)

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002"):
                x5.atomic_write_bundle(
                    root,
                    {"out/a.txt": b"new-a", "out/b.txt": b"new-b", "out/c.txt": b"new-c"},
                    replace_func=fail_second_stage,
                )
            self.assertEqual(b"old-a", (root / "out" / "a.txt").read_bytes())
            self.assertEqual(b"old-b", (root / "out" / "b.txt").read_bytes())
            self.assertFalse((root / "out" / "c.txt").exists())
            self.assertEqual([], list(root.glob(".blendlib-x5-*-*")))

    def test_atomic_bundle_preserves_backup_when_restore_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "out").mkdir()
            (root / "out" / "a.txt").write_bytes(b"old-a")
            (root / "out" / "b.txt").write_bytes(b"old-b")

            def fail_install_and_one_restore(source: str | os.PathLike[str], target: str | os.PathLike[str]) -> None:
                source_path = Path(source)
                target_path = Path(target)
                if target_path.name == "b.txt" and ".blendlib-x5-stage-" in str(source_path):
                    raise OSError("injected install fault")
                if target_path.name == "b.txt" and ".blendlib-x5-backup-" in str(source_path):
                    raise OSError("injected restore fault")
                os.replace(source, target)

            with self.assertRaisesRegex(x5.X5ToolingError, "prior bytes remain recoverable") as raised:
                x5.atomic_write_bundle(
                    root,
                    {"out/a.txt": b"new-a", "out/b.txt": b"new-b"},
                    replace_func=fail_install_and_one_restore,
                )

            backups = list(root.glob(".blendlib-x5-backup-*"))
            self.assertEqual(1, len(backups))
            self.assertIn(backups[0].name, str(raised.exception))
            self.assertEqual(b"old-b", (backups[0] / "out" / "b.txt").read_bytes())
            self.assertEqual(b"old-a", (root / "out" / "a.txt").read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))

    def test_atomic_bundle_target_resolution_failure_cleans_new_private_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original_resolve = x5.resolve_under

            def fail_target_resolution(base: Path, relative: str, label: str = "path") -> Path:
                if label == "bundle output path":
                    raise x5.X5ToolingError("BLENDLIB-X5-PATH-001", "injected target resolution failure")
                return original_resolve(base, relative, label)

            try:
                x5.resolve_under = fail_target_resolution
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002"):
                    x5.atomic_write_bundle(root, {"out/a.txt": b"new-a"})
            finally:
                x5.resolve_under = original_resolve

            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_bounded_reader_rejects_sparse_oversize_and_growth(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "oversize.json"
            with path.open("wb") as stream:
                stream.truncate(x5.MAX_REPORT_BYTES + 1)

            with self.assertRaisesRegex(x5.X5ToolingError, "exceeds its bounded size"):
                x5._read_bounded_file(path, x5.MAX_REPORT_BYTES, "BLENDLIB-X5-BATCH-005", "Batch manifest")
            exact = TrackingBytesIO(b"x" * 20_000)
            self.assertEqual(
                b"x" * 20_000,
                x5._read_bounded_stream(
                    exact, 20_000, x5.MAX_REPORT_BYTES, "BLENDLIB-X5-REFRESH-001", "Dev-refresh message"
                ),
            )
            self.assertLessEqual(exact.maximum_request, 8 * 1024)

            growth = TrackingBytesIO(b"growth")
            with self.assertRaisesRegex(x5.X5ToolingError, "changed while being read"):
                x5._read_bounded_stream(
                    growth, 3, x5.MAX_REPORT_BYTES, "BLENDLIB-X5-REFRESH-001", "Dev-refresh message"
                )
            self.assertLessEqual(growth.maximum_request, 8 * 1024)

            shrink = TrackingBytesIO(b"short")
            with self.assertRaisesRegex(x5.X5ToolingError, "changed while being read"):
                x5._read_bounded_stream(
                    shrink, 8, x5.MAX_REPORT_BYTES, "BLENDLIB-X5-REFRESH-001", "Dev-refresh message"
                )
            self.assertLessEqual(shrink.maximum_request, 8 * 1024)

            copied = io.BytesIO()
            legacy_source = TrackingBytesIO(b"p" * 20_000)
            legacy._copy_bounded_stream(legacy_source, copied, 20_000, legacy.MAX_PNG_BYTES)
            self.assertEqual(b"p" * 20_000, copied.getvalue())
            self.assertLessEqual(legacy_source.maximum_request, legacy.IO_BUFFER_BYTES)
            with self.assertRaisesRegex(legacy.ExportError, "grew"):
                legacy._copy_bounded_stream(TrackingBytesIO(b"growth"), io.BytesIO(), 3, legacy.MAX_PNG_BYTES)
            with self.assertRaisesRegex(legacy.ExportError, "changed"):
                legacy._copy_bounded_stream(TrackingBytesIO(b"short"), io.BytesIO(), 8, legacy.MAX_PNG_BYTES)

            with path.open("wb") as stream:
                stream.truncate(x5.MAX_RUNTIME_ARTIFACT_BYTES + 1)
            with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-005"):
                x5.sha256_file(path)
            with self.assertRaisesRegex(legacy.ExportError, "bounded size"):
                legacy.read_glb(path)

    def test_filesystem_refresh_is_hashed_fail_closed_and_one_second_debounced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact_relative = "build/asset.bin"
            artifact = root / "build" / "asset.bin"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"asset-v1")
            message = x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=7,
                artifact_hashes={artifact_relative: x5.sha256_file(artifact)},
                model_key="blendlib:hero/model",
            )
            x5.write_refresh_message(root, "build/refresh.json", message, runtime_resource_root="src/main/resources")
            receiver = x5.RefreshReceiver("test-session-token-1234", root)
            watcher = x5.FilesystemRefreshWatcher(root, "build/refresh.json", x5.DebouncedRefreshAdapter(receiver))
            self.assertFalse(watcher.poll_once(0))
            self.assertFalse(watcher.poll_once(999))
            self.assertTrue(watcher.poll_once(1_000))
            self.assertEqual(7, receiver.last_generation)
            with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-002"):
                receiver.receive(x5.RefreshMessage(
                    session_token="foreign-session-token-1234",
                    generation=8,
                    artifact_hashes={artifact_relative: x5.sha256_file(artifact)},
                    model_key="blendlib:hero/model",
                ))
            with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-004"):
                receiver.receive(message)
            with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-001"):
                x5.RefreshMessage.from_payload({
                    "format": x5.DEV_REFRESH_FORMAT,
                    "session_token": "test-session-token-1234",
                    "generation": 8,
                    "model_key": "blendlib:hero/model",
                    "artifact_hashes": {"../escape": "0" * 64},
                })
            bad_message = x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=8,
                artifact_hashes={artifact_relative: "0" * 64},
                model_key="blendlib:hero/model",
            )
            with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-005"):
                receiver.receive(bad_message)
            self.assertEqual(7, receiver.last_generation)
            with (root / "build" / "refresh.json").open("wb") as stream:
                stream.truncate(x5.MAX_REPORT_BYTES + 1)
            with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-001"):
                watcher.poll_once(2_000)

    def test_filesystem_refresh_watcher_rejects_runtime_message_without_advancing_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "build" / "asset.bin"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"asset")
            message = x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=9,
                artifact_hashes={"build/asset.bin": x5.sha256_file(artifact)},
                model_key="blendlib:hero/model",
            )
            runtime_message = root / "src" / "main" / "resources" / "refresh.json"
            runtime_message.parent.mkdir(parents=True)
            runtime_message.write_bytes(x5.pretty_json_bytes(message.to_payload()))
            receiver = x5.RefreshReceiver("test-session-token-1234", root)

            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.FilesystemRefreshWatcher(
                    root,
                    "src/main/resources/refresh.json",
                    x5.DebouncedRefreshAdapter(receiver),
                )
            self.assertEqual(-1, receiver.last_generation)

            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.FilesystemRefreshWatcher(
                    root,
                    "custom/runtime/refresh.json",
                    x5.DebouncedRefreshAdapter(receiver),
                    runtime_resource_roots=("custom/runtime",),
                )

            alias_root = Path(temporary) / "alias-project"
            alias_root.mkdir()
            alias_receiver = x5.RefreshReceiver("test-session-token-1234", alias_root)
            alias_watcher = x5.FilesystemRefreshWatcher(
                alias_root,
                "authoring/refresh.json",
                x5.DebouncedRefreshAdapter(alias_receiver),
            )
            alias_runtime = alias_root / "build" / "resources" / "main"
            alias_runtime.mkdir(parents=True)
            (alias_runtime / "refresh.json").write_bytes(x5.pretty_json_bytes(message.to_payload()))
            alias = alias_root / "authoring"
            try:
                alias.symlink_to(alias_runtime, target_is_directory=True)
            except OSError:
                junction = subprocess.run(
                    ["cmd.exe", "/d", "/c", "mklink", "/J", str(alias), str(alias_runtime)],
                    capture_output=True,
                    check=False,
                    text=True,
                )
                self.assertEqual(0, junction.returncode, junction.stdout + junction.stderr)
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                alias_watcher.poll_once(0)
            self.assertEqual(-1, alias_receiver.last_generation)

    def test_authoring_outputs_cannot_enter_runtime_resources(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_root = root / "src" / "main" / "resources"
            runtime_root.mkdir(parents=True)
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.require_non_runtime_output(
                    root, "src/main/resources", "src/main/resources/assets/session.json", "authoring output"
                )
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.require_non_runtime_output(
                    root, "src/main/resources", "build/resources/main/session.json", "authoring output"
                )
            common = {
                "authoring_output_root": "build/blendlib-authoring",
                "dev_refresh_path": None,
                "model_id": "hero",
                "namespace": "blendlib",
                "output_resource_root": "src/main/resources",
                "project_root": root,
                "report_path": None,
            }
            for label, overrides in (
                ("authoring root", {"authoring_output_root": "src/main/resources/authoring"}),
                ("compiled runtime alias", {"authoring_output_root": "build/resources/main/authoring"}),
                ("explicit report", {"report_path": runtime_root / "report.json"}),
                ("compiled explicit report", {"report_path": root / "build/resources/main/report.json"}),
                ("dev refresh", {"dev_refresh_path": runtime_root / "refresh.json"}),
                ("compiled dev refresh", {"dev_refresh_path": root / "build/resources/main/refresh.json"}),
            ):
                with self.subTest(label=label), self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                    x5._require_project_relative_options(SimpleNamespace(**(common | overrides)))
            message = x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=1,
                artifact_hashes={"build/asset.bin": "0" * 64},
                model_key="blendlib:hero",
            )
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.write_refresh_message(
                    root,
                    "src/main/resources/refresh.json",
                    message,
                    runtime_resource_root="src/main/resources",
                )
            self.assertFalse((runtime_root / "refresh.json").exists())
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.write_refresh_message(
                    root,
                    "build/resources/main/refresh.json",
                    message,
                    runtime_resource_root="src/main/resources",
                )

    def test_authoring_outputs_cannot_enter_runtime_resources_through_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_root = root / "src" / "main" / "resources"
            runtime_root.mkdir(parents=True)
            alias = root / "authoring-alias"
            try:
                alias.symlink_to(runtime_root, target_is_directory=True)
            except OSError as error:
                if os.name != "nt":
                    self.skipTest(f"directory symlink unavailable: {error}")
                junction = subprocess.run(
                    ["cmd.exe", "/d", "/c", "mklink", "/J", str(alias), str(runtime_root)],
                    capture_output=True,
                    check=False,
                    text=True,
                )
                if junction.returncode != 0:
                    self.skipTest("directory symlink and junction are unavailable")
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.require_non_runtime_output(
                    root, "src/main/resources", "authoring-alias/report.json", "authoring output"
                )

            compiled_runtime_root = root / "build" / "resources" / "main"
            compiled_runtime_root.mkdir(parents=True)
            compiled_alias = root / "compiled-authoring-alias"
            try:
                compiled_alias.symlink_to(compiled_runtime_root, target_is_directory=True)
            except OSError as error:
                if os.name != "nt":
                    self.skipTest(f"second directory symlink unavailable: {error}")
                junction = subprocess.run(
                    ["cmd.exe", "/d", "/c", "mklink", "/J", str(compiled_alias), str(compiled_runtime_root)],
                    capture_output=True,
                    check=False,
                    text=True,
                )
                if junction.returncode != 0:
                    self.skipTest("second directory symlink and junction are unavailable")
            with self.assertRaisesRegex(x5.X5ToolingError, "PATH-003"):
                x5.require_non_runtime_output(
                    root, "src/main/resources", "compiled-authoring-alias/report.json", "authoring output"
                )

    def test_canonical_json_uses_plain_finite_decimal_numbers(self) -> None:
        encoded = x5.canonical_json_bytes({
            "negative_zero": -0.0,
            "small": 1e-7,
            "large": 1e20,
        })
        self.assertEqual(
            b'{"large":100000000000000000000,"negative_zero":0,"small":0.0000001}',
            encoded,
        )
        for invalid in (math.nan, math.inf, -math.inf):
            with self.assertRaisesRegex(x5.X5ToolingError, "CANONICAL-001"):
                x5.canonical_json_bytes({"value": invalid})

    def test_mapping_inputs_accept_4096_and_reject_4097_before_staging(self) -> None:
        def actions(count: int) -> list[dict[str, object]]:
            return [
                {"frame_end": 2.0, "frame_start": 1.0, "name": f"action_{index:04d}"}
                for index in range(count)
            ]

        material_template = self.snapshot()["materials"][0]
        factories = {
            "actions": actions,
            "collections": lambda count: [
                {"name": f"group_{index:04d}", "objects": [], "triangle_count": 0}
                for index in range(count)
            ],
            "markers": lambda count: [
                {"frame": float(index), "name": f"event:event_{index:04d}"}
                for index in range(count)
            ],
            "materials": lambda count: [
                dict(material_template, name=f"material_{index:04d}")
                for index in range(count)
            ],
        }
        mapping_keys = {
            "actions": "action_animation_clips",
            "collections": "collection_groups_variants",
            "markers": "timeline_visual_events",
            "materials": "material_definitions",
        }
        over_result = None
        for field, factory in factories.items():
            with self.subTest(field=field, boundary="exact"):
                exact = self.snapshot()
                exact["objects"][0]["type"] = ""
                for other_field in factories:
                    exact[other_field] = []
                exact[field] = factory(x5.MAX_MAPPING_ITEMS)
                exact_result = x5.preflight_snapshot(exact)
                self.assertTrue(exact_result.ok, exact_result.report())
                exact_sidecar = x5.build_authoring_sidecar(exact)
                self.assertEqual(x5.MAX_MAPPING_ITEMS, len(exact_sidecar["mapping"][mapping_keys[field]]))
            with self.subTest(field=field, boundary="over"):
                over = self.snapshot()
                over["objects"][0]["type"] = ""
                for other_field in factories:
                    over[other_field] = []
                over[field] = factory(x5.MAX_MAPPING_ITEMS + 1)
                over_result = x5.preflight_snapshot(over)
                self.assertFalse(over_result.ok)
                self.assertTrue(any(
                    item.code == "BLENDLIB-X5-MAPPING-001" and item.location == f"scene/{field}"
                    for item in over_result.diagnostics
                ))
                with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-001"):
                    x5.build_authoring_sidecar(over)

        aggregate = self.snapshot()
        aggregate["objects"][0]["type"] = ""
        aggregate["collections"] = []
        aggregate["markers"] = []
        aggregate["actions"] = actions(x5.MAX_MAPPING_ITEMS // 2)
        aggregate["materials"] = factories["materials"](x5.MAX_MAPPING_ITEMS // 2)
        self.assertTrue(x5.preflight_snapshot(aggregate).ok)
        self.assertEqual(
            x5.MAX_MAPPING_ITEMS,
            sum(len(value) for value in x5.build_authoring_sidecar(aggregate)["mapping"].values()),
        )
        aggregate["markers"] = factories["markers"](1)
        aggregate_result = x5.preflight_snapshot(aggregate)
        self.assertTrue(any(item.code == "BLENDLIB-X5-MAPPING-002" for item in aggregate_result.diagnostics))
        with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-002"):
            x5.build_authoring_sidecar(aggregate)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            options = SimpleNamespace(
                authoring_output_root="build/blendlib-authoring",
                blend_path=root / "source.blend",
                dev_refresh_path=None,
                model_id="hero",
                namespace="blendlib",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                with self.assertRaisesRegex(x5.X5ToolingError, "PREFLIGHT-001"):
                    x5._prepare_x5_export(options, preflight=over_result)
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

    def test_mapping_iterables_are_bounded_frozen_once_and_fail_closed(self) -> None:
        material_template = self.snapshot()["materials"][0]

        def item_for(field: str, index: int) -> dict[str, object]:
            if field == "objects":
                return {"name": f"object_{index:04d}", "scale": [1.0, 1.0, 1.0], "type": "EMPTY"}
            if field == "actions":
                return {"frame_end": 2.0, "frame_start": 1.0, "name": f"action_{index:04d}"}
            if field == "collections":
                return {"name": f"group_{index:04d}", "objects": [], "triangle_count": 0}
            if field == "materials":
                return dict(material_template, name=f"material_{index:04d}")
            return {"frame": float(index), "name": f"event:event_{index:04d}"}

        class OneShotItems:
            def __init__(self, field: str, count: int, *, fail_at: int | None = None) -> None:
                self.field = field
                self.count = count
                self.fail_at = fail_at
                self.index = 0
                self.iterations = 0
                self.next_calls = 0

            def __iter__(self) -> "OneShotItems":
                self.iterations += 1
                if self.iterations > 1:
                    raise RuntimeError("secret-second-iteration-must-not-echo")
                return self

            def __next__(self) -> dict[str, object]:
                self.next_calls += 1
                if self.fail_at is not None and self.index == self.fail_at:
                    raise RuntimeError("secret-midstream-token-must-not-echo")
                if self.index >= self.count:
                    raise StopIteration
                value = item_for(self.field, self.index)
                self.index += 1
                return value

        class FailingFactory:
            def __iter__(self) -> object:
                raise RuntimeError("secret-factory-token-must-not-echo")

        mapping_keys = {
            "objects": "empty_sockets",
            "actions": "action_animation_clips",
            "collections": "collection_groups_variants",
            "markers": "timeline_visual_events",
            "materials": "material_definitions",
        }
        fields = tuple(mapping_keys)

        empty = self.snapshot()
        empty["actions"] = OneShotItems("actions", 0)
        empty_result = x5.preflight_snapshot(empty)
        self.assertTrue(empty_result.ok, empty_result.report())
        self.assertEqual(0, len(x5.build_authoring_sidecar(empty_result.snapshot)["mapping"]["action_animation_clips"]))
        self.assertEqual(1, empty["actions"].iterations)

        over_results = []
        for field in fields:
            with self.subTest(field=field, boundary="exact-generator"):
                exact = self.snapshot()
                baseline_object = dict(exact["objects"][0], type="")
                for other_field in fields:
                    exact[other_field] = []
                if field != "objects":
                    exact["objects"] = [baseline_object]
                source = OneShotItems(field, x5.MAX_MAPPING_ITEMS)
                exact[field] = source
                exact_result = x5.preflight_snapshot(exact)
                self.assertTrue(exact_result.ok, exact_result.report())
                self.assertIsInstance(exact_result.snapshot[field], tuple)
                exact_sidecar = x5.build_authoring_sidecar(exact_result.snapshot)
                self.assertEqual(x5.MAX_MAPPING_ITEMS, len(exact_sidecar["mapping"][mapping_keys[field]]))
                self.assertEqual(1, source.iterations)

            with self.subTest(field=field, boundary="over-generator"):
                over = self.snapshot()
                baseline_object = dict(over["objects"][0], type="")
                for other_field in fields:
                    over[other_field] = []
                if field != "objects":
                    over["objects"] = [baseline_object]
                source = OneShotItems(field, x5.MAX_MAPPING_ITEMS + 1)
                over[field] = source
                over_result = x5.preflight_snapshot(over)
                over_results.append(over_result)
                self.assertTrue(any(
                    item.code == "BLENDLIB-X5-MAPPING-001" and item.location == f"scene/{field}"
                    for item in over_result.diagnostics
                ), over_result.report())
                self.assertEqual(x5.MAX_MAPPING_ITEMS, len(over_result.snapshot[field]))
                self.assertEqual(1, source.iterations)
                self.assertEqual(x5.MAX_MAPPING_ITEMS + 1, source.next_calls)
                with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-001"):
                    x5.build_authoring_sidecar(over_result.snapshot)
                self.assertEqual(1, source.iterations)
                with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-001"):
                    direct = self.snapshot()
                    direct[field] = OneShotItems(field, x5.MAX_MAPPING_ITEMS + 1)
                    x5.build_authoring_sidecar(direct)

        failures = []
        for source in (FailingFactory(), OneShotItems("actions", 4, fail_at=2)):
            snapshot = self.snapshot()
            snapshot["actions"] = source
            result = x5.preflight_snapshot(snapshot)
            failures.append(result)
            rendered = json.dumps(result.report(), sort_keys=True)
            self.assertTrue(any(
                item.code == "BLENDLIB-X5-MAPPING-003" and item.location == "scene/actions"
                for item in result.diagnostics
            ), result.report())
            self.assertNotIn("secret-", rendered)
            with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-003") as frozen_raised:
                x5.build_authoring_sidecar(result.snapshot)
            self.assertNotIn("secret-", str(frozen_raised.exception))
        for source in (FailingFactory(), OneShotItems("actions", 4, fail_at=2)):
            snapshot = self.snapshot()
            snapshot["actions"] = source
            with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-003") as raised:
                x5.build_authoring_sidecar(snapshot)
            self.assertNotIn("secret-", str(raised.exception))

        aggregate = self.snapshot()
        aggregate["objects"][0]["type"] = ""
        aggregate["collections"] = []
        aggregate["markers"] = OneShotItems("markers", 1)
        aggregate["actions"] = OneShotItems("actions", x5.MAX_MAPPING_ITEMS // 2)
        aggregate["materials"] = OneShotItems("materials", x5.MAX_MAPPING_ITEMS // 2)
        aggregate_result = x5.preflight_snapshot(aggregate)
        self.assertTrue(any(item.code == "BLENDLIB-X5-MAPPING-002" for item in aggregate_result.diagnostics))
        with self.assertRaisesRegex(x5.X5ToolingError, "MAPPING-002"):
            x5.build_authoring_sidecar(aggregate_result.snapshot)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            options = SimpleNamespace(
                authoring_output_root="build/blendlib-authoring",
                blend_path=root / "source.blend",
                dev_refresh_path=None,
                model_id="hero",
                namespace="blendlib",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                for invalid in (*over_results, *failures, aggregate_result):
                    with self.assertRaisesRegex(x5.X5ToolingError, "PREFLIGHT-001"):
                        x5._prepare_x5_export(options, preflight=invalid)
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

    def test_source_blend_hash_uses_independent_authoring_cap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "large-source.blend"
            with source.open("wb") as stream:
                stream.truncate(legacy.MAX_GLB_BYTES + 1)

            digest = legacy.sha256_file(source)

            self.assertRegex(digest, r"^[0-9a-f]{64}$")
            self.assertLessEqual(legacy.IO_BUFFER_BYTES, 8 * 1024)
            tracked = TrackingBytesIO(b"x" * 20_000)
            self.assertRegex(legacy._sha256_bounded_stream(tracked, 20_000, 30_000), r"^[0-9a-f]{64}$")
            self.assertLessEqual(tracked.maximum_request, 8 * 1024)
            with self.assertRaisesRegex(legacy.ExportError, "grew"):
                legacy._sha256_bounded_stream(TrackingBytesIO(b"growth"), 3, 30_000)
            with self.assertRaisesRegex(legacy.ExportError, "shrank"):
                legacy._sha256_bounded_stream(TrackingBytesIO(b"short"), 8, 30_000)

    def test_batch_manifest_uses_project_relative_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = {
                "format": x5.BATCH_MANIFEST_FORMAT,
                "items": [
                    {"collection_name": None, "model_id": "b", "namespace": "blendlib", "profile": "blendlib:rigid_v1"},
                    {"collection_name": "Hero", "model_id": "a", "namespace": "blendlib", "profile": "blendlib:rigid_v1"},
                ],
            }
            (root / "x5").mkdir()
            (root / "x5" / "batch.json").write_bytes(x5.pretty_json_bytes(manifest))
            self.assertEqual(["a", "b"], [item.model_id for item in x5.load_batch_manifest(root, "x5/batch.json")])
            with (root / "x5" / "oversize.json").open("wb") as stream:
                stream.truncate(x5.MAX_REPORT_BYTES + 1)
            with self.assertRaisesRegex(x5.X5ToolingError, "BATCH-005"):
                x5.load_batch_manifest(root, "x5/oversize.json")

    def test_preflight_snapshot_is_deeply_immutable_and_has_no_source_aliases(self) -> None:
        source = self.snapshot()
        result = x5.preflight_snapshot(source)
        self.assertTrue(result.ok, result.report())
        before = x5.canonical_json_bytes(x5.build_authoring_sidecar(result.snapshot))

        source["namespace"] = "changed"
        source["objects"][0]["name"] = "ChangedRoot"
        source["objects"][0]["custom_properties"]["blendlib_display_name"] = "Changed"
        source["objects"].append({"name": "Late", "scale": [1, 1, 1], "type": "EMPTY"})
        self.assertEqual(before, x5.canonical_json_bytes(x5.build_authoring_sidecar(result.snapshot)))
        self.assertEqual("blendlib", result.snapshot["namespace"])
        self.assertIsInstance(result.snapshot["objects"], tuple)
        with self.assertRaises(TypeError):
            result.snapshot["namespace"] = "mutated"  # type: ignore[index]
        with self.assertRaises(TypeError):
            result.snapshot["objects"][0]["name"] = "mutated"  # type: ignore[index]

        cyclic = self.snapshot()
        cycle: list[object] = []
        cycle.append(cycle)
        cyclic["objects"][0]["custom_properties"] = {"blendlib_cycle": cycle}
        cyclic_result = x5.preflight_snapshot(cyclic)
        self.assertFalse(cyclic_result.ok)
        self.assertIn("BLENDLIB-X5-SNAPSHOT-001", [item.code for item in cyclic_result.diagnostics])

    def test_all_preflight_error_families_block_sidecar_and_stage_while_warns_pass(self) -> None:
        cases = {}
        for name in ("SCENE", "TRANSFORM", "MATERIAL", "METADATA", "PATH", "COORD"):
            cases[name] = self.snapshot()
        cases["SCENE"]["root_count"] = 0
        cases["TRANSFORM"]["objects"][0]["scale"] = [1, 2, 1]
        cases["MATERIAL"]["materials"][0]["external"] = False
        cases["METADATA"]["objects"][0]["custom_properties"] = {"blendlib_value": 10 ** 10_000}
        cases["PATH"]["output_resource_root"] = "C:/unsafe"
        cases["COORD"]["units_per_block"] = 2

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            options = SimpleNamespace(
                authoring_output_root="build/blendlib-authoring",
                blend_path=root / "source.blend",
                dev_refresh_path=None,
                model_id="hero",
                namespace="blendlib",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                for family, snapshot in cases.items():
                    with self.subTest(family=family):
                        result = x5.preflight_snapshot(snapshot)
                        self.assertFalse(result.ok, result.report())
                        expected_family = "TEXTURE" if family == "MATERIAL" else family
                        self.assertTrue(any(f"-{expected_family}-" in item.code for item in result.diagnostics), result.report())
                        with self.assertRaises(x5.X5ToolingError):
                            x5.build_authoring_sidecar(snapshot)
                        with self.assertRaises(x5.X5ToolingError):
                            x5.build_authoring_sidecar(result.snapshot)
                        with self.assertRaises(x5.X5ToolingError):
                            x5._prepare_x5_export(options, preflight=result)
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

        warning = self.snapshot()
        warning["collections"][1]["triangle_count"] = 100_001
        warning_result = x5.preflight_snapshot(warning)
        self.assertTrue(warning_result.ok, warning_result.report())
        self.assertIn("BLENDLIB-X5-LOD-003", [item.code for item in warning_result.diagnostics])
        self.assertEqual(
            x5.canonical_json_bytes(x5.build_authoring_sidecar(warning_result.snapshot)),
            x5.canonical_json_bytes(x5.build_authoring_sidecar(warning_result.snapshot)),
        )

    def test_report_and_refresh_writers_enforce_canonical_512_kib_before_write(self) -> None:
        exact = {"padding": ""}
        exact["padding"] = "x" * (x5.MAX_REPORT_BYTES - len(x5.canonical_json_bytes(exact)))
        self.assertEqual(x5.MAX_REPORT_BYTES, len(x5.asset_report_bytes(exact)))
        exact["padding"] += "x"
        with self.assertRaisesRegex(x5.X5ToolingError, "REPORT-002"):
            x5.asset_report_bytes(exact)

        refresh_base = x5.RefreshMessage(
            session_token="test-session-token-1234",
            generation=1,
            artifact_hashes={"build/.bin": "0" * 64},
            model_key="blendlib:hero",
        )
        refresh_padding = x5.MAX_REPORT_BYTES - len(x5.refresh_message_bytes(refresh_base))
        exact_refresh = x5.RefreshMessage(
            session_token="test-session-token-1234",
            generation=1,
            artifact_hashes={f"build/{'x' * refresh_padding}.bin": "0" * 64},
            model_key="blendlib:hero",
        )
        self.assertEqual(x5.MAX_REPORT_BYTES, len(x5.refresh_message_bytes(exact_refresh)))
        over_refresh = x5.RefreshMessage(
            session_token="test-session-token-1234",
            generation=1,
            artifact_hashes={f"build/{'x' * (refresh_padding + 1)}.bin": "0" * 64},
            model_key="blendlib:hero",
        )
        with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-001"):
            x5.refresh_message_bytes(over_refresh)

        warnings = tuple(
            x5.ToolingDiagnostic(
                "WARN", "BLENDLIB-X5-LOD-003", f"collection:LOD_{index}",
                "LOD exceeds the X5 performance warning budget.",
                "Reduce triangles or document the intended budget.",
            )
            for index in range(x5.MAX_MAPPING_ITEMS)
        )
        report = x5.build_asset_report(
            snapshot=self.snapshot(),
            sidecar=x5.build_authoring_sidecar(self.snapshot()),
            validation={"index_count": 3, "material_names": ["HeroMaterial"], "vertex_count": 3},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero.glb": b"glb"},
            diagnostics=warnings,
        )
        with self.assertRaisesRegex(x5.X5ToolingError, "REPORT-002"):
            x5.asset_report_bytes(report)

        warning_snapshot = self.snapshot()
        warning_snapshot["objects"] = [{"name": "Root", "scale": [1, 1, 1], "type": ""}]
        warning_snapshot["actions"] = []
        warning_snapshot["materials"] = []
        warning_snapshot["markers"] = []
        warning_snapshot["collections"] = [
            {"name": f"LOD_{index}", "objects": [], "triangle_count": 100_001}
            for index in range(x5.MAX_MAPPING_ITEMS)
        ]
        warning_preflight = x5.preflight_snapshot(warning_snapshot)
        self.assertTrue(warning_preflight.ok, warning_preflight.report())
        self.assertEqual(x5.MAX_MAPPING_ITEMS, len(warning_preflight.diagnostics))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            options = SimpleNamespace(
                authoring_output_root="build/blendlib-authoring",
                blend_path=root / "source.blend",
                dev_refresh_path=None,
                model_id="hero",
                namespace="blendlib",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                with self.assertRaisesRegex(x5.X5ToolingError, "REPORT-002"):
                    x5._prepare_x5_export(options, preflight=warning_preflight)
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

        artifact_hashes = {
            f"build/artifacts/{index:04d}-{'x' * 40}.bin": "0" * 64
            for index in range(5_000)
        }
        with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-001"):
            x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=1,
                artifact_hashes=artifact_hashes,
                model_key="blendlib:hero",
            )
        message = x5.RefreshMessage(
            session_token="test-session-token-1234",
            generation=1,
            artifact_hashes={f"build/{'x' * x5.MAX_REPORT_BYTES}.bin": "0" * 64},
            model_key="blendlib:hero",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original_atomic = x5.atomic_write_bundle
            try:
                x5.atomic_write_bundle = lambda *args, **kwargs: (_ for _ in ()).throw(
                    AssertionError("atomic writer must not run")
                )
                with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-001"):
                    x5.write_refresh_message(
                        root, "build/refresh.json", message, runtime_resource_root="src/main/resources"
                    )
            finally:
                x5.atomic_write_bundle = original_atomic
            self.assertFalse((root / "build" / "refresh.json").exists())

    def test_extreme_metadata_numbers_fail_closed_without_conversion_or_value_echo(self) -> None:
        for value in (10 ** 10_000, Decimal("1e10000"), Decimal("NaN")):
            with self.subTest(kind=type(value).__name__):
                snapshot = self.snapshot()
                snapshot["objects"][0]["custom_properties"] = {"blendlib_value": value}
                result = x5.preflight_snapshot(snapshot)
                self.assertFalse(result.ok)
                self.assertIn("BLENDLIB-X5-METADATA-002", [item.code for item in result.diagnostics])
                rendered = json.dumps(result.report(), sort_keys=True)
                self.assertLess(len(rendered), 16_384)
                self.assertNotIn("100000000000000000000", rendered)
                with self.assertRaisesRegex(x5.X5ToolingError, "METADATA-002"):
                    x5.build_authoring_sidecar(result.snapshot)

        class HostileDecimalLike:
            float_called = False
            string_called = False

            def __float__(self) -> float:
                self.float_called = True
                raise OverflowError("private-float-value")

            def __str__(self) -> str:
                self.string_called = True
                raise RuntimeError("private-string-value")

        hostile = HostileDecimalLike()
        snapshot = self.snapshot()
        snapshot["objects"][0]["custom_properties"] = {"blendlib_value": hostile}
        result = x5.preflight_snapshot(snapshot)
        self.assertFalse(result.ok)
        self.assertIn("BLENDLIB-X5-METADATA-002", [item.code for item in result.diagnostics])
        self.assertFalse(hostile.float_called)
        self.assertFalse(hostile.string_called)
        self.assertNotIn("private-", json.dumps(result.report(), sort_keys=True))

    def test_frozen_snapshot_provenance_cannot_be_forged_or_mutated(self) -> None:
        source = self.snapshot()
        warning = x5.ToolingDiagnostic(
            "WARN", "BLENDLIB-X5-LOD-003", "collection:LOD_1",
            "LOD exceeds the X5 performance warning budget.",
            "Reduce triangles or document the intended budget.",
        )
        for diagnostics in ((), (warning,), (warning, warning)):
            with self.subTest(direct_constructor=len(diagnostics)), self.assertRaisesRegex(
                x5.X5ToolingError, "SNAPSHOT-001"
            ):
                x5._FrozenSnapshot(source, diagnostics)
        with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
            x5._FrozenSnapshot(
                x5.MappingProxyType(dict(source)), (), _provenance=object()
            )

        def forged_snapshot(values: dict, diagnostics: tuple) -> object:
            forged = object.__new__(x5._FrozenSnapshot)
            object.__setattr__(forged, "_values", x5.MappingProxyType(dict(values)))
            object.__setattr__(forged, "_diagnostics", diagnostics)
            return forged

        forged_variants = tuple(
            forged_snapshot(source, diagnostics)
            for diagnostics in ((), (warning,), (warning, warning))
        )
        real_token_but_unregistered = x5._FrozenSnapshot(
            x5.MappingProxyType(dict(source)),
            (),
            _provenance=x5._FROZEN_SNAPSHOT_PROVENANCE,
        )
        forged_variants += (real_token_but_unregistered,)
        source["root_count"] = 0
        source["objects"][0]["name"] = "MutatedAfterForgery"
        for forged in forged_variants:
            with self.subTest(diagnostics=len(forged.diagnostics)):
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5.build_authoring_sidecar(forged)

        invalid = self.snapshot()
        invalid["root_count"] = 0
        invalid_result = x5.preflight_snapshot(invalid)
        forged_without_errors = forged_snapshot(dict(invalid_result.snapshot), ())
        with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
            x5.build_authoring_sidecar(forged_without_errors)

        valid_result = x5.preflight_snapshot(self.snapshot())
        self.assertIs(valid_result.snapshot, x5._sidecar_snapshot(valid_result.snapshot))
        for operation in (copy.copy, copy.deepcopy, pickle.dumps):
            with self.subTest(operation=operation.__name__), self.assertRaisesRegex(
                x5.X5ToolingError, "SNAPSHOT-001"
            ):
                operation(valid_result.snapshot)

        tampered = x5.preflight_snapshot(self.snapshot()).snapshot
        object.__setattr__(tampered, "_diagnostics", (warning,))
        with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
            x5.build_authoring_sidecar(tampered)
        tampered = x5.preflight_snapshot(self.snapshot()).snapshot
        object.__setattr__(tampered, "_values", x5.MappingProxyType(dict(tampered)))
        with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
            x5.build_authoring_sidecar(tampered)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            options = SimpleNamespace(
                authoring_output_root="build/blendlib-authoring",
                blend_path=root / "source.blend",
                dev_refresh_path=None,
                model_id="hero",
                namespace="blendlib",
                output_resource_root="src/main/resources",
                profile="blendlib:rigid_v1",
                project_root=root,
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(AssertionError("legacy exporter must not run"))
                forged = forged_snapshot(self.snapshot(), ())
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(options, preflight=x5.PreflightResult((), forged))
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

    def test_trusted_snapshot_error_diagnostic_cannot_be_mutated_to_warning(self) -> None:
        invalid = self.snapshot()
        invalid["root_count"] = 0
        result = x5.preflight_snapshot(invalid)
        result_error = next(item for item in result.diagnostics if item.severity == "ERROR")
        error = next(item for item in result.snapshot.diagnostics if item.severity == "ERROR")
        self.assertIsNot(result_error, error)
        object.__setattr__(result_error, "severity", "WARN")
        object.__setattr__(error, "severity", "WARN")

        with self.assertRaisesRegex(x5.X5ToolingError, "SCENE-002"):
            x5.build_authoring_sidecar(result.snapshot)

        mutations = {
            "severity": "WARN",
            "code": "BLENDLIB-X5-MUTATED-001",
            "location": "mutated-private-location",
            "message": "mutated-private-message",
            "remediation": "mutated-private-remediation",
        }
        for field, replacement in mutations.items():
            with self.subTest(field=field):
                invalid = self.snapshot()
                invalid["root_count"] = 0
                result = x5.preflight_snapshot(invalid)
                error = next(item for item in result.snapshot.diagnostics if item.code == "BLENDLIB-X5-SCENE-002")
                original = (
                    error.severity,
                    error.code,
                    error.location,
                    error.message,
                    error.remediation,
                )
                object.__setattr__(error, field, replacement)
                state = x5._trusted_snapshot_state(result.snapshot)
                self.assertIsNotNone(state)
                self.assertIn(original, state.diagnostic_records)
                with self.assertRaisesRegex(x5.X5ToolingError, "SCENE-002") as raised:
                    x5.build_authoring_sidecar(result.snapshot)
                self.assertNotIn("mutated-private", str(raised.exception))

    def test_trusted_snapshot_registry_uses_exact_identity_not_equality(self) -> None:
        original_eq = x5._FrozenSnapshot.__eq__
        original_hash = x5._FrozenSnapshot.__hash__
        try:
            x5._FrozenSnapshot.__eq__ = lambda self, other: True
            x5._FrozenSnapshot.__hash__ = lambda self: 1
            trusted = x5.preflight_snapshot(self.snapshot()).snapshot
            forged = object.__new__(x5._FrozenSnapshot)
            object.__setattr__(forged, "_values", trusted._values)
            object.__setattr__(forged, "_diagnostics", trusted._diagnostics)

            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5.build_authoring_sidecar(forged)

            class HostileEqualSnapshot(x5._FrozenSnapshot):
                __slots__ = ()

                def __eq__(self, other: object) -> bool:
                    raise AssertionError("snapshot equality must not run")

                def __hash__(self) -> int:
                    raise AssertionError("snapshot hashing must not run")

            derived = object.__new__(HostileEqualSnapshot)
            object.__setattr__(derived, "_values", trusted._values)
            object.__setattr__(derived, "_diagnostics", trusted._diagnostics)
            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5.build_authoring_sidecar(derived)
        finally:
            x5._FrozenSnapshot.__eq__ = original_eq
            x5._FrozenSnapshot.__hash__ = original_hash

        first = x5.preflight_snapshot(self.snapshot()).snapshot
        second = x5.preflight_snapshot(self.snapshot()).snapshot
        self.assertIsNot(first, second)
        self.assertIs(first, x5._sidecar_snapshot(first))
        self.assertIs(second, x5._sidecar_snapshot(second))
        first_state = x5._TRUSTED_SNAPSHOT_STATES[id(first)]
        second_state = x5._TRUSTED_SNAPSHOT_STATES[id(second)]
        self.assertIs(first, first_state.snapshot_ref())
        self.assertIs(second, second_state.snapshot_ref())

        replacement_generation = object()
        replacement_state = first_state._replace(generation=replacement_generation)
        x5._TRUSTED_SNAPSHOT_STATES[id(first)] = replacement_state
        try:
            x5._release_trusted_snapshot(
                id(first),
                first_state.generation,
                first_state.snapshot_ref,
            )
            self.assertIs(replacement_state, x5._TRUSTED_SNAPSHOT_STATES[id(first)])
        finally:
            x5._TRUSTED_SNAPSHOT_STATES[id(first)] = first_state

        gc.collect()
        baseline_size = len(x5._TRUSTED_SNAPSHOT_STATES)
        snapshots = [x5.preflight_snapshot(self.snapshot()).snapshot for _ in range(24)]
        snapshot_refs = [x5.ref(snapshot) for snapshot in snapshots]
        self.assertEqual(baseline_size + len(snapshots), len(x5._TRUSTED_SNAPSHOT_STATES))
        del snapshots
        gc.collect()
        self.assertTrue(all(snapshot_ref() is None for snapshot_ref in snapshot_refs))
        self.assertEqual(baseline_size, len(x5._TRUSTED_SNAPSHOT_STATES))

        released_ids: set[int] = set()
        observed_reuse = False
        for _ in range(256):
            candidate = x5._new_frozen_snapshot(x5.MappingProxyType({}), (), trusted=True)
            candidate_id = id(candidate)
            observed_reuse = observed_reuse or candidate_id in released_ids
            self.assertIs(candidate, x5._trusted_snapshot_state(candidate).snapshot_ref())
            released_ids.add(candidate_id)
            del candidate
        gc.collect()
        self.assertEqual(baseline_size, len(x5._TRUSTED_SNAPSHOT_STATES))
        self.assertTrue(observed_reuse, "CPython identity reuse probe did not exercise an ABA identity")

    def test_integer_only_authoring_fields_reject_bool_and_int_subclasses(self) -> None:
        class IntegerEnum(IntEnum):
            ZERO = 0
            ONE = 1

        class IntegerSubclass(int):
            pass

        invalid_integers = (False, True, IntegerEnum.ZERO, IntegerEnum.ONE, IntegerSubclass(0), IntegerSubclass(1))
        for value in invalid_integers:
            with self.subTest(field="generation", value_type=type(value).__name__):
                with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-003"):
                    x5.RefreshMessage(
                        session_token="test-session-token-1234",
                        generation=value,
                        artifact_hashes={"build/asset.bin": "0" * 64},
                        model_key="blendlib:hero",
                    )
                with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-001"):
                    x5.RefreshMessage.from_payload({
                        "artifact_hashes": {"build/asset.bin": "0" * 64},
                        "format": x5.DEV_REFRESH_FORMAT,
                        "generation": value,
                        "model_key": "blendlib:hero",
                        "session_token": "test-session-token-1234",
                    })
            with self.subTest(field="root_count", value_type=type(value).__name__):
                snapshot = self.snapshot()
                snapshot["root_count"] = value
                result = x5.preflight_snapshot(snapshot)
                self.assertFalse(result.ok)
                self.assertIn("BLENDLIB-X5-SCENE-002", [item.code for item in result.diagnostics])
            with self.subTest(field="triangle_count", value_type=type(value).__name__):
                snapshot = self.snapshot()
                snapshot["collections"][1]["triangle_count"] = value
                result = x5.preflight_snapshot(snapshot)
                self.assertFalse(result.ok)
                self.assertIn("BLENDLIB-X5-LOD-002", [item.code for item in result.diagnostics])

        for value in (True, IntegerEnum.ONE, IntegerSubclass(3)):
            snapshot = self.snapshot()
            snapshot["objects"][1]["face_vertex_counts"] = [value]
            result = x5.preflight_snapshot(snapshot)
            self.assertFalse(result.ok)
            self.assertIn("BLENDLIB-X5-MESH-001", [item.code for item in result.diagnostics])

        for field in ("index_count", "vertex_count"):
            for value in (False, True, IntegerEnum.ONE, IntegerSubclass(1), -1, 2 ** 63):
                with self.subTest(field=field, value_type=type(value).__name__, value=value):
                    validation = {"index_count": 0, "material_names": [], "vertex_count": 0}
                    validation[field] = value
                    with self.assertRaisesRegex(x5.X5ToolingError, "REPORT-002"):
                        x5.build_asset_report(
                            snapshot=self.snapshot(),
                            sidecar=x5.build_authoring_sidecar(self.snapshot()),
                            validation=validation,
                            artifacts={"build/asset.bin": b"asset"},
                            diagnostics=(),
                        )
            validation = {"index_count": 0, "material_names": [], "vertex_count": 0}
            validation[field] = 2 ** 63 - 1
            report = x5.build_asset_report(
                snapshot=self.snapshot(),
                sidecar=x5.build_authoring_sidecar(self.snapshot()),
                validation=validation,
                artifacts={"build/asset.bin": b"asset"},
                diagnostics=(),
            )
            expected = (2 ** 63 - 1) // 3 if field == "index_count" else 2 ** 63 - 1
            report_field = "triangles" if field == "index_count" else "vertices"
            self.assertEqual(expected, report["counts"][report_field])

        for generation in (0, 1, 2 ** 63 - 1):
            message = x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=generation,
                artifact_hashes={"build/asset.bin": "0" * 64},
                model_key="blendlib:hero",
            )
            self.assertEqual(generation, message.generation)
        with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-003"):
            x5.RefreshMessage(
                session_token="test-session-token-1234",
                generation=2 ** 63,
                artifact_hashes={"build/asset.bin": "0" * 64},
                model_key="blendlib:hero",
            )

        for triangles in (0, 1, 2 ** 63 - 1):
            snapshot = self.snapshot()
            snapshot["collections"][1]["triangle_count"] = triangles
            result = x5.preflight_snapshot(snapshot)
            self.assertTrue(result.ok, result.report())
        snapshot = self.snapshot()
        snapshot["collections"][1]["triangle_count"] = 2 ** 63
        result = x5.preflight_snapshot(snapshot)
        self.assertFalse(result.ok)
        self.assertIn("BLENDLIB-X5-LOD-002", [item.code for item in result.diagnostics])

        snapshot = self.snapshot()
        snapshot["collections"][1]["name"] = f"LOD_{2 ** 63}"
        result = x5.preflight_snapshot(snapshot)
        self.assertFalse(result.ok)
        self.assertIn("BLENDLIB-X5-LOD-002", [item.code for item in result.diagnostics])
        snapshot = self.snapshot()
        snapshot["collections"][1]["name"] = f"LOD_{2 ** 63 - 1}"
        result = x5.preflight_snapshot(snapshot)
        self.assertTrue(result.ok, result.report())

        receiver = x5.RefreshReceiver("test-session-token-1234", Path.cwd())
        adapter = x5.DebouncedRefreshAdapter(receiver)
        for value in (*invalid_integers, -1, 2 ** 63):
            with self.subTest(field="refresh_clock", value_type=type(value).__name__):
                with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-003"):
                    adapter.tick(value)
        self.assertFalse(adapter.tick(2 ** 63 - 1))
        for value in invalid_integers:
            with self.subTest(field="refresh_idle", value_type=type(value).__name__):
                with self.assertRaisesRegex(x5.X5ToolingError, "REFRESH-003"):
                    x5.DebouncedRefreshAdapter(receiver, idle_millis=value)


if __name__ == "__main__":
    unittest.main()
