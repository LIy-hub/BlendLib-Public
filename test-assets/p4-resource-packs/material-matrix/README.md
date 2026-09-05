# P4 ADR-014 / ADR-019 material-matrix fixture

此目录是仅供隔离客户端手工验收的 descriptor-only resource-pack matrix。它不属于
Showcase 默认资源、不会被构建复制、不会启动运行项，也不得复制到正式服务器、正式世界或
D:\MinecraftFabricServer-26.1.2*。

每个 pack 只覆盖同一个资源：

~~~
assets/blendlib_showcase/blend_models/fixtures/static_model.json
~~~

它们共同引用已提交的 P2 rigid asset，而不复制 GLB 或 PNG：

~~~
mesh:    blendlib_showcase:models3d/fixtures/rigid_model.glb
texture: blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png
~~~

当前受控输入 SHA-256：

~~~
rigid_model.glb                        a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764
fixtures_rigid_model__rigidsurface.png 851035d6863e6aea6a03c7a93d8734872e16918938c6f8b962392c24b25502e1
~~~

rigid_model.glb 有两个 indexed TRIANGLES primitive。RigidArm 是一个法线朝 +Z 的单面
三角形；因此在静态对象处从 +Z 一侧和 -Z 一侧观察它，可以真实区分 cull/no-cull。共享
PNG 是不透明 2x2 单色，所以在黑暗临时世界中可以真实比较普通光和 fullbright emissive；
它不能证明 alpha 边缘、透明排序或任意 cutout threshold 的视觉差异。那些不等价 threshold
必须只按 BLENDLIB-MAT-004/missing model 验收。

## 只读验证

在 D:\BlendLib 执行：

~~~powershell
& .\test-assets\p4-resource-packs\verify-p4-resource-packs.ps1
~~~

验证器不写文件。它会以 frozen v1 Draft 2020-12 schema 严格验证 18 个 descriptor、检查
每一个 pack 的 min_format=max_format=84、固定资源 ID、P2 SHA-256、严格
cutout-threshold 规则、经已接受 ADR-019 修正的 ADR-014 accept/reject 分类以及不复制
GLB/PNG 的边界。

## 手工步骤

1. 仅在 D:\BlendLib\blendlib-showcase\run\client 的临时单人世界中执行；绝不连接、
   启动、停止或修改正式服务器/世界。
2. 每次仅复制一个下表 pack 到
   D:\BlendLib\blendlib-showcase\run\client\resourcepacks\<pack>，在资源包界面启用且
   高于内置 Showcase 资源，然后按 F3+T。
3. 用 /summon blendlib_showcase:static_rigid ~ ~ ~ 生成对象，并记录
   /blendlib inspect blendlib_showcase:fixtures/static_model 与
   /blendlib diagnostics blendlib_showcase:fixtures/static_model。
4. 对 supported row：确认没有 missing model 或 BLENDLIB-MAT-004。对 cull row，分别从
   RigidArm 的 +Z 正面和 -Z 背面截图；对 emissive pair，在同一黑暗位置与对应 lit
   pack 比较亮度。不要把同一不透明 PNG 的 mode 外观误称为 alpha/threshold 视觉证明。
5. 对 rejected row：预期为 missing model 和本 generation 的 BLENDLIB-MAT-004。不得从
   missing model 推断任何 culling、emissive 或 threshold fallback；同 generation 的重复
   diagnostics 仍按 P4 一次主诊断规则记录。
6. 每轮移除隔离目录中的临时 pack，恢复基线并按 F3+T。记录模板见
   ../../../docs/manual-client-acceptance-v1.md。

## ADR-014 / ADR-019 matrix

| Classification | Pack | Descriptor intent | Manual expected result |
| --- | --- | --- | --- |
| supported | supported-opaque-single-sided-lit | opaque, single-sided, E=0 | Loads; front visible/back culled. |
| supported | supported-opaque-single-sided-emissive | opaque, single-sided, E=1 | Loads; same culling, fullbright vs lit in darkness. |
| supported | supported-cutout-single-sided-threshold-010-lit | cutout, single-sided, threshold 0.1, E=0 | Loads; front visible/back culled via the public `entityCutoutCull` path. |
| supported | supported-cutout-single-sided-threshold-010-emissive | cutout, single-sided, threshold 0.1, E=1 | Loads; same culling, fullbright vs lit in darkness. |
| supported | supported-cutout-double-sided-threshold-010-lit | cutout, double-sided, threshold 0.1, E=0 | Loads; front and back visible. |
| supported | supported-cutout-double-sided-threshold-010-emissive | cutout, double-sided, threshold 0.1, E=1 | Loads; both sides visible, fullbright vs lit in darkness. |
| supported | supported-translucent-double-sided-lit | translucent, double-sided, E=0 | Loads; front and back visible. |
| supported | supported-translucent-double-sided-emissive | translucent, double-sided, E=1 | Loads; both sides visible, fullbright vs lit in darkness. |
| rejected | rejected-opaque-double-sided-lit | opaque, double-sided, E=0 | missing model + BLENDLIB-MAT-004; no culling fallback. |
| rejected | rejected-opaque-double-sided-emissive | opaque, double-sided, E=1 | missing model + BLENDLIB-MAT-004; emissive does not bypass. |
| rejected | rejected-cutout-double-sided-threshold-025-lit | cutout, double-sided, threshold 0.25, E=0 | missing model + BLENDLIB-MAT-004; no threshold substitution. |
| rejected | rejected-cutout-double-sided-threshold-025-emissive | cutout, double-sided, threshold 0.25, E=1 | missing model + BLENDLIB-MAT-004; emissive does not bypass. |
| rejected | rejected-translucent-single-sided-lit | translucent, single-sided, E=0 | missing model + BLENDLIB-MAT-004; no no-cull fallback. |
| rejected | rejected-translucent-single-sided-emissive | translucent, single-sided, E=1 | missing model + BLENDLIB-MAT-004; emissive does not bypass. |
| rejected | rejected-additive-single-sided-lit | additive, single-sided, E=0 | missing model + BLENDLIB-MAT-004; no additive fallback. |
| rejected | rejected-additive-single-sided-emissive | additive, single-sided, E=1 | missing model + BLENDLIB-MAT-004; emissive does not bypass. |
| rejected | rejected-additive-double-sided-lit | additive, double-sided, E=0 | missing model + BLENDLIB-MAT-004; no additive fallback. |
| rejected | rejected-additive-double-sided-emissive | additive, double-sided, E=1 | missing model + BLENDLIB-MAT-004; no additive fallback. |

这份 matrix 和 verifier 只提供可重复加载输入与自动结构验证，绝不构成视觉 PASS、
P4 PASS 或任何发布结论。
