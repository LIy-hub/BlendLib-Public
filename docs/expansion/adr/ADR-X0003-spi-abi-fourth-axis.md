# ADR-X0003: SPI ABI/capability protocol is a fourth experimental compatibility axis

状态：Proposed / Experimental

## Context

既有 contract baseline 只冻结三轴：asset schema/Profile、BlendLib API SemVer、Fabric adapter target。api-stability 同时明确 current internal SPI/implementation 没有消费者兼容承诺。

## Proposal

若未来引入跨 provider 的受控 SPI，则单独版本化 SPI ABI/capability protocol：

1. 它独立于 API SemVer、asset schema/Profile 和 adapter target。
2. v1/v1.x 默认 absent/disabled，不由 internal class、extensions_used 或 public Java visibility 反向激活。
3. capability 选择不通过现有 v1 semantic animation payload 传输。
4. breaking provider/schema/wire 变化使用新 protocol/schema/API 版本和迁移路径。

## Consequences

当前受控 Experimental 实现将 host contract 标为 `1.1.0`；`1.0.0` 仅保留为历史 capability data-plane metadata。该版本化不改变前三轴，也不把 ordinary callback `Error` 的 exact-object terminal policy 变成可由历史 offer 协商回退的行为。X0 只记录这个第四轴的 proposal。它不能被引用为既有 v1 Accepted 事实，也不为 consumer 承诺 ABI。
