# Logo 正式集成验证 — 2026-09-05

范围：所有者批准的折面 B、MC 风格像素文字组合标，以及各自的透明底/白底 PNG。
本次是品牌资源维护，不改变 P0–P8 或扩展阶段的 Gate 状态。

## 静态与制品

- 四份正式 PNG 与所有者确认后保存在 `output/imagegen/brand-v2/` 的对应文件一致。
  尺寸分别为 1254 × 1254、2172 × 724，哈希见 [SHA256SUMS](./SHA256SUMS)。
- 使用 System.Drawing 读取并采样 Alpha 通道：两份透明底均存在 Alpha=0 的背景；
  白底版本不透明。没有把棋盘格背景的中间稿作为正式透明资源。
- 模组 `assets/blendlib/icon.png` 与正式白底独立 B 的 SHA-256 一致：
  `f3b5c602a6384948c134f040c95dc4995d29fa619011878c4e1b3c49b01f5a37`。
- `docs/assets/blendlib-logo.png` 与正式白底文字组合标一致；新增文档相对链接均存在。
- Java 25 下执行 `gradlew.bat --offline --no-daemon --max-workers=1 :blendlib-fabric-client:build --console=plain`
  成功，37 个测试套件、161 个测试、0 failures、0 errors。
- 检查 `blendlib-fabric-client/build/libs/blendlib-fabric-1.0.0-alpha.1+26.1.2.jar`：
  `fabric.mod.json` 的图标路径正确，JAR 内图标与正式白底图逐字节一致。
  本次未刷新 `build/release/` 完整发布包或改变发布版本。

## 客户端启动与验收边界

首次离线 `:blendlib-fabric-client:runClient` 在 `downloadAssets` 失败，客户端尚未启动。
移除 `--offline` 后以原生同一任务重试，资源下载通过，Minecraft 26.1.2、Fabric Loader
0.19.3 与 BlendLib 1.0.0-alpha.1+26.1.2 启动。

`blendlib-fabric-client/run/logs/latest.log` 中 15:32:44 的 Render thread / Indigo / LWJGL，
以及 15:32:48 的 OpenAL、Sound engine started 和图集创建提供启动证据。
检查时未出现本次运行的 crash report。日志包含开发身份的 401 / Realms 认证错误，
以及 Loom 空 client resources 目录警告；不将日志称为完全无错误。

未操作游戏、进入世界或接管 UI。启动配置没有 Mod Menu；此记录不能证明模组列表内
实际显示，也不代表 P0–P8 客户端视觉 Gate PASS。随后所有者明确回复「提交推送吧 PASS」，
本次 Logo 集成按所有者确认记录为 PASS；未新增代理游戏画面检查证据。
客户端保留运行供所有者自行操作或关闭。
