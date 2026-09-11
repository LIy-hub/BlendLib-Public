# Beta.3 verification record

Verified on 2026-09-11 using Fabric Loader 0.19.5 and the latest matching Fabric API for each target.
All 15 runtime JARs passed a source build, existing target tests, metadata/class inventory checks,
an isolated packaged server startup and clean stop (exit 0), and a production Fabric client startup.
Client reload logs report `published=true` and `diagnostics=0`. Each test client was then terminated;
normal client shutdown and in-game visuals were not tested. No client entered a world.

The packaged dependency declarations were tested with Fabric Loader's actual version parser:
the minimum and a newer patch version are accepted, while an older version is rejected.
The standalone regression check is [VerifyDependencyRanges.java](../../versions/VerifyDependencyRanges.java).

| Minecraft | Target tests | Server | Client startup/reload | Runtime SHA-256 |
| --- | --- | --- | --- | --- |
| 1.21.1 | 2 | PASS, exit 0 | PASS | `5ba8da28034faef6a7eea9a8b1d0d75ca476a0592edfe686d18b3da074064441` |
| 1.21.2 | 2 | PASS, exit 0 | PASS | `8b6a23b35e03e9ed3f3a5265967bf2532c4a7ef71bcd85317a96c357e5855da0` |
| 1.21.3 | 2 | PASS, exit 0 | PASS | `8dfc48a1b0cb73b43872568e0422329851f50e94b8d7382e76074eea7d50fba7` |
| 1.21.4 | 4 | PASS, exit 0 | PASS | `7b319a0baba393ddd84732665d5f438bb3290b916d1ccb018e8812d9b9f561bb` |
| 1.21.5 | 2 | PASS, exit 0 | PASS | `c7bd65b1bbb35d6c21fff368a0d939e1a0689020dbe83a15905e62b5d13e3397` |
| 1.21.6 | 2 | PASS, exit 0 | PASS | `6dae51968a0b8eaa8023f9cd07493d14513bde943d0e4024e2e7a53ed09b794c` |
| 1.21.7 | 2 | PASS, exit 0 | PASS | `b5e98fc712ac4b6cd513329fa4c60fac1ac9cc5d5d90c4c4166f3a9089bf18c6` |
| 1.21.8 | 2 | PASS, exit 0 | PASS | `bde51eeda675c41e8d3bfbc648c4228e74c9405ce886f346b5de318854384cc8` |
| 1.21.9 | 3 | PASS, exit 0 | PASS | `efd0dcbf31af4d62301cfaa853ee9feee3ed24b31540d457c83ca924d210c2f3` |
| 1.21.10 | 3 | PASS, exit 0 | PASS | `80326a33ceaba9cc030c41d3e7e76424cb8688f5e63a7f4cd7097d0c61b3728e` |
| 1.21.11 | 3 | PASS, exit 0 | PASS | `e5ca3cf00bd2b0d7035ff19cd842f3cbd38e30c3ff8c0b5683c17bf2b9ab9870` |
| 26.1 | — | PASS, exit 0 | PASS | `9694f1dba70a39e3a5a43e30f644a0f40ba9d31f4dcebbc5ae1b6ed957a1d829` |
| 26.1.1 | — | PASS, exit 0 | PASS | `caa2ea508d7105fa6e7048c27fa7382d3dcc7a29459ee0dad35fac5c200ba792` |
| 26.1.2 | — | PASS, exit 0 | PASS | `859337ead3113c2adc2f849b349dde84b8243147a7eb51b5d02059600d87e1a3` |
| 26.2 | 3 | PASS, exit 0 | PASS | `2d3baec35dec8d872a0dc0cb5a7c86c540679b3f91c25aeff36411a21d99babc` |

The baseline `check buildRelease` also passed. Its release output was isolated under
`build/beta3-baseline` so the pre-existing Beta.1 local Maven artifacts remain available.
The initial baseline attempt rejected those older files as extra publication artifacts; the isolated output
resolved that check without removing the old artifacts. Offline authentication service errors are separate
from BlendLib initialization. A first client log matcher was corrected to accept the intervening
`stale` and model-count fields in the actual reload log; its original receipt remains in local evidence.

Local logs and process receipts are under `build/beta3-work`; release assets are under `build/release-beta3`.
The published `verification-manifest.json` binds each runtime to its startup receipts and SHA-256.
`SHA256SUMS.txt` covers the 15 runtime and 15 source JARs.

See [release notes](beta3-release-notes.md) for dependencies and installation. The Beta.2 verification
and publication record remains available [separately](multiversion-progress.md).
