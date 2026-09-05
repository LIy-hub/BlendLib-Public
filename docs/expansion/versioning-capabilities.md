# 四轴版本与 Capability Proposal

状态：前三轴是既有 v1 事实；第四轴及本页 capability 语义均为 Proposed/Experimental。这里没有启用 provider、payload、schema 或 wire 代码。

## 兼容性轴

| 轴 | 当前事实 | v1.x 默认 | v2/Experimental 候选 | 拒绝/迁移规则 |
|---|---|---|---|---|
| Asset schema/Profile | format_version=1；严格 rigid_v1/skinned_v1 | additive 文档不能扩大已接受输入；required extension 仍拒绝 | 新 schema/profile，例如 skinned_v2 或 morph_v1 | breaking asset 使用新 schema/profile；旧 Profile 长期可读 |
| BlendLib API SemVer | 纯语义 API major 内兼容；adapter API 仅同 MC target | 仅兼容 additive API，且不得暴露 impl | 经审查的 Experimental facade/SPI 可先 opt-in | breaking API 升 major，并给迁移说明 |
| Fabric adapter target | 每个 MC target 独立 JAR | 不承诺跨 MC binary compatibility | 26.2 或其他 adapter 独立验证 | 相同 core fixture/API compile fixture，可有不同 renderer |
| SPI ABI / Capability protocol | 当前受控 Experimental host contract 为 1.1.0；没有已接受消费者合同 | 默认 absent/disabled；不得由 extensions_used 激活 | 独立 protocol version、provider ABI、capability envelope | 未知/不兼容/冲突 required 必拒绝；historical 1.0.0 不可由宽请求静默进入 current X6；wire/schema 变化另行版本化 |

第四轴的存在不修改现有三轴，也不使 internal SPI 获得消费者兼容承诺。

## Proposed capability identity 与版本范围

每个 proposal request/offer 使用现有 BlendResourceId grammar 的 canonical namespace:path ID，而不是 Minecraft Identifier 或裸 String。blendlib:* 名称保留给基础能力；第三方不得抢占。

一个 capability request 必须声明：

- canonical id；
- strictly bounded semantic version range [minInclusive, maxExclusive)；
- REQUIRED 或 OPTIONAL；
- 预先文档化的等价 baseline fallback（仅 OPTIONAL 可有）。

一个 offer 必须声明 canonical provider id、capability id、provided semantic version、不可变 priority 与支持边界。latest、无界范围、registration order 和运行时猜测版本均不合法。

## Proposed negotiation 与确定性

register 后的 discover 只读取 metadata，不执行 provider。freeze 前的选择算法如下：

1. 先筛选 id 和 version range compatible 的 offer。
2. 再按 priority 降序、provider id 的 ordinal lexical 升序形成稳定候选顺序。
3. 重复 provider id 立即是 registration error。
4. 对同一 capability 的最高优先级多个不同 provider，视为 conflict；REQUIRED fail closed，OPTIONAL 也不得以 discovery order 静默取胜。
5. REQUIRED 的 unknown、range mismatch、conflict 或 provider validation failure 使关联 model/resource 不发布，并给结构化诊断。
6. OPTIONAL 的 unknown/mismatch 只可使用已声明且语义等价的 baseline fallback；无等价 fallback 同样不发布该能力依赖的模型。

freeze 的产物是一个不可变 selection plan。register/discover/freeze 之后不允许 late registration；下一个 resource generation 才能重新协商。

## Proposed diagnostics 与降级

可为未来 ADR 保留 CAP-001 required unsupported、CAP-002 range mismatch、CAP-003 provider conflict、CAP-004 provider failure 等概念名称；它们不是已经分配的 v1 stable error code。未有接受 ADR 前，现有 required descriptor/GLB extension 仍使用既有 EXT-001 语义。

安全降级规则：

- 只在 prepare/apply/publish 前选择 CPU 或标准 public Minecraft path。
- fallback 必须预先证明与请求能力的语义等价，并受相同 hard limit/diagnostic 约束。
- submit、animation advance、socket query 中不得临时切换 backend、重新 discovery 或吞掉失败。
- 没有等价 CPU/standard fallback 时使用 missing handle/明确诊断，不做 silent material、culling、animation 或 gameplay downgrade。

## 迁移边界

当前 v1 descriptor 的 generic extensions object 只作对象形状验证；其 payload 不保留、不暴露、也不执行。nonempty extensions_required 继续 deterministic reject。任何保留 bounded immutable payload、required/optional capability envelope、network negotiation 字段或高级 Profile 都必须先有独立 ADR、schema/protocol 版本、migration/negative fixtures、实现和独立审查。

当前 host protocol 的迁移也必须显式区分 generic registry data-plane 与 adapter host selection：历史 `1.0.0` offer 可仍被 generic request/offer 工具表示，但 current X6 在 discovery 前将 caller range 与 `[1.1.0, 1.2.0)` 相交，并在 freeze 后验证 selected offer 仍在该范围。版本 range 不会协商回旧的 callback containment；每个 `Error` 都在 required terminal cleanup 后以同一对象逃逸，ordinary non-`Error` failure 才是 bounded diagnostic input。
