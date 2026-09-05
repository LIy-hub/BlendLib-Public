# BlendLib v1 手工客户端验收记录模板

状态：此文件是可执行的手工验收模板，不是 P4、P5 或任何 Gate 的
PASS 证据。只有真实客户端观察、保留的证据和对应自动化证据齐全后，主
协调者才能作出阶段 Gate 判断。

## 0. 隔离范围与证据位置

唯一允许的客户端运行目录是：

    D:\BlendLib\blendlib-showcase\run\client

每次手工验收先建立一个新的证据目录，例如：

    D:\BlendLib\build\manual-client-evidence\2026-07-29-153000

该目录至少保留下列文件名；截图可为 PNG，文本可为 TXT 或 MD：

    00-session.md
    01-baseline.png
    01-baseline-assets.txt
    01-baseline-inspect.txt
    01-baseline-log.txt
    10-valid-override.png
    10-valid-override-assets.txt
    10-valid-override-inspect.txt
    10-valid-override-log.txt
    20-baseline-restored.png
    20-baseline-restored-inspect.txt
    20-baseline-restored-log.txt
    30-malformed.png
    30-malformed-assets.txt
    30-malformed-inspect.txt
    30-malformed-diagnostics-first.txt
    30-malformed-diagnostics-repeat.txt
    30-malformed-log.txt
    40-baseline-restored.png
    40-baseline-restored-inspect.txt
    40-baseline-restored-log.txt
    result.md

00-session.md 应包含测试者、日期、BlendLib 提交、Java 版本、客户端
窗口版本、是否无 shaderpack、临时世界名称，以及资源包界面的优先级
截图文件名。

严禁启动、停止或修改以下位置，严禁将 fixture 复制进去：

- D:\MinecraftFabricServer-26.1.2
- D:\MinecraftFabricServer-26.1.2-Fresh
- 任意正式服务器或正式世界

若需要世界对象观察，只能在上面的隔离客户端目录中新建临时本地世界；
不要连接正式服务器。临时世界启用命令后，静态 P4 对象可用下列命令生成：

    /summon blendlib_showcase:static_rigid ~ ~ ~

已接受 ADR-013 的静态 P4 marker/wrapper 物品可用下列命令取得：

    /give @s blendlib_showcase:static_rigid_item

从 D:\BlendLib 启动隔离客户端的命令是：

    $env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
    .\gradlew.bat :blendlib-showcase:runClient --console=plain

每个阶段完成后，从下列隔离日志复制与 BlendLib reload、diagnostic、异常
有关的行到对应的阶段日志文件；不要只引用不断变化的 latest.log：

    D:\BlendLib\blendlib-showcase\run\client\logs\latest.log

## 1. P4：静态/刚体资源包热重载

本节只覆盖当前可执行的实体/世界对象、资源 reload、missing model 与
诊断路径。fixture 说明和只读校验位于：

    D:\BlendLib\test-assets\p4-resource-packs\README.md

### 1.1 固定顺序

不要调整下面顺序，也不要同时安装两个 fixture。每个资源包变更后均先在
资源包界面确认其优先级，再按 F3+T，等待 reload 完成后记录证据。

1. **基线。** 确认隔离 resourcepacks 目录中没有 valid-override 与
   malformed-missing-mesh 的临时副本。启动客户端，在临时本地世界中生成
   static_rigid 对象。记录正面方向、根原点、地面位置、缩放、贴图，以及：

       /blendlib assets
       /blendlib inspect blendlib_showcase:fixtures/static_model
       /blendlib diagnostics blendlib_showcase:fixtures/static_model

   截图和命令输出分别写入 01-baseline.*。此处只记录实际观察到的内容，
   不预填“正确”。

2. **valid override。** 仅将下列 fixture 内容复制到隔离客户端目录：

       D:\BlendLib\test-assets\p4-resource-packs\valid-override
       D:\BlendLib\blendlib-showcase\run\client\resourcepacks\valid-override

   在资源包界面启用它且其优先级高于内置 Showcase 资源，按 F3+T。再次
   生成/观察相同对象，运行上面的三个命令，保存 10-valid-override.*。
   记录是否实际替换为该 fixture 所指的刚体 GLB/外置贴图；不要由资源包
   已启用推断渲染结果。

3. **恢复基线。** 关闭并移除隔离目录中的 valid-override 临时副本，确认
   没有其他 fixture 生效后按 F3+T。重复 inspect 和截图，保存
   20-baseline-restored.*。记录 generation 是否前进，以及基线资源是否
   实际恢复；这一步不可省略。

4. **malformed override。** 仅复制并启用：

       D:\BlendLib\test-assets\p4-resource-packs\malformed-missing-mesh
       D:\BlendLib\blendlib-showcase\run\client\resourcepacks\malformed-missing-mesh

   按 F3+T 后保存 30-malformed.*。运行：

       /blendlib assets
       /blendlib inspect blendlib_showcase:fixtures/static_model
       /blendlib diagnostics blendlib_showcase:fixtures/static_model

   在同一个 generation 内再次执行最后一条命令，并分别保存 first/repeat
   输出。验收文字必须是“该 key 在同一 generation 中只有一条主诊断”，
   而不是“整个 session 只会报一次”。下一次 F3+T 会产生新 generation，
   因而可以再次有该 generation 的一条主诊断。记录 missing-model 占位
   是否可见、诊断 code/message、以及日志是否出现每帧重复主错误。

5. **再次恢复基线。** 关闭并移除 malformed-missing-mesh 临时副本，按
   F3+T，重复 inspect 和截图，保存 40-baseline-restored.*。确认没有
   fixture 留在隔离 resourcepacks 目录。不要删除、移动或修改项目以外
   的资源包或世界。

### 1.2 P4 仍需真实客户端证据的项目

| 项目 | 原因 | 记录方式 |
| --- | --- | --- |
| 物品 GUI/手持 transform | ADR-013 已接受，公共 marker/wrapper 已实现；但尚无真实 GUI、手持或世界物品观察。 | 标记 WAITING，记录 `/give`、截图和实际 transform；不得把启动 smoke 当作视觉通过。 |
| 已支持 material subset 的 culling/texture/emissive | ADR-014 及其已接受 ADR-019 cutout 单面修正已定稿，严格 public-path/rejection 测试已实现；但尚无完整真实客户端观察。 | 标记 WAITING，记录实际材质、culling、贴图和发光；不支持组合必须记录 `BLENDLIB-MAT-004` missing-model，而非被静默重映射。 |
| Iris/Sodium 或 P7 性能 | 属于 P7，当前没有对应正式 smoke/基准。 | 标记 WAITING；不得用本节截图替代。 |

### 1.3 P4 ADR-014 / ADR-019 material matrix

完整 fixture matrix 位于：

    D:\BlendLib\test-assets\p4-resource-packs\material-matrix\README.md

每次只能复制其中一个 pack 到：

    D:\BlendLib\blendlib-showcase\run\client\resourcepacks\<pack-name>

先在资源包界面确认它高于内置 Showcase 资源，再按 F3+T，并运行：

    /blendlib inspect blendlib_showcase:fixtures/static_model
    /blendlib diagnostics blendlib_showcase:fixtures/static_model

八个 supported-* pack 必须没有 missing model 或 BLENDLIB-MAT-004。对 single-sided
opaque 与 exact-0.10 single-sided cutout，从 RigidArm 的 +Z 正面与 -Z 背面分别截图；
对 double-sided cutout/translucent 两侧均截图。对每个 *-emissive 与对应 *-lit pack，
在同一黑暗临时世界位置比较亮度，并记录真实观察，而不是预填 fullbright 结论。已接受
ADR-019 使两个 exact-0.10 single-sided cutout pack 成为 supported input；它们仍须按
本一-pack/F3+T/恢复基线流程取得新的真实客户端证据，不得由此前 paused 记录推断结果。
共享 PNG 没有 alpha 边缘，不得把这些截图称为 alpha sorting 或任意 threshold 的视觉证据。

十个 rejected-* pack 的预期只有 missing model 加同 generation 的
BLENDLIB-MAT-004；它们不得静默得到 cull/no-cull、emissive、output target 或 threshold
fallback。记录 first/repeat diagnostics、generation、截图和相关 reload log，再移除临时
pack、按 F3+T 恢复基线。不得把任一 pack 复制到正式服务器、正式世界或项目外实例。

## 2. P5：动画、skin、socket 和实例

ADR-015 与 ADR-018 已获接受，canonical asset-bound runtime wiring 和独立 P5
fallback fixture 已实际落地；本节可在协调者安排下于隔离客户端执行。132-tick
fallback schedule 只能归因于 P5 fixture，当前入口是：

    /summon blendlib_showcase:p5_fallback_actor ~ ~-1 ~-4
    /time query gametime

其 client-only schedule 每 132 tick 依次选择 idle `[0,80)`、walk `[80,120)`、
attack `[120,132)`；至少 40 个可观察 tick 后，在 `~2 ~-1 ~-4` 生成第二个同类
实体并再次记录 `/time query gametime`，以便观察独立 instance clock。仍须记录实际
asset hash、实体 ID、clip 时长和证据文件。正常
`blendlib_showcase:animated_actor` 仍属于 P6 synchronized-animation 验收，不能用
它的视频归因于本节 fallback 时钟。P5 的 controller/palette/cache/CPU-skinning
自动化测试、真实客户端启动 smoke 和上面的命令都不是下列视觉验收的替代物；P4 的
审计/视觉条件也仍保持 WAITING。

| P5 观察项 | 需要记录的真实证据 | 当前可填写状态 |
| --- | --- | --- |
| idle、walk、attack 状态切换 | 仅 `p5_fallback_actor`：每个状态的截图/视频时间戳、生成 tick、观察到的时长；attack 到 idle 的 blend 也要单独记录。 | WAITING，fixture/runtime wiring 已完成，等待真实观察。 |
| quaternion 插值 | 旋转跨越关键帧时的视频或连续截图，明确是否出现翻转、NaN、跳变。 | WAITING，直至实际 skinned/rigid 绑定可见。 |
| 骨骼蒙皮与外置贴图 | skinned Showcase 资产的网格变形、纹理、骨骼驱动部位截图/视频。 | WAITING，skinned backend 已接线，等待真实观察。 |
| 两个独立实例 | 同一资产的两个实例同时处于不同动画时间/状态的截图或视频，并写明各自生成时间。 | WAITING，实例绑定与 Showcase 接线已完成，等待真实观察。 |
| socket marker | marker 相对指定骨骼在整个动画过程中的连续位置证据；应记录 socket 名和骨骼路径。 | WAITING，真实 Showcase 已自动验证 `blendlib_showcase:tip` 在 walk `7/24s`/`19/24s` 的模型空间位置变化，并已实现 client-only RGB axis marker。它只消费同一 extraction frame 冻结的 transform，按 entity-relative `root → units → socket` 提交；仍须截图/视频，且不能把该坐标当世界坐标。 |
| 视觉事件 | 仅记录声音、粒子、拖尾或视觉挂点回调；同时写明它不决定伤害、碰撞、消耗、掉落或命中。 | WAITING，Showcase 已为 `attack_whoosh` 接入 render-thread/liveness 守卫后的普通 `SWEEP_ATTACK` 客户端粒子；仍须真实截图/视频，且不承载玩法语义。 |
| reload 中实例行为 | 记录 generation 替换前后观测结果，但**不要**自行把它解释为 resume、snap、restart、保持 pose 或重放 event。 | WAITING，直到已接受 ADR-015 的 runtime 行为实际实现并被安排验证。 |
| disconnect 清理、远距/不可见 cadence | 只引用自动化测试、cache metrics、受控日志或 profiler 证据；手动截图不能证明 cache 为零或更新频率下降。 | WAITING，直至相应自动化/指标证据被登记。 |

## 3. 统一结果表

每次真实客户端验收都复制这张表到证据目录的 result.md。只能使用 PASS、
WAITING、BLOCKED；PASS 表示该**单项**已有对应真实证据，不自动表示 P4 或
P5 Gate PASS。

| 项目 | 状态 | 测试者/日期 | 证据文件 | generation / 资产 hash | 观察或阻塞原因 |
| --- | --- | --- | --- | --- | --- |
| P4 baseline static/rigid entity | WAITING |  |  |  |  |
| P4 valid override | WAITING |  |  |  |  |
| P4 baseline restore after valid | WAITING |  |  |  |  |
| P4 malformed missing-model | WAITING |  |  |  |  |
| P4 one primary diagnostic in one generation | WAITING |  |  |  |  |
| P4 baseline restore after malformed | WAITING |  |  |  |  |
| P4 item transform / ADR-013 | WAITING |  |  |  | Accepted public marker/wrapper needs real visual evidence |
| P4 complete material semantics / ADR-014 | WAITING |  |  |  | Accepted strict subset/rejection needs real visual evidence |
| P5 idle/walk/attack | WAITING |  |  |  | `p5_fallback_actor` fixture/runtime wiring exists; real visual evidence required |
| P5 skinned mesh | WAITING |  |  |  | Runtime wiring exists; real visual evidence required |
| P5 independent instances | WAITING |  |  |  | Runtime wiring exists; staggered-instance evidence required |
| P5 socket marker | WAITING |  |  |  | Real asset has a model-space two-time socket regression and a client-only Showcase RGB marker; still requires continuous real-client screenshot/video evidence |
| P5 presentation-only visual event | WAITING |  |  |  | Client-only `attack_whoosh` -> guarded ordinary `SWEEP_ATTACK` consumer exists; real visual evidence required |
| P5 reload behavior semantics | BLOCKED |  |  |  | Do not infer unresolved behavior |
| P5 disconnect cleanup/cadence | WAITING |  |  |  | Automated evidence required |

提交验收材料时，附上 result.md、所有命令输出、截图/视频、每个阶段的
latest.log 摘录，以及当前资源包优先级截图。缺少任何一项时，保持对应项目
WAITING；不要把 build、启动 smoke 或本模板本身写成视觉成功。
