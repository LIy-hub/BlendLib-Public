# Minecraft release-version verification

Verified on 2026-09-07/08 against the Beta.1 source baseline. Beta.2 supplies exact-version Fabric
JARs for all 15 official Minecraft Java releases from 1.21.1 through 26.2; snapshots are excluded.
The legacy port was implemented in a separate agent worktree and reviewed/integrated by the coordinator.
The coordinator implemented modern ports and performed all packaged-client checks.

## Final artifacts

Every row passed a complete source build and packaged JAR metadata/class checks, an isolated loopback
server reaching readiness then stopping with exit 0, and a production Fabric client startup with
`published=true, diagnostics=0`, followed by normal window close. Test counts below are target-native
unit tests. A dash means no new target-specific tests; the unchanged root baseline `check` also passed
(39 tasks: 3 executed, 36 up-to-date). That check is not a claim that all baseline tests were rerun.

| Minecraft | Build | Target tests | Server | Client reload/close | Runtime SHA-256 |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | PASS | 2 | PASS, exit 0 | PASS | `8c45f73ece19a33ee5d7d322544d2701c51e44a689a448acd9d294a8b3d867c6` |
| 1.21.2 | PASS | 2 | PASS, exit 0 | PASS | `b4f759b2f6690461c697c53e6356e458fb8add823fd843d432ee4b7e55b8057c` |
| 1.21.3 | PASS | 2 | PASS, exit 0 | PASS | `9cc5611b8526974f3cf3c67d61ef753d9c86102a6c22a54cdd017049b645c6be` |
| 1.21.4 | PASS | 4 | PASS, exit 0 | PASS | `8dd6f4d6113e0fb8425c0f63f1a201adf87562e73592314ec0fbd33399cae6bb` |
| 1.21.5 | PASS | 2 | PASS, exit 0 | PASS | `92cd6c64a6e90b8c44aef655ece58b05afc4172ff53715a760d74f3c81d857f9` |
| 1.21.6 | PASS | 2 | PASS, exit 0 | PASS | `99e7cdb12f057b844de4252a313398201076db1ea583da8214285be72fa32c8c` |
| 1.21.7 | PASS | 2 | PASS, exit 0 | PASS | `7816988cbcc66a64774f8c0c509c58f2724f386e9cfbb27dd532eaa078635f34` |
| 1.21.8 | PASS | 2 | PASS, exit 0 | PASS | `ccb4c5bf4a132c5a33f6d5a5ee39586cf4ad7d4f5f2b69c8a6b5c3233575319b` |
| 1.21.9 | PASS | 3 | PASS, exit 0 | PASS | `565b2d9f10595789cc1f61897375f29ba7c6a312b0a96efe36ee2691f709ee7e` |
| 1.21.10 | PASS | 3 | PASS, exit 0 | PASS | `101aacd800d0a37121f435482da84c7b9e01a8072a0a5a0a0a559de648c3d5ef` |
| 1.21.11 | PASS | 3 | PASS, exit 0 | PASS | `9e4dafa29bb5f6cee2ce60ba45aa8c150b4ba8a36d70b3577320f46a07de49cb` |
| 26.1 | PASS | — | PASS, exit 0 | PASS | `bbd5df63029d521bdb58a8e93ee238521c63c7c518154e5fcd727d9b4f8f45ad` |
| 26.1.1 | PASS | — | PASS, exit 0 | PASS | `5f11b14b2d4b83e90a829338cb3b6b84b303314b7945f006ff75089b831beae8` |
| 26.1.2 | PASS | — | PASS, exit 0 | PASS | `01a1cabe230da43222fe30baab23d3f31ed7f09868601c7d81cac9b82f5d83de` |
| 26.2 | PASS | 3 | PASS, exit 0 | PASS | `e9539358a7b6567e7ce0e7e057e46aefb5ba643e58043c9fd04d11ea2c9aeab0` |

## Evidence and boundaries

Legacy build/server evidence was produced under `D:/BlendLib-legacy/versions/legacy/`; modern evidence
and all client receipts are under `D:/BlendLib/versions/modern/`. Build outputs and logs remain local
and ignored. Release assets include `verification-manifest.json` with exact receipt locations and
`SHA256SUMS.txt` covering all 15 runtime and 15 source JARs. The staged files are byte-identical to
the artifacts referenced by their final server and client receipts.

Clients stayed at the initial UI. No client entered a world. Client process exit was observed, but
the Windows process API did not supply a numeric exit code; only server exit 0 is claimed.
Offline authentication errors and the Windows OSHI/Perflib diagnostic are classified separately.
No new in-game visual, multiplayer gameplay, Iris/Sodium, experimental GPU, or performance acceptance
is claimed. See [release notes](beta2-release-notes.md) and
[legacy API adaptation](../../versions/legacy/API_ADAPTATION.md) for platform-specific boundaries.

Earlier failed artifacts/attempts are preserved in local evidence and are excluded from the final
matrix. These include legacy 1.21.8 eager shader import failure, obsolete initial 1.21.4/.6/.7 hashes,
modern 1.21.9/.10/.11 production class-pin failures, and the initial 26.2 final-present/shutdown adapter.
Final artifact hashes above bind the subsequent passing checks. The legacy details are recorded in
[VERIFICATION.md](../../versions/legacy/VERIFICATION.md).

## Publication record

Publication target: [LIy-hub/BlendLib-Public](https://github.com/LIy-hub/BlendLib-Public),
CurseForge project [1638315](https://www.curseforge.com/minecraft/mc-mods/blendlib).
Only the task-owned source delta is transferred to a fresh public checkout. Public baseline
`c72bb55fd35e423a8a08f496439e222471208159` has the same tracked tree as the private source baseline;
private commit history and the user's existing dirty public checkout are not transferred or reset.

At this record's creation, GitHub publication is pending. CurseForge 26.1.1 file `8831617` is
Under Review; its CDN download SHA-256 matches the final artifact. File `8831612` is an earlier
duplicate in Baking state. The 26.1.2 file `8831576` is Processing File. Remaining targets are pending
upload. Processing, Baking and Under Review do not establish public approval. Final publication
receipts will be added after verification.
