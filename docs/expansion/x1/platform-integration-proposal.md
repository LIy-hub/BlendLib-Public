# X1 Platform Integration Proposal

Status: proposal for a dedicated integration/adapter task. X1 intentionally does not modify any Fabric entrypoint, platform module, metadata, root build file, or shared progress ledger.

## Required future adapter wiring

The version-specific client adapter should implement `PlatformAdapter` and install it during its own controlled bootstrap, after its client-only services exist but before consumer registration is accepted. It should:

1. expose a canonical provider id and immutable capability offers;
2. translate only the typed `HostRegistrationSpec<H>` into its own adapter-private renderer registration representation;
3. return a semantic `RegistrationReceipt` containing its id, host kind, and model key;
4. create a fresh `CapabilityRegistry` per reload/generation, then invoke `discover` and `freeze` before model preparation;
5. construct one `ProviderLifecycleSession` only from the frozen publishable plan; run prepare/apply/publish at existing lifecycle boundaries;
6. attach `ProviderLease` ownership to immutable snapshot/resource generation retirement and release it only after snapshot drain;
7. request retirement when an old generation stops accepting pins; after its final pin drains, invoke generation retire callbacks, release that session's ownership, and let shared identity ownership perform provider-global close only after the last owner releases;

The adapter must retain all existing rules: raw platform identifiers remain adapter-only, common/server classes must not load client classes, and the current CPU/standard path remains the safe baseline. No registration callback may do resource lookup, descriptor/GLB parse, provider discovery, renderer submit, or network synchronization.

## Proposed ownership handoff

| Needed work | Suggested owner | Why X1 does not edit it |
|---|---|---|
| implement/install real `PlatformAdapter` | future X4/X8 or dedicated Fabric integration task | adapter version/API specific and outside X1 ownership |
| map stable host specs to entity/block/item/GUI/VFX implementations | X4 | X4 owns host adapters |
| map selected backend/material provider plan to generation resources | X6/X7 plus integration | requires adapter resources and validated fallback behavior |
| invoke lifecycle around reload generation | integration task | touches shared client reload/lifecycle code |
| change entrypoint, metadata, build or shared ledger | integration task only | explicitly forbidden to X1 |

No shared-file change is required to compile or test the X1 pure API. This is therefore a precise handoff, not an unmade hidden dependency.

## Failure and diagnostics handoff

- A missing adapter remains stable `BLENDLIB-X1-REG-001`; it must not be replaced by an untyped null failure.
- A required capability failure means the dependent generation/model is not published. Existing active generations remain available.
- An optional fallback is valid only when the adapter documents and proves equivalence before publish. A warning diagnostic alone is not permission to silently alter material, culling, animation, or gameplay semantics.
- A provider exception must be retained as its X1 CAP diagnostic at the generation/resource boundary; it must not first surface during submit.
- A future current-X6 reload owner must request the current Experimental host-compatible line `[1.1.0, 1.2.0)`. `X6MaterialProviderGeneration` also intersects any caller-supplied range with that line and verifies frozen selections, so a broad caller request cannot promote a historical `1.0.0` offer into the current lifecycle. The generic X1 registry remains a separately versioned metadata data plane.
- Platform bootstrap, host identity, and registration callbacks run under the X1 two-phase callback guard: no owner monitor is held during external code, and nested/concurrent install, register, or uninstall is rejected with bounded `REG-005` before it can replace the outer adapter identity or leak an ownership handle. Duplicate target equality is evaluated from an operation-scoped snapshot without caller `hashCode()` and committed only after epoch/adapter revalidation.
- Platform integration must enforce the 256-code-unit canonical X1 identity boundary when reading adapter/provider metadata or receipts. Failure diagnostics omit an invalid identity and never retain exception messages or `toString()` output.
- A platform adapter may share a provider object with overlapping lifecycle generations. Install/session construction acquire identity owners; uninstall/session retirement release them. Platform code must not call provider-global `close()` directly. Ordinary non-`Error` callback failures become bounded diagnostics without exposing their throwable as a stable public cause; every adapter callback `Error` first blocks new operations, atomically detaches adapter and registration state, releases its ownership exactly once, and only then rethrows that same original object. Failed-install rollback consumes the close result before ending the operation: terminal primary keeps identity and suppresses secondary cleanup; terminal cleanup outranks an ordinary primary and suppresses it; ordinary primary plus containable cleanup produces one stable diagnostic retaining both safe type names. The detached adapter is never called again, and rollback close cannot reattach while the operation is active.
- Integration must keep existing P/Audit visual, synchronization, Iris/Sodium, reload, and performance Gates unchanged. Pure-Java success here is not platform or visual evidence.
