# 许可证与发布元数据

状态：根目录现有 `LICENSE` 和 `NOTICE` 已是 Alpha 源线采用的法律文本。本次 X1–X9
合并保留该决定；本文不选择新的许可证，也不把任何历史 pending 记录重新变成当前状态。

## 项目自身范围

| 范围 | 许可证 | 发布元数据 |
|---|---|---|
| 根项目、`blendlib-*` Java 模块、Showcase、非 Add-on 源码与资源 | Apache-2.0 | LIy-hub，`2734855720@qq.com`，GitHub 源码，CurseForge 项目页 |
| `blender-addon/` | GPL-3.0-or-later | 独立 manifest 与 `blender-addon/LICENSE` |

根目录 `LICENSE` 是官方 Apache License 2.0 全文，`NOTICE` 包含
`Copyright 2026 LIy-hub`。Java runtime、sources、Javadoc 以及 Local Maven 内的 JAR
必须包含 `META-INF/LICENSE` 和 `META-INF/NOTICE`；POM 必须记录 Apache 2.0、项目 URL、
SCM、作者与邮箱。构建验证失败时不得发布相应制品。

Add-on 的 GPL 范围不会扩展到其他目录，Apache-2.0 也不会替换
`blender-addon/LICENSE`。第三方组件仍按其自身条款处理，详见
[第三方许可证库存](./third-party-license-inventory.md)。

`publishPublicAlpha` 只用于准备 `build/local-maven/` 的本地 Alpha 消费者坐标；它不是
远端发布。构建验证未通过时不得把相应制品作为可分发结果，本次文档合并也没有运行任何
构建、库存或法律验证。

## 历史 X8 LicenseRef 记录

X8 候选源在 Alpha 许可证采用前形成，部分历史文档或候选 metadata 会提到
`LICENSE-PENDING`、`LicenseRef-PENDING` 或 `LicenseRef-BlendLib-Local-Only`。这些文字
只说明其当时的 local-candidate 边界，不是当前根许可证的来源，也不能推翻根
`LICENSE`/`NOTICE` 的 Apache-2.0 决定。

X8 的 Fabric 26.2、NeoForge、datagen、example、template、converter 与 inventory 仍是独立候选；
它们需要保留各自的来源、第三方 notice、分发与验证条件。该边界不等于许可证待定，也不
构成远端 Maven 发布、公开 tag/release、运行时或 Gate PASS。
