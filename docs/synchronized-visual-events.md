# Synchronized visual markers

Synchronized animation markers use the existing descriptor `events` and client visual-event
handler. They are presentation hints for sounds, particles, trails, or socket effects. They
must never decide damage, collision, hit detection, drops, or item consumption. Server gameplay
remains authoritative; no marker packet or server event scheduler is introduced.

## Delivery semantics

- Each instance/model generation owns a `SynchronizedVisualEventCursor` alongside its pose clock.
- Accepting a strictly newer semantic sequence establishes a silent baseline at the resolved
  controller time. Initial tracking and corrections do not replay historical events, including
  time-zero events at the baseline. Subsequent loop or `next` entry events are delivered normally.
- An ordinary forward sample delivers crossings in `(previous time, sample time]`, preserving
  descriptor speeds, loop boundaries, `next` chains, and the controller's stable marker ordering.
- Repeated extraction, equal timestamps, backward clock samples, and stale sequences emit nothing.
  A backward sample does not lower the high-water mark. A newer sequence starts a new playback
  epoch, so markers after its new baseline may legitimately fire again.
- Update buckets collect markers at their next due pose update. Cull gaps retain only the most
  recent **one controller-time second**, after the synchronized speed multiplier and before each
  descriptor state's speed. Precisely, delivery examines `(max(previous high-water, sample time
  minus 1 second), sample time]`. No historical occurrence count is computed or returned, and
  there is no deferred backlog. Older history is deliberately dropped; this is not guaranteed delivery.
- The existing fixed loop, transition, and visual-event budgets still apply. If that bounded
  interval exceeds a budget, the entire interval is dropped without partial emission or retry.
  The high-water mark still advances to the sample time, including when zero markers are returned.
  Invalid assets and invalid timeline inputs still surface as errors.
- Leaving synchronized playback deactivates the cursor. Unload, disconnect, model rebind and
  generation retirement discard the owning clock, so no cursor survives an instance lifecycle.

Pose synchronization still resolves absolute time without advancing through every old loop.
The independent event cursor does not mutate pose state, pose revisions, server state, or render
submission. Events are consumed once during extraction; frozen snapshots do not replay them.

## Tests

`SynchronizedVisualEventCursorTest` covers loops and boundary ordering, descriptor speed and
`next`, skipped frames, late tracking, sequence rejection, corrections/restarts, duplicate
extraction, rewinds, deactivation, long culls, budget overflow, terminal states, and validation.
