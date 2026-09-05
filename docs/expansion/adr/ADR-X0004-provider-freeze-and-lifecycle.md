# ADR-X0004: Provider selection is deterministic, frozen, and generation-scoped

状态：Proposed

## Context

渲染资源必须在 reload prepare/apply 形成 immutable generation，submit 只消费 snapshot。任何未来 provider 若能在热路径 discovery、重新选择或静默切换，会破坏该边界和可诊断性。

## Proposal

未来 provider 按 register -> discover -> freeze -> prepare -> apply -> publish -> retire -> close 生命周期运行：

- compatible candidates 先按 priority descending、provider id ordinal lexical ascending 排序；
- duplicate provider identity 或 highest-priority conflict 明确失败，绝不依赖 registration/discovery order；
- freeze 生成 immutable selection plan，并钉住 model/generation/snapshot；
- provider failure 在 publish 前隔离为 diagnostic/missing handle 或已声明的等价 fallback；
- retire 等待 snapshot pin drain，close 一次且幂等；
- 不允许 submit/advance/socket query I/O、parse、registry lookup、provider discovery 或 runtime backend switch。

## Consequences

这项 proposal 需要独立 API/SPI 设计、CPU fallback proof、generation/world/session failure tests、resource-release tests 与新审查。它不授权当前 source 修改或任何 P Gate 提升。
