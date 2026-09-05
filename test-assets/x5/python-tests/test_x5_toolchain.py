"""Standard-library X5 contract tests; intentionally outside packaged addon paths."""

from __future__ import annotations

import copy
import dataclasses
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


class FakeNormal(tuple):
    @property
    def length(self) -> float:
        return math.sqrt(sum(float(component) * float(component) for component in self))


class X5ToolchainTest(unittest.TestCase):
    def snapshot(self) -> dict:
        return json.loads((Path(__file__).with_name("valid_snapshot.json")).read_text(encoding="utf-8"))

    def _strict_stage_exporter(
        self,
        authorized_root: Path,
        export_calls: list[str] | None = None,
    ) -> type:
        """Return a deterministic legacy seam that writes only strict-v1 stage bytes."""

        class DeterministicLegacyExporter:
            @staticmethod
            def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                return (authorized_root,)

            @staticmethod
            def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                if export_calls is not None:
                    export_calls.append(stage_options.model_id)
                for kind, relative in legacy.strict_v1_artifact_paths(
                    stage_options,
                    ("HeroMaterial",),
                ).items():
                    target = stage_options.project_root / Path(*relative.split("/"))
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(kind.encode("ascii"))
                return {
                    "validation": {
                        "index_count": 3,
                        "material_names": ["HeroMaterial"],
                        "vertex_count": 3,
                    }
                }

        return DeterministicLegacyExporter

    def skinned_snapshot(self) -> dict:
        snapshot = self.snapshot()
        snapshot["profile"] = "blendlib:skinned_v1"
        snapshot["objects"][1]["skin_binding"] = {
            "armature_modifiers": [{
                "name": "Armature",
                "target": "HeroRig",
                "target_bones": ["root", "hand"],
                "target_exported": True,
                "target_type": "ARMATURE",
            }],
            "profile": "blendlib:skinned_v1",
            "vertex_group_assignments": [
                [{"name": "root", "weight": 1.0}],
                [{"name": "root", "weight": 0.5}, {"name": "hand", "weight": 0.5}],
                [{"name": "hand", "weight": 1.0}],
            ],
        }
        return snapshot

    def legacy_skin_scene(
        self,
        assignments: list[list[tuple[str, float]]],
        *,
        bone_names: tuple[str, ...] = ("root",),
        mesh_count: int = 1,
    ) -> tuple[list[SimpleNamespace], SimpleNamespace]:
        armature = SimpleNamespace(
            constraints=[],
            data=SimpleNamespace(bones=[SimpleNamespace(name=name) for name in bone_names]),
            modifiers=[],
            name="HeroRig",
            particle_systems=[],
            rigid_body=None,
            rigid_body_constraint=None,
            scale=(1.0, 1.0, 1.0),
            type="ARMATURE",
        )
        meshes = []
        for mesh_index in range(mesh_count):
            group_names = list(dict.fromkeys(name for vertex in assignments for name, unused in vertex))
            group_indices = {name: index for index, name in enumerate(group_names)}
            vertices = [
                SimpleNamespace(
                    groups=[
                        SimpleNamespace(group=group_indices[name], weight=weight)
                        for name, weight in vertex_assignments
                    ],
                    index=vertex_index,
                    normal=FakeNormal((0.0, 0.0, 1.0)),
                )
                for vertex_index, vertex_assignments in enumerate(assignments)
            ]
            meshes.append(SimpleNamespace(
                constraints=[],
                data=SimpleNamespace(
                    polygons=[SimpleNamespace(vertices=(0, 1, 2))],
                    uv_layers=SimpleNamespace(active=SimpleNamespace(data=[object()])),
                    vertices=vertices,
                ),
                material_slots=[SimpleNamespace(material=SimpleNamespace(name="HeroMaterial"))],
                modifiers=[SimpleNamespace(name="Armature", object=armature, type="ARMATURE")],
                name=f"HeroMesh{mesh_index}",
                particle_systems=[],
                rigid_body=None,
                rigid_body_constraint=None,
                scale=(1.0, 1.0, 1.0),
                type="MESH",
                vertex_groups=[
                    SimpleNamespace(index=index, name=name)
                    for name, index in group_indices.items()
                ],
            ))
        return [armature, *meshes], armature

    def test_import_is_bpy_free_and_preflight_is_deterministic(self) -> None:
        self.assertFalse(hasattr(x5, "bpy"))
        first = x5.preflight_snapshot(self.snapshot())
        second = x5.preflight_snapshot(self.snapshot())
        self.assertTrue(first.ok, first.report())
        self.assertEqual(first.report(), second.report())
        self.assertEqual([], [item for item in first.diagnostics if item.severity == "ERROR"])

    def test_preflight_snapshot_detaches_derived_strings_before_plan_serialization(self) -> None:
        """Mutable str subclasses cannot alter trusted sidecar bytes after preflight."""

        class RoutedStr(str):
            def __new__(cls, value: str, routed: str) -> "RoutedStr":
                instance = super().__new__(cls, value)
                instance.routed = routed
                return instance

            def __iter__(self):
                return iter(self.routed)

        snapshot = {
            RoutedStr(key, key): value
            for key, value in self.snapshot().items()
        }
        routed_namespace = RoutedStr("blendlib", "blendlib")
        snapshot["namespace"] = routed_namespace
        result = x5.preflight_snapshot(snapshot)
        self.assertTrue(result.ok, result.report())
        self.assertTrue(all(type(key) is str for key in result.snapshot))
        self.assertIsNot(routed_namespace, result.snapshot["namespace"])
        self.assertIs(str, type(result.snapshot["namespace"]))
        self.assertEqual("blendlib", result.snapshot["namespace"])

        routed_namespace.routed = "attacker"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root / "project",
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )

            class NamingOnlyLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: NamingOnlyLegacyExporter
                plan = x5._build_export_plan(options, preflight=result)
                _, _, state = x5._trusted_export_plan_state(plan)
                sidecar = json.loads(state.sidecar_payload.decode("utf-8"))
            finally:
                x5._legacy_exporter = original_exporter

        self.assertEqual("blendlib", sidecar["model"]["namespace"])
        self.assertNotIn("attacker", state.sidecar_payload.decode("utf-8"))

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
        snapshot = self.skinned_snapshot()
        mesh = snapshot["objects"][1]
        mesh["uv0"] = False
        mesh["skin_binding"]["vertex_group_assignments"][0] = [
            {"name": "root", "weight": 0.6},
            {"name": "hand", "weight": 0.3},
            {"name": "root", "weight": 0.2},
            {"name": "hand", "weight": 0.1},
            {"name": "root", "weight": 0.0},
        ]
        mesh["custom_properties"] = {"blendlib_token": "secret-value"}
        snapshot["objects"][2]["bones"][1]["parent"] = "missing"
        result = x5.preflight_snapshot(snapshot)
        codes = [item.code for item in result.diagnostics]
        self.assertFalse(result.ok)
        self.assertIn("BLENDLIB-X5-MESH-002", codes)
        self.assertIn("BLENDLIB-X5-WEIGHT-001", codes)
        self.assertIn("BLENDLIB-X5-METADATA-002", codes)
        self.assertIn("BLENDLIB-X5-ARMATURE-002", codes)

    def test_frozen_snapshot_repreflight_fails_closed_before_any_checks(self) -> None:
        cases = []
        warning = self.snapshot()
        warning["collections"][1]["triangle_count"] = 100_001
        cases.append(("warning", x5.preflight_snapshot(warning)))
        invalid = self.snapshot()
        invalid["root_count"] = 0
        cases.append(("error", x5.preflight_snapshot(invalid)))

        original_freeze = x5._freeze_mapping_snapshot
        snapshots_before = len(x5._TRUSTED_SNAPSHOT_STATES)
        results_before = len(x5._TRUSTED_PREFLIGHT_STATES)
        try:
            x5._freeze_mapping_snapshot = lambda *unused: (_ for _ in ()).throw(
                AssertionError("frozen re-preflight reached snapshot checks")
            )
            for label, result in cases:
                with self.subTest(label=label), self.assertRaisesRegex(
                    x5.X5ToolingError, "BLENDLIB-X5-SNAPSHOT-001"
                ):
                    x5.preflight_snapshot(result.snapshot)
        finally:
            x5._freeze_mapping_snapshot = original_freeze

        self.assertEqual(snapshots_before, len(x5._TRUSTED_SNAPSHOT_STATES))
        self.assertEqual(results_before, len(x5._TRUSTED_PREFLIGHT_STATES))
        self.assertEqual(1, len(cases[0][1].diagnostics))
        self.assertEqual(1, len(cases[1][1].diagnostics))
        warning_result = cases[0][1]
        sidecar = x5.build_authoring_sidecar(warning_result.snapshot)
        report = x5.build_asset_report(
            snapshot=warning_result.snapshot,
            sidecar=sidecar,
            validation={"index_count": 3, "material_names": ["HeroMaterial"], "vertex_count": 3},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero.glb": b"glb"},
            diagnostics=warning_result.diagnostics,
        )
        self.assertEqual(1, len(report["diagnostics"]))
        self.assertEqual(report["diagnostics"], report["performance_warnings"])
        with self.assertRaisesRegex(x5.X5ToolingError, "BLENDLIB-X5-SCENE-002"):
            x5.build_authoring_sidecar(cases[1][1].snapshot)

    def test_rigid_decorative_vertex_groups_are_not_runtime_skin_weights(self) -> None:
        snapshot = self.snapshot()
        snapshot["objects"][1]["skin_binding"]["vertex_group_assignments"] = [
            [{"name": "decor_half", "weight": 0.5}],
            [{"name": "decor_full", "weight": 1.0}],
            [],
        ]
        result = x5.preflight_snapshot(snapshot)

        self.assertTrue(result.ok, result.report())
        self.assertFalse(any(item.code.startswith("BLENDLIB-X5-WEIGHT-") for item in result.diagnostics))
        sidecar = x5.build_authoring_sidecar(result.snapshot)
        report = x5.build_asset_report(
            snapshot=result.snapshot,
            sidecar=sidecar,
            validation={"index_count": 3, "material_names": ["HeroMaterial"], "vertex_count": 3},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero.glb": b"glb"},
            diagnostics=(),
        )
        self.assertEqual(0, report["counts"]["vertex_weight_records"])

    def test_skinned_runtime_influences_validate_and_match_java_report_count(self) -> None:
        snapshot = self.skinned_snapshot()
        snapshot["objects"][1]["skin_binding"]["vertex_group_assignments"][1].append(
            {"name": "decorative_not_a_bone", "weight": 0.75}
        )
        result = x5.preflight_snapshot(snapshot)

        self.assertTrue(result.ok, result.report())
        sidecar = x5.build_authoring_sidecar(result.snapshot)
        report = x5.build_asset_report(
            snapshot=result.snapshot,
            sidecar=sidecar,
            validation={"index_count": 9, "material_names": ["HeroMaterial"], "vertex_count": 7},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero.glb": b"glb"},
            diagnostics=(),
        )
        self.assertEqual(7, report["counts"]["vertex_weight_records"])

    def test_legacy_skinned_validation_uses_only_bound_target_bone_groups(self) -> None:
        assignments = [
            [("root", 1.0), ("decorative_positive", 0.75), ("decorative_zero", 0.0)]
            for unused_index in range(3)
        ]
        objects, unused_armature = self.legacy_skin_scene(assignments, mesh_count=2)

        legacy._validate_source_objects(objects, "blendlib:skinned_v1")

        invalid_assignments = {
            "no-effective-bone": [[("decorative", 1.0)] for unused_index in range(3)],
            "non-normalized": [[("root", 0.75)] for unused_index in range(3)],
            "more-than-four": [
                [(f"bone_{index}", 0.2) for index in range(5)]
                for unused_vertex in range(3)
            ],
        }
        for label, invalid in invalid_assignments.items():
            bone_names = tuple(f"bone_{index}" for index in range(5)) if label == "more-than-four" else ("root",)
            invalid_objects, unused_target = self.legacy_skin_scene(invalid, bone_names=bone_names)
            with self.subTest(label=label), self.assertRaisesRegex(
                legacy.ExportError, "BLENDLIB-EXPORT-005"
            ):
                legacy._validate_source_objects(invalid_objects, "blendlib:skinned_v1")

        unbound_objects, unused_target = self.legacy_skin_scene(
            [[("root", 1.0)] for unused_index in range(3)]
        )
        unbound_objects[1].modifiers[0].object = None
        wrong_target_objects, wrong_target = self.legacy_skin_scene(
            [[("root", 1.0)] for unused_index in range(3)]
        )
        wrong_target_objects.remove(wrong_target)
        multiple_objects, unused_target = self.legacy_skin_scene(
            [[("root", 1.0)] for unused_index in range(3)]
        )
        multiple_objects[1].modifiers.append(
            SimpleNamespace(name="SecondArmature", object=multiple_objects[0], type="ARMATURE")
        )
        for label, invalid_objects in (
            ("unbound", unbound_objects),
            ("wrong-target", wrong_target_objects),
            ("multiple", multiple_objects),
        ):
            with self.subTest(label=label), self.assertRaisesRegex(
                legacy.ExportError, "BLENDLIB-EXPORT-005"
            ):
                legacy._validate_source_objects(invalid_objects, "blendlib:skinned_v1")

    def test_binding_profile_target_and_bone_group_fail_before_stage_or_legacy(self) -> None:
        cases = []

        rigid_bound = self.snapshot()
        rigid_bound["objects"][1]["skin_binding"]["armature_modifiers"] = [
            self.skinned_snapshot()["objects"][1]["skin_binding"]["armature_modifiers"][0]
        ]
        cases.append(("rigid-bound", rigid_bound, "BLENDLIB-X5-ARMATURE-004"))

        unbound = self.skinned_snapshot()
        unbound["objects"][1]["skin_binding"]["armature_modifiers"][0].update({
            "target": "", "target_bones": [], "target_exported": False, "target_type": ""
        })
        cases.append(("unbound", unbound, "BLENDLIB-X5-ARMATURE-005"))

        multiple = self.skinned_snapshot()
        multiple["objects"][1]["skin_binding"]["armature_modifiers"].append(
            copy.deepcopy(multiple["objects"][1]["skin_binding"]["armature_modifiers"][0])
        )
        cases.append(("multiple", multiple, "BLENDLIB-X5-ARMATURE-004"))

        wrong_target = self.skinned_snapshot()
        wrong_target["objects"][1]["skin_binding"]["armature_modifiers"][0].update({
            "target": "HeroRoot", "target_bones": [], "target_type": "EMPTY"
        })
        cases.append(("wrong-target", wrong_target, "BLENDLIB-X5-ARMATURE-005"))

        profile_mismatch = self.skinned_snapshot()
        profile_mismatch["objects"][1]["skin_binding"]["profile"] = "blendlib:rigid_v1"
        cases.append(("profile-mismatch", profile_mismatch, "BLENDLIB-X5-PROFILE-002"))

        non_skin_group = self.skinned_snapshot()
        non_skin_group["objects"][1]["skin_binding"]["vertex_group_assignments"][0] = [
            {"name": "decorative_not_a_bone", "weight": 1.0}
        ]
        cases.append(("non-skin-group", non_skin_group, "BLENDLIB-X5-WEIGHT-001"))

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(
                    AssertionError("invalid binding reached legacy export")
                )
                for label, snapshot, expected_code in cases:
                    result = x5.preflight_snapshot(snapshot)
                    with self.subTest(label=label):
                        self.assertFalse(result.ok, result.report())
                        self.assertIn(expected_code, [item.code for item in result.diagnostics])
                        options = SimpleNamespace(project_root=parent / label)
                        with self.assertRaisesRegex(x5.X5ToolingError, "BLENDLIB-X5-PREFLIGHT-001"):
                            x5._prepare_x5_export(options, preflight=result)
                        self.assertFalse(options.project_root.exists())
            finally:
                x5._legacy_exporter = original_exporter

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
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(options)
            finally:
                x5.preflight_blender = original_preflight
                x5._legacy_exporter = original_exporter
            self.assertFalse(root.exists())

    def test_exact_source_capability_gate_blocks_prepare_and_atomic_mutation(self) -> None:
        """No POSIX-like fallback may create a root/stage or invoke legacy export."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / "not-created"
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            public_target = parent / "out" / "file.bin"
            public_target.parent.mkdir()
            public_target.write_bytes(b"OLD-PUBLIC-BYTES")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            legacy_calls: list[str] = []
            fault_calls: list[x5._AtomicFaultEvent] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    legacy_calls.append("export")
                    raise AssertionError("unsupported exact-source runtime reached legacy export")

            original_exporter = x5._legacy_exporter
            original_capability = x5._atomic_exact_source_publication_available
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                x5._atomic_exact_source_publication_available = lambda: False
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001.*exact handle identity"):
                    x5._prepare_x5_export(options, preflight=x5.preflight_snapshot(self.snapshot()))
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001.*exact handle identity"):
                    x5.atomic_write_bundle(
                        parent,
                        {"out/file.bin": b"NEW"},
                        replace_func=fault_calls.append,
                    )
            finally:
                x5._legacy_exporter = original_exporter
                x5._atomic_exact_source_publication_available = original_capability

            self.assertEqual([], legacy_calls)
            self.assertEqual([], fault_calls)
            self.assertFalse(root.exists())
            self.assertEqual(b"OLD-PUBLIC-BYTES", public_target.read_bytes())
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-backup-*")))

    def test_batch_plan_is_stable_and_rejects_duplicates(self) -> None:
        reversed_items = [
            x5.BatchExportItem("blendlib", "zeta", "blendlib:rigid_v1", None),
            x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", "A"),
        ]
        self.assertEqual(["alpha", "zeta"], [item.model_id for item in x5.plan_batch(reversed_items)])
        with self.assertRaisesRegex(x5.X5ToolingError, "BATCH-001"):
            x5.plan_batch(reversed_items + [x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", "B")])

    def test_explicit_report_is_the_same_asset_report_as_the_default_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            blend_path = root / "source.blend"
            blend_path.write_bytes(b"blend")
            report_path = root / "reports" / "explicit.json"
            options = legacy.ExportOptions(
                blend_path=blend_path,
                project_root=root,
                namespace="blendlib",
                model_id="hero",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=report_path,
            )
            preflight = x5.preflight_snapshot(self.snapshot())

            class FakeLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    self.assertIsNone(stage_options.report_path)
                    self.assertIsNone(stage_options.dev_refresh_path)
                    self.assertIsNone(stage_options.batch_manifest_path)
                    assets = (
                        stage_options.project_root
                        / "src/main/resources/assets/blendlib"
                    )
                    descriptor = assets / "blend_models/hero.json"
                    mesh = assets / "models3d/hero.glb"
                    texture = assets / "textures/blendlib/hero__heromaterial.png"
                    for path, payload in (
                        (descriptor, b"{}"),
                        (mesh, b"glb"),
                        (texture, b"png"),
                    ):
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(payload)
                    return {
                        "validation": {
                            "animation_names": [],
                            "embedded_runtime_images": False,
                            "index_count": 3,
                            "material_names": ["HeroMaterial"],
                            "node_count": 3,
                            "profile": "blendlib:rigid_v1",
                            "vertex_count": 3,
                            "world_bounds": {"min": [0, 0, 0], "max": [1, 1, 1]},
                        }
                    }

            original_exporter = x5._legacy_exporter
            prepared = None
            try:
                x5._legacy_exporter = lambda: FakeLegacyExporter
                prepared = x5._prepare_x5_export(options, preflight=preflight)
            finally:
                x5._legacy_exporter = original_exporter
            try:
                default_relative = prepared.result["authoring_report"]
                explicit_relative = report_path.relative_to(root).as_posix()
                self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
                self.assertFalse(report_path.exists())
                self.assertEqual(prepared.outputs[default_relative], prepared.outputs[explicit_relative])
                report = json.loads(prepared.outputs[explicit_relative])
                self.assertEqual(x5.ASSET_REPORT_FORMAT, report["format"])
                self.assertEqual("1.0.0", report["schema_version"])
                self.assertEqual(3, report["counts"]["vertices"])
                self.assertEqual(0, report["counts"]["vertex_weight_records"])
                self.assertEqual(report["diagnostics"], report["performance_warnings"])
                self.assertIn("sidecar_sha256", report)
            finally:
                if prepared is not None:
                    self.assertFalse(prepared.stage_root.exists())

    def test_private_export_error_retains_unapproved_stage_with_recovery_diagnostic(self) -> None:
        """An unapproved legacy-stage entry is never recursively deleted after an exporter error."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            public_sidecar = root / "build/blendlib-authoring/blendlib/hero.blendlib-authoring.json"
            public_sidecar.parent.mkdir(parents=True)
            public_sidecar.write_bytes(b"OLD-PUBLIC-SIDECAR")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            marker: Path | None = None

            class FailingLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    nonlocal marker
                    for kind, relative in legacy.strict_v1_artifact_paths(
                        stage_options, ("HeroMaterial",)
                    ).items():
                        target = stage_options.project_root / Path(*relative.split("/"))
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(kind.encode("ascii"))
                    marker = stage_options.project_root / "FOREIGN-MARKER"
                    marker.write_bytes(b"DO-NOT-DELETE")
                    raise RuntimeError("injected legacy failure")

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: FailingLegacyExporter
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001.*retained for recovery") as raised:
                    x5._prepare_x5_export(options, preflight=x5.preflight_snapshot(self.snapshot()))
            finally:
                x5._legacy_exporter = original_exporter

            self.assertIsInstance(raised.exception.__cause__, RuntimeError)
            self.assertEqual(b"OLD-PUBLIC-SIDECAR", public_sidecar.read_bytes())
            self.assertIsNotNone(marker)
            self.assertTrue(marker.is_file())
            self.assertEqual(b"DO-NOT-DELETE", marker.read_bytes())
            self.assertEqual(1, len(list(root.glob(".blendlib-x5-export-*"))))
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_frozen_export_plan_mutation_during_private_export_cleans_partial_stage(self) -> None:
        """A legacy-time mutation of public plan state blocks publication and cleans known leaves."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            public_sidecar = root / "build/blendlib-authoring/blendlib/hero.blendlib-authoring.json"
            public_sidecar.parent.mkdir(parents=True)
            public_sidecar.write_bytes(b"OLD-PUBLIC-SIDECAR")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            plan: x5._FrozenExportPlan | None = None
            export_calls: list[str] = []

            class MutatingLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    export_calls.append("export")
                    kind, relative = next(iter(legacy.strict_v1_artifact_paths(
                        stage_options, ("HeroMaterial",)
                    ).items()))
                    target = stage_options.project_root / Path(*relative.split("/"))
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(kind.encode("ascii"))
                    self.assertIsNotNone(plan)
                    object.__setattr__(plan.options, "model_id", "attacker-model")
                    return {"validation": {}}

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: MutatingLegacyExporter
                preflight = x5.preflight_snapshot(self.snapshot())
                plan = x5._build_export_plan(options, preflight=preflight)
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(options, plan=plan)
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual(["export"], export_calls)
            self.assertEqual(b"OLD-PUBLIC-SIDECAR", public_sidecar.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_artifact_graph_rejects_report_collisions_before_export_and_preserves_old_bytes(self) -> None:
        """A report target never replaces another planned artifact before staging."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            model_id = "hero/model"
            output = root / "src/main/resources/assets/blendlib"
            sidecar = root / "build/blendlib-authoring/blendlib/hero/model.blendlib-authoring.json"
            targets = {
                "sidecar": sidecar,
                "descriptor": output / "blend_models/hero/model.json",
                "mesh": output / "models3d/hero/model.glb",
                "texture": output / "textures/blendlib/hero_model__heromaterial.png",
                "file-parent": sidecar.parent,
                "dot-parent": sidecar.parent / "nested" / ".." / sidecar.name,
                "case-alias": root / "BUILD/blendlib-authoring/blendlib/hero/model.blendlib-authoring.json",
                "windows-separator": Path(
                    str(root) + "\\build\\blendlib-authoring\\blendlib\\hero\\model.blendlib-authoring.json"
                ),
            }
            sidecar.parent.mkdir(parents=True, exist_ok=True)
            sidecar.write_bytes(b"published-sidecar-before-failure")
            preflight = x5.preflight_snapshot(self.snapshot())
            export_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    export_calls.append(stage_options.model_id)
                    raise AssertionError("report collision reached legacy export")

            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                x5.preflight_blender = lambda unused_options: preflight
                for label, report_path in targets.items():
                    options = legacy.ExportOptions(
                        blend_path=source,
                        project_root=root,
                        namespace="blendlib",
                        model_id=model_id,
                        profile="blendlib:rigid_v1",
                        collection_name=None,
                        output_resource_root="src/main/resources",
                        report_path=report_path,
                    )
                    with self.subTest(label=label), self.assertRaises(x5.X5ToolingError):
                        x5.x5_export_open_blend(options)
                    self.assertEqual([], export_calls)
                    self.assertEqual(b"published-sidecar-before-failure", sidecar.read_bytes())
                    self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
                    self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
                    self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

                refresh_options = dataclasses.replace(
                    options,
                    report_path=None,
                    dev_refresh_path=sidecar,
                    dev_session_token="test-session-token-1234",
                    dev_generation=1,
                )
                with self.assertRaisesRegex(x5.X5ToolingError, "PATH-004"):
                    x5.x5_export_open_blend(refresh_options)
                self.assertEqual([], export_calls)
                self.assertEqual(b"published-sidecar-before-failure", sidecar.read_bytes())
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

    def test_report_alias_through_symlink_is_rejected_before_export(self) -> None:
        """Resolve existing directory aliases before approving the report graph."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            real_authoring = root / "authoring-real"
            alias_authoring = root / "authoring-alias"
            real_authoring.mkdir()
            try:
                alias_authoring.symlink_to(real_authoring, target_is_directory=True)
            except OSError as error:
                junction = subprocess.run(
                    ["cmd.exe", "/d", "/c", "mklink", "/J", str(alias_authoring), str(real_authoring)],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                if junction.returncode != 0:
                    self.skipTest(
                        "directory alias unavailable for symlink/junction contract: "
                        f"{error}; {junction.stderr.strip()}"
                    )
            source = root / "source.blend"
            source.write_bytes(b"blend")
            sidecar = real_authoring / "blendlib/hero/model.blendlib-authoring.json"
            sidecar.parent.mkdir(parents=True)
            sidecar.write_bytes(b"old-sidecar")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=alias_authoring / "blendlib/hero/model.blendlib-authoring.json",
                authoring_output_root="authoring-real",
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: (_ for _ in ()).throw(
                    AssertionError("alias collision reached legacy exporter")
                )
                with self.assertRaisesRegex(x5.X5ToolingError, "PATH-004"):
                    x5._prepare_x5_export(options, preflight=x5.preflight_snapshot(self.snapshot()))
            finally:
                x5._legacy_exporter = original_exporter
            self.assertEqual(b"old-sidecar", sidecar.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

    def test_only_matching_explicit_and_default_report_targets_are_deduplicated(self) -> None:
        """The legal report alias leaves the sidecar payload untouched and exports once."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            default_report = root / "build/blendlib-authoring/blendlib/hero/model.asset-report.json"
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=default_report,
            )
            export_calls: list[str] = []

            class FakeLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    export_calls.append(stage_options.model_id)
                    for relative, payload in (
                        ("src/main/resources/assets/blendlib/blend_models/hero/model.json", b"{}"),
                        ("src/main/resources/assets/blendlib/models3d/hero/model.glb", b"glb"),
                        ("src/main/resources/assets/blendlib/textures/blendlib/hero_model__heromaterial.png", b"png"),
                    ):
                        target = stage_options.project_root / Path(*relative.split("/"))
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(payload)
                    return {
                        "validation": {
                            "animation_names": [],
                            "embedded_runtime_images": False,
                            "index_count": 3,
                            "material_names": ["HeroMaterial"],
                            "node_count": 3,
                            "profile": "blendlib:rigid_v1",
                            "vertex_count": 3,
                            "world_bounds": {"min": [0, 0, 0], "max": [1, 1, 1]},
                        }
                    }

            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: FakeLegacyExporter
                x5.preflight_blender = lambda unused_options: x5.preflight_snapshot(self.snapshot())
                result = x5.x5_export_open_blend(options)
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight
            self.assertEqual(["hero/model"], export_calls)
            self.assertEqual(default_report.relative_to(root).as_posix(), result["authoring_report"])
            sidecar_path = root / result["authoring_sidecar"]
            self.assertEqual(x5.AUTHORING_SIDECAR_FORMAT, json.loads(sidecar_path.read_text(encoding="utf-8"))["format"])
            report = json.loads(default_report.read_text(encoding="utf-8"))
            self.assertEqual(x5.ASSET_REPORT_FORMAT, report["format"])
            self.assertNotEqual(sidecar_path.read_bytes(), default_report.read_bytes())

    @unittest.skipUnless(os.name == "nt", "case-folded publication aliases are a Windows contract")
    def test_casefolded_explicit_and_default_report_alias_is_deduplicated(self) -> None:
        """Distinct Windows spellings of one legal report target publish one byte payload."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            default_report = root / "build/blendlib-authoring/blendlib/hero/model.asset-report.json"
            explicit_report = root / "BUILD/BLENDLIB-AUTHORING/BLENDLIB/HERO/MODEL.ASSET-REPORT.JSON"
            self.assertNotEqual(
                default_report.relative_to(root).as_posix(),
                explicit_report.relative_to(root).as_posix(),
            )
            self.assertEqual(
                x5._filesystem_identity(default_report),
                x5._filesystem_identity(explicit_report),
            )
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=explicit_report,
            )
            export_calls: list[str] = []
            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(root, export_calls)
                x5.preflight_blender = lambda unused_options: x5.preflight_snapshot(self.snapshot())
                result = x5.x5_export_open_blend(options)
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

            self.assertEqual(["hero/model"], export_calls)
            self.assertEqual(default_report.relative_to(root).as_posix(), result["authoring_report"])
            self.assertEqual(x5.ASSET_REPORT_FORMAT, json.loads(default_report.read_text(encoding="utf-8"))["format"])
            self.assertNotEqual(
                (root / result["authoring_sidecar"]).read_bytes(),
                default_report.read_bytes(),
            )

    def test_batch_preflights_every_sorted_item_before_any_prepare_side_effect(self) -> None:
        valid = x5.preflight_snapshot(self.snapshot())
        invalid_snapshot = self.snapshot()
        invalid_snapshot["root_count"] = 0
        invalid = x5.preflight_snapshot(invalid_snapshot)

        cases = (
            (
                "two-late-invalid",
                [
                    x5.BatchExportItem("blendlib", "z_invalid", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "a_valid", "blendlib:rigid_v1", None),
                ],
            ),
            (
                "three-late-invalid-reversed-input",
                [
                    x5.BatchExportItem("blendlib", "m_valid", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "z_invalid", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "a_valid", "blendlib:rigid_v1", None),
                ],
            ),
            (
                "invalid-sorts-first",
                [
                    x5.BatchExportItem("blendlib", "z_valid", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "a_invalid", "blendlib:rigid_v1", None),
                ],
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            original_preflight = x5.preflight_blender
            original_prepare = x5._prepare_x5_export
            original_atomic = x5.atomic_write_bundle
            try:
                for label, items in cases:
                    root = parent / label
                    prepare_calls = []

                    def fake_preflight(item_options: legacy.ExportOptions) -> x5.PreflightResult:
                        return invalid if "invalid" in item_options.model_id else valid

                    def forbidden_prepare(
                        item_options: legacy.ExportOptions,
                        *,
                        preflight: x5.PreflightResult | None = None,
                    ) -> x5._PreparedExport:
                        prepare_calls.append(item_options.model_id)
                        root.mkdir(parents=True, exist_ok=True)
                        (root / "prepare-side-effect.txt").write_text("unexpected", encoding="utf-8")
                        stage = root / f".blendlib-x5-export-{item_options.model_id}"
                        stage.mkdir()
                        return x5._PreparedExport(item_options, {"model_key": item_options.model_id}, {}, stage)

                    x5.preflight_blender = fake_preflight
                    x5._prepare_x5_export = forbidden_prepare
                    x5.atomic_write_bundle = lambda *unused_args, **unused_kwargs: (_ for _ in ()).throw(
                        AssertionError("invalid batch reached atomic publication")
                    )
                    with self.subTest(label=label), self.assertRaisesRegex(
                        x5.X5ToolingError, "BLENDLIB-X5-BATCH-004"
                    ):
                        x5.x5_batch_export_open_blend(
                            legacy.ExportOptions(
                                blend_path=parent / "source.blend",
                                project_root=root,
                                namespace="blendlib",
                                model_id="unused",
                                profile="blendlib:rigid_v1",
                                collection_name=None,
                                output_resource_root="src/main/resources",
                                report_path=None,
                            ),
                            items,
                        )
                    self.assertEqual([], prepare_calls)
                    self.assertFalse(root.exists())
            finally:
                x5.preflight_blender = original_preflight
                x5._prepare_x5_export = original_prepare
                x5.atomic_write_bundle = original_atomic

    def test_batch_full_artifact_graph_rejects_slug_report_refresh_and_sidecar_collisions_pre_stage(self) -> None:
        """Every batch collision is found before any item reaches the exporter."""

        valid = x5.preflight_snapshot(self.snapshot())
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            original_preflight = x5.preflight_blender
            original_prepare = x5._prepare_x5_export
            prepare_calls: list[str] = []
            try:
                x5.preflight_blender = lambda unused_options: valid

                def forbidden_prepare(
                    item_options: legacy.ExportOptions,
                    *,
                    preflight: x5.PreflightResult | None = None,
                    plan: x5._FrozenExportPlan | None = None,
                ) -> x5._PreparedExport:
                    prepare_calls.append(item_options.model_id)
                    raise AssertionError("batch graph collision reached staged exporter")

                x5._prepare_x5_export = forbidden_prepare

                def options_for(root: Path, **overrides: object) -> legacy.ExportOptions:
                    values: dict[str, object] = {
                        "blend_path": source,
                        "project_root": root,
                        "namespace": "blendlib",
                        "model_id": "unused",
                        "profile": "blendlib:rigid_v1",
                        "collection_name": None,
                        "output_resource_root": "src/main/resources",
                        "report_path": None,
                    }
                    values.update(overrides)
                    return legacy.ExportOptions(**values)  # type: ignore[arg-type]

                cases = (
                    (
                        "texture-slug",
                        options_for(parent / "texture-slug"),
                        [
                            x5.BatchExportItem("blendlib", "a/b", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "a_b", "blendlib:rigid_v1", None),
                        ],
                        ("texture:HeroMaterial", "blendlib:a/b", "blendlib:a_b"),
                    ),
                    (
                        "explicit-report",
                        options_for(parent / "explicit-report", report_path=parent / "explicit-report/reports/shared.json"),
                        [
                            x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "beta", "blendlib:rigid_v1", None),
                        ],
                        ("explicit-report", "blendlib:alpha", "blendlib:beta"),
                    ),
                    (
                        "refresh",
                        options_for(
                            parent / "refresh",
                            dev_refresh_path=parent / "refresh/dev/shared.json",
                            dev_session_token="test-session-token-1234",
                            dev_generation=4,
                        ),
                        [
                            x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "beta", "blendlib:rigid_v1", None),
                        ],
                        ("dev-refresh", "blendlib:alpha", "blendlib:beta"),
                    ),
                    (
                        "sidecar-report",
                        options_for(
                            parent / "sidecar-report",
                            report_path=parent / "sidecar-report/build/blendlib-authoring/blendlib/beta.blendlib-authoring.json",
                        ),
                        [
                            x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "beta", "blendlib:rigid_v1", None),
                        ],
                        ("sidecar", "explicit-report", "blendlib:beta"),
                    ),
                )
                for label, options, items, fragments in cases:
                    with self.subTest(label=label), self.assertRaisesRegex(x5.X5ToolingError, "BATCH-002") as raised:
                        x5.x5_batch_export_open_blend(options, items)
                    for fragment in fragments:
                        self.assertIn(fragment, raised.exception.message)
                    self.assertEqual([], prepare_calls)
                    self.assertFalse(options.project_root.exists())
            finally:
                x5.preflight_blender = original_preflight
                x5._prepare_x5_export = original_prepare

    def test_all_valid_batch_is_sorted_and_prepare_failure_cleans_prior_stages(self) -> None:
        valid = x5.preflight_snapshot(self.snapshot())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original_preflight = x5.preflight_blender
            original_prepare = x5._prepare_x5_export
            original_atomic = x5.atomic_write_bundle
            published: list[dict[str, bytes]] = []
            prepared_order: list[str] = []
            try:
                x5.preflight_blender = lambda unused_options: valid

                def prepare(
                    item_options: legacy.ExportOptions,
                    *,
                    preflight: x5.PreflightResult | None = None,
                    plan: x5._FrozenExportPlan | None = None,
                    _batch_root_binding: x5._AtomicDirectoryBinding | None = None,
                ) -> x5._PreparedExport:
                    self.assertIsNotNone(plan)
                    _, _, plan_state = x5._trusted_export_plan_state(plan)
                    if _batch_root_binding is not None:
                        self.assertEqual(plan_state.root_route.anchor, _batch_root_binding)
                    prepared_order.append(item_options.model_id)
                    if item_options.model_id == "z_fail":
                        raise x5.X5ToolingError("BLENDLIB-X5-ATOMIC-001", "injected export failure")
                    # Real prepares remove their legacy private stage before
                    # returning bytes for the later atomic publication.  Keep
                    # this fake seam stage-free so the batch ordering test
                    # does not exercise a weaker pathname cleanup fallback.
                    stage = root / f".blendlib-x5-export-{item_options.model_id}"
                    return x5._PreparedExport(
                        item_options,
                        {"model_key": f"blendlib:{item_options.model_id}"},
                        {f"out/{item_options.model_id}.bin": item_options.model_id.encode("ascii")},
                        stage,
                        (),
                        plan_state.root_route.anchor,
                        plan_state.bundle_bindings,
                    )

                x5._prepare_x5_export = prepare
                def record_atomic_bundle(
                    unused_root: Path,
                    outputs: dict[str, bytes],
                    *,
                    approved_claims: tuple[x5._CanonicalArtifactClaim, ...] | None = None,
                    claim_conflict_code: str = "BLENDLIB-X5-ATOMIC-001",
                    approved_bindings: x5._AtomicBundleBindings | None = None,
                    approved_root_binding: x5._AtomicDirectoryBinding | None = None,
                ) -> None:
                    if approved_claims is not None:
                        self.assertEqual("BLENDLIB-X5-BATCH-002", claim_conflict_code)
                    self.assertIsNotNone(approved_bindings)
                    self.assertIsNotNone(approved_root_binding)
                    published.append(dict(outputs))

                x5.atomic_write_bundle = record_atomic_bundle
                options = legacy.ExportOptions(
                    blend_path=root / "source.blend",
                    project_root=root,
                    namespace="blendlib",
                    model_id="unused",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )
                valid_items = [
                    x5.BatchExportItem("blendlib", "zeta", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "a_b", "blendlib:rigid_v1", None),
                    x5.BatchExportItem("blendlib", "a-b", "blendlib:rigid_v1", None),
                ]
                results = x5.x5_batch_export_open_blend(options, valid_items)
                self.assertEqual(
                    ["blendlib:a-b", "blendlib:a_b", "blendlib:alpha", "blendlib:zeta"],
                    [item["model_key"] for item in results],
                )
                self.assertEqual(["a-b", "a_b", "alpha", "zeta"], prepared_order)
                self.assertEqual(
                    {
                        "out/a-b.bin": b"a-b",
                        "out/a_b.bin": b"a_b",
                        "out/alpha.bin": b"alpha",
                        "out/zeta.bin": b"zeta",
                    },
                    published[0],
                )
                self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

                prepared_order.clear()
                published.clear()
                with self.assertRaisesRegex(x5.X5ToolingError, "injected export failure"):
                    x5.x5_batch_export_open_blend(
                        options,
                        [
                            x5.BatchExportItem("blendlib", "a_valid", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "z_fail", "blendlib:rigid_v1", None),
                        ],
                    )
                self.assertEqual(["a_valid", "z_fail"], prepared_order)
                self.assertEqual([], published)
                self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
            finally:
                x5.preflight_blender = original_preflight
                x5._prepare_x5_export = original_prepare
                x5.atomic_write_bundle = original_atomic

    def test_valid_batch_creates_initially_missing_root_once_and_reuses_binding(self) -> None:
        """A two-item batch may create one missing project root without adopting a replacement."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"SOURCE-OUTSIDE-PROJECT")
            root = parent / "new-project"
            self.assertFalse(root.exists())
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="unused",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            export_calls: list[str] = []
            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent, export_calls)
                x5.preflight_blender = lambda unused_options: x5.preflight_snapshot(self.snapshot())
                results = x5.x5_batch_export_open_blend(
                    options,
                    (
                        x5.BatchExportItem("blendlib", "beta", "blendlib:rigid_v1", None),
                        x5.BatchExportItem("blendlib", "alpha", "blendlib:rigid_v1", None),
                    ),
                )
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

            self.assertTrue(root.is_dir())
            self.assertEqual(["alpha", "beta"], export_calls)
            self.assertEqual(["blendlib:alpha", "blendlib:beta"], [item["model_key"] for item in results])
            self.assertEqual(10, len([path for path in root.rglob("*") if path.is_file()]))
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_creates_initially_missing_root_from_rebased_binding(self) -> None:
        """Generic publication rebases an empty plan chain to its one controlled root lease."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "new-project"
            self.assertFalse(root.exists())
            x5.atomic_write_bundle(root, {"out/file.bin": b"NEW-PAYLOAD"})
            self.assertEqual(b"NEW-PAYLOAD", (root / "out/file.bin").read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_failure_restores_prior_files_and_leaves_no_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "out").mkdir()
            (root / "out" / "a.txt").write_bytes(b"old-a")
            (root / "out" / "b.txt").write_bytes(b"old-b")

            def fail_second_stage(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "before-install" and event.relative == "out/b.txt":
                    raise OSError("injected replacement fault")

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

            def fail_install_and_one_restore(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "before-install" and event.relative == "out/b.txt":
                    raise OSError("injected install fault")
                if event.phase == "before-restore" and event.relative == "out/b.txt":
                    raise OSError("injected restore fault")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002") as raised:
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

    def test_atomic_bundle_preserves_post_public_x5_cause_after_complete_rollback(self) -> None:
        """A post-install X5 error remains the direct cause after all public bytes are restored."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_target = root / "old.bin"
            new_target = root / "new.bin"
            old_target.write_bytes(b"OLD")
            original = x5.X5ToolingError("BLENDLIB-X5-PROBE-777", "ORIGINAL-BOUNDED-X5-FAULT")

            def fail_after_old_install(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "after-install" and event.relative == "old.bin":
                    raise original

            with self.assertRaises(x5.X5ToolingError) as raised:
                x5.atomic_write_bundle(
                    root,
                    {"old.bin": b"NEW-OLD", "new.bin": b"NEW-ABSENT"},
                    replace_func=fail_after_old_install,
                )

            error = raised.exception
            rendered = f"{error.message} {str(error)} {original.message} {str(original)}"
            self.assertEqual("BLENDLIB-X5-ATOMIC-002", error.code)
            self.assertIs(error.__cause__, original)
            self.assertEqual("BLENDLIB-X5-PROBE-777", original.code)
            self.assertEqual("ORIGINAL-BOUNDED-X5-FAULT", original.message)
            self.assertIsNot(type(error.__cause__), RuntimeError)
            self.assertEqual(b"OLD", old_target.read_bytes())
            self.assertFalse(new_target.exists())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))
            self.assertNotIn(str(root), rendered)
            self.assertNotRegex(rendered, r"(?i)[a-z]:[\\/]")

    def test_atomic_bundle_preserves_post_public_x5_cause_when_rollback_is_uncertain(self) -> None:
        """An unrecovered old leaf retains its backup while the original X5 failure remains chained."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "old.bin"
            target.write_bytes(b"OLD")
            original = x5.X5ToolingError("BLENDLIB-X5-PROBE-777", "ORIGINAL-BOUNDED-X5-FAULT")

            def fail_install_and_restore(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "after-install" and event.relative == "old.bin":
                    raise original
                if event.phase == "before-restore" and event.relative == "old.bin":
                    raise OSError("injected bounded restore uncertainty")

            with self.assertRaises(x5.X5ToolingError) as raised:
                x5.atomic_write_bundle(root, {"old.bin": b"NEW"}, replace_func=fail_install_and_restore)

            error = raised.exception
            backups = list(root.glob(".blendlib-x5-backup-*"))
            rendered = f"{error.message} {str(error)} {original.message} {str(original)}"
            self.assertEqual("BLENDLIB-X5-ATOMIC-002", error.code)
            self.assertIn("Rollback restoration was incomplete or uncertain", error.message)
            self.assertEqual(1, len(backups))
            self.assertIn(backups[0].name, error.message)
            self.assertIs(error.__cause__, original)
            self.assertEqual("BLENDLIB-X5-PROBE-777", original.code)
            self.assertEqual("ORIGINAL-BOUNDED-X5-FAULT", original.message)
            self.assertIsNot(type(error.__cause__), RuntimeError)
            self.assertFalse(target.exists())
            self.assertEqual(b"OLD", (backups[0] / "old.bin").read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertNotIn(str(root), rendered)
            self.assertNotRegex(rendered, r"(?i)[a-z]:[\\/]")

    def test_atomic_bundle_rethrows_prepublication_x5_error_without_atomic_wrapping(self) -> None:
        """A private-stage X5 error remains the original direct pre-publication failure."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = x5.X5ToolingError("BLENDLIB-X5-PROBE-777", "ORIGINAL-BOUNDED-X5-FAULT")

            def fail_after_stage_allocation(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "after-stage-allocation":
                    raise original

            with self.assertRaises(x5.X5ToolingError) as raised:
                x5.atomic_write_bundle(root, {"old.bin": b"NEW"}, replace_func=fail_after_stage_allocation)

            self.assertIs(original, raised.exception)
            self.assertEqual("BLENDLIB-X5-PROBE-777", raised.exception.code)
            self.assertEqual("ORIGINAL-BOUNDED-X5-FAULT", raised.exception.message)
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

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
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                    x5.atomic_write_bundle(root, {"out/a.txt": b"new-a"})
            finally:
                x5.resolve_under = original_resolve

            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_rejects_root_and_all_output_ancestors_before_private_stage(self) -> None:
        """The final writer rejects root/parent/grandparent conflicts before any stage or replace."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            outputs = {
                "authoring/sidecars/deep/model.json": b"sidecar",
                "refresh/messages/deep/next.json": b"refresh",
                "runtime/assets/deep/model.glb": b"glb",
                "reports/assets/deep/model.json": b"report",
            }
            stage_calls: list[str] = []
            replace_calls: list[tuple[str, str]] = []

            def forbidden_transaction_event(event: x5._AtomicFaultEvent) -> None:
                if event.phase in {"after-stage-allocation", "after-backup-allocation"}:
                    stage_calls.append(event.phase)
                    raise AssertionError("unsafe bundle reached private staging")
                replace_calls.append((event.phase, event.relative))
                raise AssertionError("unsafe bundle reached public replacement")

            root_file = parent / "project-root-file"
            root_file.write_bytes(b"old-root")
            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                x5.atomic_write_bundle(root_file, outputs, replace_func=forbidden_transaction_event)
            self.assertEqual(b"old-root", root_file.read_bytes())

            for output_index, relative in enumerate(outputs):
                target = Path(*relative.split("/"))
                for label, ancestor in (
                    ("parent", target.parent),
                    ("grandparent", target.parent.parent),
                ):
                    with self.subTest(relative=relative, ancestor=label):
                        root = parent / f"{label}-{output_index}"
                        root.mkdir()
                        blocker = root / ancestor
                        blocker.parent.mkdir(parents=True, exist_ok=True)
                        old_bytes = f"old:{relative}:{label}".encode("utf-8")
                        blocker.write_bytes(old_bytes)
                        with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                            x5.atomic_write_bundle(root, outputs, replace_func=forbidden_transaction_event)
                        self.assertEqual(old_bytes, blocker.read_bytes())
                        self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                        self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))

            self.assertEqual([], stage_calls)
            self.assertEqual([], replace_calls)

            legal_root = parent / "legal-project"
            legal_root.mkdir()
            x5.atomic_write_bundle(legal_root, outputs)
            for relative, payload in outputs.items():
                self.assertEqual(payload, (legal_root / Path(*relative.split("/"))).read_bytes())

    def test_atomic_bundle_rejects_internal_symlink_and_junction_output_ancestors_before_stage(self) -> None:
        """Resolved internal directory aliases cannot hide a later file parent from the final writer."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            aliases: list[tuple[str, Path]] = []

            symlink_root = parent / "symlink"
            symlink_root.mkdir()
            symlink_real = symlink_root / "real"
            symlink_real.mkdir()
            try:
                (symlink_root / "alias").symlink_to(symlink_real, target_is_directory=True)
                aliases.append(("symlink", symlink_root))
            except OSError:
                pass

            junction_root = parent / "junction"
            junction_root.mkdir()
            junction_real = junction_root / "real"
            junction_real.mkdir()
            junction = subprocess.run(
                ["cmd.exe", "/d", "/c", "mklink", "/J", str(junction_root / "alias"), str(junction_real)],
                check=False,
                capture_output=True,
                text=True,
            )
            if junction.returncode == 0:
                aliases.append(("junction", junction_root))
            if not aliases:
                self.skipTest("directory symlink and junction aliases are unavailable")

            stage_calls: list[str] = []
            replace_calls: list[tuple[str, str]] = []

            def forbidden_transaction_event(event: x5._AtomicFaultEvent) -> None:
                if event.phase in {"after-stage-allocation", "after-backup-allocation"}:
                    stage_calls.append(event.phase)
                    raise AssertionError("aliased file ancestor reached private staging")
                replace_calls.append((event.phase, event.relative))
                raise AssertionError("aliased file ancestor reached public replacement")

            for label, root in aliases:
                blocker = root / "real" / "blocked"
                old_bytes = f"old-{label}".encode("ascii")
                blocker.write_bytes(old_bytes)
                with self.subTest(alias=label), self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                    x5.atomic_write_bundle(
                        root,
                        {"alias/blocked/output.bin": b"new"},
                        replace_func=forbidden_transaction_event,
                    )
                self.assertEqual(old_bytes, blocker.read_bytes())
                self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))

            self.assertEqual([], stage_calls)
            self.assertEqual([], replace_calls)

    def test_atomic_bundle_rechecks_retargeted_alias_before_public_replace(self) -> None:
        """A post-stage alias retarget cannot redirect generic or refresh publication."""

        def make_junction(alias: Path, target: Path) -> bool:
            result = subprocess.run(
                ["cmd.exe", "/d", "/c", "mklink", "/J", str(alias), str(target)],
                check=False,
                capture_output=True,
                text=True,
            )
            return result.returncode == 0

        aliases: list[str] = []
        with tempfile.TemporaryDirectory() as temporary:
            probe_root = Path(temporary)
            symlink = probe_root / "symlink"
            target = probe_root / "target"
            target.mkdir()
            try:
                symlink.symlink_to(target, target_is_directory=True)
                aliases.append("symlink")
            except OSError:
                pass
            junction = probe_root / "junction"
            if make_junction(junction, target):
                aliases.append("junction")
        if not aliases:
            self.skipTest("directory symlink and junction aliases are unavailable")

        for alias_kind in aliases:
            for writer in ("atomic", "refresh"):
                for boundary, expected_private_calls in (("stage", 1), ("backup", 2)):
                    with self.subTest(
                        alias=alias_kind,
                        writer=writer,
                        boundary=boundary,
                    ), tempfile.TemporaryDirectory() as temporary:
                        root = Path(temporary)
                        approved = root / "approved-real"
                        runtime = root / "src" / "main" / "resources"
                        approved.mkdir()
                        runtime.mkdir(parents=True)
                        alias = root / "approved"
                        if alias_kind == "symlink":
                            alias.symlink_to(approved, target_is_directory=True)
                        else:
                            self.assertTrue(make_junction(alias, approved))
                        safe_file = approved / "refresh.json"
                        runtime_file = runtime / "refresh.json"
                        safe_file.write_bytes(b"OLD-SAFE-BYTES")
                        runtime_file.write_bytes(b"OLD-RUNTIME-BYTES")
                        original_atomic = x5.atomic_write_bundle
                        private_calls: list[str] = []
                        replace_calls: list[tuple[str, str]] = []
                        atomic_calls: list[str] = []

                        def retarget_after_private_allocation(event: x5._AtomicFaultEvent) -> None:
                            if event.phase in {"after-stage-allocation", "after-backup-allocation"}:
                                private_calls.append(event.phase)
                            if len(private_calls) == expected_private_calls:
                                if alias_kind == "symlink":
                                    alias.unlink()
                                    alias.symlink_to(runtime, target_is_directory=True)
                                else:
                                    alias.rmdir()
                                    self.assertTrue(make_junction(alias, runtime))
                                return
                            if event.phase in {"after-stage-allocation", "after-backup-allocation"}:
                                return
                            replace_calls.append((event.phase, event.relative))
                            raise AssertionError("retargeted output reached public replacement")

                        def tracked_atomic(root_arg: Path, outputs: dict[str, bytes]) -> None:
                            atomic_calls.append("atomic")
                            original_atomic(root_arg, outputs, replace_func=retarget_after_private_allocation)

                        try:
                            if writer == "atomic":
                                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                                    x5.atomic_write_bundle(
                                        root,
                                        {"approved/refresh.json": b"new-public-bytes"},
                                        replace_func=retarget_after_private_allocation,
                                    )
                            else:
                                x5.atomic_write_bundle = tracked_atomic
                                message = x5.RefreshMessage(
                                    session_token="test-session-token-1234",
                                    generation=1,
                                    artifact_hashes={"approved/refresh.json": "0" * 64},
                                    model_key="blendlib:hero",
                                )
                                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                                    x5.write_refresh_message(
                                        root,
                                        "approved/refresh.json",
                                        message,
                                        runtime_resource_root="src/main/resources",
                                    )
                        finally:
                            x5.atomic_write_bundle = original_atomic

                        self.assertEqual(expected_private_calls, len(private_calls))
                        self.assertEqual(["atomic"] if writer == "refresh" else [], atomic_calls)
                        self.assertEqual([], replace_calls)
                        self.assertEqual(b"OLD-SAFE-BYTES", safe_file.read_bytes())
                        self.assertEqual(b"OLD-RUNTIME-BYTES", runtime_file.read_bytes())
                        self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
                        self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_leases_output_parent_through_rollback(self) -> None:
        """A fault observer cannot rename an empty output parent after its backup move."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            approved = root / "approved"
            approved.mkdir()
            safe_file = approved / "refresh.json"
            safe_file.write_bytes(b"OLD-SAFE-BYTES")
            parent_rename_blocked = False

            def retargeting_fault(event: x5._AtomicFaultEvent) -> None:
                nonlocal parent_rename_blocked
                if event.phase == "after-backup" and event.relative == "approved/refresh.json":
                    try:
                        approved.rmdir()
                    except OSError:
                        parent_rename_blocked = True
                    raise OSError("injected fault after the backup move")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002.*all affected targets were restored"):
                x5.atomic_write_bundle(
                    root,
                    {"approved/refresh.json": b"NEW-PUBLIC-BYTES"},
                    replace_func=retargeting_fault,
                )

            self.assertTrue(parent_rename_blocked)
            self.assertEqual(b"OLD-SAFE-BYTES", safe_file.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_windows_leases_project_root_and_created_output_parent(self) -> None:
        """Windows directory handles keep both root and writer-created parents stable."""

        if os.name != "nt":
            self.skipTest("Windows transaction-handle behavior")
        with tempfile.TemporaryDirectory() as temporary:
            outer = Path(temporary)
            root = outer / "project"
            root.mkdir()
            created_parent = root / "created"
            target = created_parent / "file.bin"
            root_rename_blocked = False
            parent_rename_blocked = False

            def try_renames_after_install(event: x5._AtomicFaultEvent) -> None:
                nonlocal root_rename_blocked, parent_rename_blocked
                if event.phase != "after-install":
                    return
                try:
                    os.replace(root, outer / "project-displaced")
                except OSError:
                    root_rename_blocked = True
                try:
                    os.replace(created_parent, root / "created-displaced")
                except OSError:
                    parent_rename_blocked = True
                raise OSError("injected post-install failure")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002.*all affected targets were restored"):
                x5.atomic_write_bundle(
                    root,
                    {"created/file.bin": b"NEW-BYTES"},
                    replace_func=try_renames_after_install,
                )

            self.assertTrue(root_rename_blocked)
            self.assertTrue(parent_rename_blocked)
            self.assertFalse(target.exists())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    @unittest.skipUnless(os.name == "nt", "Windows retained-leaf share contract")
    def test_atomic_bundle_windows_retained_leaf_handles_block_content_tampering(self) -> None:
        """Stage, backup, and installed bytes stay handle-bound through rollback."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "out" / "file.bin"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"OLD-AUTHORITATIVE")
            blocked: list[str] = []
            changed: list[str] = []

            def try_foreign_write(phase: str, path: Path, payload: bytes) -> None:
                try:
                    path.write_bytes(payload)
                except OSError:
                    blocked.append(phase)
                else:
                    changed.append(phase)

            def tamper_retained_leaf(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "after-backup-allocation":
                    stage_root = next(root.glob(".blendlib-x5-stage-*"))
                    try_foreign_write("stage", stage_root / "out" / "file.bin", b"FOREIGN-STAGE")
                elif event.phase == "after-backup" and event.relative == "out/file.bin":
                    backup_root = next(root.glob(".blendlib-x5-backup-*"))
                    try_foreign_write("backup", backup_root / "out" / "file.bin", b"FOREIGN-BACKUP")
                elif event.phase == "after-install" and event.relative == "out/file.bin":
                    try_foreign_write("public", target, b"FOREIGN-PUBLIC")
                    raise OSError("force rollback after installed-leaf tamper probe")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002"):
                x5.atomic_write_bundle(
                    root,
                    {"out/file.bin": b"APPROVED-PAYLOAD"},
                    replace_func=tamper_retained_leaf,
                )

            self.assertEqual(["stage", "backup", "public"], blocked)
            self.assertEqual([], changed)
            self.assertEqual(b"OLD-AUTHORITATIVE", target.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    @unittest.skipUnless(os.name == "nt", "Windows retained-leaf share contract")
    def test_atomic_bundle_windows_prelocks_all_public_leaves_and_blocks_backup_replacement(self) -> None:
        """No bundle member can be rewritten/replaced after the first public move."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "a" / "file.bin"
            second = root / "b" / "file.bin"
            first.parent.mkdir(parents=True)
            second.parent.mkdir(parents=True)
            first.write_bytes(b"OLD-A")
            second.write_bytes(b"OLD-B")
            blocked: list[str] = []
            changed: list[str] = []

            def try_action(phase: str, action) -> None:
                try:
                    action()
                except OSError:
                    blocked.append(phase)
                else:
                    changed.append(phase)

            def try_late_mutations(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "after-backup" and event.relative == "a/file.bin":
                    backup_root = next(root.glob(".blendlib-x5-backup-*"))
                    displaced = root / "backup-a-displaced.bin"
                    try_action(
                        "backup-identity-replacement",
                        lambda: os.replace(backup_root / "a" / "file.bin", displaced),
                    )
                elif event.phase == "after-install" and event.relative == "a/file.bin":
                    try_action("unprocessed-public", lambda: second.write_bytes(b"FOREIGN-SECOND"))
                    raise OSError("force rollback after all-target lock probe")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002"):
                x5.atomic_write_bundle(
                    root,
                    {"a/file.bin": b"NEW-A", "b/file.bin": b"NEW-B"},
                    replace_func=try_late_mutations,
                )

            self.assertEqual(
                ["backup-identity-replacement", "unprocessed-public"],
                blocked,
            )
            self.assertEqual([], changed)
            self.assertEqual(b"OLD-A", first.read_bytes())
            self.assertEqual(b"OLD-B", second.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    @unittest.skipUnless(os.name == "nt", "Windows retained-leaf share contract")
    def test_atomic_bundle_windows_rejects_preexisting_writer_before_public_mutation(self) -> None:
        """A writer that withholds DELETE sharing blocks the bundle before backup/install."""

        import ctypes
        from ctypes import wintypes

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "out" / "file.bin"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"OLD-PUBLIC")
            create_file = ctypes.WinDLL("kernel32", use_last_error=True).CreateFileW
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
                str(target),
                0x40000000,  # GENERIC_WRITE
                0x00000001 | 0x00000002,  # FILE_SHARE_READ | FILE_SHARE_WRITE; no DELETE
                None,
                3,  # OPEN_EXISTING
                0x00000080,  # FILE_ATTRIBUTE_NORMAL
                None,
            )
            self.assertNotEqual(ctypes.c_void_p(-1).value, handle)
            close_handle = ctypes.WinDLL("kernel32", use_last_error=True).CloseHandle
            close_handle.argtypes = [wintypes.HANDLE]
            close_handle.restype = wintypes.BOOL
            try:
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                    x5.atomic_write_bundle(root, {"out/file.bin": b"NEW-PUBLIC"})
            finally:
                self.assertTrue(close_handle(handle))

            self.assertEqual(b"OLD-PUBLIC", target.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    @unittest.skipUnless(os.name == "nt", "Windows FileIdInfo identity contract")
    def test_windows_directory_binding_uses_complete_handle_file_id_identity(self) -> None:
        """Directory approval remains native-handle based across Python runtimes."""

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            binding = x5._atomic_existing_directory_binding(directory, "FileIdInfo test directory")
            graph = x5._AtomicLeaseGraph()
            lease = graph.acquire(binding, "FileIdInfo test directory")
            try:
                self.assertEqual("windows", binding.identity.namespace)
                self.assertIsInstance(binding.identity.secondary, bytes)
                self.assertEqual(16, len(binding.identity.secondary))
                self.assertIsNotNone(lease.windows_handle)
                live = x5._atomic_windows_file_information(lease.windows_handle, "FileIdInfo test directory")
                self.assertEqual(binding.identity, live.identity)
            finally:
                graph.close_all()

    def test_atomic_bundle_recovers_moves_that_raise_after_filesystem_replacement(self) -> None:
        """Every public move is journaled before a post-move replacement exception."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            existing = root / "out" / "existing.bin"
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b"OLD-EXISTING")
            existing_events: list[str] = []

            def raise_after_first_existing_move(event: x5._AtomicFaultEvent) -> None:
                existing_events.append(event.phase)
                if event.phase == "after-backup":
                    raise OSError("injected after backup move")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002"):
                x5.atomic_write_bundle(
                    root,
                    {"out/existing.bin": b"NEW-EXISTING"},
                    replace_func=raise_after_first_existing_move,
                )
            self.assertIn("after-backup", existing_events)
            self.assertEqual(b"OLD-EXISTING", existing.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

            new_target = root / "out" / "new.bin"
            new_events: list[str] = []

            def raise_after_first_new_move(event: x5._AtomicFaultEvent) -> None:
                new_events.append(event.phase)
                if event.phase == "after-install":
                    raise OSError("injected after staged install")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002"):
                x5.atomic_write_bundle(
                    root,
                    {"out/new.bin": b"NEW-ABSENT"},
                    replace_func=raise_after_first_new_move,
                )
            self.assertIn("after-install", new_events)
            self.assertFalse(new_target.exists())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_leases_every_output_parent_until_rollback_complete(self) -> None:
        """A first restore cannot make the following restore follow a renamed parent."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first_parent = root / "a"
            second_parent = root / "b"
            first_parent.mkdir()
            second_parent.mkdir()
            first = first_parent / "file.bin"
            second = second_parent / "file.bin"
            first.write_bytes(b"OLD-A")
            second.write_bytes(b"OLD-B")
            parent_rename_blocked = False

            def fail_second_install_then_try_parent_rename(event: x5._AtomicFaultEvent) -> None:
                nonlocal parent_rename_blocked
                if event.phase == "before-install" and event.relative == "b/file.bin":
                    raise OSError("injected before second staged install")
                if event.phase == "after-restore" and event.relative == "b/file.bin":
                    # Rollback now removes every installed public leaf by its
                    # retained handle before it restores any backup.  The
                    # first parent is therefore already empty here; its live
                    # directory lease, not a coincidental child file, must
                    # still prevent a retarget before the next restore.
                    try:
                        os.replace(first_parent, root / "a-displaced")
                    except OSError:
                        parent_rename_blocked = True

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002.*all affected targets were restored"):
                x5.atomic_write_bundle(
                    root,
                    {"a/file.bin": b"NEW-A", "b/file.bin": b"NEW-B"},
                    replace_func=fail_second_install_then_try_parent_rename,
                )

            self.assertTrue(parent_rename_blocked)
            self.assertEqual(b"OLD-A", first.read_bytes())
            self.assertEqual(b"OLD-B", second.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_recognizes_a_restore_that_moves_then_raises(self) -> None:
        """A post-move restore exception is not reported as an empty recoverable backup."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "out" / "file.bin"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"OLD-BYTES")
            restore_events: list[str] = []

            def fail_install_then_raise_after_restore(event: x5._AtomicFaultEvent) -> None:
                restore_events.append(event.phase)
                if event.phase == "before-install":
                    raise OSError("injected before staged install")
                if event.phase == "after-restore":
                    raise OSError("injected after rollback restore")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002.*all affected targets were restored"):
                x5.atomic_write_bundle(
                    root,
                    {"out/file.bin": b"NEW-BYTES"},
                    replace_func=fail_install_then_raise_after_restore,
                )

            self.assertIn("after-restore", restore_events)
            self.assertEqual(b"OLD-BYTES", target.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_atomic_bundle_never_restores_from_a_swapped_backup_root(self) -> None:
        """A restore source stays anchored to the leased backup object, never a recreated name."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "out" / "file.bin"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"OLD-BYTES")
            attempted_swap = False
            backup_rename_blocked = False
            foreign_path: Path | None = None

            def swap_backup_before_restore(event: x5._AtomicFaultEvent) -> None:
                nonlocal attempted_swap, backup_rename_blocked, foreign_path
                if event.phase == "before-install":
                    raise OSError("injected staged-install failure")
                if event.phase != "before-restore":
                    return
                attempted_swap = True
                backup_root = next(root.glob(".blendlib-x5-backup-*"))
                displaced = root / f"{backup_root.name}-displaced"
                try:
                    os.replace(backup_root, displaced)
                except OSError:
                    backup_rename_blocked = True
                    return
                backup_root.mkdir()
                foreign_parent = backup_root / "out"
                foreign_parent.mkdir()
                foreign_path = foreign_parent / "file.bin"
                foreign_path.write_bytes(b"FOREIGN-BYTES")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-002.*all affected targets were restored"):
                x5.atomic_write_bundle(
                    root,
                    {"out/file.bin": b"NEW-BYTES"},
                    replace_func=swap_backup_before_restore,
                )

            self.assertTrue(attempted_swap)
            self.assertEqual(b"OLD-BYTES", target.read_bytes())
            self.assertNotEqual(b"FOREIGN-BYTES", target.read_bytes())
            if foreign_path is not None:
                self.assertEqual(b"FOREIGN-BYTES", foreign_path.read_bytes())
            else:
                self.assertTrue(backup_rename_blocked)

    def test_atomic_bundle_cleanup_retains_unknown_stage_content_without_recursive_delete(self) -> None:
        """An unexpected private entry is retained, never recursively removed after publish."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "file.bin"
            marker: Path | None = None

            def retain_unknown_stage_entry(event: x5._AtomicFaultEvent) -> None:
                nonlocal marker
                if event.phase != "after-install":
                    return
                stage_root = next(root.glob(".blendlib-x5-stage-*"))
                marker = stage_root / "FOREIGN-MARKER"
                marker.write_bytes(b"DO-NOT-DELETE")

            with self.assertRaisesRegex(x5.X5ToolingError, "cleanup was retained"):
                x5.atomic_write_bundle(
                    root,
                    {"file.bin": b"NEW-BYTES"},
                    replace_func=retain_unknown_stage_entry,
                )

            self.assertEqual(b"NEW-BYTES", target.read_bytes())
            self.assertIsNotNone(marker)
            self.assertTrue(marker.is_file())
            self.assertEqual(b"DO-NOT-DELETE", marker.read_bytes())

    def test_atomic_bundle_rollback_reports_retained_unknown_stage_recovery_material(self) -> None:
        """Successful public rollback still reports the retained private-stage recovery tree."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "a" / "file.bin"
            second = root / "b" / "file.bin"
            first.parent.mkdir(parents=True)
            second.parent.mkdir(parents=True)
            first.write_bytes(b"OLD-A")
            second.write_bytes(b"OLD-B")
            retained_stage: Path | None = None
            marker: Path | None = None
            marker_digest: str | None = None

            def fail_after_install_with_unknown_stage_entry(event: x5._AtomicFaultEvent) -> None:
                nonlocal retained_stage, marker, marker_digest
                if event.phase != "after-install" or event.relative != "a/file.bin":
                    return
                retained_stage = next(root.glob(".blendlib-x5-stage-*"))
                marker = retained_stage / "FOREIGN-STAGE-MARKER"
                marker.write_bytes(b"KEEP-FOREIGN-STAGE")
                marker_digest = x5.sha256_file(marker)
                raise OSError("injected after-install failure")

            with self.assertRaises(x5.X5ToolingError) as raised:
                x5.atomic_write_bundle(
                    root,
                    {"a/file.bin": b"NEW-A", "b/file.bin": b"NEW-B"},
                    replace_func=fail_after_install_with_unknown_stage_entry,
                )

            error = raised.exception
            self.assertEqual("BLENDLIB-X5-ATOMIC-002", error.code)
            self.assertIsInstance(error.__cause__, OSError)
            self.assertIn("all affected targets were restored from staging backups", error.message)
            self.assertIn("Private transaction cleanup was retained for recovery", error.message)
            self.assertIsNotNone(retained_stage)
            self.assertIsNotNone(marker)
            self.assertIsNotNone(marker_digest)
            assert retained_stage is not None
            assert marker is not None
            assert marker_digest is not None
            self.assertIn(retained_stage.name, error.message)
            self.assertNotIn(str(root), error.message)
            self.assertNotRegex(error.message, r"(?i)[a-z]:[\\/]")
            self.assertNotIn("FOREIGN-STAGE-MARKER", error.message)
            self.assertEqual(b"OLD-A", first.read_bytes())
            self.assertEqual(b"OLD-B", second.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))
            self.assertEqual([retained_stage], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual(b"KEEP-FOREIGN-STAGE", marker.read_bytes())
            self.assertEqual(marker_digest, x5.sha256_file(marker))

    def test_atomic_bundle_retained_private_cleanup_keeps_post_public_x5_cause(self) -> None:
        """R1 retained-stage recovery detail does not replace the post-publication X5 cause."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "old.bin"
            target.write_bytes(b"OLD")
            original = x5.X5ToolingError("BLENDLIB-X5-PROBE-777", "ORIGINAL-BOUNDED-X5-FAULT")
            retained_stage: Path | None = None
            marker: Path | None = None

            def retain_stage_then_fail(event: x5._AtomicFaultEvent) -> None:
                nonlocal retained_stage, marker
                if event.phase != "after-install" or event.relative != "old.bin":
                    return
                retained_stage = next(root.glob(".blendlib-x5-stage-*"))
                marker = retained_stage / "FOREIGN-STAGE-MARKER"
                marker.write_bytes(b"KEEP-FOREIGN-STAGE")
                raise original

            with self.assertRaises(x5.X5ToolingError) as raised:
                x5.atomic_write_bundle(root, {"old.bin": b"NEW"}, replace_func=retain_stage_then_fail)

            error = raised.exception
            rendered = f"{error.message} {str(error)} {original.message} {str(original)}"
            self.assertEqual("BLENDLIB-X5-ATOMIC-002", error.code)
            self.assertIn("all affected targets were restored from staging backups", error.message)
            self.assertIn("Private transaction cleanup was retained for recovery", error.message)
            self.assertIs(error.__cause__, original)
            self.assertEqual("BLENDLIB-X5-PROBE-777", original.code)
            self.assertEqual("ORIGINAL-BOUNDED-X5-FAULT", original.message)
            self.assertIsNot(type(error.__cause__), RuntimeError)
            self.assertIsNotNone(retained_stage)
            self.assertIsNotNone(marker)
            assert retained_stage is not None
            assert marker is not None
            self.assertIn(retained_stage.name, error.message)
            self.assertEqual(b"OLD", target.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))
            self.assertEqual([retained_stage], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual(b"KEEP-FOREIGN-STAGE", marker.read_bytes())
            self.assertNotIn(str(root), rendered)
            self.assertNotRegex(rendered, r"(?i)[a-z]:[\\/]")

    def test_atomic_bundle_rollback_sorts_retained_stage_and_backup_diagnostics(self) -> None:
        """Both private recovery trees are reported once in stable bounded order."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "file.bin"
            target.write_bytes(b"OLD-BYTES")
            retained_stage: Path | None = None
            retained_backup: Path | None = None
            stage_marker: Path | None = None
            backup_marker: Path | None = None
            stage_digest: str | None = None
            backup_digest: str | None = None

            def retain_unknown_private_entries_then_fail(event: x5._AtomicFaultEvent) -> None:
                nonlocal retained_stage, retained_backup, stage_marker, backup_marker, stage_digest, backup_digest
                if event.phase == "after-backup" and event.relative == "file.bin":
                    retained_backup = next(root.glob(".blendlib-x5-backup-*"))
                    backup_marker = retained_backup / "FOREIGN-BACKUP-MARKER"
                    backup_marker.write_bytes(b"KEEP-FOREIGN-BACKUP")
                    backup_digest = x5.sha256_file(backup_marker)
                elif event.phase == "after-install" and event.relative == "file.bin":
                    retained_stage = next(root.glob(".blendlib-x5-stage-*"))
                    stage_marker = retained_stage / "FOREIGN-STAGE-MARKER"
                    stage_marker.write_bytes(b"KEEP-FOREIGN-STAGE")
                    stage_digest = x5.sha256_file(stage_marker)
                    raise OSError("injected after-install failure")

            with self.assertRaises(x5.X5ToolingError) as raised:
                x5.atomic_write_bundle(
                    root,
                    {"file.bin": b"NEW-BYTES"},
                    replace_func=retain_unknown_private_entries_then_fail,
                )

            error = raised.exception
            self.assertEqual("BLENDLIB-X5-ATOMIC-002", error.code)
            self.assertIn("all affected targets were restored from staging backups", error.message)
            self.assertIn("Private transaction cleanup was retained for recovery", error.message)
            self.assertIsNotNone(retained_stage)
            self.assertIsNotNone(retained_backup)
            self.assertIsNotNone(stage_marker)
            self.assertIsNotNone(backup_marker)
            self.assertIsNotNone(stage_digest)
            self.assertIsNotNone(backup_digest)
            assert retained_stage is not None
            assert retained_backup is not None
            assert stage_marker is not None
            assert backup_marker is not None
            assert stage_digest is not None
            assert backup_digest is not None
            self.assertLess(error.message.index(retained_backup.name), error.message.index(retained_stage.name))
            self.assertEqual(1, error.message.count(retained_backup.name))
            self.assertEqual(1, error.message.count(retained_stage.name))
            self.assertLessEqual(len(error.message), x5.MAX_SNAPSHOT_TEXT + 160)
            self.assertNotIn(str(root), error.message)
            self.assertNotRegex(error.message, r"(?i)[a-z]:[\\/]")
            self.assertNotIn("FOREIGN-BACKUP-MARKER", error.message)
            self.assertNotIn("FOREIGN-STAGE-MARKER", error.message)
            self.assertEqual(b"OLD-BYTES", target.read_bytes())
            self.assertEqual([retained_stage], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([retained_backup], list(root.glob(".blendlib-x5-backup-*")))
            self.assertEqual(b"KEEP-FOREIGN-STAGE", stage_marker.read_bytes())
            self.assertEqual(b"KEEP-FOREIGN-BACKUP", backup_marker.read_bytes())
            self.assertEqual(stage_digest, x5.sha256_file(stage_marker))
            self.assertEqual(backup_digest, x5.sha256_file(backup_marker))

    def test_atomic_bundle_rejects_retargeted_private_stage_and_backup_roots(self) -> None:
        """A post-lease private-root retarget cannot receive bytes outside the project."""

        def make_junction(alias: Path, target: Path) -> bool:
            result = subprocess.run(
                ["cmd.exe", "/d", "/c", "mklink", "/J", str(alias), str(target)],
                check=False,
                capture_output=True,
                text=True,
            )
            return result.returncode == 0

        def make_directory_alias(alias: Path, target: Path) -> bool:
            try:
                alias.symlink_to(target, target_is_directory=True)
                return True
            except OSError:
                return make_junction(alias, target)

        for private_kind, allocation_phase in (
            ("stage", "after-stage-allocation"),
            ("backup", "after-backup-allocation"),
        ):
            with self.subTest(private_kind=private_kind), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                root = parent / "project"
                outside = parent / "outside"
                root.mkdir()
                outside.mkdir()
                target = root / "approved" / "file.bin"
                target.parent.mkdir()
                target.write_bytes(b"OLD-PUBLIC-BYTES")
                probe = root / "junction-probe"
                if not make_directory_alias(probe, outside):
                    self.skipTest("directory symlink and junction aliases are unavailable")
                if probe.is_symlink():
                    probe.unlink()
                else:
                    probe.rmdir()

                allocations: list[str] = []
                replace_calls: list[tuple[str, str]] = []
                retarget_blocked = False

                def retarget_private_allocation(event: x5._AtomicFaultEvent) -> None:
                    nonlocal retarget_blocked
                    if event.phase in {"after-stage-allocation", "after-backup-allocation"}:
                        if event.phase != allocation_phase:
                            return
                        allocations.append(event.phase)
                        private_prefix = ".blendlib-x5-stage-" if private_kind == "stage" else ".blendlib-x5-backup-"
                        allocated = next(root.glob(private_prefix + "*"))
                        try:
                            allocated.rmdir()
                        except OSError:
                            # A Windows no-delete-share lease is expected to
                            # reject the attack before it can create an alias.
                            retarget_blocked = True
                            raise OSError("private directory lease blocked retarget")
                        self.assertTrue(make_directory_alias(allocated, outside))
                        return
                    replace_calls.append((event.phase, event.relative))
                    raise AssertionError("retargeted private allocation reached a public replacement")

                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                    x5.atomic_write_bundle(
                        root,
                        {"approved/file.bin": b"NEW-PUBLIC-BYTES"},
                        replace_func=retarget_private_allocation,
                    )

                self.assertEqual([allocation_phase], allocations)
                self.assertEqual([], replace_calls)
                self.assertEqual(b"OLD-PUBLIC-BYTES", target.read_bytes())
                self.assertEqual([], list(outside.rglob("*")))
                if os.name == "nt":
                    self.assertTrue(retarget_blocked)

    def test_atomic_bundle_rejects_recreated_ordinary_output_parent_before_public_replace(self) -> None:
        """A same-spelling directory replacement invalidates the physical approval binding."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            approved = root / "approved"
            approved.mkdir()
            original_identity = (os.lstat(approved).st_dev, os.lstat(approved).st_ino)
            stage_allocations: list[str] = []
            replace_calls: list[tuple[str, str]] = []

            def recreate_parent_after_stage(event: x5._AtomicFaultEvent) -> None:
                if event.phase == "after-stage-allocation":
                    stage_allocations.append(event.phase)
                    approved.rmdir()
                    approved.mkdir()
                    return
                replace_calls.append((event.phase, event.relative))
                raise AssertionError("directory recreation must fail before public replacement")

            with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001"):
                x5.atomic_write_bundle(
                    root,
                    {"approved/new.bin": b"NEW-PUBLIC-BYTES"},
                    replace_func=recreate_parent_after_stage,
                )

            recreated_identity = (os.lstat(approved).st_dev, os.lstat(approved).st_ino)
            self.assertNotEqual(original_identity, recreated_identity)
            self.assertEqual(["after-stage-allocation"], stage_allocations)
            self.assertEqual([], replace_calls)
            self.assertFalse((approved / "new.bin").exists())
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
        report = x5.build_asset_report(
            snapshot=warning_preflight.snapshot,
            sidecar=x5.build_authoring_sidecar(warning_preflight.snapshot),
            validation={"index_count": 3, "material_names": [], "vertex_count": 3},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero.glb": b"glb"},
            diagnostics=(),
        )
        with self.assertRaisesRegex(x5.X5ToolingError, "REPORT-002"):
            x5.asset_report_bytes(report)
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

    def test_frozen_snapshot_backing_mapping_mutation_fails_before_sidecar_or_stage(self) -> None:
        """Reflection through MappingProxyType cannot alter a trusted snapshot in place."""

        def backing_mapping(proxy: object) -> dict:
            mappings = [item for item in gc.get_referents(proxy) if type(item) is dict]
            self.assertEqual(1, len(mappings))
            return mappings[0]

        sidecar_result = x5.preflight_snapshot(self.snapshot())
        state = x5._trusted_snapshot_state(sidecar_result.snapshot)
        self.assertIsNotNone(state)
        execution_values = x5._snapshot_authority_values(sidecar_result.snapshot)
        self.assertIsNot(sidecar_result.snapshot._values, state.authority_values)
        self.assertIsNot(state.authority_values, execution_values)
        top_level = backing_mapping(sidecar_result.snapshot._values)
        top_level["namespace"] = "attacker"
        self.assertEqual("blendlib", execution_values["namespace"])
        self.assertIsNone(x5._trusted_snapshot_state(sidecar_result.snapshot))
        with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
            sidecar_result.report()
        with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
            x5.build_authoring_sidecar(sidecar_result.snapshot)

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            project_root = parent / "project"
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=project_root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            export_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("mutated frozen snapshot reached legacy export")

            original_exporter = x5._legacy_exporter

            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                preflight = x5.preflight_snapshot(self.snapshot())
                plan = x5._build_export_plan(options, preflight=preflight)
                top_level = backing_mapping(preflight.snapshot._values)
                nested_object = top_level["objects"][0]
                backing_mapping(nested_object)["name"] = "attacker-object"
                self.assertIsNone(x5._trusted_snapshot_state(preflight.snapshot))
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(options, plan=plan)
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual([], export_calls)
            self.assertFalse(project_root.exists())
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))

    def test_frozen_snapshot_mutation_during_private_export_blocks_before_publication(self) -> None:
        """A public snapshot changed by legacy export cannot reach report or public publication."""

        def backing_mapping(proxy: object) -> dict:
            mappings = [item for item in gc.get_referents(proxy) if type(item) is dict]
            self.assertEqual(1, len(mappings))
            return mappings[0]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            public_sidecar = root / "build/blendlib-authoring/blendlib/hero/model.blendlib-authoring.json"
            public_sidecar.parent.mkdir(parents=True)
            public_sidecar.write_bytes(b"OLD-PUBLIC-SIDECAR")
            export_calls: list[str] = []
            original_exporter = x5._legacy_exporter

            try:
                preflight = x5.preflight_snapshot(self.snapshot())

                class MutatingLegacyExporter:
                    @staticmethod
                    def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                        return (root,)

                    @staticmethod
                    def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                        export_calls.append("export")
                        for kind, relative in legacy.strict_v1_artifact_paths(
                            stage_options, ("HeroMaterial",)
                        ).items():
                            target = stage_options.project_root / Path(*relative.split("/"))
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(kind.encode("ascii"))
                        backing_mapping(preflight.snapshot._values)["namespace"] = "attacker"
                        return {
                            "validation": {
                                "index_count": 3,
                                "material_names": ["HeroMaterial"],
                                "vertex_count": 3,
                            }
                        }

                x5._legacy_exporter = lambda: MutatingLegacyExporter
                plan = x5._build_export_plan(options, preflight=preflight)
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(options, plan=plan)
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual(["export"], export_calls)
            self.assertEqual(b"OLD-PUBLIC-SIDECAR", public_sidecar.read_bytes())
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_frozen_export_plan_rejects_copy_forgery_and_public_mutation_before_stage(self) -> None:
        """The graph approved before staging cannot be changed through public Python state."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )

            def plan() -> x5._FrozenExportPlan:
                return x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))

            original = plan()
            for operation in (copy.copy, copy.deepcopy, pickle.dumps):
                with self.subTest(operation=operation.__name__), self.assertRaisesRegex(
                    x5.X5ToolingError, "SNAPSHOT-001"
                ):
                    operation(original)

            forged = x5._FrozenExportPlan(
                original.snapshot,
                original.options,
                original.claims,
                original.sidecar_payload,
                original.default_report_relative,
                original.explicit_report_relative,
                original.refresh_relative,
                original.runtime_relatives,
            )
            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5._trusted_export_plan_state(forged)

            rebuilt = dataclasses.replace(original)
            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5._trusted_export_plan_state(rebuilt)

            raw_reconstruction = object.__new__(x5._FrozenExportPlan)
            for field in dataclasses.fields(x5._FrozenExportPlan):
                object.__setattr__(raw_reconstruction, field.name, getattr(original, field.name))
            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5._trusted_export_plan_state(raw_reconstruction)

            tampered_options = plan()
            object.__setattr__(tampered_options.options, "model_id", "forged")
            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5._prepare_x5_export(options, plan=tampered_options)

            tampered_claim = plan()
            object.__setattr__(tampered_claim.claims[0], "relative", "build/forged.json")
            with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                x5._prepare_x5_export(options, plan=tampered_claim)
            self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))

    def test_export_plan_requires_exact_plain_field_types_before_stage(self) -> None:
        """Equal-valued derived fields cannot change plan execution behavior."""

        class RoutedStr(str):
            def __new__(cls, value: str, routed: str) -> "RoutedStr":
                instance = super().__new__(cls, value)
                instance.routed = routed
                return instance

            def split(self, separator: str | None = None, maximum: int = -1) -> list[str]:
                return self.routed.split(separator, maximum)

        class EqualPathLike(os.PathLike[str]):
            def __init__(self, value: Path) -> None:
                self.value = value

            def __fspath__(self) -> str:
                return os.fspath(self.value)

            def __eq__(self, other: object) -> bool:
                return other == self.value

        class EqualTuple(tuple):
            pass

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            export_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    export_calls.append(stage_options.model_id)
                    raise AssertionError("inconsistent plan reached the legacy exporter")

            def options(label: str) -> legacy.ExportOptions:
                root = parent / label
                return legacy.ExportOptions(
                    blend_path=source,
                    project_root=root,
                    namespace="blendlib",
                    model_id="hero/model",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )

            def plan(label: str) -> tuple[legacy.ExportOptions, x5._FrozenExportPlan]:
                current_options = options(label)
                return current_options, x5._build_export_plan(
                    current_options,
                    preflight=x5.preflight_snapshot(self.snapshot()),
                )

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter

                routed_options, routed_plan = plan("routed-relative")
                sidecar = next(claim for claim in routed_plan.claims if claim.kind == "sidecar")
                report = next(claim for claim in routed_plan.claims if claim.kind == "default-report")
                sidecar_relative = sidecar.relative
                report_relative = report.relative
                sidecar_path = routed_options.project_root / Path(*sidecar_relative.split("/"))
                report_path = routed_options.project_root / Path(*report_relative.split("/"))
                sidecar_path.parent.mkdir(parents=True)
                sidecar_path.write_bytes(b"old-sidecar")
                report_path.write_bytes(b"old-report")
                object.__setattr__(sidecar, "relative", RoutedStr(sidecar_relative, report_relative))
                object.__setattr__(report, "relative", RoutedStr(report_relative, sidecar_relative))
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(routed_options, plan=routed_plan)
                self.assertEqual(b"old-sidecar", sidecar_path.read_bytes())
                self.assertEqual(b"old-report", report_path.read_bytes())

                derived_cases = []
                derived_options, derived_plan = plan("derived-option-string")
                object.__setattr__(
                    derived_plan.options,
                    "model_id",
                    RoutedStr(derived_plan.options.model_id, "different/model"),
                )
                derived_cases.append((derived_options, derived_plan))

                path_options, path_plan = plan("derived-option-path")
                object.__setattr__(
                    path_plan.options,
                    "project_root",
                    EqualPathLike(path_plan.options.project_root),
                )
                derived_cases.append((path_options, path_plan))

                tuple_options, tuple_plan = plan("derived-option-container")
                object.__setattr__(
                    tuple_plan.options,
                    "texture_source_roots",
                    EqualTuple(tuple_plan.options.texture_source_roots),
                )
                derived_cases.append((tuple_options, tuple_plan))

                claim_options, claim_plan = plan("derived-claim-field")
                claim = claim_plan.claims[0]
                object.__setattr__(claim, "kind", RoutedStr(claim.kind, "different-kind"))
                derived_cases.append((claim_options, claim_plan))

                container_options, container_plan = plan("derived-plan-container")
                object.__setattr__(container_plan, "claims", EqualTuple(container_plan.claims))
                derived_cases.append((container_options, container_plan))

                class DerivedOptions(x5._FrozenLegacyOptions):
                    pass

                subclass_options, subclass_plan = plan("derived-option-record")
                object.__setattr__(
                    subclass_plan,
                    "options",
                    DerivedOptions(*x5._frozen_option_record(subclass_plan.options)),
                )
                derived_cases.append((subclass_options, subclass_plan))

                class DerivedClaim(x5._ArtifactClaim):
                    pass

                claim_subclass_options, claim_subclass_plan = plan("derived-claim-record")
                first_claim = claim_subclass_plan.claims[0]
                object.__setattr__(
                    claim_subclass_plan,
                    "claims",
                    (
                        DerivedClaim(
                            first_claim.relative,
                            first_claim.identity,
                            first_claim.kind,
                            first_claim.owner,
                            first_claim.payload_group,
                        ),
                        *claim_subclass_plan.claims[1:],
                    ),
                )
                derived_cases.append((claim_subclass_options, claim_subclass_plan))

                scalar_options, scalar_plan = plan("derived-plan-scalars")
                object.__setattr__(scalar_plan, "sidecar_payload", bytes(bytearray(scalar_plan.sidecar_payload)))
                derived_cases.append((scalar_options, scalar_plan))

                runtime_options, runtime_plan = plan("derived-runtime-container")
                object.__setattr__(runtime_plan, "runtime_relatives", EqualTuple(runtime_plan.runtime_relatives))
                derived_cases.append((runtime_options, runtime_plan))

                for current_options, current_plan in derived_cases:
                    with self.subTest(project=current_options.project_root.name), self.assertRaisesRegex(
                        x5.X5ToolingError, "SNAPSHOT-001"
                    ):
                        x5._prepare_x5_export(current_options, plan=current_plan)
                    self.assertFalse(current_options.project_root.exists())
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual([], export_calls)
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-backup-*")))

    def test_export_plan_public_surface_sentinels_reject_reconstruction_before_stage(self) -> None:
        """Every public plan field is presentation-only, including equal rebuilt values."""

        class RoutedStr(str):
            def __new__(cls, value: str) -> "RoutedStr":
                return super().__new__(cls, value)

        class EqualTuple(tuple):
            pass

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            explicit = root / "reports/explicit.json"
            refresh = root / "refresh/next.json"
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=explicit,
                dev_refresh_path=refresh,
                dev_session_token="test-session-token-1234",
                dev_generation=1,
            )
            old_files = {
                explicit: b"old-explicit",
                refresh: b"old-refresh",
                root / "build/blendlib-authoring/blendlib/hero/model.asset-report.json": b"old-default",
                root / "build/blendlib-authoring/blendlib/hero/model.blendlib-authoring.json": b"old-sidecar",
            }
            for target, payload in old_files.items():
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(payload)
            export_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("mutated plan reached strict-v1 exporter")

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter

                def plan() -> x5._FrozenExportPlan:
                    return x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))

                baseline = plan()
                alternate_snapshot = x5.preflight_snapshot(self.snapshot()).snapshot
                mutations = (
                    ("snapshot", alternate_snapshot),
                    ("options", dataclasses.replace(baseline.options)),
                    ("claims", EqualTuple(baseline.claims)),
                    ("sidecar_payload", bytes(bytearray(baseline.sidecar_payload))),
                    ("default_report_relative", RoutedStr(baseline.default_report_relative)),
                    ("explicit_report_relative", RoutedStr(baseline.explicit_report_relative)),
                    ("refresh_relative", RoutedStr(baseline.refresh_relative)),
                    ("runtime_relatives", EqualTuple(baseline.runtime_relatives)),
                )
                for field, value in mutations:
                    current = plan()
                    object.__setattr__(current, field, value)
                    with self.subTest(field=field), self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                        x5._prepare_x5_export(options, plan=current)
                    self.assertEqual([], list(root.glob(".blendlib-x5-export-*")))
                    self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
                for target, payload in old_files.items():
                    self.assertEqual(payload, target.read_bytes())
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual([], export_calls)

    def test_export_plan_registry_generation_and_normal_plan_remain_deterministic(self) -> None:
        """A stale cleanup cannot remove a replacement state; an intact plan remains usable."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root / "project",
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                class DeterministicLegacyExporter:
                    @staticmethod
                    def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                        return (root,)

                    @staticmethod
                    def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                        for kind, relative in legacy.strict_v1_artifact_paths(
                            stage_options, ("HeroMaterial",)
                        ).items():
                            target = stage_options.project_root / Path(*relative.split("/"))
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(kind.encode("ascii"))
                        return {
                            "validation": {
                                "index_count": 3,
                                "material_names": ["HeroMaterial"],
                                "vertex_count": 3,
                            }
                        }

                x5._legacy_exporter = lambda: DeterministicLegacyExporter
                plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                snapshot, frozen_options, state = x5._trusted_export_plan_state(plan)
                self.assertIs(plan.snapshot, snapshot)
                self.assertEqual(options.project_root.resolve(), frozen_options.project_root)
                self.assertTrue(state.claims)

                replacement_generation = object()
                replacement_state = state._replace(generation=replacement_generation)
                x5._TRUSTED_EXPORT_PLAN_STATES[id(plan)] = replacement_state
                try:
                    x5._release_trusted_export_plan(id(plan), state.generation, state.plan_ref)
                    self.assertIs(replacement_state, x5._TRUSTED_EXPORT_PLAN_STATES[id(plan)])
                finally:
                    x5._TRUSTED_EXPORT_PLAN_STATES[id(plan)] = state
                prepared = x5._prepare_x5_export(options, plan=plan)
                try:
                    self.assertEqual(options.project_root.resolve(), prepared.options.project_root)
                    self.assertEqual("blendlib:hero/model", prepared.result["model_key"])
                    self.assertEqual(tuple(state.claims), prepared.claims)
                finally:
                    self.assertFalse(prepared.stage_root.exists())
            finally:
                x5._legacy_exporter = original_exporter

    def test_plan_root_binding_blocks_same_spelling_recreation_before_private_stage(self) -> None:
        """Prepare must reopen the approval-time project root, never a replacement at that spelling."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / "project"
            root.mkdir()
            source = root / "source.blend"
            source.write_bytes(b"ORIGINAL-SOURCE")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            export_calls: list[str] = []
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent, export_calls)
                plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                approved_root = parent / "approved-project"
                root.rename(approved_root)
                root.mkdir()
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001.*approved anchor changed identity"):
                    x5._prepare_x5_export(options, plan=plan)
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual([], export_calls)
            self.assertEqual(b"ORIGINAL-SOURCE", (approved_root / "source.blend").read_bytes())
            self.assertEqual([], list(root.rglob("*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))

    def test_prepared_root_binding_blocks_same_spelling_recreation_before_atomic_stage(self) -> None:
        """The high-level handoff preserves root identity from prepare into the writer."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"SOURCE-OUTSIDE-PROJECT")
            root = parent / "project"
            root.mkdir()
            sentinel = root / "approved-sentinel.txt"
            sentinel.write_bytes(b"OLD-SENTINEL")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent)
                plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                prepared = x5._prepare_x5_export(options, plan=plan)
                _, _, plan_state = x5._trusted_export_plan_state(plan)
                self.assertIsNotNone(prepared.root_binding)
                self.assertIsNotNone(prepared.bundle_bindings)
                approved_root = parent / "approved-project"
                root.rename(approved_root)
                root.mkdir()
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001.*approved project root changed identity"):
                    x5.atomic_write_bundle(
                        root,
                        prepared.outputs,
                        approved_claims=plan_state.claims,
                        claim_conflict_code="BLENDLIB-X5-PATH-004",
                        approved_bindings=prepared.bundle_bindings,
                        approved_root_binding=prepared.root_binding,
                    )
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual(b"SOURCE-OUTSIDE-PROJECT", source.read_bytes())
            self.assertEqual(b"OLD-SENTINEL", (approved_root / "approved-sentinel.txt").read_bytes())
            self.assertEqual([], list(root.rglob("*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-backup-*")))

    def test_prepared_bundle_binding_blocks_recreated_existing_output_parent_before_atomic_stage(self) -> None:
        """A prepared output-parent inode is as authoritative as the prepared project root."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"SOURCE-OUTSIDE-PROJECT")
            root = parent / "project"
            output_parent = root / "src/main/resources"
            output_parent.mkdir(parents=True)
            sentinel = output_parent / "approved-sentinel.txt"
            sentinel.write_bytes(b"OLD-SENTINEL")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent)
                plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                prepared = x5._prepare_x5_export(options, plan=plan)
                _, _, plan_state = x5._trusted_export_plan_state(plan)
                self.assertIsNotNone(prepared.root_binding)
                self.assertIsNotNone(prepared.bundle_bindings)
                approved_parent = root / "approved-resources"
                output_parent.rename(approved_parent)
                output_parent.mkdir(parents=True)
                with self.assertRaisesRegex(x5.X5ToolingError, "ATOMIC-001.*changed physical directory identity"):
                    x5.atomic_write_bundle(
                        root,
                        prepared.outputs,
                        approved_claims=plan_state.claims,
                        claim_conflict_code="BLENDLIB-X5-PATH-004",
                        approved_bindings=prepared.bundle_bindings,
                        approved_root_binding=prepared.root_binding,
                    )
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual(b"SOURCE-OUTSIDE-PROJECT", source.read_bytes())
            self.assertEqual(b"OLD-SENTINEL", (approved_parent / "approved-sentinel.txt").read_bytes())
            self.assertEqual([], list(output_parent.rglob("*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(root.glob(".blendlib-x5-backup-*")))

    def test_publication_record_multiset_preserves_complete_alias_claim_bindings(self) -> None:
        """The final output check retains roles/payloads even when reports share an identity."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            default_report = root / "build/blendlib-authoring/blendlib/hero/model.asset-report.json"
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=default_report,
            )

            class DeterministicLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    for kind, relative in legacy.strict_v1_artifact_paths(
                        stage_options, ("HeroMaterial",)
                    ).items():
                        target = stage_options.project_root / Path(*relative.split("/"))
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(kind.encode("ascii"))
                    return {
                        "validation": {
                            "index_count": 3,
                            "material_names": ["HeroMaterial"],
                            "vertex_count": 3,
                        }
                    }

            original_exporter = x5._legacy_exporter
            original_records = x5._publication_record_multiset
            observed: list[tuple[x5._CanonicalPublicationRecord, ...]] = []

            def records(
                claims: tuple[x5._CanonicalArtifactClaim, ...], payloads: dict[str, bytes]
            ) -> tuple[x5._CanonicalPublicationRecord, ...]:
                value = original_records(claims, payloads)
                observed.append(value)
                return value

            prepared = None
            try:
                x5._legacy_exporter = lambda: DeterministicLegacyExporter
                plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                _, _, state = x5._trusted_export_plan_state(plan)
                default_claim = x5._single_claim(state.claims, "default-report")
                explicit_claim = x5._single_claim(state.claims, "explicit-report")
                self.assertEqual(default_claim.identity, explicit_claim.identity)
                self.assertNotEqual(default_claim.kind, explicit_claim.kind)
                payloads = {
                    claim.relative: f"payload:{claim.relative}".encode("utf-8")
                    for claim in state.claims
                }
                records_with_alias = original_records(state.claims, payloads)
                records_without_alias = original_records(
                    tuple(claim for claim in state.claims if claim.kind != "explicit-report"),
                    payloads,
                )
                self.assertNotEqual(records_with_alias, records_without_alias)
                self.assertEqual(len(state.claims), len(records_with_alias))
                x5._publication_record_multiset = records
                prepared = x5._prepare_x5_export(options, plan=plan)
                self.assertEqual(2, len(observed))
                self.assertEqual(observed[0], observed[1])
                self.assertEqual(
                    {"default-report", "explicit-report"},
                    {record.kind for record in observed[0] if record.identity == default_claim.identity},
                )
                self.assertTrue(all(type(record.payload) is bytes for record in observed[0]))
            finally:
                x5._publication_record_multiset = original_records
                x5._legacy_exporter = original_exporter
                if prepared is not None:
                    self.assertFalse(prepared.stage_root.exists())

    def test_final_publication_record_mismatch_blocks_atomic_publication(self) -> None:
        """A final claim-field mismatch cleans staging and cannot reach the public writer."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            project = root / "project"
            sidecar = project / "build/blendlib-authoring/blendlib/hero/model.blendlib-authoring.json"
            sidecar.parent.mkdir(parents=True)
            sidecar.write_bytes(b"OLD-SIDECAR-BYTES")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=project,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            export_calls: list[str] = []
            publication_calls: list[str] = []

            class DeterministicLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(stage_options: legacy.ExportOptions) -> dict:
                    export_calls.append("export")
                    for kind, relative in legacy.strict_v1_artifact_paths(
                        stage_options, ("HeroMaterial",)
                    ).items():
                        target = stage_options.project_root / Path(*relative.split("/"))
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(kind.encode("ascii"))
                    return {
                        "validation": {
                            "index_count": 3,
                            "material_names": ["HeroMaterial"],
                            "vertex_count": 3,
                        }
                    }

            original_exporter = x5._legacy_exporter
            original_records = x5._publication_record_multiset
            original_atomic = x5.atomic_write_bundle
            original_preflight = x5.preflight_blender
            record_calls: list[tuple[x5._CanonicalPublicationRecord, ...]] = []
            returned_records: list[tuple[x5._CanonicalPublicationRecord, ...]] = []

            def mismatching_records(
                claims: tuple[x5._CanonicalArtifactClaim, ...], payloads: dict[str, bytes]
            ) -> tuple[x5._CanonicalPublicationRecord, ...]:
                records = original_records(claims, payloads)
                record_calls.append(records)
                if len(record_calls) == 2:
                    returned = (records[0]._replace(owner="forged-owner"), *records[1:])
                else:
                    returned = records
                returned_records.append(returned)
                return returned

            def forbidden_atomic(*unused_args: object, **unused_kwargs: object) -> None:
                publication_calls.append("atomic")
                raise AssertionError("mismatched publication records reached atomic writer")

            try:
                x5._legacy_exporter = lambda: DeterministicLegacyExporter
                x5.preflight_blender = lambda unused_options: x5.preflight_snapshot(self.snapshot())
                x5._publication_record_multiset = mismatching_records
                x5.atomic_write_bundle = forbidden_atomic
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5.x5_export_open_blend(options)
            finally:
                x5._legacy_exporter = original_exporter
                x5._publication_record_multiset = original_records
                x5.atomic_write_bundle = original_atomic
                x5.preflight_blender = original_preflight

            self.assertEqual(["export"], export_calls)
            self.assertEqual([], publication_calls)
            self.assertEqual(2, len(record_calls))
            self.assertEqual(record_calls[0], record_calls[1])
            self.assertNotEqual(returned_records[0], returned_records[1])
            self.assertEqual(b"OLD-SIDECAR-BYTES", sidecar.read_bytes())
            self.assertEqual([], list(project.glob(".blendlib-x5-export-*")))
            self.assertEqual([], list(project.glob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(project.glob(".blendlib-x5-backup-*")))

    def test_publication_record_multiset_is_sensitive_to_every_bound_field(self) -> None:
        """Owner, role, path, identity, payload group, and bytes remain independent final bindings."""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.blend"
            source.write_bytes(b"blend")
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="hero/model",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )

            class NamingOnlyLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (root,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    raise AssertionError("publication-record test must not stage an export")

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: NamingOnlyLegacyExporter
                plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                _, _, state = x5._trusted_export_plan_state(plan)
                payloads = {
                    claim.relative: f"payload:{index}".encode("ascii")
                    for index, claim in enumerate(state.claims)
                }
                baseline = x5._publication_record_multiset(state.claims, payloads)
                self.assertEqual(len(state.claims), len(baseline))
                selected = state.claims[0]
                for field, replacement in (
                    ("owner", "forged-owner"),
                    ("kind", "forged-kind"),
                    ("payload_group", "forged-payload-group"),
                    ("identity", "forged-identity"),
                ):
                    with self.subTest(field=field):
                        altered = selected._replace(**{field: replacement})
                        altered_claims = tuple(altered if claim is selected else claim for claim in state.claims)
                        self.assertNotEqual(
                            baseline,
                            x5._publication_record_multiset(altered_claims, payloads),
                        )

                with self.subTest(field="relative"):
                    replacement_relative = "altered/" + selected.relative.replace("/", "_")
                    altered = selected._replace(relative=replacement_relative)
                    altered_claims = tuple(altered if claim is selected else claim for claim in state.claims)
                    altered_payloads = dict(payloads)
                    altered_payloads[replacement_relative] = altered_payloads.pop(selected.relative)
                    self.assertNotEqual(
                        baseline,
                        x5._publication_record_multiset(altered_claims, altered_payloads),
                    )

                with self.subTest(field="payload"):
                    altered_payloads = dict(payloads)
                    altered_payloads[selected.relative] = b"forged-payload"
                    self.assertNotEqual(
                        baseline,
                        x5._publication_record_multiset(state.claims, altered_payloads),
                    )
            finally:
                x5._legacy_exporter = original_exporter

    def test_output_ancestor_files_fail_through_public_export_before_any_stage(self) -> None:
        """Every planned runtime/authoring output rejects a file ancestor during approval."""

        valid = x5.preflight_snapshot(self.snapshot())
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            export_calls: list[str] = []
            atomic_calls: list[str] = []
            refresh_calls: list[str] = []
            mkdir_calls: list[str] = []
            private_allocations: list[str] = []
            staged_open_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("file ancestor reached strict-v1 exporter")

            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            original_atomic = x5.atomic_write_bundle
            original_refresh = x5.refresh_message_bytes
            original_mkdir = x5.Path.mkdir
            original_open = x5.Path.open

            def forbidden_atomic(*unused_args: object, **unused_kwargs: object) -> None:
                atomic_calls.append("atomic")
                raise AssertionError("file ancestor reached atomic publication")

            def tracked_refresh(message: x5.RefreshMessage) -> bytes:
                refresh_calls.append("refresh")
                return original_refresh(message)

            def tracked_mkdir(path: Path, *args: object, **kwargs: object) -> None:
                mkdir_calls.append(os.fspath(path))
                return original_mkdir(path, *args, **kwargs)

            def tracked_open(path: Path, *args: object, **kwargs: object):
                rendered = os.fspath(path)
                if ".blendlib-x5-export-" in rendered or ".blendlib-x5-stage-" in rendered:
                    staged_open_calls.append(rendered)
                return original_open(path, *args, **kwargs)

            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                x5.preflight_blender = lambda unused_options: valid
                x5.atomic_write_bundle = forbidden_atomic
                x5.refresh_message_bytes = tracked_refresh
                x5.Path.mkdir = tracked_mkdir
                x5.Path.open = tracked_open
                cases = (
                    ("runtime", "src", {}),
                    ("authoring", "build", {}),
                    ("explicit-report", "reports", {"report_path": "reports/report.json"}),
                    (
                        "refresh",
                        "refresh-parent",
                        {
                            "dev_generation": 1,
                            "dev_refresh_path": "refresh-parent/refresh.json",
                            "dev_session_token": "test-session-token-1234",
                        },
                    ),
                )
                for label, blocker_relative, overrides in cases:
                    with self.subTest(label=label):
                        root = parent / label
                        root.mkdir()
                        source = root / "source.blend"
                        source.write_bytes(b"blend")
                        blocker = root / blocker_relative
                        blocker.write_bytes(f"old-{label}".encode("ascii"))
                        mkdir_calls.clear()
                        private_allocations.clear()
                        staged_open_calls.clear()
                        option_values: dict[str, object] = {
                            "blend_path": source,
                            "project_root": root,
                            "namespace": "blendlib",
                            "model_id": "hero/model",
                            "profile": "blendlib:rigid_v1",
                            "collection_name": None,
                            "output_resource_root": "src/main/resources",
                            "report_path": None,
                        }
                        for key, value in overrides.items():
                            option_values[key] = root / value if key in {"report_path", "dev_refresh_path"} else value
                        with self.assertRaisesRegex(
                            x5.X5ToolingError, "PATH-004.*existing non-directory ancestor"
                        ):
                            x5.x5_export_open_blend(legacy.ExportOptions(**option_values))
                        for count in (2, 3):
                            items = [
                                x5.BatchExportItem(
                                    "blendlib", f"hero/{index}", "blendlib:rigid_v1", None
                                )
                                for index in range(count)
                            ]
                            with self.subTest(label=label, count=count), self.assertRaisesRegex(
                                x5.X5ToolingError, "BATCH-002.*existing non-directory ancestor"
                            ):
                                x5.x5_batch_export_open_blend(
                                    legacy.ExportOptions(**option_values), items
                                )
                        self.assertEqual(f"old-{label}".encode("ascii"), blocker.read_bytes())
                        self.assertEqual([], list(root.rglob(".blendlib-x5-export-*")))
                        self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                        self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))
                        self.assertEqual([], mkdir_calls)
                        self.assertEqual([], private_allocations)
                        self.assertEqual([], staged_open_calls)
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight
                x5.atomic_write_bundle = original_atomic
                x5.refresh_message_bytes = original_refresh
                x5.Path.mkdir = original_mkdir
                x5.Path.open = original_open

            self.assertEqual([], export_calls)
            self.assertEqual([], atomic_calls)
            self.assertEqual([], refresh_calls)

    def test_each_actual_claim_ancestor_and_late_batch_item_fail_before_side_effects(self) -> None:
        """Each X5 claim is checked, including a blocker unique to the final batch item."""

        valid = x5.preflight_snapshot(self.snapshot())
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            export_calls: list[str] = []
            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("claim ancestor reached strict-v1 exporter")

            def options(
                root: Path,
                *,
                include_report_and_refresh: bool,
            ) -> legacy.ExportOptions:
                return legacy.ExportOptions(
                    blend_path=source,
                    project_root=root,
                    namespace="blendlib",
                    model_id="hero/model",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=(root / "reports/explicit.json") if include_report_and_refresh else None,
                    dev_refresh_path=(root / "refresh/next.json") if include_report_and_refresh else None,
                    dev_session_token="test-session-token-1234" if include_report_and_refresh else None,
                    dev_generation=1 if include_report_and_refresh else None,
                )

            def assert_no_side_effects(root: Path, callback: object, expression: str) -> None:
                atomic_calls: list[str] = []
                refresh_calls: list[str] = []
                mkdir_calls: list[str] = []
                private_allocations: list[str] = []
                staged_open_calls: list[str] = []
                original_atomic = x5.atomic_write_bundle
                original_refresh = x5.refresh_message_bytes
                original_mkdir = x5.Path.mkdir
                original_open = x5.Path.open

                def forbidden_atomic(*unused_args: object, **unused_kwargs: object) -> None:
                    atomic_calls.append("atomic")
                    raise AssertionError("claim ancestor reached atomic publication")

                def tracked_refresh(message: x5.RefreshMessage) -> bytes:
                    refresh_calls.append("refresh")
                    return original_refresh(message)

                def tracked_mkdir(path: Path, *args: object, **kwargs: object) -> None:
                    mkdir_calls.append(os.fspath(path))
                    return original_mkdir(path, *args, **kwargs)

                def tracked_open(path: Path, *args: object, **kwargs: object):
                    rendered = os.fspath(path)
                    if ".blendlib-x5-export-" in rendered or ".blendlib-x5-stage-" in rendered:
                        staged_open_calls.append(rendered)
                    return original_open(path, *args, **kwargs)

                try:
                    x5.atomic_write_bundle = forbidden_atomic
                    x5.refresh_message_bytes = tracked_refresh
                    x5.Path.mkdir = tracked_mkdir
                    x5.Path.open = tracked_open
                    with self.assertRaisesRegex(x5.X5ToolingError, expression):
                        callback()
                finally:
                    x5.atomic_write_bundle = original_atomic
                    x5.refresh_message_bytes = original_refresh
                    x5.Path.mkdir = original_mkdir
                    x5.Path.open = original_open

                self.assertEqual([], export_calls)
                self.assertEqual([], atomic_calls)
                self.assertEqual([], refresh_calls)
                self.assertEqual([], mkdir_calls)
                self.assertEqual([], private_allocations)
                self.assertEqual([], staged_open_calls)
                self.assertEqual([], list(root.rglob(".blendlib-x5-export-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))

            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                x5.preflight_blender = lambda unused_options: valid
                template_root = parent / "template"
                template_plan = x5._build_export_plan(
                    options(template_root, include_report_and_refresh=True), preflight=valid
                )
                _, _, template_state = x5._trusted_export_plan_state(template_plan)
                claim_relatives = tuple(claim.relative for claim in template_state.claims)
                self.assertEqual(
                    {
                        "descriptor",
                        "glb",
                        "texture:HeroMaterial",
                        "sidecar",
                        "default-report",
                        "explicit-report",
                        "dev-refresh",
                    },
                    {claim.kind for claim in template_state.claims},
                )

                case_index = 0
                for relative in claim_relatives:
                    parts = relative.split("/")
                    for depth in range(1, len(parts)):
                        case_index += 1
                        root = parent / f"claim-{case_index}"
                        root.mkdir()
                        blocker = root / Path(*parts[:depth])
                        blocker.parent.mkdir(parents=True, exist_ok=True)
                        old_bytes = f"old-{case_index}".encode("ascii")
                        blocker.write_bytes(old_bytes)
                        current_options = options(root, include_report_and_refresh=True)
                        with self.subTest(relative=relative, ancestor=blocker.relative_to(root).as_posix()):
                            assert_no_side_effects(
                                root,
                                lambda current_options=current_options: x5.x5_export_open_blend(current_options),
                                "PATH-004.*existing non-directory ancestor",
                            )
                        self.assertEqual(old_bytes, blocker.read_bytes())

                for count, items in (
                    (
                        2,
                        (
                            x5.BatchExportItem("blendlib", "alpha/one", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "z-last/two", "blendlib:rigid_v1", None),
                        ),
                    ),
                    (
                        3,
                        (
                            x5.BatchExportItem("blendlib", "alpha/one", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "middle/two", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "z-last/three", "blendlib:rigid_v1", None),
                        ),
                    ),
                ):
                    root = parent / f"late-batch-{count}"
                    root.mkdir()
                    blocker = root / "build/blendlib-authoring/blendlib/z-last"
                    blocker.parent.mkdir(parents=True, exist_ok=True)
                    old_bytes = f"old-late-{count}".encode("ascii")
                    blocker.write_bytes(old_bytes)
                    current_options = options(root, include_report_and_refresh=False)
                    with self.subTest(batch=count, late_item=True):
                        assert_no_side_effects(
                            root,
                            lambda current_options=current_options, items=items: x5.x5_batch_export_open_blend(
                                current_options, items
                            ),
                            "BATCH-002.*existing non-directory ancestor",
                        )
                    self.assertEqual(old_bytes, blocker.read_bytes())
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

            self.assertEqual([], export_calls)

    def test_windows_legacy_path_budget_allows_boundary_and_blocks_next_unit_before_legacy_export(self) -> None:
        """X5 reserves the exact Win32 file/dir budget before any private stage exists."""

        if os.name != "nt":
            self.skipTest("Windows legacy path budgeting is platform-specific")

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            valid = x5.preflight_snapshot(self.snapshot())
            export_calls: list[str] = []

            def path_at_units(target_units: int, tag: str, marker: str = "") -> Path:
                base = parent / tag
                base.mkdir()
                remaining_units = target_units - x5._windows_utf16_path_units(base, "test root") - 1
                marker_units = len(marker.encode("utf-16-le")) // 2
                self.assertGreater(remaining_units, marker_units)
                root = base / (marker + ("r" * (remaining_units - marker_units)))
                self.assertEqual(target_units, x5._windows_utf16_path_units(root, "test root"))
                return root

            def options(project_root: Path) -> legacy.ExportOptions:
                return legacy.ExportOptions(
                    blend_path=source,
                    project_root=project_root,
                    namespace="blendlib",
                    model_id="a",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )

            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent, export_calls)
                x5.preflight_blender = lambda unused_options: valid

                # The longest strict-v1 private leaf is the texture under the
                # 52-character legacy/backup private directory. At this root
                # it is exactly 259 UTF-16 units and must remain legal.
                boundary_root = path_at_units(133, "boundary")
                boundary_root.mkdir()
                result = x5.x5_export_open_blend(options(boundary_root))
                self.assertEqual("blendlib:a", result["model_key"])
                self.assertEqual(["a"], export_calls)
                for relative in (
                    "src/main/resources/assets/blendlib/models3d/a.glb",
                    "src/main/resources/assets/blendlib/blend_models/a.json",
                    "src/main/resources/assets/blendlib/textures/blendlib/a__heromaterial.png",
                    "build/blendlib-authoring/blendlib/a.blendlib-authoring.json",
                    "build/blendlib-authoring/blendlib/a.asset-report.json",
                ):
                    self.assertTrue((boundary_root / Path(*relative.split("/"))).is_file(), relative)
                self.assertEqual([], list(boundary_root.rglob(".blendlib-x5-export-*")))
                self.assertEqual([], list(boundary_root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(boundary_root.rglob(".blendlib-x5-backup-*")))

                # One more UTF-16 unit makes the private texture leaf 260.
                # The old public byte must survive and neither mkdir nor the
                # compatibility exporter may be reached.
                export_calls.clear()
                over_root = path_at_units(134, "over")
                over_root.mkdir()
                old_descriptor = over_root / "src/main/resources/assets/blendlib/blend_models/a.json"
                old_descriptor.parent.mkdir(parents=True)
                old_descriptor.write_bytes(b"old-descriptor")
                mkdir_calls: list[str] = []
                original_mkdir = x5.Path.mkdir

                def tracked_mkdir(path: Path, *args: object, **kwargs: object) -> None:
                    mkdir_calls.append(os.fspath(path))
                    return original_mkdir(path, *args, **kwargs)

                try:
                    x5.Path.mkdir = tracked_mkdir
                    with self.assertRaisesRegex(
                        x5.X5ToolingError,
                        "PATH-005.*private legacy export stage",
                    ):
                        x5.x5_export_open_blend(options(over_root))
                finally:
                    x5.Path.mkdir = original_mkdir
                self.assertEqual([], export_calls)
                self.assertEqual([], mkdir_calls)
                self.assertEqual(b"old-descriptor", old_descriptor.read_bytes())
                self.assertEqual([], list(over_root.rglob(".blendlib-x5-export-*")))
                self.assertEqual([], list(over_root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(over_root.rglob(".blendlib-x5-backup-*")))
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

    def test_windows_legacy_path_budget_counts_non_bmp_utf16_units_and_rejects_extended_roots(self) -> None:
        """Supplementary characters cannot bypass a code-unit budget or enable ``\\?\\`` paths."""

        if os.name != "nt":
            self.skipTest("Windows legacy path budgeting is platform-specific")

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            valid = x5.preflight_snapshot(self.snapshot())
            export_calls: list[str] = []

            base = parent / "non-bmp"
            base.mkdir()
            remaining_units = 134 - x5._windows_utf16_path_units(base, "test root") - 1
            root = base / ("😀" + ("r" * (remaining_units - 2)))
            self.assertEqual(134, x5._windows_utf16_path_units(root, "test root"))
            root.mkdir()
            old_descriptor = root / "src/main/resources/assets/blendlib/blend_models/a.json"
            old_descriptor.parent.mkdir(parents=True)
            old_descriptor.write_bytes(b"old-descriptor")
            private_texture = (
                root
                / x5._private_directory_budget_name(x5._LEGACY_EXPORT_STAGE_PREFIX)
                / "src/main/resources/assets/blendlib/textures/blendlib/a__heromaterial.png"
            )
            self.assertEqual(260, x5._windows_utf16_path_units(private_texture, "private texture"))
            self.assertEqual(259, len(os.fspath(private_texture)))

            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent, export_calls)
                x5.preflight_blender = lambda unused_options: valid
                options = legacy.ExportOptions(
                    blend_path=source,
                    project_root=root,
                    namespace="blendlib",
                    model_id="a",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )
                with self.assertRaisesRegex(
                    x5.X5ToolingError,
                    "PATH-005.*private legacy export stage",
                ):
                    x5.x5_export_open_blend(options)
                self.assertEqual([], export_calls)
                self.assertEqual(b"old-descriptor", old_descriptor.read_bytes())
                self.assertEqual([], list(root.rglob(".blendlib-x5-export-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))

                for spelling in (r"\\?\C:\x5-unsupported", r"\\.\C:\x5-unsupported"):
                    with self.subTest(project_root=spelling), self.assertRaisesRegex(x5.X5ToolingError, "PATH-005"):
                        x5._freeze_export_options(dataclasses.replace(options, project_root=Path(spelling)))
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

    def test_windows_batch_path_budget_runs_after_union_graph_validation(self) -> None:
        """A batch collision wins over PATH-005, then a valid union fails before legacy export."""

        if os.name != "nt":
            self.skipTest("Windows legacy path budgeting is platform-specific")

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            valid = x5.preflight_snapshot(self.snapshot())
            base = parent / "batch"
            base.mkdir()
            remaining_units = 134 - x5._windows_utf16_path_units(base, "test root") - 1
            root = base / ("r" * remaining_units)
            self.assertEqual(134, x5._windows_utf16_path_units(root, "test root"))
            root.mkdir()
            export_calls: list[str] = []
            options = legacy.ExportOptions(
                blend_path=source,
                project_root=root,
                namespace="blendlib",
                model_id="unused",
                profile="blendlib:rigid_v1",
                collection_name=None,
                output_resource_root="src/main/resources",
                report_path=None,
            )
            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: self._strict_stage_exporter(parent, export_calls)
                x5.preflight_blender = lambda unused_options: valid
                with self.assertRaisesRegex(x5.X5ToolingError, "BATCH-002") as collision:
                    x5.x5_batch_export_open_blend(
                        options,
                        (
                            x5.BatchExportItem("blendlib", "a/b", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "a_b", "blendlib:rigid_v1", None),
                        ),
                    )
                self.assertNotIn("PATH-005", str(collision.exception))
                self.assertEqual([], export_calls)
                self.assertEqual([], list(root.rglob(".blendlib-x5-export-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))

                with self.assertRaisesRegex(x5.X5ToolingError, "PATH-005.*private legacy export stage"):
                    x5.x5_batch_export_open_blend(
                        options,
                        (
                            x5.BatchExportItem("blendlib", "a", "blendlib:rigid_v1", None),
                            x5.BatchExportItem("blendlib", "b", "blendlib:rigid_v1", None),
                        ),
                    )
                self.assertEqual([], export_calls)
                self.assertEqual([], list(root.rglob(".blendlib-x5-export-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

    def test_project_root_file_ancestors_fail_during_single_plan_approval(self) -> None:
        """Every existing project-root ancestor must already be a directory."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            blocker = parent / "existing-file"
            blocker.write_bytes(b"old-blocker")
            export_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("invalid root ancestry reached exporter")

            def options(project_root: Path) -> legacy.ExportOptions:
                return legacy.ExportOptions(
                    blend_path=source,
                    project_root=project_root,
                    namespace="blendlib",
                    model_id="hero/model",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )

            original_exporter = x5._legacy_exporter
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                invalid_roots = (
                    blocker,
                    blocker / "project",
                    blocker / "missing-parent" / "project",
                    blocker / "missing-parent" / "child" / ".." / "project",
                )
                for project_root in invalid_roots:
                    with self.subTest(project_root=project_root), self.assertRaisesRegex(
                        x5.X5ToolingError, "PATH-004"
                    ):
                        x5._build_export_plan(
                            options(project_root),
                            preflight=x5.preflight_snapshot(self.snapshot()),
                        )
                    self.assertEqual(b"old-blocker", blocker.read_bytes())

                existing_root = parent / "existing-project"
                existing_root.mkdir()
                existing_plan = x5._build_export_plan(
                    options(existing_root),
                    preflight=x5.preflight_snapshot(self.snapshot()),
                )
                self.assertEqual(existing_root.resolve(), x5._trusted_export_plan_state(existing_plan)[1].project_root)

                missing_root = parent / "legal-parent" / "missing-project"
                legal_plan = x5._build_export_plan(
                    options(missing_root),
                    preflight=x5.preflight_snapshot(self.snapshot()),
                )
                self.assertEqual(missing_root.resolve(), x5._trusted_export_plan_state(legal_plan)[1].project_root)
                self.assertFalse(missing_root.exists())
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual([], export_calls)
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))

    def test_project_root_file_ancestor_alias_and_batch_fail_before_side_effects(self) -> None:
        """Resolved directory aliases cannot conceal a file ancestor in single or batch work."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            source = parent / "source.blend"
            source.write_bytes(b"blend")
            real_parent = parent / "real-parent"
            real_parent.mkdir()
            blocker = real_parent / "blocker"
            blocker.write_bytes(b"old-blocker")
            aliases: list[Path] = []
            symlink_parent = parent / "symlink-parent"
            try:
                symlink_parent.symlink_to(real_parent, target_is_directory=True)
                aliases.append(symlink_parent)
            except OSError:
                pass
            junction_parent = parent / "junction-parent"
            junction = subprocess.run(
                ["cmd.exe", "/d", "/c", "mklink", "/J", str(junction_parent), str(real_parent)],
                check=False,
                capture_output=True,
                text=True,
            )
            if junction.returncode == 0:
                aliases.append(junction_parent)
            if not aliases:
                self.skipTest("directory symlink and junction aliases are unavailable")

            def options(project_root: Path) -> legacy.ExportOptions:
                return legacy.ExportOptions(
                    blend_path=source,
                    project_root=project_root,
                    namespace="blendlib",
                    model_id="unused",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )

            preflight = x5.preflight_snapshot(self.snapshot())
            export_calls: list[str] = []

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("invalid aliased root reached exporter")

            original_exporter = x5._legacy_exporter
            original_preflight = x5.preflight_blender
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                x5.preflight_blender = lambda unused_options: preflight
                for alias_parent in aliases:
                    invalid_roots = (
                        alias_parent / "blocker",
                        alias_parent / "blocker" / "project",
                        alias_parent / "blocker" / "missing-parent" / "project",
                    )
                    for project_root in invalid_roots:
                        with self.subTest(alias=alias_parent.name, project_root=project_root), self.assertRaisesRegex(
                            x5.X5ToolingError, "PATH-004"
                        ):
                            x5._build_export_plan(options(project_root), preflight=preflight)
                        for count in (2, 3):
                            items = [
                                x5.BatchExportItem("blendlib", f"hero/{index}", "blendlib:rigid_v1", None)
                                for index in range(count)
                            ]
                            with self.subTest(alias=alias_parent.name, project_root=project_root, count=count), self.assertRaisesRegex(
                                x5.X5ToolingError, "BATCH-002"
                            ):
                                x5.x5_batch_export_open_blend(options(project_root), items)
                            self.assertEqual(b"old-blocker", blocker.read_bytes())
                    legal_parent = real_parent / "legal-existing"
                    legal_parent.mkdir(exist_ok=True)
                    legal_root = alias_parent / "legal-existing" / "missing-project"
                    legal_plan = x5._build_export_plan(options(legal_root), preflight=preflight)
                    self.assertEqual(
                        (legal_parent / "missing-project").resolve(),
                        x5._trusted_export_plan_state(legal_plan)[1].project_root,
                    )
                    self.assertFalse((legal_parent / "missing-project").exists())
            finally:
                x5._legacy_exporter = original_exporter
                x5.preflight_blender = original_preflight

            self.assertEqual([], export_calls)
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-stage-*")))
            self.assertEqual([], list(parent.rglob(".blendlib-x5-backup-*")))

    def test_frozen_plan_rejects_retargeted_output_alias_before_stage(self) -> None:
        """A post-approval symlink/junction retarget cannot reinterpret an approved output identity."""

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            export_calls: list[str] = []
            original_exporter = x5._legacy_exporter

            class ForbiddenLegacyExporter:
                @staticmethod
                def _authorized_texture_roots(unused_options: object) -> tuple[Path, ...]:
                    return (parent,)

                @staticmethod
                def export_open_blend(unused_options: object) -> dict:
                    export_calls.append("export")
                    raise AssertionError("retargeted plan reached strict-v1 export")

            aliases = 0
            try:
                x5._legacy_exporter = lambda: ForbiddenLegacyExporter
                for alias_kind in ("symlink", "junction"):
                    with self.subTest(alias=alias_kind):
                        root = parent / alias_kind
                        root.mkdir()
                        source = root / "source.blend"
                        source.write_bytes(b"blend")
                        left = root / "left"
                        right = root / "right"
                        left.mkdir()
                        right.mkdir()
                        descriptor = left / "assets/blendlib/blend_models/hero.json"
                        descriptor.parent.mkdir(parents=True)
                        descriptor.write_bytes(b"old-descriptor")
                        options = legacy.ExportOptions(
                            blend_path=source,
                            project_root=root,
                            namespace="blendlib",
                            model_id="hero",
                            profile="blendlib:rigid_v1",
                            collection_name=None,
                            output_resource_root="left",
                            report_path=right / "assets/blendlib/blend_models/hero.json",
                            authoring_output_root="right",
                        )
                        plan = x5._build_export_plan(options, preflight=x5.preflight_snapshot(self.snapshot()))
                        right.rmdir()
                        if alias_kind == "symlink":
                            try:
                                right.symlink_to(left, target_is_directory=True)
                            except OSError:
                                continue
                        else:
                            junction = subprocess.run(
                                ["cmd.exe", "/d", "/c", "mklink", "/J", str(right), str(left)],
                                check=False,
                                capture_output=True,
                                text=True,
                            )
                            if junction.returncode != 0:
                                continue
                        aliases += 1
                        with self.assertRaisesRegex(x5.X5ToolingError, "PATH-004"):
                            x5._prepare_x5_export(options, plan=plan)
                        self.assertEqual(b"old-descriptor", descriptor.read_bytes())
                        self.assertEqual([], list(root.rglob(".blendlib-x5-export-*")))
                        self.assertEqual([], list(root.rglob(".blendlib-x5-stage-*")))
                        self.assertEqual([], list(root.rglob(".blendlib-x5-backup-*")))
            finally:
                x5._legacy_exporter = original_exporter

            if aliases == 0:
                self.skipTest("directory symlink and junction aliases are unavailable")
            self.assertEqual([], export_calls)

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

    def test_preflight_result_authority_ignores_public_diagnostic_mutation_and_rejects_forgery(self) -> None:
        warning_snapshot = self.snapshot()
        warning_snapshot["collections"][1]["triangle_count"] = 100_001
        warning_result = x5.preflight_snapshot(warning_snapshot)
        original_warning = next(item for item in warning_result.diagnostics if item.severity == "WARN")
        original_warning_json = original_warning.to_json()
        object.__setattr__(original_warning, "severity", "ERROR")
        fake_error = x5.ToolingDiagnostic(
            "ERROR", "BLENDLIB-X5-MUTATED-001", "mutated", "fake error", "ignore it"
        )
        object.__setattr__(warning_result, "diagnostics", (fake_error,))

        self.assertTrue(warning_result.ok)
        self.assertEqual([original_warning_json], warning_result.report()["diagnostics"])
        warning_sidecar = x5.build_authoring_sidecar(warning_result.snapshot)
        warning_report = x5.build_asset_report(
            snapshot=warning_result.snapshot,
            sidecar=warning_sidecar,
            validation={"index_count": 3, "material_names": ["HeroMaterial"], "vertex_count": 3},
            artifacts={"src/main/resources/assets/blendlib/models3d/hero.glb": b"glb"},
            diagnostics=(fake_error,),
        )
        self.assertEqual([original_warning_json], warning_report["diagnostics"])
        self.assertEqual([original_warning_json], warning_report["performance_warnings"])

        invalid = self.snapshot()
        invalid["root_count"] = 0
        invalid_result = x5.preflight_snapshot(invalid)
        original_error = next(item for item in invalid_result.diagnostics if item.severity == "ERROR")
        original_error_json = original_error.to_json()
        object.__setattr__(original_error, "severity", "WARN")
        object.__setattr__(invalid_result, "diagnostics", ())
        self.assertFalse(invalid_result.ok)
        self.assertEqual([original_error_json], invalid_result.report()["diagnostics"])

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)

            def options(name: str) -> legacy.ExportOptions:
                root = parent / name
                return legacy.ExportOptions(
                    blend_path=parent / "source.blend",
                    project_root=root,
                    namespace="blendlib",
                    model_id="hero",
                    profile="blendlib:rigid_v1",
                    collection_name=None,
                    output_resource_root="src/main/resources",
                    report_path=None,
                )

            original_exporter = x5._legacy_exporter
            calls = 0

            def reached_legacy() -> object:
                nonlocal calls
                calls += 1
                raise AssertionError("legacy exporter reached")

            x5._legacy_exporter = reached_legacy
            try:
                with self.assertRaisesRegex(AssertionError, "legacy exporter reached"):
                    x5._prepare_x5_export(options("warning"), preflight=warning_result)

                for label, forged in (
                    ("direct", x5.PreflightResult((), warning_result.snapshot)),
                    ("copy", copy.copy(warning_result)),
                ):
                    with self.subTest(label=label), self.assertRaisesRegex(
                        x5.X5ToolingError, "SNAPSHOT-001"
                    ):
                        x5._prepare_x5_export(options(label), preflight=forged)
                    with self.subTest(label=f"{label}-report"), self.assertRaisesRegex(
                        x5.X5ToolingError, "SNAPSHOT-001"
                    ):
                        forged.report()

                class DerivedPreflightResult(x5.PreflightResult):
                    pass

                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(
                        options("derived"),
                        preflight=DerivedPreflightResult((), warning_result.snapshot),
                    )

                cross_snapshot = x5.preflight_snapshot(self.snapshot()).snapshot
                object.__setattr__(warning_result, "snapshot", cross_snapshot)
                with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                    x5._prepare_x5_export(options("cross"), preflight=warning_result)

                with self.assertRaisesRegex(x5.X5ToolingError, "PREFLIGHT-001"):
                    x5._prepare_x5_export(options("invalid"), preflight=invalid_result)

                forged_batch = x5.PreflightResult((), cross_snapshot)
                original_preflight = x5.preflight_blender
                try:
                    x5.preflight_blender = lambda unused: invalid_result
                    with self.assertRaisesRegex(x5.X5ToolingError, "BATCH-004"):
                        x5.x5_batch_export_open_blend(
                            options("invalid-batch"),
                            [x5.BatchExportItem("blendlib", "hero", "blendlib:rigid_v1", None)],
                        )
                    x5.preflight_blender = lambda unused: forged_batch
                    with self.assertRaisesRegex(x5.X5ToolingError, "SNAPSHOT-001"):
                        x5.x5_batch_export_open_blend(
                            options("batch"),
                            [x5.BatchExportItem("blendlib", "hero", "blendlib:rigid_v1", None)],
                        )
                finally:
                    x5.preflight_blender = original_preflight
            finally:
                x5._legacy_exporter = original_exporter

            self.assertEqual(1, calls)
            self.assertFalse((parent / "invalid").exists())
            self.assertFalse((parent / "invalid-batch").exists())
            self.assertFalse((parent / "batch").exists())
            self.assertEqual([], list(parent.rglob(".blendlib-x5-export-*")))

        gc.collect()
        baseline_results = len(x5._TRUSTED_PREFLIGHT_STATES)
        registered_results = [x5.preflight_snapshot(self.snapshot()) for _ in range(24)]
        result_refs = [x5.ref(result) for result in registered_results]
        self.assertEqual(baseline_results + len(registered_results), len(x5._TRUSTED_PREFLIGHT_STATES))
        del registered_results
        gc.collect()
        self.assertTrue(all(result_ref() is None for result_ref in result_refs))
        self.assertEqual(baseline_results, len(x5._TRUSTED_PREFLIGHT_STATES))

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
