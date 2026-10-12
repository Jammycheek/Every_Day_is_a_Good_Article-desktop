# USB-MTP / editing-session PoC plan

Status: synthetic session model plus a Windows WPD probe. Hardware unverified; no Android lock.
Baseline: main 20133cb732dead6017254a11b7b1731b36d9c9e2; initial README, Apache-2.0 LICENSE, .gitignore; no issues or PRs at inspection on 2026-10-12.

## Scope
Windows first. Kotlin common session core; JVM desktop target. Compose Multiplatform diagnostic UI is a subsequent step, not yet implemented. macOS/Linux share the core and will require separate transport adapters.
No Android source, application schema, credentials, photos, real journals or device identifiers enter this public repo.

Three independent boundaries:
1. TransportProbe: USB evidence, WPD/MTP availability, permission/read/write capability are separate observations.
2. FileTransport: bounded synthetic payloads in an explicitly selected disposable PoC folder. Never scan/import journals by default.
3. SessionChannel: authenticated request/grant/renew/release and applied acknowledgement from the private app. MTP presence cannot grant editing rights.

Windows WPD device enumeration is an implementation candidate, not proof of USB: WPD also supports other transports. Verify the device transport property and corroborate PnP attachment; unsupported/unknown stays unknown. A filename, friendly name, or connection event is not application identity.
A separate Windows C++ WPD helper implements device/child enumeration and a bounded synthetic round trip. Kotlin launches it as a process; hardware confirmation remains pending. Do not treat MTP as a mounted filesystem or assume atomic rename / instant visibility.

## Safety and protocol proposal (requires private Android review)
Phone remains authoritative. Phone serializes editing and grants a bounded lease only after it commits its own lock.
Desktop edits only while a matching authenticated lease is valid and transfer capability is available.
Use session nonce, stable application identity, monotonic lease deadline, base revision and idempotent operation ID. Raw WPD identifiers must not substitute for app identity.
On detach, error, expiry or revoke: stop writes, invalidate session, release ordinary snapshot, preserve only unacknowledged delta. Real data requires encrypted durable journal + atomic write, restricted access and retention policy BEFORE edits are enabled.
On reconnect: reacquire lease and compare phone identity/base revision; conflicts require explicit reconciliation, never blind overwrite.
A transfer success is not commit success. Clear a delta only after validated app-level applied acknowledgement. Lost acknowledgements require querying operation status before resubmission.
Phone lease expiry/crash recovery must unlock safely. Never depend on receiving a desktop release message. MTP mailbox timing, background execution and renewal channel are unconfirmed; no final wire protocol is selected.
A lease expiry can race an in-flight transfer. Phone MUST validate session/revision when applying and reject stale requests. The synthetic gate does not implement those private-side rules.
Snapshot release in the model is a flag only; no secure memory erasure or physical data deletion is claimed.

## Validation stages and acceptance
A. Automatic synthetic tests: connection alone; disconnect; lease expiry; wrong phone/revision; stale/wrong ack; grant replacement. Single-threaded model only, no concurrent callbacks.
B. Windows native smoke test: no phone / charging-only / MTP / locked-screen / denied access / multiple phones. Record separate USB and MTP evidence without publishing identifiers.
C. Synthetic round trip: user creates/selects dedicated disposable folder; transfer random bounded fixture PC->phone->PC; compare SHA-256, size and bytes. Test Unicode names and empty payload. Partial transfers must be reported, never applied.
D. Faults: unplug during read/write, switch USB mode, PC/app crash, phone restart, delayed/duplicate ack. Timeout every native operation and leave recoverable pending delta.
E. Private Android integration: lock grant/renew/expiry/revoke, revision conflict and idempotent apply; private test harness only.
F. Compose UI: show USB/MTP/transfer/session independently; never show mock observations as hardware results.

Proceed to B only after the execution environment and a real Windows adapter are ready. Tell the user to connect the phone then; connection is not needed during preparation.
Go/no-go: B+C demonstrate actual transport; E demonstrates lock safety. A alone cannot pass either hardware or Android integration.

## Current limitations
Local shell and alternate runtime failed before executing due to MXC volume E: filesystem error (1005). Build/tests/hardware have not been run locally.
The native helper and Kotlin process runner are implemented. Durable journal, authentication, real phone lock and Compose UI are not implemented. Actual phone transfer remains unverified. See WINDOWS-PROBE.md. This is not production synchronization.
Build with Gradle 8.12.1 and JDK 17: gradle jvmTest. No Gradle wrapper generated because local execution is unavailable.

## References
- https://learn.microsoft.com/en-us/windows/win32/wpd_sdk/enumerating-devices
- https://learn.microsoft.com/en-us/windows-hardware/drivers/portable/wpd-drivers-overview
- https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
