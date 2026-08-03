# X9 Experimental Asset Fixtures

Status: experimental fixtures; no stable compatibility guarantee.

This directory contains declarative, repository-owned X9 fixture expectations.
It does not contain runtime `.blend`, FBX, OBJ, Draco, Meshopt, or KTX2 input.
The Java tests construct a compact in-memory GLB so bounds and non-finite input
paths can be exercised without introducing additional third-party assets.

- `descriptor-matrix.json` records the positive, negative, and fallback cases.
- `golden/validation-summary.json` defines the expected feature counters for
  the positive morph candidate.
- `disabled-codecs.json` makes the no-enable decision machine-readable.
