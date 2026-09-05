# BlendLib P6 隔离双客户端同步验收清单

状态：这是 P6 的可执行验收模板和隔离运行约束，不是同步、视觉或 P6 Gate
PASS 证据。只有保留真实 dedicated server、两个真实客户端、截图/视频、三份
日志和自动化结果后，协调者才能判定对应项目。

## 0. 严格隔离范围

此清单只允许使用下列由 `blendlib-showcase` 定义的 P6 专用目录：

```text
D:\BlendLib\blendlib-showcase\run\p6-sync-server
D:\BlendLib\blendlib-showcase\run\p6-client-a
D:\BlendLib\blendlib-showcase\run\p6-client-b
```

三个目录不得互相复制 `world`、`saves`、`options.txt`、`config`、日志或资源包。
服务器模板固定为 `127.0.0.1:25575`、`online-mode=false`、禁用 RCON，且只产生
`blendlib-p6-sync-world`。`BlendLibP6A` 和 `BlendLibP6B` 是仅本地测试身份，不能
用于正式服务器。

严禁启动、停止、修改或复制任何内容到：

- `D:\MinecraftFabricServer-26.1.2`
- `D:\MinecraftFabricServer-26.1.2-Fresh`
- 任意正式服务器、正式世界或普通 Showcase `run/server`、`run/client`

每次验收新建证据目录，例如：

```text
D:\BlendLib\build\manual-p6-sync-evidence\2026-07-29-210000
```

至少保留：

```text
00-session.md
01-server-start.txt
10-client-a-connect.png
11-client-b-connect.png
20-actor-start-a.png or .mp4
21-actor-start-b.png or .mp4
30-altar-before-late-tracking-a.png
31-altar-tracking-replay-b.png
40-sequence-automated.txt
41-transient-expiry.txt
50-disconnect-cleanup-a.txt
60-dimension-change-a.txt
61-late-packet-risk.md
70-server-log.txt
71-client-a-log.txt
72-client-b-log.txt
result.md
```

`00-session.md` 必须记录测试者、日期、BlendLib 提交、Java、Minecraft/Fabric
版本、三个 P6 运行目录、端口、两个测试用户名、无 shaderpack 状态和临时世界名。

## 1. 启动命令

在 `D:\BlendLib` 中设置 Java 25。三个长运行命令必须各启动一次并保持运行，
不得改用普通 `runServer` 或 `runClient`：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-showcase:runP6SyncServer --console=plain
.\gradlew.bat :blendlib-showcase:runP6ClientA --console=plain
.\gradlew.bat :blendlib-showcase:runP6ClientB --console=plain
```

先等待服务器日志中的 `Done`，并保存到 `01-server-start.txt`。两个客户端分别在
多人游戏中直接连接 `127.0.0.1:25575`；连接成功后分别保存
`10-client-a-connect.png` 和 `11-client-b-connect.png`。用服务器控制台或取得 OP 的
测试身份执行：

```mcfunction
/summon blendlib_showcase:animated_actor ~ ~ ~
/setblock ~2 ~ ~ blendlib_showcase:animated_altar
```

仅在 P6 临时世界内执行这些命令。不要让视觉模型参与伤害、碰撞、掉落或命中判断。

## 2. 真实同步观察

### 2.1 两个当前 tracker 的同一起点

让 A、B 同时在 animated actor 的 tracking range 内。服务器 actor 每 80 game tick
触发一次 semantic attack。保留同一轮 attack 的 A/B 连续截图或视频时间戳，记录实体
ID、服务器 game tick 和两个客户端可见 attack 起点。两端都必须无 missing-model/
BlendLib 网络异常诊断；否则该项目为 `FAIL` 或 `WAITING`，不可推断通过。

### 2.2 persistent tracking replay

先让 A 看见已加载的 animated altar 并确认其 persistent idle loop。仅在该状态已经建立后
让 B 连接或进入 altar 的 tracking range。保存 A 的 `30-altar-before-late-tracking-a.*`
和 B 的 `31-altar-tracking-replay-b.*`，证明 B 获得当前 persistent state，而不是等待
下一次 transient。

### 2.3 sequence replacement 与 transient expiry

正常网络不应人工伪造乱序包。把下列自动化输出保存为
`40-sequence-automated.txt`，它是 sequence replacement / stale drop 的自动证据：

```powershell
.\gradlew.bat :blendlib-fabric-client:test --tests '*ClientAnimationSyncStoreTest' --tests '*SkinnedAnimationRuntimeTest' --rerun-tasks --console=plain
```

同时从 A/B 的真实视频中记录 attack 结束后回到其 descriptor 声明的下一状态；B 在 attack
结束后才开始 tracking 时不得收到旧 transient。写入 `41-transient-expiry.txt`。这证明实际
路径未把 transient 作为 tracking replay；它不替代乱序自动化测试。

### 2.4 disconnect 与 dimension change

断开 A，保存断开前后日志和重新连接后的观察到
`50-disconnect-cleanup-a.txt`。persistent altar 可在重新 tracking 后重放；旧 transient
不得被当作 persistent 重放。

在服务器控制台只对 A 执行一次换维度再返回：

```mcfunction
/execute in minecraft:the_nether run tp BlendLibP6A 0 80 0
/execute in minecraft:overworld run tp BlendLibP6A 0 80 0
```

保存 `60-dimension-change-a.txt`。entity/block-entity payload 按 v1 设计不携带
dimension/session；因此换维度后的无异常观察不能单独证明晚到旧包安全。将所有相关日志和
观察写入 `61-late-packet-risk.md`：若出现旧世界状态命中新 entity ID 或 block position，
立即停止、保留证据并提出 ADR；不得静默增加协议字段。若未能受控注入晚到包，该项保持
`WAITING`，而不是伪造 PASS。

## 3. 日志与结果

从下列隔离日志复制相关行，不能只引用不断变化的 `latest.log`：

```text
D:\BlendLib\blendlib-showcase\run\p6-sync-server\logs\latest.log
D:\BlendLib\blendlib-showcase\run\p6-client-a\logs\latest.log
D:\BlendLib\blendlib-showcase\run\p6-client-b\logs\latest.log
```

`result.md` 逐项写 `PASS`、`WAITING` 或 `BLOCKED`，不得把启动成功当作同步或视觉 PASS：

| 项目 | 状态 | 证据 | 说明 |
| --- | --- | --- | --- |
| dedicated server loopback startup | WAITING |  | `Done` 与端口/目录检查 |
| 两个 tracker 同一 attack 起点 | WAITING |  | A/B 视频或截图 |
| late tracking persistent replay | WAITING |  | altar A/B 证据 |
| sequence replacement | WAITING |  | 自动化测试输出 |
| transient expiry / non-replay | WAITING |  | 视频、日志、自动化 |
| disconnect cleanup | WAITING |  | A 断线/重连日志 |
| dimension change | WAITING |  | A 换维度日志 |
| late-packet risk | WAITING |  | 未受控注入时不得写 PASS |
| no formal server/world/publication use | WAITING |  | session 记录 |

完成后还要保存当前 `git diff --check`、测试输出和三个运行目录的相关日志摘录。此文件不授权
push、发布、公开许可证选择或生产部署。
