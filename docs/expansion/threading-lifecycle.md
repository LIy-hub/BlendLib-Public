# 线程、Generation 与 Provider 生命周期 Proposal

状态：既有 prepare/apply/publish/retire 架构护栏受当前 GOAL 保护；下列 provider lifecycle 细节为 Proposed，尚未实现。

## 阶段与 owner

| 阶段 | 唯一 owner/线程 | 允许 | 禁止 |
|---|---|---|---|
| register | adapter bootstrap owner | 静态 provider metadata、重复 ID 预检 | I/O、GLB/JSON parse、GPU、late consumer mutation |
| discover | controlled registry owner | 读取 immutable metadata、收集候选 | 执行 provider、依赖 discovery order、访问 world |
| freeze | registry/generation owner | version/priority/conflict validation，生成 immutable plan | late registration、可变 provider selection、submit-time retry |
| prepare | reload background worker | descriptor/GLB I/O、parse、CPU validation、bounded CPU fallback preparation | GPU/Minecraft client objects、world/entity access |
| apply | adapter client thread | 创建/更新 backend resources，绑定 immutable plan | 长时间 parse、阻塞 I/O、partial publish |
| publish | registry owner | atomic generation swap | 按模型逐个暴露半成品、改变旧 snapshot |
| retire | generation/resource owner | 停止新绑定，等待已钉住 snapshot drain，释放旧 CPU/GPU/provider lease | 释放仍被 snapshot 使用的资源、跨 session 重用状态 |
| close | adapter shutdown owner | 幂等释放 provider/backend/registry 资源 | 触发 reload、发送 wire、访问已关闭 world |

## Scope 与隔离

每个运行对象需要显式 scope：

- ResourceGeneration：immutable model map、diagnostics、selected capability plan、backend handles。
- Instance：typed instance key、model key、generation、controller/pose cache；不得只用裸 entity id。
- World/session：connection identity、dimension/level identity、unknown-target queue；不得跨 disconnect、换维度或 session 复用。
- Provider lease：provider id、selection plan、generation refcount、close-once state。

已接受的 ADR-021 receiver guard 是此隔离的现有 client-private 例子：在 receipt 时捕获 level，deferred callback 只在 exact same level 时进入 runtime/unknown queue。它不修改 payload，也不提升 P6 Gate。

## Failure containment

provider metadata、version、priority 或 validation 失败必须在 freeze/prepare/apply 前后被归类，而不是在 submit 中才显现：

1. 一个 provider failure 只影响依赖该 selection 的 model/resource；其他已发布 generation 保持可用。
2. REQUIRED failure 产生明确 diagnostic/missing handle，不发布半初始化 handle。
3. OPTIONAL 只能使用预先说明的语义等价 fallback；否则同样 fail closed。
4. provider exception 不得泄漏为 world/session 全局状态，不能遗留 cache、GPU handle 或 deferred callback。
5. retire/close 必须在 snapshot pin 释放后执行，且 close 必须幂等、可诊断。

## 热路径规则

无论未来是否接受 SPI，submit、animation advance、sample、socket query 都只消费已经 freeze 的 immutable state。它们不得做 I/O、JSON/GLB parse、provider discovery、registry lookup、world mutation 或隐式 GPU/CPU backend 转换。
