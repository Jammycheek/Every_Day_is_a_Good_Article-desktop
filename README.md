# Every_Day_is_a_Good_Article-desktop
Windows-first desktop companion PoC. Kotlin Multiplatform core, Kotlin/JVM process boundary,
Windows WPD/DPAPI helper and planned Compose Multiplatform UI. The phone remains authoritative.

- [Transport validation plan](docs/POC-PLAN.md)
- [PoC results and remaining work](docs/POC-RESULTS.md)
- [Application session contract proposal](docs/SESSION-CONTRACT-PROPOSAL.md)
- [Request-bound synthetic grants, epochs and renewal](docs/REQUEST-BOUND-GRANTS.md)
- [Review follow-up: applied receipt reconciliation](docs/REVIEW-RECONCILIATION.md)
- [Review follow-up: persisted revision floors](docs/REVIEW-REVISION-FLOOR.md)
- [Review follow-up: confirmed local pending discard](docs/REVIEW-PENDING-DISCARD.md)
- [Review follow-up: exclusive recovery-directory ownership](docs/REVIEW-DIRECTORY-OWNERSHIP.md)
- [Review follow-up: confirmed history recovery](docs/REVIEW-HISTORY-RECOVERY.md)
- [Review follow-up: storage names and recovery-model cleanup](docs/REVIEW-STORAGE-CLEANUP.md)
- [Fourth review: failure classification and final PR scope](docs/REVIEW-FAILURE-CLASSIFICATION.md)
- [Windows probe guide](docs/WINDOWS-PROBE.md)
- [Real-phone hardware evidence](docs/HARDWARE-VALIDATION.md)
- [PC synthetic recovery](docs/RECOVERY-POC.md)
- [Transport/session bridge](docs/TRANSPORT-SESSION-BRIDGE.md)

Implemented: bounded synthetic WPD roundtrip, USB evidence, explicit lease gate,
PC snapshot release and encrypted one-operation delta recovery. Reconnection alone never grants editing.
Synthetic grants now require a one-use local request, matching nonce, durable epoch and a send-time duration deadline; renewal cannot revive an abandoned session. This is correlation validation, not an authenticated phone channel.
The polling bridge revokes PC mock-session permission on observed transport loss; live unplug/reconnect verified no automatic renewal.
Real-phone transfer, USB-mode changes, idle reconnect and interruption/recovery have been tested on one phone.
DPAPI recovery tests use synthetic data only; no Android source or application data is included.

Remaining: authenticated private Android session/ack channel, actual app lock, production data schema,
native device events and rapid reconnect coverage, history recovery/retention and application lifecycle policy, Compose UI and macOS/Linux adapters.
No production synchronization or real-data safety claim.

JDK 17 + Gradle 8.12.1. Build native helper first, set POC_HELPER to its absolute path, run `gradle jvmTest`.
`gradle runRecoveryRestartSmoke` verifies a synthetic delta and retained revision floor across three separate JVM processes.
See Windows guide for local hardware commands. Do not publish raw device identifiers or directory listings.

PR #2 review follow-up: [P1-P3 implementation and validation](docs/REVIEW-REQUEST-BOUND-FOLLOWUP.md). Verified floor/epoch history is unified; controller time is internal; unrelated grant replies preserve active editing.
