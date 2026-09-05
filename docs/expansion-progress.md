# BlendLib Expansion Progress

**Current X7 closeout state (2026-09-05):** T2a2, T2a3, T5, T6a, T6b, metrics, T3b, T4/T4p,
and T3c are integrated on the formal branch. The first final gate is permanently retained as
559 tests / 2 failures; independent Terra test-only repair 7ef959be fixed the two stale test
contracts, and the sole remediation gate passed 97 XML / 559 tests / 0 failures / 0 errors /
0 skipped with outer exit 0. This is a **bounded formal integration candidate / PENDING fresh
final Sol review**, not an X7, GPU, runtime, visual, or performance PASS. X8 remains **LOCKED**.
See [the canonical ledger](expansion/integration/x7-final-r1.md).

状态：X0 已获 /root/x0_independent_review_r4（gpt-5.6-sol/max）独立 Verdict: PASS、无 findings；X1、X5、X9 的单轨与其有界 formal r2 PASS 均保留各自 metadata-only fresh-review 边界。X2 仍为 **isolated internal runtime reviewed PASS / integration REVIEW**，X3 仍为 **isolated r13 PASS / formal integration REVIEW**，X4 仍为 **isolated candidate reviewed PASS / formal integration REVIEW**；这些状态均不解锁其各自下游。X6 的历史 `e66e57a7b2cedeb0ac36cc988bf2b29aaf2e82b7` FAIL / 禁止集成记录保留；R15 candidate `2d8a34e1bee13f7e010d5ac798858e27d13b4e1f` 已唯一线性进入正式 `eb6be3e6e834fef5fc44eb5e8a0e3ee11c225746`，并获 fresh formal integration **PASS（0C/0H/0M/0L）**。X7 的 policy、GPU B1、benchmark、D1、D2a 与 D2b 六个 isolated candidates 均有 bounded independent PASS；其 r1 formal integration 已获 bounded structural **PASS（0C/0H/0M/0L）**，T1a trusted owner/pass-host 也已独立 **PASS（0C/0H/0M/0L）**并快进正式树。T1b generic D1 attachment 保留 R1 FAIL 与 remediation 后 fresh-rereview 边界；T1c 的 R1 **FAIL（0C/1H/0M/0L）**保留为历史，而 repair candidate `b3660bb6007ba89847461f5f0cc3c43f4d0d7478` / tree `dacf56e6080c50f13cbdbc6cad9e376196c8d9b6` 已获独立 R2 **PASS（0C/0H/0M/0L）**，并已精确快进至正式分支。R2 的 source/static audit 与保留的 5-suite/30-test focused evidence（hash-list `092B2B2AA0478D105FFBC2FCD66A7135A948966DC0FC5BD946D41AD9D0722D1D`）均不提升任何 runtime/visual/hardware/performance Gate。X7 仍不是 complete、GPU/runtime 或 performance PASS。X8 仍为 **LOCKED / PENDING**，直至 X1--X7 全部完成。上述状态不改变既有 P0--P8、visual、sync、reload、Iris/Sodium、hardware、performance 或许可证 Gate。

**T3a formal update (2026-09-04):** the independently reviewed candidate `b218aa090b39909b053a8b6645285cafd68af5e6` / tree `541b4223fa08f9708c2d66bd354a50e2222ff3f7` was fast-forwarded exactly from formal parent `c76777069ce00845171eeadc3a8f1f26b897aa53`. This is a bounded T3a infrastructure integration, not an X7 completion or any runtime, visual, compatibility, or performance PASS; X8 remains **LOCKED / PENDING**.

**T2a1 formal integration update (2026-09-05):** after preserving the preceding T3a fast-forward, the exact reviewed cumulative source chain `95b3c25a836b55ab99755f8a563e0a2eb5ff604a` -> `318fbccd7d237a1d8d25e2ae2ee4b9290496ce80` -> `1f52641b67c27d97977b52a608983a8aa6516608` (source tree `c9c88bd6496dd29ab6c84402958c90eeed9813ae`, source base `c76777069ce00845171eeadc3a8f1f26b897aa53`) was cherry-picked in that order from the T3a-advanced formal base. This records an **integrated T2a1 candidate pending fresh Sol formal integration review**, not an X7 or runtime/visual/performance PASS; X7 remains incomplete and X8 remains **LOCKED / PENDING**.

## Capture and existing Gate separation

本台账引用 source 的实时 capture inventory，不替换 docs/implementation-progress.md。capture 时的既有 phase 事实为：P0/P1/P2 PASS；P3 REVIEW；P4 WAITING；P5 IN_PROGRESS；P6/P7/P8 WAITING。

同一 X0 capture-time/history 还继承独立审计整体 FAIL。其当时六项未关闭 repair 为：

1. descriptor state speed upper bound 与 loop visual-event cycle/event budget；
2. synchronized payload speed 与 descriptor speed 的 correction/continuous-advance 公式一致性；
3. deferred callback 的 level A -> replacement level B 防护；
4. strict GLB accessor normalized/min/max 语义与 required use；
5. skin hierarchy/root、skeleton ancestry、duplicate non-zero influences、inverse-bind shape/count；
6. rigid/skinned animation 超出 rest-pose bounds 的 culling。

ADR-021 在实时 capture 为 Accepted，仅授权 receive-time exact ClientLevel object guard 和 client-private regression seam；P6 仍为 WAITING，wire/API/version/Gate 不变。上述审计需要各自独立 implementer/reviewer 证据和原审计线程明确 PASS；X 轨道不替代它。

Post-capture live-base note: the current source ledger at `D:\BlendLib\docs\implementation-progress.md:71-124` records a later independent overall PASS for the six-item audit-repair Gate, and the current expansion baseline includes those repair changes. This corrects neither the historical capture record above nor any P3--P8 status: P3 remains REVIEW; P4, P6, P7, and P8 remain WAITING; P5 remains IN_PROGRESS. The audit result closes only the six findings; it is not a v1, whole-project, or X1/X5/X9 integration PASS, and the candidate does not use it to promote a phase Gate.

## X0--X9 ledger

| Track | Task | Agent / model | Branch / worktree | Commit | Independent review | Tests / evidence | Gate | Dependency / risk |
|---|---|---|---|---|---|---|---|---|
| X0 | inherit stable source snapshot; freeze expansion contracts, ADRs, ownership and ledger | /root/x0_baseline_contracts_r2; gpt-5.6-terra/ultra | agentloop/blendlib-expansion; D:\BlendLib-agentloop | checkpoint `8874d0b5b85a8e5e07270d185f3c0f0e6053feac`; docs `da198a160b8c52a7511a6f063af12f36009e9aa2`; r1 TSV `183cfba1f72d3b63028bee5519efd5ddaab469f9`; byte correction `0102a9fea4e01abda19cb1975a46360a9cdf02ed`; evidence `b5d7093330fe12d1edba4580e3dadffcf3e4d728`; strict JSON `8d3aa0f4535970ebcfd5f55267d0811b213b7066`; r4 PASS metadata closure (this commit) | r1 FAIL: malformed TSV. r2 FAIL: raw blob EOL loss, broken local symref, missing ledger link. r3 FAIL: only malformed empty-array field. `/root/x0_independent_review_r4`; gpt-5.6-sol/max; `Verdict: PASS`; no findings. | [TSV validation](expansion/manifest/eligible-files-validation.json); [byte correction](expansion/manifest/checkpoint-byte-preservation-correction.json); r3 fresh Java 25: `clean check` exit 0, 285 tests/0 failures; `buildRelease` exit 0, 62 tasks, 17 release hashes/0 mismatch. r4 independently recomputed all 9 strict JSON, old/corrected blobs/manifests, metadata/fsck, and inspected r3 build evidence; it did not rerun the docs-only build. | PASS | X0 expansion track only; X1/X5/X9 X0 review dependency unlocked. P3 REVIEW, P4 WAITING, P5 IN_PROGRESS, P6/P7/P8 WAITING; the six-repair audit FAIL is capture-time history, while the live audit-repair PASS closes only those six findings. |
| X1 | public Facade, Builder and controlled SPI | /root/x1_public_api_impl, including /root/x1_public_api_repair_r8 lineage; gpt-5.6-terra/ultra | agentloop/x1-public-api; D:\BlendLib-worktrees\x1-public-api | HEAD 7e3fedc860175197af993758ef1d04aa1eb57fb1; 10/10 commits; 0 merges | /root/x1_independent_review_r10_final; gpt-5.6-sol/max; Verdict: PASS; no findings | 636-test, consumer, API/ABI, jdeps, release, and X0 replay evidence | single-track PASS; formal X1/X5/X9 r2 PASS in bounded scope; metadata-only closeout awaits fresh review | SPI remains Experimental; no real platform adapter or reload integration |
| X2 | animation system v2 | X2 internal runtime candidate; gpt-5.6-terra/ultra | agentloop/x2-animation; source candidate `9035b25649f8ef91b95afb354b76be877a9d7ce0`; formal tree `agentloop/blendlib-expansion` | originals `f90ef2b`, `4d99a5d`, `1182ed1`, `1123297`, `9035b25`; integrated `19b8259`, `a8b90e3`, `5066a85`, `0d7ce69`, `8d35adc`; all 5 stable patch-ids equal; 0 merges | `/root/review_x2_animation_v2_r5`; gpt-5.6-sol/max; Verdict: PASS; no findings | r5: targeted 8 suites/48 tests; full 106 suites/747 tests; jdeps/source/classload boundary; independent quaternion 2,000-permutation, 512-diagnostic, and 256/257 identifier evidence. r1 integration replay: Java 25 targeted 8/48 and full 106/747, boundaries, release/consumer checks; [evidence](expansion/integration/x2-r1.md) | isolated internal runtime reviewed PASS; integration REVIEW | Proposed/Experimental internal runtime only. New payload, actual client lifecycle/reload, X4/X6 consumption, real network, visual, 20 reload, Iris/Sodium, and hardware performance remain WAITING. Requires fresh integration review before X3 dependency advances. |
| X3 | procedural bones, attachments and visual events | X3 formal integrator; gpt-5.6-terra/ultra | source `agentloop/x3-procedural-events`; `D:\BlendLib-worktrees\x3-procedural-events`; formal `agentloop/blendlib-expansion`; `D:\BlendLib-agentloop` | r1 six originals remain integrated; r13 candidate `5a6246b94564ade0361a98bb95fcfb601f508db4` nine originals map linearly into formal tree with stable patch-id 9/9, 0 merges; mapping in [r2 evidence](expansion/integration/x3-r2.md) | r1 isolated r4 PASS retained. r13 source/compatibility review is isolated **PASS (0C/0H/0M/0L)**; fresh formal integration review is PENDING. | Java 25 r13 replay: focused 9/116, full Core 29/255, X2 task-bound 3/26 + 2/11 + 3/14, X3 11/93, consumers 4/9, X4 8/59 + 2/7, X1 1/2, root seven-module 124/912; Java boundary/probe in [r2 evidence](expansion/integration/x3-r2.md) | **isolated r13 PASS / formal integration REVIEW** | Proposed/Experimental only; no X2 integration-Gate change or downstream unlock. Fresh integration review, real Minecraft/client/reload/visual/network/Iris/Sodium/hardware performance and P3--P8 remain WAITING/unchanged. |
| X4 | new host adapters | X4 candidate / formal integrator; gpt-5.6-terra/ultra | source `agentloop/x4-hosts`; formal `agentloop/blendlib-expansion` | source `01ea2a5141d2748c5ac2eca4b7b168e4297e5baf`; 11/11 originals linearly integrated (mapping in [r1 evidence](expansion/integration/x4-r1.md)) | `/root/review_x4_host_adapters_r9`; gpt-5.6-sol/max; Verdict: PASS; no findings | isolated r9: client-first 41 suites/188 tests and full 108 suites/765 tests; formal r1 replay is recorded in [evidence](expansion/integration/x4-r1.md) | isolated candidate reviewed PASS; formal integration REVIEW | Proposed/Experimental client-host candidate only. No shared renderer/bootstrap/reload/network wiring or real-client evidence; X4 r1 did not integrate its then-unreviewed X3/X6 dependencies. The later bounded X6 R15 formal review is PASS, but does not promote this separate X4 REVIEW gate or unlock X8. |
| X5 | Blender native toolchain | /root/x5_blender_toolchain_impl plus /root/x5_handle_txn_stabilize; gpt-5.6-terra/ultra | agentloop/x5-blender; D:\BlendLib-worktrees\x5-blender | HEAD d5a422b1b3063b190a10e38347ae94e9d2a21f72; 17/17 commits; 0 merges | /root/x5_postcommit_review_r3; gpt-5.6-sol/max; Verdict: PASS; no findings | System Python and Blender Python 97/97; real Blender; root validator/cache matrix; Java XML/release/X0 evidence. Remediation runner passes in direct/caller pwsh 7 and Windows PowerShell 5.1 mode with exit 0/sentinel 1, and its four matching failure injections fail closed. | single-track PASS; formal X1/X5/X9 r2 PASS in bounded scope; metadata-only closeout awaits fresh review | interactive Blender UI/viewport and Minecraft listener/client remain WAITING |
| X6 | Variant, RenderLayer and material extension; R15 terminal epoch/order repair touches X4 contracts/tests/docs only | R15 candidate / formal source integrator; gpt-5.6-terra/ultra | source `agentloop/x6-r15-terminal-order-repair`; formal `agentloop/blendlib-expansion` | candidate `2d8a34e1bee13f7e010d5ac798858e27d13b4e1f` -> formal `eb6be3e6e834fef5fc44eb5e8a0e3ee11c225746`; one parent each; equal tree `c2608f98018ff7f4d7df9d615766ef843db1f2b6` and patch-id `670034b92eba083b7b8945ba2c96e55363add347` | historical R3 FAIL retained; isolated committed-candidate R2 PASS; fresh gpt-5.6-sol/max formal integration **PASS (0C/0H/0M/0L)** | reviewer did not run Gradle; independently checked two manifests, retained XML and release SHA17. Gate 1 `BEHAVIOR_PASS_WITH_EXIT_EVIDENCE_LIMITATION` has XML 1/6/0F/0E/0S and no numeric OS exit; final buildRelease also lacks retained wrapper numeric OS exit. Both limitations are nonblocking; no wrapper exit-0 claim. [r1 evidence](expansion/integration/x6-r1.md) | **fresh formal integration PASS (0C/0H/0M/0L)** | no X6 dispatcher/reload/entrypoint/renderer/resource/shared-owner wiring; X6 remains signal-only; runtime/visual/reload/Iris/Sodium/hardware/performance and P0--P8 gates WAITING/unchanged. X7 r1 structural and T1a reviews are now bounded PASS, but X7 remains incomplete and T1b awaits review; X8 remains LOCKED/PENDING. |
| X7 | performance, LOD and advanced rendering backend | policy/GPU/D1/D2a/D2b/benchmark plus T1a/T1b/T1c implementers and `/root/x7_formal_integration_r1` | source X7 worktrees; formal `agentloop/blendlib-expansion` | r1 formal `191a555`; reviewed/formally integrated T1a `8e0e58d`; T1b M1 remediation `3914f89`; reviewed/formally fast-forwarded T1c `b3660bb6007ba89847461f5f0cc3c43f4d0d7478` / tree `dacf56e6080c50f13cbdbc6cad9e376196c8d9b6`; [r1 evidence](expansion/integration/x7-r1.md) | six isolated reviews PASS; r1 bounded structural PASS 0C/0H/0M/0L; T1a PASS 0C/0H/0M/0L; T1b R1 FAIL 0C/0H/1M/0L with rereview pending; T1c R1 immutable FAIL retained and R2 PASS 0C/0H/0M/0L | r1 module build client 71/423 + Showcase 29/106/1 skip; T1a 7/45; T1b R1 6/50 plus M1 repair 1/11; T1c retained 5/30 focused XML, hash-list `092B2B2AA0478D105FFBC2FCD66A7135A948966DC0FC5BD946D41AD9D0722D1D`; R2 ran no Gradle/runtime command | **bounded structural/T1a/T1c PASS; T1b REVIEW; X7 incomplete** | T1c is fail-safe shutdown infrastructure only. Production allocation/attachment, GPU draw/pass completion, real live-resource shutdown, full budget-LOD-animation projection, runtime/reload/visual/Iris/Sodium/hardware/performance remain WAITING. X7 not complete; X8 stays locked. |
| X7-T3a | Minecraft 26.1.2 pass-host, static probe pipeline, and ordinary submission-receipt boundary | bounded formal integration task | formal `agentloop/blendlib-expansion`; `D:\BlendLib-agentloop` | candidate/formal `b218aa090b39909b053a8b6645285cafd68af5e6`, tree `541b4223fa08f9708c2d66bd354a50e2222ff3f7`, exact parent `c76777069ce00845171eeadc3a8f1f26b897aa53` | independent R1 **PASS (0C/0H/0M/0L)**; `D:\BlendLib-review-artifacts\x7-t3a-pass-host-review-r1-20260904-224758168\FINAL_REVIEW.md`; report SHA-256 `5962A5913B964428645F2972AF8045AED7DB457C2DCD3379EEBDF10C5A4AF361`, manifest SHA-256 `1BCF9AD811C334A093CCE07D94D914E6ADC05F3511A8A74DA0071BDCDB6014DF` | first supplied run: 13 selected test paths = 12 pass / 1 test-path selection failure, not 13 green; corrected Target pinpoint rerun 4/4, XML SHA-256 `D1A312D8B396C29314D2547866C55D0349DE8019C55ABA78CD7E0FC1AD39BE79`; Host/Receipt hashes are supplied only (`79488C5061784B1ABDFF20EFC667978EF891C1C7EF52EF921865937B5AFEE061` / `96A3F96E064503468460A36BF30F3E31F30028D92C94E4B6BBF04851F5851EBE`) | **T3a formal fast-forward complete; X7 incomplete** | Exact reviewed fast-forward permits no duplicate module/client/server/runtime run. The complete module gate is deferred to final X7 formal integration and runs once. Real client shader compile, target/pass/draw, D1 consumption, batching, instancing, skinning, visual, Iris/Sodium compatibility, hardware/JFR and performance remain WAITING; X8 remains locked. |
| X7-T2a1 | canonical pre-publication generation-resource island | formal integration task | source `agentloop/x7-t2a0-canonical-bridge`; formal `agentloop/blendlib-expansion`; `D:\BlendLib-agentloop` | source base `c76777069ce00845171eeadc3a8f1f26b897aa53`; reviewed chain `95b3c25a836b55ab99755f8a563e0a2eb5ff604a` -> `318fbccd7d237a1d8d25e2ae2ee4b9290496ce80` -> `1f52641b67c27d97977b52a608983a8aa6516608`, source tree `c9c88bd6496dd29ab6c84402958c90eeed9813ae`; formal picks `d1f92e9a222b8cae0c177521837ba289d90f2e92` -> `cc95d3c4e3c67f827ef08310022d101487a30069` -> `b080869392542a05d0411a81142c349d6b26c24d` | R1 **FAIL (0C/1H/3M/0L)**, report SHA-256 `CE57EB18C3C6F1DE67FD0236B7B80B4B1FDD2BC016451344F062AE63A0080549`; R2 **FAIL (0C/0H/1M/0L)**, SHA-256 `7CBBC0115FC1C402F89E3858EFD0D5BD78C8C7BEB104C432CB4FFD0F0FEBCE73`; R3 **PASS (0C/0H/0M/0L)**, SHA-256 `15B36DC9115DFCEA215A7F1A68584FFE5FA96180313892B7F22119B719558731` | R3 retained one fresh focused command: 3 XML suites / **19 green**. The earlier first repair run was 21 tests with one culture-sort failure; only its boundary-class rerun was green, so this is not described as all 21 green. No Gradle/module/client/server run was added by this integration. | **integrated candidate / fresh Sol formal integration review PENDING; X7 incomplete** | T2a2 must still implement pre-CAS D1 claim/adoption/publication. Live GPU allocation/upload/draw, client/runtime/reload/visual, Iris/Sodium, hardware/JFR and performance remain **WAITING**; X8 remains **LOCKED / PENDING**. |
| X8 | platforms, ecosystem, examples and final integration | `/root/x8_integration_impl`; gpt-5.6-terra/ultra | `agentloop/x8-platform-ecosystem`; `D:\BlendLib-worktrees\x8-platform-ecosystem` | reviewed source `148a2c9`, tree `f08c58d`; historical `d4eee37` and `4bbdfd3` FAIL records retained; [closure](expansion/x8/static-review-closure.md) | `/root/x8_final_static_review`; gpt-5.6-sol/max; **PASS (0C/0H/0M/0L)** for immutable source `148a2c9` | independent read-only Git/source/static parsing; zero test/build/compile/converter/client/server execution | **static source PASS; dependency/release Gate LOCKED / PENDING** | GOAL requires X1--X7 complete. X6 now has bounded formal integration PASS, but X2/X3/X4 remain REVIEW and X7 is not complete; therefore X8 remains locked; every dynamic gate and NeoForge binding remain WAITING. |
| X9 | advanced asset Profile design | /root/x9_profiles_impl, including /root/x9_profiles_repair_r4 and /root/x9_repair_r5; gpt-5.6-terra/ultra | agentloop/x9-profiles; D:\BlendLib-worktrees\x9-profiles | HEAD bb933d3be25c0077c8b6254476fe20e360d8e3e6; 7/7 commits; 0 merges | /root/x9_independent_review_r6; gpt-5.6-sol/max; Verdict: PASS; no findings | Historical single-track record: 334 integration tests. Current integration reran schema 5-valid/10-invalid and validator 54/0/0/0. Khronos JUnit is 3 tests over 8 direct unique payloads, not 25; external Khronos Validator unavailable (retained evidence gap). ABI, release, and X0 replay evidence | single-track PASS; formal X1/X5/X9 r2 PASS in bounded scope; metadata-only closeout awaits fresh review | Proposed/Experimental validation candidate; no runtime binding; Draco/Meshopt/KTX2 disabled; rigid_v1/skinned_v1 unchanged |

## Current X7 final formal integration

| Property | Current verified state |
|---|---|
| Formal candidate | 7ef959be52ee69971dc70041a430a489e1d0ceac / fec817aecf6f3a564ae3052ae691eb6a057ad730 / clean |
| Integrated bounded packets | T2a2, T2a3, T5, T6a, T6b, metrics, T3b, T4/T4p, T3c; historical X7 r1/T1/T3a records retained |
| Latest packet review | T3c R4 PASS 0C/0H/0M/0L; D:\BlendLib-review-artifacts\x7-t3c-host-wiring-review-r4-20260905-111727988\FINAL_REVIEW.md; SHA-256 D61604DED5D9858BC7089859CA5028E4E5DA8474E4B7053CBB642502A01E14E0 |
| Gate / next step | First gate failure retained; sole remediation gate passed 97 XML / 559 tests / 0F / 0E / 0S / outer exit 0; PENDING fresh final Sol review; X8 LOCKED |

## X1/X5/X9 formal remediation integration

- Formal base: `181f00c8752bfd0bd336232083c6acad9c0efa4e` in `agentloop/blendlib-expansion`; the historical candidate cherry-picks 10 X1 + 17 X5 + 7 X9 commits (34 total).
- At reviewed head `19fc6572f6c5f2c1e08dd2a38fe51509bda0909c`, formal reviewer r2 counted 39 linear commits and 0 merges from X0 base `4749b2dd7f1efbeafb2b4f056c70d65161cea1e6`; all 34 original and 3 remediation patch-ids matched (37/37). Pre-amend `9555dce` is not an ancestor.
- The formal tree additionally contains the three reviewed repair mappings `c00be375745e5c3c4e69536a7792a7aaa119a624` -> `c20b71f519203bca42dca85401d7bd265d9ba004`, `d94e4ac5270eaf10897989c5d8170acaf031af34` -> `ca4ec77ba5ea1137eff2cd11f5ee362dcb411787`, and `fcd33bf2e92602eeaef98866bc463435118664ae` -> `ed5e7eea7f34f925ea11917e566f7ab55015e87a`; all three have equal patch IDs and were conflict-free.
- Inputs were captured clean: X1 `7e3fedc860175197af993758ef1d04aa1eb57fb1`, X5 `d5a422b1b3063b190a10e38347ae94e9d2a21f72`, X9 `bb933d3be25c0077c8b6254476fe20e360d8e3e6`. X0 baseline inventory blob stayed `393cf904c766d8b3d72f18a885f0f58563747bbc`.
- Review chronology: original `/root/integration_x1_x5_x9_review_r1` was `Verdict: FAIL` with H/M/L; `/root/integration_x1_x5_x9_repair_review_r1` was `Verdict: FAIL` for the capture-time/current-source documentation Medium and retained `D:\b` Low; supplied repair review r2 was `Verdict: PASS` with no High/Medium and still disclosed that Low; fresh `/root/formal_x1_x5_x9_review_r2` (gpt-5.6-sol/max) then returned formal `Verdict: PASS` with no Critical/High/Medium and one X5 documentation Low corrected here.
- Formal reviewer evidence: formal tree clean at `19fc6572f6c5f2c1e08dd2a38fe51509bda0909c`; Java 25 clean check 98 suites / 699 tests / 0 fail/error/skip; X1 API 350, consumer 7, API JAR 70 classes, `jdeps` only `java.base`, forbidden references 0; X5 system/Blender Python 97/97, real Blender 10/10, runner success in four pwsh/Windows PowerShell direct/caller cases and four wrong-model failures closed; X9 schema 5 valid/10 invalid, boundary 32 accepted/33 rejected, validator 54/54, Khronos-derived 3/3 over 8 direct payloads; buildRelease 62/62, Local Maven 5/5, SHA256SUMS 17/17, 5 primary plus 3 nested JARs safe. No-local clone `D:\BFR2-20260803-024543153` is retained.
- Shared wiring: `build.gradle.kts:24` applies `gradle/blendlib-x5-asset-validator.gradle.kts` exactly once. Root `validateBlendlibAsset` is visible/callable, remains opt-in, and is not attached to `check` or `buildRelease`.
- Current formal evidence and retained gaps are in [integration/x1-x5-x9-r1.md](expansion/integration/x1-x5-x9-r1.md) and [its remediation record](expansion/integration/x1-x5-x9-r1-remediation.md), including the external Khronos Validator GAP.
- Gate: formal X1/X5/X9 integration review r2 is `PASS` only in the stated scope. This metadata-only commit now requires a fresh gpt-5.6-sol/max review; until it passes, the coordinator must not formally unlock X2/X4/X6 or promote any phase Gate.

## X2 r1 integration

- Formal start/base: `ecf234f04d37b3e84326c81b3e523b834eff6558`, clean `agentloop/blendlib-expansion`; input branch `agentloop/x2-animation`, candidate `9035b25649f8ef91b95afb354b76be877a9d7ce0`.
- Review input: `/root/review_x2_animation_v2_r5` (gpt-5.6-sol/max) returned `Verdict: PASS` with no findings for the isolated internal runtime candidate.
- Integration: exactly five supplied single-parent commits were cherry-picked in order, conflict-free, into `19b8259687c916efb97aab1f610e237c5f0454eb`, `a8b90e3ed2ab78a34d41d60bfa9d7d742de14a7b`, `5066a8535c2f0ff0e0678e0697d05cfe20edb4a3`, `0d7ce694356c0b8665b22bc6c3cf8da10b32a831`, and `8d35adce9031e422c7c4d7f24e8cf5708e21d632`. Parent-chain and stable patch-id checks are 5/5; the integrated range has 0 merge commits.
- Java 25 integration replay: X2 targeted XML is 8 suites/48 tests/0 failures/0 errors/0 skipped; full required `check --rerun-tasks --no-daemon --max-workers=1 --console=plain` is 106 suites/747 tests/0 failures/0 errors/0 skipped for its seven invoked modules. `buildRelease --rerun-tasks` and existing core/consumer/showcase boundary tasks exit 0; release `SHA256SUMS` is 17/17 matching. Full commands, XML-scope definition, boundary evidence, audit, retained risks, and mappings are in [X2 r1 integration evidence](expansion/integration/x2-r1.md).
- Gate: **X2 isolated internal runtime reviewed PASS / integration REVIEW**. A fresh gpt-5.6-sol/max integration review is required. This does not declare real network payload, actual client lifecycle/reload, 20 reload, single/dual-client visuals, X4/X6 application, Iris/Sodium, or hardware performance. All remain WAITING.

## X4 r1 formal integration

- Formal start/base: clean `agentloop/blendlib-expansion` at `2197d328e8bbde0bda2b27645abddfc5128a718e`; source base `ecf234f04d37b3e84326c81b3e523b834eff6558`; input branch `agentloop/x4-hosts`, candidate `01ea2a5141d2748c5ac2eca4b7b168e4297e5baf`.
- Review input: `/root/review_x4_host_adapters_r9` (gpt-5.6-sol/max) independently returned `Verdict: PASS` with no findings for the isolated X4 candidate. This replaces neither the historical r5 FAIL record nor the required formal integration review.
- Integration: exactly 11 supplied single-parent commits were cherry-picked in source order, conflict-free. Parent-chain and independently recomputed stable patch-id checks are 11/11; the integrated source range has 0 merge commits. Full source-to-formal mapping and validation evidence are in [X4 r1 integration evidence](expansion/integration/x4-r1.md).
- Formal Java 25 replay includes a no-local/no-hardlinks client-first clone (44 suites/202 tests/0 fail/error/skip, with no Showcase output created), focused X4 client 59/0/0/0, Showcase X4 7/0/0/0, X1 rollback 2/0/0/0, full `check` 116 suites/813 tests/0 fail/error/skip across its seven invoked modules, `buildRelease` 63 tasks, and 17/17 release SHA entries matching. These are automated evidence only.
- Gate: **X4 isolated candidate reviewed PASS / formal integration REVIEW**. A fresh gpt-5.6-sol/max formal X4 integration review is required; this metadata record is not that review. It does not accept a v1 API, client bootstrap, shared renderer wiring, network semantics, real server/client runtime, visual output, F3+T/20 reload, Iris/Sodium, GPU, or performance.
- Exclusions at this historical X4 r1 boundary: X3 `cd54837` and historical X6 `e66e57` were not ancestors and were not integrated there. The separately recorded later R15 X6 formal review below is a bounded PASS; it does not promote this separate X4 REVIEW gate, and it unlocks only X7 readiness, not X8. No source worktree, review clone, quarantine, `D:\BlendLib`, Minecraft server, or FabricMod path was modified by this integration.

## X6 r15 formal integration PASS

- Historical X6 failure history is retained: R3 is **FAIL (0C/4H/2M/0L)**, R4/R5 are non-verdict designs, and the six-test/one-intended-failure result is a valid RED. These checkpoints are not erased by later source-candidate evidence.
- The isolated committed R15 source candidate `2d8a34e1bee13f7e010d5ac798858e27d13b4e1f` received fresh committed-candidate R2 **PASS (0C/0H/0M/0L)**. Phase 1 then cherry-picked that one reviewed commit, conflict-free, into formal commit `eb6be3e6e834fef5fc44eb5e8a0e3ee11c225746` atop `2e08dc8661f0dc13d75370e8aa60a970524e3183`. Candidate/formal tree `c2608f98018ff7f4d7df9d615766ef843db1f2b6` and stable patch-id `670034b92eba083b7b8945ba2c96e55363add347` are equal; exact path scope is 16 modified paths and zero merges.
- Phase-1 evidence is [X6 r1 source-integration handoff](expansion/integration/x6-r1.md): `FINAL_PHASE1_REPORT.md` SHA-256 `03B260A184F29EAE859D3DE593A190F989847495796454A4D3452ED2CD29CE39` and self-excluded manifest SHA-256 `EF01F098CB373B370253BAB9D2D980472948B6423E97BFD8DBCEBC611BBAB51F`.
- The authorized reduced formal source-gate artifact has `FINAL-RESULT.json` SHA-256 `F30EB6C651825A7DA8B0A35362CCC228F3428CDA9CD7968098DD9C3114232F3C` and self-excluded manifest SHA-256 `E61FC2D4B7C4A7EF1506B618E75667EA47C9A2AF353F33C80DC29EF1CE3C9364`. Gate 1 is **BEHAVIOR_PASS_WITH_EXIT_EVIDENCE_LIMITATION**: `BUILD SUCCESSFUL` and fresh XML 1 suite / 6 tests / 0 failures / 0 errors / 0 skipped, but no retained numeric OS exit. The final reviewer classifies that limitation as nonblocking and does **not** claim wrapper OS exit 0. Gate 2's root source gate retains true `Process.ExitCode` and durable recorded exit both 0, `BUILD SUCCESSFUL`, fresh XML 134 suites / 1,033 tests / 0 failures / 0 errors / 0 skipped, 134 unique `module|suite` keys, and 0 duplicates.
- Fresh independent gpt-5.6-sol/max final formal integration review is **PASS (0C/0H/0M/0L)**. Its report is `D:\BlendLib-review-artifacts\x6-r15-formal-integration-final-review-20260829-204846364\FINAL_REVIEW.md` (SHA-256 `77A5FB8283D2E4EF3BB21EB5056EE2E764F8CE1BD7719735E5EF56E26FA8A2C6`); its self-excluded validated manifest is `D:\BlendLib-review-artifacts\x6-r15-formal-integration-final-review-20260829-204846364\VALIDATED_MANIFEST.md` (SHA-256 `73724F1DC98E7912EC10E231D54435AFBFC8FDA1C2C4DCE3C3AA486BD745D6CD`).
- The final reviewer ran **no Gradle**. Instead, it independently rehashed and strictly parsed the two retained self-excluded evidence manifests, retained XML, and release SHA17 evidence, while auditing the committed source, tests, docs, and Git objects. The separate final `buildRelease` evidence likewise lacks a retained numeric **wrapper** OS exit; its nested/root `BUILD SUCCESSFUL`, required task markers, fresh root/Local Maven XML, and matching SHA17/17 were sufficient for the truthful nonblocking `BUILD_BEHAVIOR_PASS_WITH_EXIT_EVIDENCE_LIMITATION` classification. This is never a wrapper exit-0 claim.
- R15 changes X4 terminal epoch/order behavior plus related X4/X6 contract docs/tests. It adds no X6 production dispatcher/reload/entrypoint/renderer/resource/shared-owner wiring; X6 remains signal-only. The original malformed build `REPORT.md` remains **INVALID**; its separate reconciliation is PASS, and both candidate/build evidence are recorded in the r1 handoff.
- Gate: **fresh formal integration PASS (0C/0H/0M/0L)**. At X6 closeout, GOAL's X6 -> X7 edge made X7 **UNLOCKED / READY** only to begin implementation. The later X7 r1 bounded structural review and T1a review are PASS, but X7 remains incomplete and T1b still awaits fresh review. X8 remains **LOCKED / PENDING** until X1--X7 are all complete. No X0--X9 overall, P0--P8, stable-v1, release, client, network, reload, visual, Iris/Sodium, hardware, or performance Gate is promoted.

## X3 r1 formal integration

- Formal pre-base was clean `23d265aceb2a681a9c56535731e7cad87ffb9825`; source base was `2197d328e8bbde0bda2b27645abddfc5128a718e`, source candidate `af198bc1f094be8c79fba13cd2da958cf9695087`, and both source/formal inputs were clean. The supplied range is six single-parent commits with zero merges.
- Exactly those six commits were cherry-picked in order, conflict-free; stable patch IDs are equal 6/6. Java 25.0.2 offline replay passed the X3 core/client matrices and independent r4 lifecycle/compatibility/client probes, X2 compatibility, X4 focused client/Showcase boundaries, both included consumer fixtures, root `check` (122 suites/854 tests, zero failures/errors), and `buildRelease` (63 actionable tasks). Independent release-manifest recomputation is 17/17 matching.
- Scope, exact mappings, preserved failed classpath probe invocation plus corrected probe replay, source/bytecode boundaries, X4 three-line Low closure, retained logs, and WAITING limits are in [X3 r1 formal integration record](expansion/integration/x3-r1.md). **Gate: 正式集成候选，等待 fresh formal review.** This is not a stable API/release/P3--P8 conclusion and does not unlock downstream tracks.

## X3 r2 / r13 formal integration

- Formal start was clean `91e44671d79943a44ede87d5aaecdabac3f8e26c`; source was clean `agentloop/x3-procedural-events` at `5a6246b94564ade0361a98bb95fcfb601f508db4`, whose sole parent is `e6407bc3b190af6e4213a93dae8068f577ae855f`. The initial six r1 X3 commits were already present and were not replayed.
- Independent r13 source/compatibility review is **PASS (0 Critical / 0 High / 0 Medium / 0 Low)** only for the isolated candidate: report SHA-256 `128BD1815BDB5165CAAC0E6E20334A7D9AAB3A2CEFA3788F49569EF3050744E0`; its 634-entry manifest SHA-256 `073745A71F6765B1A248CE7C70E02E4EED2B607F1004BCE2E1346F1597D3AE36` independently rechecked 0 bad. Historical r5--r12 FAIL findings remain retained in the r2 record.
- Exactly nine supplied single-parent source commits were cherry-picked linearly and conflict-free; source-to-formal mapping and equal stable patch IDs are 9/9, with 0 merges. The final historic authorized 26-path union is blob/SHA equal 26/26 to the source candidate. `Transform.java` is stable `1a6ce8fc` blob `a73df9f7a3e7a00ab730954e221b83bd8e93a599`; `Quaternion.java` is unchanged.
- Fresh Java 25 automated evidence is focused 9 suites/116 tests, full Core 29/255, X2 task-bound core/common/client 3/26 + 2/11 + 3/14, X3 core/client 11/93, API+Fabric consumers 4/9, X4 client/Showcase 8/59 + 2/7, X1 rollback 1/2, and strict seven-module root check 124/912; every reported XML count has 0 failures/errors/skips. Java 25 classload/legacy-consumer/jdeps/source/constant-pool/common-isolation and hot-path evidence is recorded with the added-Core classification.
- Baseline API surface is unchanged. Core has 10 disclosed X2-internal `animation.v2` additions; among 228 pre-existing Core classes there are 0 removed/changed descriptors and seven documented additive symbols across five classes. `AnimationV2ObserverTraversal` is Java-public but an immutable Proposed/Experimental internal read-only X2-to-X3 seam with private/package-controlled construction and no public mutator; it does not expose Minecraft/Fabric/version-private or mutable construction surface.
- Gate: **isolated r13 PASS / formal integration REVIEW**. A fresh independent formal integration review is **PENDING**. No statement here is integration PASS, stable v1, real-client/reload/network/visual/Iris/Sodium/hardware performance approval, or a P0--P8 change. At that historical X3 r2 boundary X7/X8 were locked; X6 later unlocked X7, and X7 has since received bounded structural r1 and T1a PASS verdicts while remaining incomplete. X8 remains locked. Full retained evidence, INVALID harness classifications, release-pending freeze statement, and protected-tree fingerprint are in [X3 r2 formal integration record](expansion/integration/x3-r2.md).

## X7 r1 bounded structural formal integration PASS

- Formal base was clean `991d5477d67114c0a3b9676a0411771c781ca140` / tree
  `c6fb2397a39108962eca6b02b150a696e32c03a9`.
- `git merge --ff-only 73fe3c9...` retained the exact 14-commit policy/GPU/D1/D2a/D2b chain.
  The independent 4-commit benchmark chain was cherry-picked in source order to `0596467`,
  `46ac1bd`, `6b24853`, and `f1c2fc0`; stable patch-id is equal 4/4 and benchmark blobs are equal
  18/18. Shared 91 paths and benchmark 18 paths have zero overlap; the formal source head before
  metadata is `f1c2fc0f345ab47c584c183832820c01482e804e`, tree
  `d5d3a2f7f43be5dc0a70ea8b36029df792e68228`, with zero merge commits.
- Six exact isolated final reviews are PASS with no findings. Their report/manifest paths and
  SHA-256 values, full commit/patch mapping, build command, XML digest, artifacts, and exclusions
  are recorded in [X7 r1 formal integration candidate](expansion/integration/x7-r1.md).
- The only formal Gradle invocation exited 0 with `BUILD SUCCESSFUL in 48s`: 100 suites / 529
  tests / 0 failures / 0 errors / 1 host-permission symlink skip. No focused/root/release/client/
  server/game/benchmark command was added.
- Independent review is **PASS (0C/0H/0M/0L)** for the exact bounded structural candidate. Its
  report SHA-256 is `66EB0909DB9F90FD3ECC211FBED06E509A1038E02D873F04861077B9434A8613`
  and manifest SHA-256 is `5E0A5F0397910B8EC0F1B4BE0FC31795BE54AF7CFED7885EE38563AB2001FBB8`.
  Actual GPU draw and production lifecycle close, pass/deferred-callback completion,
  full budget/LOD/animation runtime projection, real reload/client/server/visual/Iris/Sodium,
  trusted hardware/JFR/performance evidence and all unrelated P0--P8 Gates remain **WAITING**.
  X7 is not complete and X8 remains **LOCKED / PENDING**.

## X7 T1a trusted render-owner and pass-host formal integrated PASS

- Exact base: `191a5552f540a2c9689304189931c555d6788ccb`; reviewed candidate/formal head
  `8e0e58db3e1b67fb80adc160bd72d3d177a5fab9`. Independent review is **PASS
  (0C/0H/0M/0L)**; report SHA-256 is
  `B13E265658FD325739C01A7D12F5A7E6925F5D502DF2138CF5A9B079D4A155BC` and manifest SHA-256 is
  `CEB25826AFC876FA2DD6BB763B8BD97FADB0A33290A0167E84E9E3BE9294A58F`.
- The production entrypoint uses one no-argument trusted registry factory fixed to Minecraft
  execute, render-thread assertion, and fenced-task callbacks. The public registry constructor and
  CPU backend remain unchanged. No resource attachment or empty/dormant close callback exists.
- A package-private host registers the exact Fabric `AFTER_SOLID_FEATURES` and
  `BEFORE_TRANSLUCENT_TERRAIN` phases once. Each callback asserts the render thread and returns only
  after its phase-only scope returns; the T1a scope is empty and creates no RenderPass or draw.
- D1 remains the only close/retry truth. X7's nested generation/scheduler is not wired, and the
  existing X4/X6 parents are not duplicated. `CLIENT_STOPPING` starts retirement but is not a
  `queueFencedTask` drain, so live-resource shutdown and GPU allocation remain **WAITING**.
- One offline focused client-test command passed 7 suites / 45 tests / 0 failures / 0 errors / 0
  skipped with process exit `0` and `BUILD SUCCESSFUL in 18s`. No retry, module/root build,
  client/server, benchmark, or network run occurred. Exact evidence and all WAITING gates are in
  [the T1a ledger](expansion/x7/t1a-pass-owner.md) and ADR-X7006.
- Gate: **formal integrated T1a PASS in its bounded scope; X7 remains incomplete**. Allocation/upload,
  target/pipeline, RenderPass/draw, pass receipt, live-resource shutdown, reload/runtime/visual,
  Iris/Sodium, hardware/JFR, benchmark and performance all remain **WAITING**. X8 remains locked.

## Integration rules

- X0 and X9 may propose contract/index changes; only a dedicated integration task may alter shared build/version/entrypoint/metadata or existing docs.
- Each future write task uses explicit-path staging, never git add -A or git add dot.
- A build is not visual, synchronization, reload or performance proof.
- No checkpoint or X documentation declares stable v1, release, push, publication, tag, deployment or license choice.
- The successful build commands are static/automated evidence only. They do not replace real-client visual, dual-client synchronization, Iris/Sodium, 20-reload, or performance evidence. The logs contain inherited serial/deprecation/Javadoc and Gradle-deprecation warnings; no X0 production change was made to address them.

## X0 r1 review and remediation

- r1 returned **FAIL** with exactly one Medium blocker: `docs/expansion/manifest/eligible-files.tsv` was not conventional TSV because its first and last field quotes were malformed.
- r1 otherwise passed checkpoint size/hash evidence, PRE/POST 490 evidence, post-checkpoint drift proof, inherited whitespace semantics, architecture/status scope, and independent Java 25 `clean check` / `buildRelease` verification (62 tasks, 17 release SHA-256 values with zero mismatch).
- The r1 follow-up changes only the malformed TSV, its machine-readable validation record, and references/ledger text. A different reviewer must re-evaluate it; the implementer does not convert this remediation into PASS.

## X0 r2 review and remediation

- r2 returned **FAIL** after raw Git-blob inspection found that the original checkpoint had normalized 22 captured CRLF/mixed-byte paths to LF-only blobs (a 4,321-byte aggregate loss), the target had an invalid local `refs/remotes/origin/HEAD` symref, and the ledger lacked a direct link to the TSV validation evidence.
- The content remediation is the separate commit `0102a9fea4e01abda19cb1975a46360a9cdf02ed`: exact 22-path `.gitattributes` `-text -eol` preservation rules plus explicit re-stage. [Correction evidence](expansion/manifest/checkpoint-byte-preservation-correction.json), [staged raw blob proof](expansion/manifest/checkpoint-byte-preservation-staged-index.json), [raw/semantic diff check](expansion/manifest/checkpoint-byte-preservation-diff-check.json), and the [corrected tree manifest](expansion/manifest/corrected-checkpoint-tree-manifest.tsv) record 22/22 restored raw blobs, 576 historical matches, no unexpected mismatch, and only the intentional `.gitattributes` delta.
- The metadata operation was only `git symbolic-ref -d refs/remotes/origin/HEAD`; [repository-metadata-repair.json](expansion/manifest/repository-metadata-repair.json) records `git fsck --full --no-dangling` changing from exit 2 to exit 0. The repair did not change tracked worktree paths.
- A fresh no-local/no-hardlinks Java 25.0.2 checkout recorded 22/22 raw byte matches, 4/4 descriptor-to-golden SHA matches, clean post-build status, `clean check` exit 0, and `buildRelease` exit 0 in [fresh-checkout-validation.json](expansion/manifest/fresh-checkout-validation.json). These remain automated evidence only.
- This r2 evidence follow-up was not self-reviewed. r2's FAIL remains history; the later r3 strict-JSON review and r4 independent verdict are recorded below.

## X0 r3 review and strict-JSON follow-up

- r3 returned **FAIL** with exactly one Medium blocker: `checkpoint-byte-preservation-correction.json` had only one malformed strict-JSON value, `"unexpected_paths": ,`; the independently recomputed value is the empty array `[]`.
- r3 otherwise passed the corrected 577-path / 2,891,800-byte tree (manifest SHA-256 `31CB92ED7F2C067F35A234B35FC40B4B7E149E626EF84767611DB90B5725963D`), 22/22 captured blobs, 4/4 descriptor-golden checks, raw correction scope, metadata repair, and fresh Java 25 validation: `clean check` exit 0 with 285 tests / 0 failures, and `buildRelease` exit 0 with 62 tasks plus 17 release SHA-256 values / 0 mismatch (`D1B287175D99D1719C6294783D7AA3C04E340AD2BA38B207F18BB7BACBD5F4A1`).
- This follow-up changed only that empty-array token and the X0 review history. r3's FAIL remains historical; the later r4 independent verdict is recorded below.

## X0 r4 independent review

- `/root/x0_independent_review_r4` using gpt-5.6-sol/max returned `Verdict: PASS` with no blocking or nonblocking findings.
- r4 independently recomputed all nine strict JSON files; the old and corrected blobs/manifests; 22/22 restoration and 4/4 descriptor-golden evidence; and metadata/fsck state. It inspected the r3 fresh Java 25 evidence (`clean check` exit 0, 285 tests / 0 failures; `buildRelease` exit 0, 62 tasks; 17 release hashes / 0 mismatch) without rerunning the docs-only build.
- X0's expansion-track Gate is therefore `PASS`, unlocking the X0 review dependency for X1, X5, and X9 only. It does not alter inherited P0--P8 states: P3 remains REVIEW, P4 WAITING, P5 IN_PROGRESS, and P6/P7/P8 WAITING. The separate six-repair audit FAIL is capture-time history; the later independent live audit-repair PASS closes only those six findings and does not make X0, v1, the whole project, or this integration PASS.
- This metadata-only closure records r4's result and will itself receive one fresh gpt-5.6-sol/max metadata review; it does not claim any other track, release, visual, synchronization, reload, performance, or audit PASS.

## X7 T1b generic D1 attachment isolated candidate (2026-08-30)

- Exact base: clean `agentloop/x7-t1b-resource-attach` at
  `8e0e58db3e1b67fb80adc160bd72d3d177a5fab9`. This is isolated implementation evidence; fresh
  independent review is pending and the formal X7 r1 state is unchanged.
- One package-private non-empty complete resource-set type carries exact generation object identity,
  a fixed synchronous physical-close action, and caller-versus-D1 ownership. The public no-argument
  registry remains CPU-only; its only attachment forwarder and every attachment/result/callback
  type are package-private.
- D1 owns one attachment slot per lifecycle record. Admission, retire, supersede, and registry close
  share the existing D1 monitor. Attachment freezes on retirement, rejecting paths preserve caller
  ownership, and caller close versus D1 move has exactly one winner. No empty/dormant callback can
  be registered.
- The set runs first inside D1's already-fenced callback and synchronously closes or throws. A
  failure retains only failed ownership/callback state for the existing D1 retry; successful
  callbacks never replay. Sneaky checked failures leave neither caller nor D1 permanently in a
  closing state and preserve their cause.
- The sole offline focused invocation exited `0` with `BUILD SUCCESSFUL in 18s`; fresh XML is 6
  suites / 50 tests / 0 failures / 0 errors / 0 skipped, including 10 attachment tests. No retry,
  module/root build, client/server, benchmark, or network run occurred. Exact command and exclusions
  are in [the T1b ledger](expansion/x7/t1b-attachment.md) and ADR-X7006.
- R1 review is retained as **FAIL (0C/0H/1M/0L)**: the original race accepted either scheduler
  winner. The M1-only repair adds a package-private pre-monitor admission barrier with a production
  NOOP and proves both fixed orders against the existing transaction claim hook. Its sole focused
  run passed 1 suite / 11 tests / 0 failures / 0 errors / 0 skipped with exit `0` and `BUILD
  SUCCESSFUL in 13s`; the other five suites were not rerun. Fresh independent rereview is pending.
- No production completed-set creator/caller or real GPU resource exists. Allocation/upload,
  source-plan mapping, target/pipeline, `CommandEncoder`/`RenderPass`, draw, pass receipt, bounded
  live-resource shutdown, reload/runtime/visual, Iris/Sodium, hardware/JFR, benchmark and
  performance remain **WAITING**. X7 is not complete and X8 remains locked.

## X7 T1c final-present owned-fence shutdown R2 PASS and formal fast-forward (2026-08-30)

- Exact base is `3914f89f1203ade13e90c4959801b910657eccdf`, tree
  `40f8863b73ccf4ab00b9c7a1b8762cd5448ceb1d`, on isolated branch
  `agentloop/x7-t1c-shutdown-fence`. R1 candidate
  `18d4a96daad26147361c79a80cbd0000a5973252` keeps immutable **FAIL (0C/1H/0M/0L)**
  history; its repair candidate `b3660bb6007ba89847461f5f0cc3c43f4d0d7478`, tree
  `dacf56e6080c50f13cbdbc6cad9e376196c8d9b6`, received independent R2 **PASS
  (0C/0H/0M/0L)**. R2 report SHA-256 is
  `FADB6814BD02E499E9DEC5CE367E15EA477F53EC533495B9DE960AFB4FE8438F`; its self-excluded
  validated manifest SHA-256 is
  `15E3639FCCBD86C25399FBCE434713176EB24463213FD7862C2644FC81C0A369`.
- Formal `D:\BlendLib-agentloop` / `agentloop/blendlib-expansion` is exactly that reviewed
  commit/tree. Its reflog records `merge b3660bb6007ba89847461f5f0cc3c43f4d0d7478:
  Fast-forward` at `2026-08-30 12:20:36 +0800`; no cherry-pick or rewrite was used.
- `QUIESCING` closes package-private creator/device admission before final-path device access. D1
  then seals one immutable shutdown-only attempt or records exact outstanding creator/lease,
  handoff-not-run, or external-normal-fence non-closed state. It never moves existing global FIFO
  work into the final batch.
- A package-private owned-fence adapter pinned to only the exact audited Mojang/runtime raw and
  Loom-processed class hashes, plus two client-only `require=1` Inject callbacks,
  bracket the unique original 26.1.2 `flipFrame`. The original present is neither redirected nor
  repeated. Only a post-present `awaitCompletion(0L) == true` lets D1 synchronously close the sealed
  batch; timeout/error/fallback closes only the owned fence object.
- Ordinary reload continues through its existing `Minecraft.execute -> queueFencedTask` path. A
  normal record already queued/in handoff and a final batch can never both own close authority; a
  late invalidated handoff checks authority before queueing.
- `CLIENT_STOPPING` is an idempotent, non-throwing diagnostic fallback and never claims to drain the
  private FIFO. Public registry descriptors, the CPU-only public constructor, production backend
  selection, X4/X6 parents, pass host, and all `render.x7gpu` classes remain unchanged.
- Focused evidence retains the exact failure/repair sequence: the eight-class run recorded 63/65
  with two contract-evidence failures; the first approved contract-only repair recorded 3/4; the
  final approved contract-only run passed 4/4 with exit `0` and retained XML SHA-256
  `68D024315076EE56F26D0D546ED02CAE354D15682911887AE00BA98905050802`. The other 61 passing tests
  were not rerun, so no green aggregate rerun is claimed. Full evidence is in
  [the T1c ledger](expansion/x7/t1c-shutdown-fence.md). No module/root build, client/server,
  benchmark, performance, or network command ran.
- R2 parsed and rehashed the retained five repair XML suites: 5 suites / 30 tests / 0 failures /
  0 errors / 0 skipped, with filename-sorted hash-list SHA-256
  `092B2B2AA0478D105FFBC2FCD66A7135A948966DC0FC5BD946D41AD9D0722D1D`. R2 ran no Gradle,
  client, server, benchmark, performance-capture, or network command; its PASS is bounded to
  source and retained focused evidence.
- Production allocation/upload/attachment, target/pipeline, real RenderPass/draw, last-use/pass
  receipt, shutdown with real live resources, repeated reload, runtime/visual, Iris/Sodium,
  hardware/JFR, operational bound acceptance, benchmark/performance, X7 completion, and X8 unlock
  remain **WAITING**.

## X7 T3a pass-host, static-pipeline, and ordinary-receipt R1 PASS / formal fast-forward (2026-09-04)

- Exact base was formal `c76777069ce00845171eeadc3a8f1f26b897aa53`, tree
  `3efc5104b81f7a3bcefb11019dd433cae5e5ee40`. The clean isolated candidate branch
  `agentloop/x7-t3a-pass-host` supplied single-parent commit
  `b218aa090b39909b053a8b6645285cafd68af5e6`, tree
  `541b4223fa08f9708c2d66bd354a50e2222ff3f7`. Formal
  `D:\BlendLib-agentloop` / `agentloop/blendlib-expansion` was still exactly that parent and was
  advanced with `git merge --ff-only b218aa090b39909b053a8b6645285cafd68af5e6`; no cherry-pick,
  rewrite, or implementation-semantic change was made.
- The independent T3a R1 reviewer returned **PASS (0C/0H/0M/0L)** for that immutable candidate.
  Artifact: `D:\BlendLib-review-artifacts\x7-t3a-pass-host-review-r1-20260904-224758168\FINAL_REVIEW.md`;
  report SHA-256 `5962A5913B964428645F2972AF8045AED7DB457C2DCD3379EEBDF10C5A4AF361`; self-excluded
  manifest SHA-256 `1BCF9AD811C334A093CCE07D94D914E6ADC05F3511A8A74DA0071BDCDB6014DF`.
- T3a remains client-private, dormant infrastructure only: it adds the bounded pass-host target
  scope, a static rigid probe-pipeline registration surface, and an ordinary owned-fence receipt.
  It does not activate a resource attachment, D1 consumer, real pass, draw, batch, instancing, or
  skinning path, and it leaves T1c shutdown-only behavior separate.
- Evidence remains deliberately non-inflated. The first supplied implementation run selected 13
  test paths and recorded 12 pass / 1 test-path selection failure; it is not described as a
  13-green aggregate. The corrected Target pinpoint rerun is 4/4 with XML SHA-256
  `D1A312D8B396C29314D2547866C55D0349DE8019C55ABA78CD7E0FC1AD39BE79`. The Host and Receipt XML
  hashes are supplied only—`79488C5061784B1ABDFF20EFC667978EF891C1C7EF52EF921865937B5AFEE061` and
  `96A3F96E064503468460A36BF30F3E31F30028D92C94E4B6BBF04851F5851EBE`—because their XML files were
  no longer present for a current rehash.
- This exact fast-forward integration ran only identity/status/tree/scope/diff-check and ledger
  consistency checks; it did not rerun Gradle, a module build, client, server, runtime, or
  benchmark. The full module gate is explicitly deferred to the final X7 formal integration and
  must run only once there.
- Real client initial/reload shader compilation, live target/pass binding and draw, D1 receipt
  consumption, batching, instancing, GPU skinning, real runtime/visual evidence, Iris/Sodium
  compatibility, hardware/JFR, and performance remain **WAITING**. X7 is incomplete and X8 remains
  **LOCKED / PENDING**. **Agent Innovation: None.**

## X7 T2a1 canonical resource island R3 PASS / formal candidate integration (2026-09-05)

- T2a1's immutable design gate is the T2a0 report
  `D:\BlendLib-review-artifacts\x7-t2a0-design-20260904-212222431\FINAL_REVIEW.md`, SHA-256
  `3F70C35C9E19CC88A186C515AD5DA6603D321F331401C6491C9A7F10787AFD57`. The reviewed source range
  begins at `c76777069ce00845171eeadc3a8f1f26b897aa53` and is exactly the three single-parent commits
  `95b3c25a836b55ab99755f8a563e0a2eb5ff604a`,
  `318fbccd7d237a1d8d25e2ae2ee4b9290496ce80`, and
  `1f52641b67c27d97977b52a608983a8aa6516608`, whose candidate tree is
  `c9c88bd6496dd29ab6c84402958c90eeed9813ae`.
- T3a's earlier exact fast-forward is retained as history. From the clean T3a formal head
  `063781ac2bbb6c784b3eddfd4e8eb692d20546b0`, the three supplied T2a1 commits were cherry-picked in
  that exact order, without conflict or semantic alteration, to `d1f92e9a222b8cae0c177521837ba289d90f2e92`,
  `cc95d3c4e3c67f827ef08310022d101487a30069`, and
  `b080869392542a05d0411a81142c349d6b26c24d`. Stable patch IDs are equal 3/3, the cumulative patch ID
  is equal, and all 45 candidate changed paths are blob-equal after the picks.
- Review chronology is preserved, not collapsed: R1 **FAIL (0C/1H/3M/0L)** at
  `D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r1-20260904-222841158\FINAL_REVIEW.md`,
  SHA-256 `CE57EB18C3C6F1DE67FD0236B7B80B4B1FDD2BC016451344F062AE63A0080549`; R2
  **FAIL (0C/0H/1M/0L)** at
  `D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r2-20260904-232114646\FINAL_REVIEW.md`,
  SHA-256 `7CBBC0115FC1C402F89E3858EFD0D5BD78C8C7BEB104C432CB4FFD0F0FEBCE73`; and R3
  **PASS (0C/0H/0M/0L)** at
  `D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r3-20260904-235816042\FINAL_REVIEW.md`,
  SHA-256 `15B36DC9115DFCEA215A7F1A68584FFE5FA96180313892B7F22119B719558731`.
- R3's preserved fresh evidence is exactly one focused command with three XML suites totaling
  **19 green**. The prior first repair run had 21 tests with one culture-sort failure; only the
  boundary-class rerun was green. Therefore no all-21-green claim is made. This formal integration
  deliberately added no Gradle/module/root build, client, server, runtime, or benchmark command;
  the one complete module gate remains reserved for final X7 formal integration.
- Gate: **integrated T2a1 candidate pending fresh gpt-5.6-sol/max formal integration review**. T2a2
  pre-CAS D1 transaction claim/adoption/publication is still required. Real GPU
  allocation/upload/draw, client/runtime/reload/visual evidence, Iris/Sodium compatibility,
  hardware/JFR, and performance remain **WAITING**. X7 is incomplete and X8 remains
  **LOCKED / PENDING**. **Agent Innovation: None.**

## X8 local implementation and shared integration candidate (2026-09-05)

- Exact base: `8df1b90f9ebca03de6a1b78bb0b184e6529aaa5e`, branch
  `agentloop/x8-platform-ecosystem`, worktree
  `D:\BlendLib-worktrees\x8-platform-ecosystem`, implementing agent
  `/root/x8_integration_impl` (GPT-5 implementation agent). The four supplied implementation
  commits were cherry-picked linearly in the required order:
  `56e20a756d9882502fb5e0a3239c3802549b3717` -> `3976f8f`,
  `8942db703b90876ad4d7a3e16d8cf36ace02d3d5` -> `759a329`,
  `25fc0cd441d9ab44e736268a562bf3e8e1cc2cf4` -> `347ec4e`, and
  `79c0b607ab2cd1f480d17631be0581cb15c0465f` -> `1e90dd3`.
- Shared configuration/assembly wiring is `1f77131` (`build(x8): wire local candidate assembly`).
  It root-includes pure-Java `blendlib-datagen`; declares separate Fabric 26.2, NeoForge 26.2
  WAITING bridge, detached example/provider, converter/template, sources/Javadoc, local inventory,
  and SHA-256 task paths; and keeps `x8AssembleLocalCandidate` outside `buildRelease` and remote
  publication. The aggregate is workspace-local and requires the same checkout's
  `build/local-maven`; it is not a standalone Maven closure or release artifact.
- The candidate includes real source for a Fabric 26.2 adapter/JAR declaration, NeoForge pure
  bridge plus non-loadable metadata template, pure-Java datagen, ecosystem and independent consumer
  examples, model-pack template, tutorial/migration guidance, bounded offline Blockbench/GeckoLib
  converter, AssetProfile/Material/Backend/Host Adapter examples, compatibility matrix, and
  license/source inventory. `PlatformAdapterControl.uninstallIfSame` is documented as exact-object
  stale-receipt protection; it does not change ordinary `uninstall()` semantics.
- Static implementation checks only: branch/HEAD/tree/status and source-commit lineage inspection,
  changed-path inventory, `git diff --check` / `git show --check`, and `rg` boundary scans for no
  newly changed test path, no Minecraft/Fabric/NeoForge/GeckoLib/implementation-internal imports in
  datagen/provider examples, and no raw OpenGL/private-reflection pattern in X8 source. These are
  implementer static checks, not an independent review or any build/runtime evidence.
- By explicit task constraint, zero tests, zero Gradle/gradlew build/check/compile, zero Python
  converter/help/self-test, and zero client, server, reload, benchmark, network, or other dynamic
  command ran. Build/package/SHA generation, loader binding, compile, conversion, resource reload,
  client/server, visual, hardware, compatibility, and performance evidence are all **WAITING**.
  Final independent gpt-5.6-sol/max static review is **PENDING**.
- The latest user authorization permits this isolated X8 implementation/integration candidate while
  the dependency Gate stays **LOCKED / PENDING**. It does not unlock or raise X1--X7/P0--P8, and
  preserves the existing X2/X3/X4 review states and the current X7 candidate state unchanged.
  NeoForge official 26.2 loader binding and every non-addon license/publication decision remain
  **WAITING/PENDING**. The final documentation commit and reviewer range are intentionally resolved
  only at handoff, not self-referenced here. **Agent Innovation:** isolated artifact identities,
  a local-only inventory/SHA chain, exact-instance lifecycle cleanup, and bounded offline conversion
  preserve the strict runtime boundary without fabricating dynamic evidence.

## X8 predecessor-static-review remediation candidate (2026-09-05)

- The predecessor review at `d4eee37b7bb8bf75a3572c7eb687da9b1a01ca5b` remains **FAIL**. This
  implementer record neither revises that verdict nor calls any repair PASS. The exact eight-finding
  source repair chain is `35d888ac1068b2e2d97632b8e1884e47c099e372` (X8-H1/H2/H3/M1/M2),
  `e906b0f` (selected-scene palette correction for X8-H2), and converter source
  `c36cd90fff37a2bb4753618744fb1cfea506fdad` integrated unchanged as
  `5504048d4fa0fefe59e8a33903af78e3c3a1d514` (X8-H4/H5/M3). The subsequent shared-documents
  handoff commit deliberately remains subject to the final branch-range review.
- X8-H1 now has candidate public Fabric entity, block-entity, and item registration paths whose
  dispatcher acquires a pinned snapshot, calls submission, and releases it; it records native
  binding metadata only after installation succeeds. X8-H2 preserves primitive `nodeIndex`, a
  generation-bound selected-scene palette/world transform, and per-primitive `unitsPerBlock`
  conversion. X8-H3 preserves the precise receipt through client stop and leaves close retryable
  until retire/drain/coordinator close/exact uninstall have all succeeded. Compilation, loader,
  lifecycle, reload, and visual evidence remain **WAITING**.
- X8-H4 classifies exact Blockbench/GeckoLib object fields and names unknown or known
  unrepresentable semantics; default behavior rejects and only explicit `--allow-lossy` records an
  omission. X8-H5 validates rest and animated float32 scale as positive/uniform and maps declared
  source units through `--source-units-per-block` (default 16). X8-M3 requires `--force` for an
  existing regular target, caps external animation/texture inputs at 64 each with a 128 MiB
  cumulative input budget, and verifies/reverifies safe output targets. Conversion execution and
  filesystem-race evidence remain **WAITING**.
- X8-M1 supplies frozen immutable-spec cardinality/total/output-size limits and validates all
  generated outputs before the first publication write. X8-M2 gives NeoForge prepared generations
  private bridge provenance, bounded non-poisonable generations, per-asset generation validation,
  and single-use application. Datagen generation, compilation, official NeoForge loader binding,
  loader lifecycle, and all runtime evidence remain **WAITING**.
- Scope/agent: `agentloop/x8-platform-ecosystem` in
  `D:\BlendLib-worktrees\x8-platform-ecosystem`, `/root/x8_integration_impl` (GPT-5 implementation
  agent), with the converter source integrated from its separately owned path. Only static source,
  path, dependency, and diff inspection occurred: `git status`, commit/tree/path inventory,
  `git diff --check`, `git show --check`, and `rg` boundary scans. No tests, Gradle/gradlew
  build/check/compile, Python converter/help/self-test, client, server, benchmark, or other dynamic
  command ran. The latest authorization permits this candidate while the dependency Gate remains
  **LOCKED / PENDING**; it changes no X1--X7 or P0--P8 acceptance state. One fresh independent
  gpt-5.6-sol/max static review of the final exact range is **PENDING**. **Agent Innovation:** none
  beyond the bounded repairs and isolated candidate wiring already documented above.

## X8 same-reviewer final-findings remediation candidate (2026-09-05)

- The predecessor independent static-review **FAIL** remains historical. In the same reviewer's
  later final-findings pass, X8-H2/X8-H5/X8-M1/X8-M2/X8-M3 were recorded closed; X8-H1/X8-H3/X8-H4
  remained and X8-H6 was added. This implementer record does not revise either review verdict or
  call a candidate PASS.
- `5bef5b0` (`fix(x8): complete Fabric animation and retry lifecycle`) repairs H1/H3/H6: the
  separate Fabric 26.2 artifact declares Loom-nested exact API/core dependencies; private
  registration installation retains the complete typed source and evaluates only
  `animationFor(host())`; actual entity/block hosts use bounded weak-reference isolated
  controllers while item uses a transient `Item.STATELESS` LOOP controller; extraction freezes a
  generation-bound pose palette and submit consumes only that palette. Snapshot/dispatcher close
  removes leases only after successful exact release and retains unfinished phases for exact retry.
- The separately owned converter mirror-type repair
  `0488adb8bf91ded9ab1a2531df8bfb556ce83901` was cherry-picked unchanged as `ae90a39`
  (`fix: reject invalid Gecko cube mirror values`): Gecko cube `mirror` must be a JSON boolean and
  emits `BLX8-INPUT-BOOLEAN` on any other type, regardless of lossy mode. This is the H4 repair
  candidate, not converter execution evidence.
- Scope/agent remains `agentloop/x8-platform-ecosystem` in
  `D:\BlendLib-worktrees\x8-platform-ecosystem`, `/root/x8_integration_impl` (GPT-5 implementation
  agent). Static-only checks are limited to source/contract reading, changed-path inventory,
  `git status`, `git diff --check`, `git show --check`, and targeted `rg`; no test paths were
  written or changed.
- Zero tests, Gradle/gradlew build/check/compile, Python converter/help/self-test, client, server,
  reload, benchmark, network, or other dynamic command ran. Dependency resolution, JAR packaging,
  SHA generation, loader binding, compilation, animation/close behavior, conversion, reload,
  visual, hardware, compatibility, and performance evidence remain **WAITING**. X8 and the
  dependency Gate remain **LOCKED / PENDING** and no X1--X7/P0--P8 state changes.
- The same independent reviewer's static confirmation for H1/H3/H4/H6 is **PENDING**. The exact
  final branch range is resolved only after this documentation handoff commit. **Agent Innovation:**
  none beyond the isolated artifact boundary and bounded lifecycle/authoring repairs already
  recorded.

## X8 independent static closure PASS (2026-09-05)

- This supersedes only the pending static-review status recorded above for the immutable reviewed
  source candidate. The same independent reviewer `/root/x8_final_static_review`
  (`gpt-5.6-sol`, `max`) issued **PASS (0C/0H/0M/0L)** for HEAD
  `148a2c935a7cc7398107107c0317728f03a2b82f`, tree
  `f08c58d19f2c98988b72115366b30135518da30b`, in
  `D:\BlendLib-worktrees\x8-platform-ecosystem` on `agentloop/x8-platform-ecosystem`.
  The final correction delta is
  `4bbdfd358bc49e04cbcf3005b9998cfd2e270041..148a2c935a7cc7398107107c0317728f03a2b82f`;
  the accepted complete X8 source range is
  `8df1b90f9ebca03de6a1b78bb0b184e6529aaa5e..148a2c935a7cc7398107107c0317728f03a2b82f`.
- Both historical FAIL records remain preserved: the original reviewed candidate at `d4eee37b` and
  the intermediate closure at `4bbdfd3`. The final PASS closes H1/H2/H3/H4/H5/H6/M1/M2/M3. It is
  an independent static source verdict, not an implementer self-review or dynamic acceptance.
- Closure evidence: H1 typed registration source/per-host controller/frozen pose path; H2 primitive
  node/selected-scene palette/unit conversion; H3 success-before-terminal retry lifecycle; H4 strict
  JSON-boolean cube mirror; H5 scale/unit mapping; H6 Loom API/core nesting; M1 datagen limits;
  M2 NeoForge provenance; and M3 converter output/input safety. The reviewer also confirmed no
  X8 scope shrinkage, test/fixture/generated path, raw GL/reflection, runtime authoring reader,
  common/client leakage, or provenance regression in the 30-path final delta.
- The independent review used read-only Git, scoped source/text reading, and static parsing. It
  performed zero writes, tests, builds, compilation, converter execution, client/server launch, or
  other candidate execution. This later documentation bookkeeping commit is not part of reviewed
  source HEAD `148a2c9`; see [X8 static-review closure](x8/static-review-closure.md).
- All dependency resolution, generated JAR/sources/Javadoc/SHA artifacts, loader/reload/close race,
  animation/render/visual behavior, converter/datagen execution, consumers, network, Iris/Sodium,
  hardware, and performance evidence remain **WAITING**. NeoForge official loader binding and
  license/publication remain **WAITING/PENDING**. X8's dependency/release Gate remains
  **LOCKED / PENDING**; no X1--X7, P0--P8, release, or dynamic acceptance state changes.
