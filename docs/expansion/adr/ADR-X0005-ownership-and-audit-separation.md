# ADR-X0005: X ownership does not absorb existing audit or phase Gates

状态：Accepted by current GOAL authority

## Context

当前 source capture 继承了一个独立审计 FAIL：descriptor speed/event budget、sync speed formula、deferred level guard、GLB accessor semantics、skin validation、animated bounds culling。现有 phase ledger 同时保留 P0/P1/P2 PASS、P3 REVIEW、P4 WAITING、P5 IN_PROGRESS、P6/P7/P8 WAITING。

## Decision

X0--X9 使用 GOAL 指定的 DAG、每任务独立 worktree/branch/owner，并把 root build/version/entrypoint/metadata/既有 docs 设为 integration-only。既有 audit repair 不是 X 编号任务，不能由 X 文档或 checkpoint 宣称关闭。

当前实时 ADR-021 已 Accepted，但范围仅为 client-private receipt-level exact-object guard/test seam；P6 继续 WAITING，wire/API/Gate 不变。

## Consequences

每个 repair 与每个 X implementation 都需要独立 implementer/reviewer。原审计线程必须明确 PASS；visual、two-client、Iris/Sodium、20-reload、performance 等已有 Gate 仍需要其原始证据。
