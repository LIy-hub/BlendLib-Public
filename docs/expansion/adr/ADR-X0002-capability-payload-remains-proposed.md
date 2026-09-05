# ADR-X0002: Capability negotiation cannot reuse the v1 extensions payload

状态：Proposed

## Context

当前 accepted ADR-016 保持 RC 的严格边界：generic descriptor extensions 只验证对象形状；payload 不保留或暴露；nonempty required extension 仍为 deterministic EXT-001 reject。把这个对象直接描述成可执行 capability payload 会越过已接受 v1 合同。

## Proposal

未来若需要 capability request/offer，必须建立独立的、bounded immutable envelope，并定义：

- canonical namespace:path id、严格 bounded semantic version range、REQUIRED/OPTIONAL；
- provider identity/priority、deterministic conflict policy、freeze 后 immutable plan；
- payload size/depth/count limit、diagnostic code allocation、negative fixtures；
- semantic-equivalent CPU/standard fallback 或 fail-closed missing handle；
- schema/profile/protocol migration，而不是在 v1 descriptor 内静默扩大 acceptance set。

## Consequences

在此 ADR 被 local project owner 接受、实现并独立审查前：

- v1.x 默认不启用 capability payload；
- required extension 继续拒绝；
- optional generic data 不可被 runtime 解码或执行；
- 不得用 custom RenderType/pipeline、reflection、raw GL 或 private Minecraft/Fabric API 绕过 ADR-016。
