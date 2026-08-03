"""Real Blender regression for a project-shared PNG outside the blend directory."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

sys.dont_write_bytecode = True

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
ADDON_ROOT = REPOSITORY_ROOT / "blender-addon"
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

import bpy  # type: ignore  # noqa: E402

from blendlib_exporter import ExportOptions  # noqa: E402
from blendlib_x5_toolchain import run_x5_cli  # noqa: E402


def main() -> None:
    project = REPOSITORY_ROOT / "build" / "x5-shared-png-real"
    if project.exists():
        shutil.rmtree(project)
    source_directory = project / "source"
    shared_directory = project / "shared-textures"
    source_directory.mkdir(parents=True)
    shared_directory.mkdir(parents=True)

    blend_path = source_directory / "shared-png.blend"
    shared_png = shared_directory / "static_surface.png"
    shutil.copyfile(REPOSITORY_ROOT / "test-assets" / "static" / "source.blend", blend_path)
    shutil.copyfile(REPOSITORY_ROOT / "test-assets" / "static" / "textures" / "static_surface.png", shared_png)

    bpy.ops.wm.open_mainfile(filepath=str(blend_path))
    images = [image for image in bpy.data.images if image.type == "IMAGE" and image.name != "Render Result"]
    assert len(images) == 1, [image.name for image in images]
    images[0].filepath = str(shared_png)
    bpy.ops.wm.save_as_mainfile(filepath=str(blend_path))

    result = run_x5_cli(ExportOptions(
        blend_path=blend_path,
        project_root=project,
        namespace="blendlib_showcase",
        model_id="fixtures/shared_png",
        profile="blendlib:rigid_v1",
        collection_name="BlendLibExport",
        output_resource_root="src/main/resources",
        report_path=None,
    ))
    assert result["strict_v1_validation"]["material_names"] == ["StaticSurface"], result
    exported_png = project / "src/main/resources/assets/blendlib_showcase/textures/blendlib/fixtures_shared_png__staticsurface.png"
    assert exported_png.read_bytes() == shared_png.read_bytes()
    print("BLENDLIB_X5_SHARED_PROJECT_PNG_PASS")


if __name__ == "__main__":
    main()
