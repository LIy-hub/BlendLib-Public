# P5 隔离真实客户端观察（2026-07-30）

状态：**PARTIAL REAL-CLIENT VISUAL EVIDENCE / P5 GATE WAITING**。

本记录只登记一次真实 26.1.2 Showcase 客户端会话中已经直接观察到的内容；它不替代
P3/P4 审核、P5 完整视觉验收、P6 双客户端同步、P7 性能或任何发布条件。

## 隔离范围和命令

协调端使用 Java 25 只启动：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat --no-daemon :blendlib-showcase:runClient --console=plain
```

运行目录仅为：

```text
D:\BlendLib\blendlib-showcase\run\client
```

进入的本地世界是该运行目录内的 `BlendLib Visual RC`。没有启动、停止或修改
`D:\MinecraftFabricServer-26.1.2`、`D:\MinecraftFabricServer-26.1.2-Fresh`、任何正式
服务器或正式世界。

在昼间观察条件下，先在玩家前方生成一个 Showcase actor，随后以不同位置/时间生成第二个：

```text
/summon blendlib_showcase:animated_actor ~ ~-1 ~-4
/summon blendlib_showcase:animated_actor ~2 ~-1 ~-4
```

`run\client\logs\latest.log` 记录了相应的 `animated_actor` summon；第二次相隔约四分钟。
客户端经“保存并退出到标题界面”正常关闭，`runClient` 以 exit code `0` 结束。

## 可复核的截图

| 文件 | SHA-256 | 可记录的实际观察 |
| --- | --- | --- |
| `run\client\screenshots\2026-07-30_00.59.56.png` | `AB36B4C2FFC1945FCDD909F15DF951C8B54C50C3C89D4D9C2AC75BE3DA0ED962` | 前方单个已生成 actor 的蓝/橙纹理 skinned 表面可见。 |
| `run\client\screenshots\2026-07-30_01.01.53.png` | `7097A3D759360D00BBC61834EAE576FB9E9B5D3B9FEC456036599F8EDC34B169` | 两个在不同时刻、不同位置生成的 actor 同时可见；前景仍有既存 P4 fixture，未把它们误归为 actor。 |
| `run\client\screenshots\2026-07-30_01.02.36.png` | `DF54C8B33016D306AECB420F160366D518521D957DAA0487179A94DA7ACD4124` | 后续帧仍可见两个 actor，且会话未出现 BlendLib 崩溃。 |

这些文件是由正在运行的 Minecraft 客户端 F2 实际生成；哈希由协调端在会话结束后用
`Get-FileHash -Algorithm SHA256` 独立计算。当前 `crash-reports` 目录只有此前已经登记的
两份修复前报告（LineWidth 与断线 teardown），本次没有新增报告。

## 本次可成立的结论

- 在真实 26.1.2 客户端中，当前 Showcase `animated_actor` 的 skinned、外置贴图表面可见；
  这不是仅凭 unit test 或启动 smoke 推断。
- 两个由单独 summon 操作创建的 actor 可在同一帧同时渲染。它们的命令时间和相对位置已由
  日志/截图共同保留。
- 会话保存、返回标题界面和关闭均正常完成，Gradle exit code 为 0；本次没有重现已修复的
  `LineWidth` 或断线 lifecycle 崩溃。

## 明确不成立的结论

- 这三张静态截图不能证明完整的 idle/walk/attack 时长、quaternion 连续性、或两个实例已
  处于可归因的不同 animation state。
- 该普通 actor 接收 P6 同步状态；其 fallback 132-tick schedule 不能从本次会话归因。详见
  `docs/adr/ADR-018-p5-showcase-schedule-provenance.md`。
- 截图没有提供连续 socket RGB marker 轨迹，因而 socket visual item 仍为 WAITING。
- 实际交互帧曾出现 `SWEEP_ATTACK` 弧形粒子，但本次落盘 F2 帧未稳定捕获该瞬间；它不被计入
  持久的 visual-event PASS 证据。
- 本次未执行 F3+T reload 矩阵、断线 cache/cadence profiler、双客户端同步或 P7 性能捕获。

因此 P5 仍为 `IN_PROGRESS`，所有未由对应真实证据支持的 P5 子项继续 `WAITING` 或
`BLOCKED`；本记录不授权阶段提交、发布、推送、标签或生产部署。

## fallback 连续录像尝试（2026-07-30，未达 Gate）

本次只使用 `D:\BlendLib\blendlib-showcase\run\client` 和其中可丢弃的
`BlendLib Visual RC` 本地世界；未启动、停止或修改正式服务器、正式世界或
`D:\MinecraftFabricServer-26.1.2`。`p5_fallback_actor` 的隔离客户端会话最终按
“保存并退出到标题界面”正常结束：`latest.log` 记录 `Render thread ... Stopping!` 于
`09:13:44`，启动器记录 `BUILD SUCCESSFUL in 44m 6s`，本次未产生新的 crash report。

原始最终视频为
`D:\BlendLib\build\manual-p5-fallback-capture-evidence\2026-07-30-082500\22-p5-fallback-final-continuous.mp4`：
`180.000 s`、`854×480`、`15 fps`、`2,700` 帧、`2,739,075` bytes，SHA-256 为
`F1C3275AE30D406F61DBE38AB24EADCF6138AE90E2C5C30EE6D3C22703E4CCF3`。它在最终
首次生成和第一次 `/time query gametime` 后已结束；因此它**没有覆盖第二次生成**，不能作为
完整 132-tick 周期、第二实例至少错相 40 tick 或两个实例同帧不同状态的连续视频证据。

独立日志可复核以下命令事实，但它们不能替代缺失的视频/实体标识：

| 日志时间 | 事实 |
| --- | --- |
| `08:47:48` | 首次生成 `blendlib_showcase:p5_fallback_actor`。 |
| `08:48:57` | `/time query gametime` 返回 `256971`（T0）。 |
| `08:50:40` | 第二次生成 fallback actor。 |
| `08:52:22` | `/time query gametime` 返回 `261067`（T1，`T1 - T0 = 4096`）。 |

录像帧及现场相机/yaw 探查仅将 skinned 平面呈现为很窄的橙/蓝边线；这不足以人工判定
idle/walk/attack/return、四元数连续性或双实例相位，不能据此推断渲染失败，也不授权修改
刻意单面、零厚度的 P5 fixture。一次 `/gamerule doDaylightCycle false` 在本固定版本的命令
解析中被拒绝；它只是观察环境控制失败，没有改变实现、设计、ADR 或验收标准。

因此，本次仅新增“隔离真实客户端正常退出”和“部分 fallback 命令日志/原始录像”事实。socket
RGB marker、visual event、完整 fallback schedule、双实例连续可见相位、F3+T reload 以及其余
P5 人工项仍为 `WAITING`；P5 仍为 `IN_PROGRESS`，不得据此提交、发布或声称视觉 Gate 通过。

## fallback 正面连续捕获（2026-07-30，真实客户端但阶段未通过）

为排除上一段录像只拍到背/掠视面的影响，本次在同一隔离本地世界将相机固定在
`/tp @s 0 66 4 180 28` 的世界 `+Z` 正面，再以 `/kill` 清理该世界已有的两个 fallback
实例、`/time set day` 后开始录制。整个过程仍只使用
`D:\BlendLib\blendlib-showcase\run\client` 和可丢弃的 `BlendLib Visual RC`；没有操作
正式服务器、正式世界或 `D:\MinecraftFabricServer-26.1.2`。

原始视频为
`D:\BlendLib\build\manual-p5-fallback-capture-evidence\2026-07-30-092802\30-p5-fallback-front-continuous-10m.mp4`：
H.264、`854×480`、`15 fps`、`9,000` 帧、`600.000 s`、`14,195,298` bytes，SHA-256 为
`B3F8096AC2BEBCAE8C57484889330B454965D7E2577E8775C7A1DFCE592FC715`。它从 `09:44:22`
持续至 `09:54:22`，完整覆盖以下命令/聊天输出；最终保存、返回标题并退出后，日志记录
`Render thread ... Stopping!` 于 `10:07:08`，启动器记录 `BUILD SUCCESSFUL in 39m 6s`，且
`crash-reports` 仍只有两份此前已登记的历史报告。

| 墙钟时间 / 视频偏移 | 真实记录 |
| --- | --- |
| `09:45:35` / 约 `73 s` | 清理两个旧 `p5_fallback_actor` 实例。 |
| `09:46:39` / 约 `137 s` | 设置昼间光照。 |
| `09:47:43` / 约 `201 s` | 生成第一个 `p5_fallback_actor`。 |
| `09:48:34` / 约 `252 s` | `/time query gametime` 返回 `306229`。 |
| `09:49:35` / 约 `313 s` | 最近实例 UUID 为 `[I; 738674508, -689289378, -1440243297, 1358251708]`。 |
| `09:51:10` / 约 `408 s` | 以 `~2 ~-1 ~-4` 生成第二个 fallback 实例。 |
| `09:52:07` / 约 `465 s` | `/time query gametime` 返回 `310492`；两次查询相差 `4263` tick。 |
| `09:53:26` / 约 `544 s` | 最远实例 UUID 为 `[I; 894911640, -49591355, -1247660059, 447083003]`。 |

当前实际资源哈希为：descriptor
`D2E044E4E2C251E79DF95E6F028AE457DF76630FE3CDE05AA76ABAEA81353A94`、GLB
`EF6DCD3426341F984367A8598FA7FC159D85DA17B8F6709AA3B7F4D183615D53`、外置 PNG
`1110B7C256A8F1100FC659934BFE360340694F98DDE0243D37D6FC8DCDBA8362`。

协调端直接检查了原始视频及其派生帧：

- `30-frames/first-cycle-actor-contact-sheet-201s-to-209s.png` 连续展示首个正面可见的
  双色 skinned/external-texture 平面，从小幅连续变化到明显 tip swing 后再回到直立；没有把
  该几何较窄的视觉表现误判为 missing model。
- `frame-410.0s-two-instance.png`、`frame-412.2s-two-instance-motion.png` 以及原始视频在
  同一连续片段中同时显示两个不同 UUID 的 fallback 实例，且二者当帧姿态不同。
- 这些画面是实际客户端输出，因而能支持“该正面机位下 skinned 网格/外置贴图可见”和
  “两个独立 fallback 实例同时可见”的有限事实；并不只是源码或单元测试推断。

但这仍不是 P5 Gate PASS：两个 `/time query` 分别发生在出生后的较晚时刻，未提供精确的
出生 tick 或屏幕上的 state 标签；`T1 - T0 = 4263` 只证明两个保留查询之间的游戏刻跨度，不能
替代每个实例的精确 animation age。故 idle/walk/attack 的完整 132-tick 手工归因、四元数
连续性、socket marker、`attack_whoosh` 视觉事件、F3+T reload 及 cache/cadence 仍保持
`WAITING`。ADR-018 下 fallback 本身未绑定 socket/event，这两个项目也不能从这段录像外推。
完整结果表、ffprobe、原始日志和派生帧位于同一 capture 目录；P5 仍为 `IN_PROGRESS`。

## presentation visual event 与实例内重载观察（2026-07-30）

本次继续只使用 `D:\BlendLib\blendlib-showcase\run\client` 和其中可丢弃的
`BlendLib Visual RC` 本地世界。运行器用 Java 25 正常启动，最终从本地世界“保存并退出”到
标题界面、退出客户端；`01-runClient-gradle-stdout.log` 记录 Render thread 正常停止，且
Gradle 记录 `BUILD SUCCESSFUL in 42m 58s`。没有启动、停止或修改正式服务器、正式世界或
`D:\MinecraftFabricServer-26.1.2`。

本次可复核材料位于
`D:\BlendLib\build\manual-p5-presentation-reload-evidence\2026-07-30-102841`：

- F2 图 `2026-07-30_10.42.32.png`，SHA-256
  `199E312DB83F1C0415E88E53439EE5AC5ED8D486B65F6B6E63D8AF3B60FAAB9D`，实际显示已生成的
  普通 `animated_actor` 旁的白色 `SWEEP_ATTACK` 弧形粒子。日志将该 actor 的 summon 记录为
  `10:38:44`，在 `10:42:32` 保存该图，并在 `10:51:31` 才清理它。协调端复核
  `BlendLibShowcaseClientEntrypoint.java:67-86` 和精确 Java 源检索：这个 Showcase client-only
  绑定仅在 `attack_whoosh` 事件 key、render-thread/liveness 守卫通过时发射
  `ParticleTypes.SWEEP_ATTACK`；不存在第二个自定义 Showcase emitter。因此
  **presentation-only visual-event 单项有真实截图 PASS**。它不证明 event 的精确 tick、P6
  同步、132-tick fallback 时钟，且绝不承载伤害、碰撞、消耗、掉落或命中语义。
- F3+T 后，`02-runClient-gradle-stderr.log` 记录
  `candidate_generation=1 active_generation=1 ... models=4 missing=0 diagnostics=0` 到
  `candidate_generation=2 active_generation=2 ... models=4 missing=0 diagnostics=0`；重载前/后
  `/blendlib inspect` 均为 `discovered=true missing=false diagnostic=none`，而
  `2026-07-30_11.05.53.png`（SHA-256
  `06FB33BC25C6971631512A25BA86A6816C305ABC516E1EE3F3D92F5958AA2F5B`）显示重载后 fallback
  actor 仍可见。这是 registry/diagnostic/可见性观察的窄 PASS，**不**解释为 resume、snap、
  restart、保持 pose 或 event replay。
- 同目录 `10-p5-presentation-reload-raw.mp4` 的 SHA-256 为
  `B3791DB2212D5255B2088B29D6C5BCD5FD85791BB8B7A954D7B10F421D8070D8`，但 ffprobe 返回
  `moov atom not found`；该文件无效，明确不作为视频、时序、socket 或 event 证据。

RGB socket marker 没有获得连续可复核截图/视频，保持 `WAITING`。本次也不改变完整 fallback
schedule、quaternion、cache/cadence、P5 Gate 或用户管理审核的状态；P5 仍为 `IN_PROGRESS`。

## 侧向 RGB socket marker 可见性观察（2026-07-30）

协调端随后只使用 Java 25 的 `:blendlib-showcase:runClient`、
`D:\BlendLib\blendlib-showcase\run\client` 和可丢弃的 `BlendLib Visual RC` 本地世界；没有
启动、停止或修改正式服务器、正式世界或 `D:\MinecraftFabricServer-26.1.2`。清理 fallback actor
后，日志在 `12:00:55` 记录普通 `blendlib_showcase:animated_actor` 的 summon，并在 `12:04:56`
记录侧向观察位置 `(3.500000, 66.000000, 0.500000)`。

可复核材料位于
`D:\BlendLib\build\manual-p5-socket-side-evidence\2026-07-30-114245`：

- F2 图 `2026-07-30_12.05.35.png`（396,778 bytes，SHA-256
  `B8087722F8C0F9024DBC3EDA1FD296FA13EA07237DA97060324342AB4F07E82D`）和
  `2026-07-30_12.11.17.png`（393,577 bytes，SHA-256
  `3ADAB44B1F508AE6406FF0DD8BF6BD59677FC0EE321C79C8A8E3B9022A804F91`）均直接显示
  交叉准星上方的小型红、绿、青 RGB 三轴 marker。普通 actor 的客户端绑定在
  `BlendLibShowcaseClientEntrypoint.java:65-86` 配置 `skinnedSocketMarker(TIP_SOCKET_KEY)`；
  `SkinnedSocketMarkerSubmitter.java:30-80` 仅在 extraction 已捕获可见 skinned socket 时，使用
  `RenderTypes.lines()` 提交三轴几何。这些截图可支持**“普通 actor 的 client-only RGB socket
  marker 能在真实客户端侧向机位出现”这一窄 PASS**。
- 运行日志记录两张图保存、`12:15:56` 开始保存、`12:16:20` 所有维度保存及本地玩家退出，
  `12:16:29` Render thread `Stopping!`，随后 Gradle `BUILD SUCCESSFUL in 33m 43s`。该隔离
  run 目录未新增 crash report；仍只有两份 2026-07-29 历史报告。

两张图没有状态、entity-age 或 socket-transform 遥测，且 marker 的画面位置基本相同。因此它们
**不**证明 idle/walk/attack 内持续的骨骼相对轨迹、世界坐标数值或 quaternion 连续性；不证明
132-tick fallback schedule、P6 同步、F3+T 的 resume/snap/restart/event 语义、cache/cadence 或
性能。该 socket 可见性窄 PASS 不改变 P3/P4 审核、P5 总 Gate 或任何暂存/提交/发布状态；P5
仍为 `IN_PROGRESS`。完整边界和正常退出证据见同目录 `result.md`。

## 正面 fallback 双实例补充（2026-07-30，真实客户端）

协调端随后针对单面、零厚度的 Showcase 平面，先清理此前两个 fallback actor，设置日间光照，
并在已验证的 `+Z` 正面机位 `/tp @s 0 66 4 180 28` 重新执行采集。仍仅使用 Java 25、
`blendlib-showcase/run/client` 和可丢弃的 `BlendLib Visual RC` 本地世界；没有操作正式服务器、
正式世界或 `D:\MinecraftFabricServer-26.1.2`。

此次可采用的连续原始视频是
`D:\BlendLib\build\manual-p5-schedule-evidence\2026-07-30-123000\60-p5-front-visible-two-instance-15s.mp4`：
H.264、`854×480`、`15 fps`、`225` 帧、`15.000 s`、`424,129` bytes，SHA-256 为
`E34E8CD936323B12B6805558A78D89220A282DC9C8D417A45FAEBF8553FCA679`。协调端直接检查了
`frames/front-visible-{000,003,006,009,012,014}s.png`：两个同一 Showcase asset 的橙/深蓝
skinned 外置贴图平面连续可见，且画面中保持可区分的姿态。F2 图
`90-p5-front-double-instance-2026-07-30_16.20.54.png`（SHA-256
`00B029C4AF8B9B911E9BB79D559B5A29C1F700C9AB3E4E201A82513B09376800`）同帧显示两个实例，
其中右侧实例倾斜而左侧实例近直立。

保留日志 `91-client-latest.log` 记录首个 fallback 生成于 `16:19:03`，随后的查询为
`425886` tick；第二个 `~2 ~-1 ~-4` fallback 生成于 `16:20:01`，随后的查询为 `427079` tick。
两份查询相差 `1193` tick，确实超过第二实例要求的 40 tick。它们都在相应生成之后而非精确出生时，
因此可支持**“两个独立实例同屏、且有明显错相/姿态差”**和**“正面 skinned 网格/外置贴图可见”**
两个窄项，但不支持为任一画面精确命名 idle/walk/attack，或计算 80/120/132 tick 边界。

本次客户端从菜单保存退出；日志记录所有维度保存、`Render thread ... Stopping!` 及 Gradle
`BUILD SUCCESSFUL in 25m 7s`。没有新增 crash report。早期 10/20/40/50 系列录像仍保留但因空场、
背/掠视机位、启动参数失败或在生成前结束而明确排除。P5 仍为 `IN_PROGRESS`：quaternion 全关键帧
连续性、精确 schedule/attack-to-idle blend、continuous socket、reload 语义及指标型 cache/cadence
仍未被这次视频替代。
