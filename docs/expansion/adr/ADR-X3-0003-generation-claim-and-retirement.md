# ADR-X3-0003：generation-wide attachment claim 与显式退役

状态：Proposed / Experimental candidate。

## 背景

仅在单个 `ProceduralAttachmentGraph` 对象上记录 `published` 不足以证明一个 resource generation 的拓扑唯一。两份分别完整、分别无环、但 identity 不同的同代 graph 可以依次发布，例如 `G1: A→B` 与 `G2: B→A`；若两边 runtime 同时存在，组合后的实际 child-model 关系重新形成环。

生产 client 的 resource generation 由一个 class-loader 内唯一的 client model registry 分配；它与 X2 的 session、world、instance scope 是不同维度。因此 generation claim 不能做成 caller/session-local 对象，也不能依靠各 graph 自己的布尔值。

## 决定

- 对所有需要 complete publication 的 graph，以 `generation` 为 key 使用一个 class-loader/JVM-global claim registry。同一 generation 同时最多有一个 graph identity；语义相同但 identity 不同仍拒绝。不同 generation 可以并存。
- registry 固定最多 256 个 active claim。它只保存 `WeakReference<ProceduralAttachmentGraph>`，不保存 runtime；弱清理只是最后的泄漏保护，正常 reload/disconnect 必须显式调用 graph `close()`。
- publication 先在 graph lock 内验证完整、精确的 plan identity set，再按唯一锁序 `graph -> registry` claim。registry 从不回调 graph。foreign/partial plan、同代冲突或容量失败都不改变 graph 状态；exact same graph + exact complete plan set 的重复 publish 幂等。
- `close()` 永久、幂等地把 graph 置为 retired 并释放 claim。retired graph 不能再注册 plan、publish、构造可用 runtime、读取 runtime snapshot 或提交 frame；旧对象不能重新激活。
- runtime 的最终 snapshot/replay/crossing commit 与 `close()` 共用 graph 线性化点。若退役发生在 evaluate 中途，frame 以 `ATTACHMENT_GRAPH_RETIRED` fail closed，且不替换上一 snapshot、不提交 replay/crossing。
- 两参数 `ProceduralRigRuntime(plan, scope)` 只兼容空 marker catalog。非空 catalog 必须在构造时立即拒绝，并要求三参数构造器绑定 exact X2 runtime；不能构造一个永远静默产出空事件的 runtime。

## 后果

- 同代分别发布的完整 graph 不再能跨 identity 组合出环；并发 publish 也只有一个 winner。
- future platform integration 必须把 graph 生命周期纳入 resource apply owner：发布新 generation，原子替换 owner 引用，并在旧 generation 不再可见后显式 `close()` 旧 graph。pure-core fixture 可传任意 non-negative generation，但真正 integration 必须使用唯一 generation owner 分配的值。
- 容量拒绝不会驱逐仍 active 的 generation；集成层必须先修正未退役 owner，而不是重试风暴或依赖 GC。
- 本 ADR 不声明真实 Minecraft reload 已接线或通过；20-reload、late callback 与 disconnect 仍为 `WAITING`。

## 非决定

本 ADR 不创建共享 entrypoint、Fabric listener、renderer、network payload、X4 host 或 X6 material lifetime。它只收紧 X3 core 候选的 publication 与 retirement 语义。
