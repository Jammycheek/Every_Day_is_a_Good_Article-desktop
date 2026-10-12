# Every_Day_is_a_Good_Article-desktop
Windows-first desktop companion PoC. Kotlin Multiplatform core, Kotlin/JVM process boundary,
Windows WPD/DPAPI helper and planned Compose Multiplatform UI. The phone remains authoritative.

- [Transport validation plan](docs/POC-PLAN.md)
- [PoC results and remaining work](docs/POC-RESULTS.md)
- [Application session contract proposal](docs/SESSION-CONTRACT-PROPOSAL.md)
- [Windows probe guide](docs/WINDOWS-PROBE.md)
- [Real-phone hardware evidence](docs/HARDWARE-VALIDATION.md)
- [PC synthetic recovery](docs/RECOVERY-POC.md)
- [Transport/session bridge](docs/TRANSPORT-SESSION-BRIDGE.md)

Implemented: bounded synthetic WPD roundtrip, USB evidence, explicit lease gate,
PC snapshot release and encrypted one-operation delta recovery. Reconnection alone never grants editing.
The polling bridge revokes PC mock-session permission on observed transport loss; live unplug/reconnect verified no automatic renewal.
Real-phone transfer, USB-mode changes, idle reconnect and interruption/recovery have been tested on one phone.
DPAPI recovery tests use synthetic data only; no Android source or application data is included.

Remaining: authenticated private Android session/ack channel, actual app lock, production data schema,
native device events and rapid reconnect coverage, retention/multi-process policy, Compose UI and macOS/Linux adapters.
No production synchronization or real-data safety claim.

JDK 17 + Gradle 8.12.1. Build native helper first, set POC_HELPER to its absolute path, run `gradle jvmTest`.
`gradle runRecoveryRestartSmoke` verifies a synthetic delta across two separate JVM processes.
See Windows guide for local hardware commands. Do not publish raw device identifiers or directory listings.
