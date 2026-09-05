# X7 T2a2: Pre-CAS D1 resource adoption

## Scope

This isolated implementation applies the frozen T2a2 contract to the canonical client reload
package only. It replaces the historical post-publication attachment seam with one explicit
`PendingGenerationTransaction` payload prepared before publication:

- `CPU_ONLY` contains the exact candidate and no physical aggregate.
- `COMPLETE_SET` contains that exact candidate and one nonempty exact
  `CompletedGenerationResourceSet`.

No production caller activates real GPU resource construction in this tranche. The reload listener
still creates `CPU_ONLY`; a future creator must compose a complete aggregate before calling D1 and
may not extend an already-published generation.

## Ownership sequence

The transaction starts `PREPARED_CALLER_OWNED`. A direct caller abort leaves its exact set caller
owned until D1 makes the one cleanup claim, which transitions through `CLAIMED_ABORT` and ends
`ABORTED`. A normal transaction transitions through `CLAIMED`, `POLICY_FROZEN`, and `PUBLISHED`.
The transaction monitor performs the transaction claim and the aggregate's
`CALLER_OWNED_COMPLETE -> CLAIM_OWNED_COMPLETE` move in the same critical section.

D1 first acquires publication admission, then preallocates the lifecycle record, exact set
reference, derived resource totals, CAS proof, and close bookkeeping. After claim it freezes policy
and validates under the D1 monitor. Normal publication inserts and adopts the same record, marks
its physical resource ready, enables its one CAS proof, and only then invokes registry CAS.

Losers use that same record as a detached D1 cleanup record. Its `resourceReady` proves D1 physical
ownership; its separate `publicationReady` remains false, so it cannot consume the CAS proof.
Foreign/current winners are never retired or mutated by a loser. Exact aggregate close stays in the
existing D1 fence/retry state machine: a partial failure retains only failed leaves, while final
shutdown terminal failures forbid normal retry.

## Boundary and evidence expectations

The removed forms have no deprecated alias: post-publication attach entry points, attachment
admission/barrier types, attachment slots/freeze flags, callback transfer, and generic transaction
or D1 `List<Runnable>` cleanup are absent from production source. `PublicationAdoptionProbe` is
package-private and immutable; it carries no raw resource.

The focused implementation suites cover pre-CAS ownership, cutoff, direct abort, claim replay,
caller-close race, foreign/duplicate/stale/CAS losers, `Throwable` preservation, D1 close retry,
final fence terminal behavior, listener composition, source boundaries, and retained public ABI.
Their command/XML results remain implementation evidence only; an independent fresh review is
required before integration.

## Waiting

Fresh independent T2a2 review, final X7 integration, real client/resource-reload/runtime/visual
validation, hardware/performance evidence, and all GPU activation remain **WAITING**.
