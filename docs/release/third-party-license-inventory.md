# 第三方与许可证库存（Public Alpha）

构建生成以下机器可读记录：

- `build/release/dependency-inventory.txt`
- `build/release/license-inventory.txt`
- `build/release/SHA256SUMS`

`generateReleaseInventories`、`verifyReleaseInventories` 与负例校验负责检查覆盖、重复、格式、
archive 范围和缺失说明。每次发布候选构建后必须重新生成；本文不复制易过期的哈希。

## 打包边界

| 范围 | 许可证/NOTICE 处理 |
|---|---|
| 26.1.2 runtime 与三个 nested BlendLib JAR | Apache-2.0；各 JAR 包含 `META-INF/LICENSE` 与 `META-INF/NOTICE` |
| Showcase JAR | Apache-2.0；不得隐藏打包第三方 nested JAR；包含 LICENSE/NOTICE |
| Aggregate sources 与 Javadoc JAR | 包含项目 LICENSE/NOTICE；Javadoc 自带的 Oracle、jQuery、jQuery UI、DejaVu legal 文件另行登记 |
| Blender Add-on ZIP | 仅 Add-on payload 与 GPL-3.0-or-later `LICENSE`；排除源码 fixture 的 `jsonschema` helper |
| Fabric、Minecraft、JDK 宿主 | 记录为未打包的 host runtime，不把宿主条款误报为 BlendLib 再分发内容 |

本机 POM/JAR 没有足够许可证或 NOTICE 证据时，库存保留 `ABSENT` 与精确的 bounded reason，
不猜测法律结论。测试资产 `test-assets/third_party/khronos/glTF-Sample-Assets/` 不是 runtime
回退资源；若未来进入发布包，必须按其 provenance 单独复核。

这是早期测试版本的库存说明；最终发布包仍须使用同一次构建生成的最新库存。
