# ADR-X0001: X0 layer classification preserves v1 guardrails

状态：Accepted by current GOAL authority

## Context

现有 v1 契约已经区分纯语义 API、asset schema、同一 Minecraft target 的 adapter API 与 internal implementation。当前 dirty WIP 还包含技术上 public 的 loader、registry、backend 和 sync 类型；技术可见性不能自动形成消费者 ABI。

## Decision

X0 采用 stable API、controlled SPI、internal implementation 三层分类：

- stable API 只承诺小而纯的语义 surface，且不能泄漏 mutable core/GLB/Minecraft internal/render handle；
- controlled SPI 在被接受前只能处于 experimental proposal，provider 选择受 adapter 控制；
- internal implementation 无消费者兼容承诺，即使当前 Java visibility 较宽。

此分类不改变既有 v1 hard guardrails：strict GLB、api/core pure Java、Identifier adapter-only、resource/instance/snapshot 分离、submit 无 I/O/parse、semantic wire、presentation-only event、client isolation、no raw GL/private reflection、per-MC JAR、required unknown reject、CPU fallback。

## Consequences

X1 必须在公开新 facade/SPI 前增加 API-surface/consumer-boundary 证明。当前 WIP public 类型不能被示例或第三方当作稳定入口。本 ADR 不接受任何新的 provider ABI、descriptor payload、Profile 或 wire 字段。
