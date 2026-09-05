# X7 shared generation lifecycle test evidence (D1 repair candidate)

## Scope and status

This evidence records the D1 repair's focused verification status only. It does not establish a
real render owner, a GPU upload/draw, performance, hardware, Iris/Sodium compatibility,
client/server runtime behavior, or manual visual acceptance. Those gates remain **WAITING**.
The D1 repair itself remains **WAITING** a fresh independent review.

## Focused coverage

`ClientGenerationResourceOwnerTest` covers:

| Behavior | Test coverage |
|---|---|
| complete CPU candidate freeze and one active CAS publication | `completeCpuCandidateFreezesAndPublishesThroughTheRegistrysSingleActiveReference` |
| transaction replay and concurrent duplicate publication have one owner claim and no active callback close | `replayedPublishedTransactionIsRejectedWithoutClosingItsActiveCallback`, `concurrentDuplicatePublicationHasOneClaimWinnerAndOneRetirementClose` |
| abort-first stops before CAS; publish-first rejects abort and retires the displaced generation | `abortFirstStopsPublicationBeforeCasAndTransfersCleanupOnlyOnce`, `publicationClaimRejectsAbortAndStillRetiresTheDisplacedGenerationAfterCas` |
| freeze, stale, or shutdown failure after a claim transfers one retryable callback obligation | `freezeFailureAfterClaimTransfersItsCallbackOnceAndLeavesRetryableCleanup`, `staleFailureAfterClaimTransfersItsCallbackOnceAndLeavesRetryableCleanup`, `shutdownAfterClaimTransfersItsCallbackOnceAndLeavesRetryableCleanup` |
| other failure or stale candidate leaves the old generation active | `abortedCandidateLeavesThePreviouslyPublishedGenerationActive`, `staleCandidateIsAbortedWithoutASecondActiveGenerationReference`, `staleUnpublishedCandidateClosesCallbacksAndReleasesItsRecord` |
| retirement waits for a lease and late release queues the fence | `retirementWaitsForTheExactLeaseAndQueuesFenceOnlyAfterLateRelease` |
| non-zero close attempts, failure/retry, and no duplicate successful callback | `closeFailureRetriesOnlyTheFailedCallbackAndUsesNewNonzeroAttemptId` |
| stale and cross-generation rejection | `staleAndCrossGenerationHandlesCannotAcquireNewLeases` |
| no installed render owner executes no experiment callback while CPU publication remains complete | `defaultOwnerPublishesCompleteCpuCandidateWithoutExecutingAnyGpuExperimentCallback` |
| registry/client-stop drain of current plus retiring generations | `registryCloseDrainsCurrentAndRetiringGenerationsAfterTheirLeasesRelease` |
| terminal records release owner retention across normal reloads and reject old leases | `normalReloadCyclesReleaseTerminalOwnerRecordsAndRejectEveryRetiredGeneration` |
| closed-registry, explicit-abort, and duplicate-current cleanup | `closedRegistryUnpublishedCandidateClosesCallbacksAndNeverBecomesActive`, `explicitlyAbortedCandidateRetainsOnlyFailedCleanupUntilRetrySucceeds`, `duplicateCurrentCandidateUsesDetachedCleanupWithoutRetiringTheActiveGeneration` |
| same failure object reaches retryable close failure rather than self-suppression escape | `sameThrowableIdentityTransitionsToCloseFailedAndRetriesOnlyUnclosedCallbacks` |
| unpublished callback queue rejection remains diagnosable and retryable | `unpublishedQueueFailureRemainsRetryableUntilItsCallbackCloses` |
| permanent atomic owner binding across two registries, loser-only cleanup/retry, and initial-adoption rejection | `foreignRegistryPublicationCleansOnlyLoserAndCannotRetireWinner`, `concurrentForeignRegistryPublicationHasOneOwnerBindingAndOneWinner`, `foreignOwnerCleanupFailureRetriesThroughLoserCloseWithoutTouchingWinner`, `foreignBoundInitialGenerationFailsBeforeSecondRegistryRecordsArePublished` |

The existing reload tests continue to cover descriptor/GLB preparation and full immutable backend
handle creation. D1 does not add a `GpuBuffer` public API or invoke B1 resource factory/device code
from the reload listener.

## Command and observed result

The one permitted focused command for this ownership repair was:

```text
gradlew.bat :blendlib-fabric-client:test --tests com.liy.blendlib.fabric.client.reload.ClientGenerationResourceOwnerTest --no-daemon --console=plain
```

It ran once after the atomic owner-binding repair, returned outer exit `0`, and printed `BUILD SUCCESSFUL in
11s`. The current XML is one suite / **27 tests / 0 failures / 0 errors / 0 skipped**, timestamp
`2026-08-29T19:51:30.690Z`, SHA-256
`DD4FF37837EEC6DA49E42D3747D2366952C11B4165E4B3E9C6331DBEF96E6714`.

The preceding 23-test XML belongs only to the `6173bef` candidate, and the preceding 16-test XML
belongs only to the earlier `42e4301` candidate; both are retained as history and are not used as
verification for this ownership repair.

No module/root/release check was run for this repair, and this document makes no module-wrapper
exit claim.

No client or server was launched for this candidate.
