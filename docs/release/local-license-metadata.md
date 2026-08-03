# 许可证与发布元数据

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

这些元数据不代表上传、tag 或发布已经发生。
