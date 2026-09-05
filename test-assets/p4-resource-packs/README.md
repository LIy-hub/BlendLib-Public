# P4 隔离资源包 fixture

这两个目录是 P4 的手工资源包验收 fixture；它们不属于默认 Showcase 资源、不会被构建脚本复制到运行目录，也不得用于正式服务器、正式世界或公开发布。

| 目录 | 目的 | 对 `blendlib_showcase:fixtures/static_model` 的结果 |
| --- | --- | --- |
| `valid-override` | 高优先级 descriptor 覆盖 | 将静态 key 解析为已提交的刚体 GLB 和外置贴图，验证 descriptor-only 覆盖后的最终资源组合。 |
| `malformed-missing-mesh` | 有意损坏的诊断 fixture | descriptor 本身仍符合 v1 schema，但 GLB 指向不存在的资源；预期为 missing model 和该 key 的一次主诊断。 |

## 为什么 valid pack 不复制 GLB 或 PNG

BlendLib v1 允许资源包分别覆盖 descriptor、GLB 和贴图，但最终组合必须完整校验。`valid-override` 只覆盖：

```text
assets/blendlib_showcase/blend_models/fixtures/static_model.json
```

它引用 Showcase 基线资源中已提交的 P2 刚体 fixture：

```text
assets/blendlib_showcase/models3d/fixtures/rigid_model.glb
assets/blendlib_showcase/textures/blendlib/fixtures_rigid_model__rigidsurface.png
```

因此它只能与本项目的 Showcase mod 一起用于隔离验收。这样可以直接证明高优先级 descriptor 与低优先级 GLB/PNG 的最终解析组合，而不是通过复制二进制资源绕开资源包优先级。

当前基线 SHA-256：

```text
rigid_model.glb                              a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764
fixtures_rigid_model__rigidsurface.png       851035d6863e6aea6a03c7a93d8734872e16918938c6f8b962392c24b25502e1
```

## 本地、隔离的手工验收

1. 仅启动 `D:\BlendLib\blendlib-showcase` 的隔离客户端运行目录；不要启动或修改 `D:\MinecraftFabricServer-26.1.2`、`D:\MinecraftFabricServer-26.1.2-Fresh` 或任何正式世界。
2. 在客户端关闭后，手工将**一个** fixture 目录的内容复制到隔离目录 `D:\BlendLib\blendlib-showcase\run\client\resourcepacks\<pack-name>`。本仓库不会自动执行这一步。
3. 启动 `:blendlib-showcase:runClient`，在资源包界面启用该 fixture，使其优先级高于内置 Showcase 资源，然后按 `F3+T`。
4. 对 `valid-override`：在 static-rigid Showcase 对象处观察同一个 static model key 已替换为刚体 fixture，并检查 `/blendlib inspect blendlib_showcase:fixtures/static_model` 没有 missing model/primary diagnostic。
5. 退出并移除该临时复制的 pack，再对 `malformed-missing-mesh` 重复步骤 2–3：观察 static model 显示 missing-model 占位，并用 `/blendlib diagnostics` 确认该 model key 只有一次主诊断；不可把该错误当成视觉 PASS。
6. 每次操作后删除隔离 `resourcepacks` 中的临时副本或保留在隔离目录；不得把它移动到任何正式实例、服务器或世界。

真实客户端观察仍需要人工证据；本 README 和自动化文件校验不构成视觉 Gate PASS。

## 统一手工验收记录

此 fixture 的完整、固定顺序为：基线 → valid override → 基线恢复 →
malformed override → 基线恢复。请使用
[BlendLib v1 手工客户端验收记录模板](../../docs/manual-client-acceptance-v1.md)，
在唯一允许的隔离运行目录
D:\BlendLib\blendlib-showcase\run\client 保留截图、命令输出和每个
generation 的日志摘录。

该模板明确区分当前可执行的 P4 reload/missing-model 检查、ADR-013 的物品
阻塞、ADR-014 的材质阻塞，以及尚不能执行的 P5 动画验证。它不会解除任何
ADR，也不构成 P4/P5 的视觉或 Gate PASS。正式服务器、正式世界和
D:\MinecraftFabricServer-26.1.2 均不得使用这些 fixture。

## 只读 fixture 校验

在仓库根目录运行：

```powershell
& .\test-assets\p4-resource-packs\verify-p4-resource-packs.ps1
```

该命令只读取 `pack.mcmeta`、两个 descriptor 和已提交的 Showcase 刚体 GLB/PNG；它验证本地 26.1.2 所要求的 `min_format: 84` 与 `max_format: 84`、资源 ID、路径存在性、P2 fixture SHA-256，以及故障 fixture 没有意外携带缺失 GLB。
