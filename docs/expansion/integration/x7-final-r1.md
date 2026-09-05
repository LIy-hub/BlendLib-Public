# X7 final r1 formal-integration ledger

**Status: bounded formal integration candidate / PENDING fresh final Sol review.**

This is a bounded code-and-automated-evidence record. It is not an X7 completion, GPU activation,
runtime, visual, compatibility, reload, hardware, or performance PASS. X8 is **LOCKED**.

## Exact formal identity

| Property | Verified value |
|---|---|
| Formal worktree / branch | D:\BlendLib-agentloop / agentloop/blendlib-expansion |
| Protected start | 2dc2622b1f0c64dfeb9a17eb436236f8ca2269ab / tree 1231eb3be51a0baea9e0812592a2ed2ccdae5fd7 |
| Reviewed T3c code result | 785f865af8b1effa1eca6cf146e758a2054f02dc / tree f9850ec547d4d27b77de9a27f39fdad0ca26470b |
| Current test-repair result | 7ef959be52ee69971dc70041a430a489e1d0ceac / tree fec817aecf6f3a564ae3052ae691eb6a057ad730 / sole parent 785f865af8b1effa1eca6cf146e758a2054f02dc |
| Repair scope | Independent Terra test-only change to ClientDiagnosticsCommandRegistryTest.java and X7SourceBoundaryTest.java; this integrator did not review it |
| Status at remediation gate | clean porcelain status |

Preflight evidence is D:\BlendLib-review-artifacts\x7-final-closeout-preflight-20260905-103926\REPORT.md,
SHA-256 6DAFE184B7BF36E43C0CDEA563D1132EB2821D2FDD8C2F663F61D2E6BD73EB11.
The T3c admission input is R4 PASS 0C/0H/0M/0L:
D:\BlendLib-review-artifacts\x7-t3c-host-wiring-review-r4-20260905-111727988\FINAL_REVIEW.md,
SHA-256 D61604DED5D9858BC7089859CA5028E4E5DA8474E4B7053CBB642502A01E14E0.

## T3c source-to-formal mapping

All picks used cherry-pick -x, were conflict-free and linear, and have equal source/formal stable
patch IDs.

| Order | Source | Formal | Stable patch-id | Result tree |
|---:|---|---|---|---|
| 1 | 83734bfcd8da524b14bbb3016877f6f180b099c5 | 32e79a9f14a97e3c22a63bc3502def19f53a81bf | 57ef162c5d6138ce299908601d64a3a0f813d391 | a6e70015c436764d9f72d141f1f7693c743ec7dd |
| 2 | 0f10b89d65478cd7d87fa299369261738de2e311 | a62a82a8460228c4ea571273d154494e463dc826 | 9d827bdba942f73f4ca9220d294c607db62bd10f | a24643c899b833a5985c243ec631789bc563961b |
| 3 | 0d91094bf53406020a3762923a6f84a66d30feac | ab53eac5148fdde64e62befbd38fc98ad83bcc93 | 29637e79c2b065b6b17751485bbeb6f18a7e67a4 | 3c98e62bddd104f5271b6b6d2cbe50583423d417 |
| 4 | 691f8eb95752acab54635fde5fb97006c93590d3 | 317f28ede222cb45f8956f1540e3451f48d63842 | 9d052b67974e44806157e051e4afc96c4ec94d04 | f2536dd49d71852a6185ae0052109b416b06fb29 |
| 5 | a937d5a0266c65d8aeacee598fd67b2872660c24 | 785f865af8b1effa1eca6cf146e758a2054f02dc | 153a128b3a9877db0883da8a30d2617a3e8206bc | f9850ec547d4d27b77de9a27f39fdad0ca26470b |

## Retained gate chronology

| Gate | Evidence |
|---|---|
| First final gate | **FAIL**, permanently retained at D:\BlendLib-review-artifacts\x7-final-formal-integration-20260905-112537978. 97 XML / 559 tests / 2 failures / 0 errors / 0 skipped. Transcript SHA-256 51653E1F8498A4F22DCBDD32FDC08FC9D5F16794F58A7A96944D5AFFF632A56B; manifest SHA-256 6019C67D4524FA9FD4381DB549614018460F8985426F420C6145AA4CD74862E9; FAILURE.md SHA-256 BB6F1F036707DB6705DFE2ED4D7192E365D506CC1F948D4399F0A3CCEBA9921D. The initial numeric outer exit was not retained by the yielded wrapper and was not recovered by rerun. |
| First-gate cause | Two stale test contracts: expected command literals omitted x7, and a source-boundary assertion rejected the intended client-only Blaze3D link in X7Minecraft2612GpuDevice$MinecraftBuffer. |
| Independent repair | 7ef959be; supplied focused evidence outer exit 0 / 4 green; registry XML SHA-256 3F2760929EF0B096DF49886C8280A815759FBE6AF95B18C2E213473BF42C0675 and source-boundary XML SHA-256 A95AE66BCA63C516576384DE25CC7E7DEF834BE8C72E9DB1D43F5531EE8EDAAB. |
| Sole remediation gate | .\gradlew.bat :blendlib-fabric-client:test --offline --no-daemon --max-workers=1 --console=plain; 2026-09-05T11:38:02.3473393+08:00 to 2026-09-05T11:38:47.8509884+08:00; numeric outer exit 0; BUILD SUCCESSFUL. |
| Remediation evidence | D:\BlendLib-review-artifacts\x7-final-formal-integration-r2-20260905033800958; 97 XML / 559 tests / 0 failures / 0 errors / 0 skipped; transcript SHA-256 34423CE3333A552555D37E662A4F511F4F9D347C0C48BF6C3EB59DD173D926CC; manifest SHA-256 DE2793CD66B1C2EDD0DC6F42F73950C3D183E37DD607342307348E67EF60A23A; metadata SHA-256 4D06FA14E64DDFD02E843F4716A68D9E1A9ABE7B8A712A121B7CE6DAE6B103DC. |

No additional module gate is authorized by this ledger.

## Prior X7 packet / formal-review inventory

Artifact names below are directories under D:\BlendLib-review-artifacts, and each listed hash is
that directory's FINAL_REVIEW.md. The older full source chains and six foundational packets remain
canonical in [x7-r1](x7-r1.md); no earlier report is deleted or rewritten here.

| Boundary | Formal identity | Latest retained review evidence |
|---|---|---|
| X7 r1 foundation | 191a5552f540a2c9689304189931c555d6788ccb | x7-formal-integration-r1-review-20260830-083317208, PASS, 66EB0909DB9F90FD3ECC211FBED06E509A1038E02D873F04861077B9434A8613; x7-r1 records the six underlying policy/GPU B1/benchmark/D1/D2a/D2b PASS packets |
| T1a trusted owner | 8e0e58db3e1b67fb80adc160bd72d3d177a5fab9 | x7-t1a-owner-review-20260830-092904793, PASS, B13E265658FD325739C01A7D12F5A7E6925F5D502DF2138CF5A9B079D4A155BC |
| T1b attachment history | 3914f89f1203ade13e90c4959801b910657eccdf | R1 FAIL A309205D85F80C29E52A950979A6014437C31BC1E16B4C8CA78C909E4CDE09C8; R2 PASS C3A2B669A3377D2B874D495039385B09B9C01955087D483AB120595A0D000DFA; superseded by T2a2 pre-CAS adoption |
| T1c shutdown fence | b3660bb6007ba89847461f5f0cc3c43f4d0d7478 | R1 FAIL CD43A7DAB790B07F9F70A9518D463D18EBEFA84744E4C48BB4CCBDF8D5C70056; R2 PASS FADB6814BD02E499E9DEC5CE367E15EA477F53EC533495B9DE960AFB4FE8438F |
| T3a pass host | b218aa090b39909b053a8b6645285cafd68af5e6 | source PASS 5962A5913B964428645F2972AF8045AED7DB457C2DCD3379EEBDF10C5A4AF361; formal PASS 5B82DB2BEF337B417D399A52C7F8B39EC9FD640E87B51D85E7F57B89B86B6872 |
| T2a1 resource island | d1f92e9a -> cc95d3c4 -> b0808693 | R1/R2 FAIL retained; R3 PASS 15B36DC9115DFCEA215A7F1A68584FFE5FA96180313892B7F22119B719558731; formal PASS BFEC1C7576357A48755CFBF3E8C5411CE4AA47AFAB8F3586B5A3D94D6A9A66A2 |
| T2a2 pre-CAS adoption | source 5a3e5a07 -> formal f5832530 | source PASS A8BAD1B1C47254F060F5BCBBE92C0F260E89982BCC4E3D914B4B5F3F1B3C535C; formal PASS 79A2D0DF53739D5CAB9D42FDB98F2133E5A119539A6B75B4917EB76FD644BEDE |
| T2a3 submitted lease | source b8bb5d48 -> formal a5c964f -> 1074f1d -> 731779e | R1/R2 FAIL retained; R3 PASS 6EC61891FD15A98B1264073F015DEC857424FD1CBE716409CC2CD9EA3619CD29; formal PASS 68D53E303B5EF8712FF744DE219D3D4BDEF631D761F453F1021BC5B72D412080 |
| T5 policy | source 938a49d0 -> formal 64ca73c -> 369258c | R1 FAIL retained; R3 PASS 4E0F3D13F8AF2324E86FA75CBE8F2524AA67BC3910FF3AD5E27A9C5A89D39ED5; formal PASS 2F8A728FCAABFCA39588E39FFAB668A05CAEAAE15830EE6D5C4758E61615F522 |
| T6a diagnostics / metrics | ca7b102b and 82e4a153 -> 5387701f | T6a source/formal PASS FEC7566497CCFD3FF25DD5F002FCE93F5C8AB0E25C271BC644F2B3C292881DA7 / CBF7A43D706350850B68429BD9417612F81C8C2A0AA570DE420E1617777F0303; metrics source/formal PASS F6387F46B8259FBA3393AED378F1EF76122CD31BC7421DD80FC39FD64729A984 / FD3B85FC8B4AEDB6E0D9F90976EBA5D078B52A043B157666C02EEA4A12FC16DC |
| T6b capture | source 74da65f4 -> formal c863fdc through 1e43eb2 | R1-R5 FAIL retained; R6 PASS 901E14A2ADA04EC8E6D7D0D947081043F44F4BA3640E68557097B19C9A2154B6; formal PASS B5CFE83C8A40D6CD41ACA9744FEE6A2A0A91AAF86AE7C86FFBC0ED8C42BB4022 |
| T3b static draw | source d8cc768e -> formal 5c54151 -> 2668817 -> efde4a4 | R1/R2 FAIL retained; R3 PASS 595F28F9EC420AE2F0244534B7CAD37C6795B859BC12A7C528A5898E1FF4712E; formal PASS E0FC559FF8317B5D2FB32365E4755D4C8DAF1BFAD86FCFEC8DE0DB437403CA3B |
| T4/T4p GPU skinning | source 17ed9c5c -> formal 77c1404 through 2dc2622 | R1-R4 FAIL retained; R5 PASS 0DD0B9DD8F19AA3D172F4635C13A9CACE04D5449F5F3E22BE3CEEAD53CEE8328; formal PASS 2EC5CEEBA9E876887310A902DCAE53A63392074A6CB8300EFBC53F9A0B17D60C |
| T3c host wiring | five mapping rows above -> 785f865a | R1-R3 FAIL retained; R4 PASS D61604DED5D9858BC7089859CA5028E4E5DA8474E4B7053CBB642502A01E14E0 |

## Bounded 15-item X7 delivery map

Closed means bounded code, automated, and reviewed formal evidence only; it never means real
GPU, runtime, visual, or performance success.

| # | GOAL delivery | Bounded state | Still WAITING |
|---:|---|---|---|
| 1 | GPU skinning backend | T4/T4p structural backend | public source-order proof; shader/device/upload/pass/draw/fence; hardware |
| 2 | reliable CPU fallback | D2b/T2a1/T2a2/T2a3/T4 structure | selected-route behavior and live producer |
| 3 | shared mesh/vertex buffer | B1/T2a/T4 ownership structure | allocation, sharing, upload, close, reload |
| 4 | static instancing plus batching | B1/T3b planner and queue | actual draw/batch execution and benefit |
| 5 | animation sampling cache | policy contract | real cache producer/consumer |
| 6 | pose-cache boundary | policy and lease boundary | cache lifecycle/invalidation |
| 7 | off-screen pause/downclock | policy decision | live visibility/cadence |
| 8 | far-distance downclock | policy decision | live distance/cadence |
| 9 | frustum culling | conservative decision contract | renderer/frustum behavior |
| 10 | mesh/primitive/bone culling | scope/identity checks | runtime application |
| 11 | LOD | policy/structural integration | LOD switching and visual validation |
| 12 | budgets | arithmetic/admission policy | completed-frame producer and enforcement |
| 13 | metrics/diagnostic command | T6a/metrics surface | verified T5 live producer; no performance conclusion |
| 14 | reload resource release | CPU-safe lifecycle structure | real GPU resources, 20 reloads, last-use/fence/release |
| 15 | reproducible benchmark | offline capture plumbing | trusted capture, JFR/allocation, comparison/performance |

## Remaining acceptance boundary

The following remain **WAITING**: public source-order proof, live T5-T6 completed-frame and
resource publisher integration, real GPU/device/target/pass/draw/fence, client/server and
F3+T-equivalent reload behavior, 20 reload release evidence, runtime/visual parity, Iris/Sodium,
hardware/JFR capture, benchmark comparison, and every performance conclusion. Fresh final Sol
review has not occurred and must not be prefilled as PASS. X8 remains **LOCKED**.
