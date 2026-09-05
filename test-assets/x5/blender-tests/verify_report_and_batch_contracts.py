"""Real Blender regressions for X5 report schema and two-phase batch export."""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.dont_write_bytecode = True

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
ADDON_ROOT = REPOSITORY_ROOT / "blender-addon"
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

import bpy  # type: ignore  # noqa: E402

import blendlib_exporter as legacy  # noqa: E402
import blendlib_x5_toolchain as x5  # noqa: E402


def _project(name: str, *, create: bool = False) -> Path:
    project = REPOSITORY_ROOT / "build" / "x5-r13-report-batch" / name
    if project.exists():
        shutil.rmtree(project)
    if create:
        project.mkdir(parents=True)
    return project


def _options(
    project: Path,
    model_id: str,
    *,
    collection: str = "BlendLibExport",
    report: str | None = None,
    refresh: str | None = None,
) -> legacy.ExportOptions:
    return legacy.ExportOptions(
        blend_path=REPOSITORY_ROOT / "test-assets" / "static" / "source.blend",
        project_root=project,
        namespace="blendlib_showcase",
        model_id=model_id,
        profile="blendlib:rigid_v1",
        collection_name=collection,
        output_resource_root="src/main/resources",
        report_path=project / report if report is not None else None,
        dev_refresh_path=project / refresh if refresh is not None else None,
        dev_session_token="test-session-token-1234" if refresh is not None else None,
        dev_generation=7 if refresh is not None else None,
    )


def _open_static() -> None:
    bpy.ops.wm.open_mainfile(
        filepath=str(REPOSITORY_ROOT / "test-assets" / "static" / "source.blend")
    )


def _clone_invalid_collection(name: str) -> str:
    source = bpy.data.collections["BlendLibExport"]
    clone = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(clone)
    copies = {}
    for original in source.all_objects:
        copied = original.copy()
        copied.name = f"{original.name}_{name}"
        clone.objects.link(copied)
        copies[original] = copied
    for original, copied in copies.items():
        copied.parent = copies.get(original.parent)
    mesh = next(item for item in copies.values() if item.type == "MESH")
    mesh.scale = (1.0, 2.0, 1.0)
    return clone.name


def _tree_hashes(project: Path) -> dict[str, str]:
    return {
        path.relative_to(project).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(project.rglob("*"))
        if path.is_file()
    }


def verify_explicit_report_schema() -> None:
    project = _project("explicit-report")
    _open_static()
    options = _options(project, "fixtures/explicit_report", report="reports/explicit.json")
    result = x5.x5_export_open_blend(options)

    default_report = project / result["authoring_report"]
    explicit_report = project / "reports/explicit.json"
    default_bytes = default_report.read_bytes()
    explicit_bytes = explicit_report.read_bytes()
    assert explicit_bytes == default_bytes, (
        json.loads(default_bytes),
        json.loads(explicit_bytes),
    )
    report = json.loads(explicit_bytes)
    assert report["format"] == x5.ASSET_REPORT_FORMAT, report
    assert report["schema_version"] == "1.0.0", report
    assert report["model"] == {
        "model_id": "fixtures/explicit_report",
        "namespace": "blendlib_showcase",
        "profile": "blendlib:rigid_v1",
    }, report
    assert report["counts"]["vertices"] == 3, report
    assert report["counts"]["triangles"] == 1, report
    assert report["counts"]["vertex_weight_records"] == 0, report
    assert report["diagnostics"] == report["performance_warnings"], report
    assert report["sidecar_sha256"] == result["sidecar_sha256"], report
    assert result["report_sha256"] == hashlib.sha256(default_bytes).hexdigest(), result
    print(f"BLENDLIB_X5_EXPLICIT_REPORT_SCHEMA_PASS project={project}")


def verify_report_alias_and_collision_contracts() -> None:
    """Exercise r13 report graph handling through real Blender preflight/export."""

    alias_project = _project("default-report-alias")
    alias_model = "fixtures/default_report_alias"
    default_relative = (
        "build/blendlib-authoring/blendlib_showcase/"
        "fixtures/default_report_alias.asset-report.json"
    )
    _open_static()
    alias_result = x5.x5_export_open_blend(
        _options(alias_project, alias_model, report=default_relative)
    )
    alias_sidecar = alias_project / alias_result["authoring_sidecar"]
    alias_report = alias_project / default_relative
    assert alias_result["authoring_report"] == default_relative, alias_result
    assert json.loads(alias_sidecar.read_bytes())["format"] == x5.AUTHORING_SIDECAR_FORMAT
    assert json.loads(alias_report.read_bytes())["format"] == x5.ASSET_REPORT_FORMAT
    assert alias_sidecar.read_bytes() != alias_report.read_bytes()

    for label, field in (("report-sidecar-collision", "report"), ("refresh-sidecar-collision", "refresh")):
        project = _project(label, create=True)
        model_id = "fixtures/report_collision"
        sidecar_relative = (
            "build/blendlib-authoring/blendlib_showcase/"
            "fixtures/report_collision.blendlib-authoring.json"
        )
        sidecar = project / sidecar_relative
        sidecar.parent.mkdir(parents=True)
        sidecar.write_bytes(b"published-sidecar-before-r13-collision")
        _open_static()
        kwargs = {field: sidecar_relative}
        options = _options(project, model_id, **kwargs)
        original_export = legacy.export_open_blend
        calls: list[str] = []

        def tracked_export(stage_options: legacy.ExportOptions) -> dict:
            calls.append(stage_options.model_id)
            return original_export(stage_options)

        legacy.export_open_blend = tracked_export
        try:
            try:
                x5.x5_export_open_blend(options)
            except x5.X5ToolingError as error:
                assert error.code == "BLENDLIB-X5-PATH-004", error
            else:
                raise AssertionError(f"{label} reached publication")
        finally:
            legacy.export_open_blend = original_export
        assert calls == [], calls
        assert sidecar.read_bytes() == b"published-sidecar-before-r13-collision"
        assert _tree_hashes(project) == {sidecar_relative: hashlib.sha256(sidecar.read_bytes()).hexdigest()}
        assert not list(project.glob(".blendlib-x5-export-*")), project
    print("BLENDLIB_X5_REPORT_ALIAS_AND_COLLISION_ZERO_SIDE_EFFECT_PASS")


def _verify_invalid_batch(
    name: str,
    item_specs: list[tuple[str, bool]],
) -> None:
    project = _project(name)
    _open_static()
    invalid_collection = _clone_invalid_collection(f"Invalid_{name}")
    options = _options(project, "unused")
    items = [
        x5.BatchExportItem(
            "blendlib_showcase",
            model_id,
            "blendlib:rigid_v1",
            invalid_collection if invalid else "BlendLibExport",
        )
        for model_id, invalid in item_specs
    ]
    original_export = legacy.export_open_blend
    calls: list[str] = []

    def tracked_export(stage_options: legacy.ExportOptions) -> dict:
        calls.append(stage_options.model_id)
        return original_export(stage_options)

    legacy.export_open_blend = tracked_export
    try:
        try:
            x5.x5_batch_export_open_blend(options, items)
        except x5.X5ToolingError as error:
            assert error.code == "BLENDLIB-X5-BATCH-004", error
        else:
            raise AssertionError("invalid batch reached publication")
    finally:
        legacy.export_open_blend = original_export

    assert calls == [], calls
    assert not project.exists(), project
    print(f"BLENDLIB_X5_BATCH_PREFLIGHT_ZERO_SIDE_EFFECT_PASS case={name} items={len(items)}")


def verify_batch_two_phase_and_rollback() -> None:
    _verify_invalid_batch(
        "valid-before-late-invalid",
        [("z_invalid", True), ("a_valid", False)],
    )
    _verify_invalid_batch(
        "two-valid-before-late-invalid",
        [("m_valid", False), ("z_invalid", True), ("a_valid", False)],
    )
    _verify_invalid_batch(
        "input-reversed-invalid-sorts-first",
        [("z_valid", False), ("a_invalid", True)],
    )

    slug_project = _project("texture-slug-collision")
    _open_static()
    slug_calls: list[str] = []
    original_export = legacy.export_open_blend

    def tracked_slug_export(stage_options: legacy.ExportOptions) -> dict:
        slug_calls.append(stage_options.model_id)
        return original_export(stage_options)

    legacy.export_open_blend = tracked_slug_export
    try:
        try:
            x5.x5_batch_export_open_blend(
                _options(slug_project, "unused"),
                [
                    x5.BatchExportItem("blendlib_showcase", "a/b", "blendlib:rigid_v1", "BlendLibExport"),
                    x5.BatchExportItem("blendlib_showcase", "a_b", "blendlib:rigid_v1", "BlendLibExport"),
                ],
            )
        except x5.X5ToolingError as error:
            assert error.code == "BLENDLIB-X5-BATCH-002", error
            assert "texture:" in error.message and "blendlib_showcase:a/b" in error.message, error
        else:
            raise AssertionError("texture slug collision reached publication")
    finally:
        legacy.export_open_blend = original_export
    assert slug_calls == [], slug_calls
    assert not slug_project.exists(), slug_project
    print("BLENDLIB_X5_BATCH_FULL_GRAPH_ZERO_SIDE_EFFECT_PASS case=texture-slug-collision")

    item_order_a = [
        x5.BatchExportItem("blendlib_showcase", "zeta", "blendlib:rigid_v1", "BlendLibExport"),
        x5.BatchExportItem("blendlib_showcase", "alpha", "blendlib:rigid_v1", "BlendLibExport"),
        x5.BatchExportItem("blendlib_showcase", "a_b", "blendlib:rigid_v1", "BlendLibExport"),
        x5.BatchExportItem("blendlib_showcase", "a-b", "blendlib:rigid_v1", "BlendLibExport"),
    ]
    item_order_b = list(reversed(item_order_a))
    batch_results = []
    hashes = []
    for name, items in (("all-valid-a", item_order_a), ("all-valid-b", item_order_b)):
        project = _project(name)
        _open_static()
        result = x5.x5_batch_export_open_blend(_options(project, "unused"), items)
        batch_results.append(result)
        hashes.append(_tree_hashes(project))
        assert not list(project.glob(".blendlib-x5-*-*")), project
    assert batch_results[0] == batch_results[1], batch_results
    assert hashes[0] == hashes[1], (hashes[0], hashes[1])

    project = _project("export-failure-rollback", create=True)
    marker = project / "existing.txt"
    marker.write_bytes(b"prior")
    _open_static()
    original_export = legacy.export_open_blend

    def fail_second_export(stage_options: legacy.ExportOptions) -> dict:
        if stage_options.model_id == "z_fail":
            raise legacy.ExportError("BLENDLIB-EXPORT-009", "injected second export failure")
        return original_export(stage_options)

    legacy.export_open_blend = fail_second_export
    try:
        try:
            x5.x5_batch_export_open_blend(
                _options(project, "unused"),
                [
                    x5.BatchExportItem("blendlib_showcase", "a_valid", "blendlib:rigid_v1", "BlendLibExport"),
                    x5.BatchExportItem("blendlib_showcase", "z_fail", "blendlib:rigid_v1", "BlendLibExport"),
                ],
            )
        except legacy.ExportError as error:
            assert error.code == "BLENDLIB-EXPORT-009", error
        else:
            raise AssertionError("injected export failure did not stop batch")
    finally:
        legacy.export_open_blend = original_export
    assert marker.read_bytes() == b"prior"
    assert [path.relative_to(project).as_posix() for path in project.rglob("*") if path.is_file()] == [
        "existing.txt"
    ]
    assert not list(project.glob(".blendlib-x5-*-*")), project
    print("BLENDLIB_X5_BATCH_ALL_VALID_DETERMINISTIC_AND_EXPORT_ROLLBACK_PASS")


def main() -> None:
    if "--" not in sys.argv:
        raise SystemExit("use -- --case <report|batch|all>")
    arguments = sys.argv[sys.argv.index("--") + 1 :]
    if arguments == ["--case", "report"]:
        verify_explicit_report_schema()
        verify_report_alias_and_collision_contracts()
    elif arguments == ["--case", "batch"]:
        verify_batch_two_phase_and_rollback()
    elif arguments == ["--case", "all"]:
        verify_explicit_report_schema()
        verify_report_alias_and_collision_contracts()
        verify_batch_two_phase_and_rollback()
    else:
        raise SystemExit("unknown case")


if __name__ == "__main__":
    main()
