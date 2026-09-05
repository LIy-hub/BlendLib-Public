# ADR-X4001：X4 typed client host target boundary

状态：Proposed / implementation candidate；等待独立审查和共享集成，不修改 X1 stable API。

## Context

X1 的 stable `HostKind` 只有 ENTITY、BLOCK_ENTITY、ITEM，且 host token 对 pure API 保持 opaque。X4 需要 Armor、Projectile、distinct Held Item、First-person hand、GUI Preview、World Object、Persistent VFX，以及更高风险的 player/composite target。把这些 target 加入 X1 enum、common/server 或普通 marker-item path 会把 26.1.2 renderer detail 扩散到稳定表面，并使 Experimental scope 不可逆。

## Decision

1. 新 target 使用 client-only `X4HostKind`、typed `X4HostConfigurations` 和 `X4HostFrames`；不修改 X1 `HostKind` 或 pure API。
2. Formal targets 是 Armor、Projectile、Held Item、First-person hand、GUI Preview、World Object、Persistent VFX。Held Item 的 hand/context path 与 ordinary marker-item path 明确不同。
3. Player replacement、mount/composite entity、multi-entity composite 保持 Experimental；factory 和 configuration 均需不可伪造的 explicit `X4ExperimentalAccess` token，graph 有 node/edge/depth/acyclic limits。
4. 所有 target 使用 scoped `X4HostIdentity(scope, localId)`，不能用裸 entity id 跨 world/session 重用；composite graph 的所有成员必须同 scope、从 configured root 连通，且 root 必须等于 host spec identity。
5. Showcase catalog 是隔离 consumer example，不自动注册进 client entrypoint。

## Consequences

- 新 host API 能在版本特定 client module 演进，不改变 X1 semantic facade。
- 每个 target 的 bounds、lifetime、owner/session、viewport 与 graph invariant 在 prepare 前可 fail closed。
- common/server bytecode 不链接 X4 host package；实际 server launch 仍需后续验证。
- 现有 renderer 接线与 Showcase entrypoint 接线成为显式 shared integration，而不是被 X4 偷改。

## Rejected alternatives

- 将全部 target 加入 stable `HostKind`：会使 platform-specific render behavior 进入 pure API。
- 将 Held Item 映射到 existing marker item：会混淆 stack/stateless policy 与 hand/context identity。
- 用一个 `Object`/map adapter 接收所有 target：不能在 build/prepare 前验证 target-specific invariant，也不能约束 Experimental opt-in。
