# P4 ADR-014 / ADR-019 material-matrix fixture evidence

状态：**FIXTURE INPUT + READ-ONLY STRUCTURAL VERIFICATION ONLY / P4 VISUAL WAITING**。
本记录不运行客户端、不观察渲染、不替代 P4 Gate，也不改变已接受 ADR-014 的 API、
render path 或 rejection boundary。

## 目的和范围

ADR-014 经已接受 ADR-019 修正后，要求 26.1.2 adapter 只接受 exact public material
path。opaque,true、translucent,false、任何非 0.1 的 cutout threshold 与 additive 都必须在
reload 得到 missing model 与 BLENDLIB-MAT-004，不能静默改变 culling、output target 或
alpha threshold。`cutout,false,cutout_threshold=0.10` 则是已支持的
`entityCutoutCull` ordinary culling path，不是 MAT-004 rejection。

受控 fixture 位于：

~~~
D:\BlendLib\test-assets\p4-resource-packs\material-matrix
~~~

它新增 18 个 descriptor-only pack，并始终只覆盖
blendlib_showcase:fixtures/static_model。每个 descriptor 继续引用已提交 P2
blendlib_showcase:models3d/fixtures/rigid_model.glb 与外置 PNG；没有复制、修改或替换
任何 Showcase 默认 asset、GLB 或 PNG。

## 可区分性预检

只读 GLB 解码得到：

- RigidArm：一个 indexed triangle，局部位置 (0,0,0.3), (0.4,0,0.3),
  (0,0.6,0.3)，三个 normals 均为 (0,0,1)；节点平移为 (0,1,0)。
- RigidBase：第二个 indexed triangle；整个 asset 共两个 TRIANGLES primitive、6 vertices、
  6 indices，只有 RigidSurface material slot。
- P2 GLB SHA-256：
  a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764。
- 外置 PNG 是 2x2、ARGB 全不透明单色 (255,255,115,26)，SHA-256：
  851035d6863e6aea6a03c7a93d8734872e16918938c6f8b962392c24b25502e1。

所以 RigidArm 可在正/反面真实区分 cull/no-cull；在同一黑暗位置，fullbright emissive
可以与普通光真实比较。PNG 没有 alpha 边缘，故本 fixture 不声称可视觉证明 alpha sorting
或任意 threshold；0.25 的行只验证它必须被 MAT-004 拒绝。

## 固定 matrix

完整 pack 名、descriptor intent 与手工观察预期见
test-assets/p4-resource-packs/material-matrix/README.md 和 material-matrix-v1.json。
八个 supported input 是：

- supported-opaque-single-sided-{lit,emissive}；
- supported-cutout-single-sided-threshold-010-{lit,emissive}；
- supported-cutout-double-sided-threshold-010-{lit,emissive}；
- supported-translucent-double-sided-{lit,emissive}。

其余 10 个 pack 是 explicit MAT-004 rejection input：opaque double-sided、cutout
double-sided non-0.1 threshold、translucent single-sided 与 additive（均覆盖
lit/emissive；additive 也覆盖 double-sided）。

## 自动验证

~~~powershell
& D:\BlendLib\test-assets\p4-resource-packs\verify-p4-resource-packs.ps1
~~~

该只读命令验证：

1. 每个 pack 的 pack.mcmeta 只有 pack，且 min_format=max_format=84；
2. 每个 matrix descriptor 都通过 frozen Draft 2020-12 schema，且属性、资源 ID、
   profile、material slot、cutout-threshold presence/absence 均精确；
3. 18 行与经已接受 ADR-019 修正的 ADR-014 current mapping formula 一致，其中
   exact-0.10 single-sided cutout 为 `entityCutoutCull` supported input；
4. 所有 pack 都只含 metadata 和 descriptor，不复制 GLB/PNG；
5. P2 GLB/PNG 路径、SHA-256、GLB 2.0 external-texture boundary、TRIANGLES/normal/UV0
   shape 与 2x2 PNG 输入保持固定。

## ADR-019 alignment structural verification

在 2026-07-30 的已接受分类对齐后，执行：

~~~powershell
& D:\BlendLib\test-assets\p4-resource-packs\verify-p4-resource-packs.ps1
~~~

输出为：

~~~text
P4_MATERIAL_MATRIX_FIXTURES_VERIFIED rows=18 supported=8 rejected=10 pack_format=84 schema=strict p2_glb_png=verified
~~~

此结果只证明 fixture、schema、受控 asset 与已接受 ADR-014/ADR-019 matrix 的结构一致；
它没有启动 Minecraft，也不是两条 newly supported single-sided cutout row 的真实客户端
观察或 P4 visual/Gate PASS。

## 手工验收边界

每次只把一个 pack 临时复制到：

~~~
D:\BlendLib\blendlib-showcase\run\client\resourcepacks\<pack-name>
~~~

它只能在该隔离客户端的临时本地世界使用；不得连接、启动、停止、修改
D:\MinecraftFabricServer-26.1.2、D:\MinecraftFabricServer-26.1.2-Fresh、任何正式服务器
或正式世界。supported row 必须有真实正/反面、黑暗 emissive 比较和 /blendlib 输出；
rejected row 必须保留同 generation 的 missing-model/MAT-004 输出。

在用户实际观察、截图/视频、F3+T logs 与独立审计完成前，P4 仍为 WAITING。
