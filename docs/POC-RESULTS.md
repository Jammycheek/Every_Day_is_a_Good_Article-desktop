# PoC results and next boundary

2026-10-12. Windows, one phone, synthetic data only. Detailed evidence: [hardware validation](HARDWARE-VALIDATION.md); reproducible drivers: [bridge guide](TRANSPORT-SESSION-BRIDGE.md).

## Outcome
The Windows transport and PC synthetic-session preparation stage is demonstrated. USB declaration/PnP evidence and MTP file transfer work on the tested phone. Transport loss drives PC permission revocation and snapshot release; transport restoration alone never renews permission. This is not acceptance of private Android lock safety or production synchronization.

| Check | Result | Evidence boundary |
| --- | --- | --- |
| USB/MTP detection | Passed | Driver USB declaration, PnP ancestry, WPD Open; no independent packet capture |
| 64 KiB, empty and Unicode-name roundtrip | Passed | Dedicated folder, new synthetic files only |
| Unplug during slow write | Failure detected; fresh transfer passed after reconnect | No false success; remote rollback not guaranteed |
| Live unplug/reconnect and charging-only/restore | Passed | PC mock permission revoked, snapshot released, pending retained; no automatic grant |
| Lease expiry with MTP ready | Passed | 15-second mock grant, synthetic delta reloaded unchanged; expiry latency not measured |
| Normal process restart | Passed | New JVM restores pending edit only |
| Forced process termination after completed save | Passed | Dedicated child stopped; journal unchanged; new JVM has no grant/snapshot |
| Corrupted encrypted journal | Rejected safely | Disposable copy retained; active mock grant revoked; original unchanged |
| Wrong application identity/base revision | Rejected | Fixture identities only; matching explicit mock grant accepted |
| Screen lock | Observations recorded | Established access retained metadata visibility; locked reconnect confounded by charging-only mode |
| Android lock/authentication/apply acknowledgement | Not implemented or hardware verified | Private integration required |

## Validation and repository state
Core implementation runtime: 8f86a1021b0647f42a034398b18a4b6546b067b1 (CI-built jar/helper). Later commits add manual Java fixture drivers and evidence; each driver was locally executed and compiled for Java 17. These hardware drivers are not run by CI.
Windows CI for reviewed head da6098d131c7e7c4af7b0ccbd259d82608f7bf91 passed native build/tests, Kotlin session/recovery tests (25 tests), separate-JVM recovery smoke and CLI packaging: [run 38222805225](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38222805225).
Work remains on poc/usb-mtp-session-preparation and draft PR #1. Main remains at the initial inspected baseline 20133cb732dead6017254a11b7b1731b36d9c9e2; no merge was performed. Public tracked files contain source, synthetic fixtures and sanitized evidence, not Android source, raw identifiers, journal blobs or application data.

## Remaining risks and next work
1. Define the private Android session contract: application identity, authoritative revision, lock/grant/renew/expiry/revoke, idempotent operation status and applied acknowledgement. Review the proposed boundary before private implementation; do not publish Android-specific source or real data here.
2. Test interruption during journal encryption/replacement separately. Completed-save process termination does not establish power-loss durability. Windows reboot and power loss remain untested; orphan encrypted temporary files need a future explicit retention/recovery policy.
3. Address multi-process exclusion, retention, rapid unplug/replug missed by polling and production timing policy. Denied access, multiple physical devices and other phones remain unverified on hardware.
4. Build the Compose diagnostic UI with independent transport/session/pending states after the core boundary is agreed. No Compose UI exists yet. macOS/Linux need transport and protected-storage adapters.

The next recommended phase is private session-contract review, not enabling real-data editing. Lost applied acknowledgements must be resolved by querying Android operation status before any retry. Local DPAPI/ACL protection does not address same-user malware or every memory copy.
The platform-neutral review draft is [SESSION-CONTRACT-PROPOSAL.md](SESSION-CONTRACT-PROPOSAL.md). It does not implement or finalize the private channel.
The first review correction separates exact applied-receipt reconciliation from editing permission; see [REVIEW-RECONCILIATION.md](REVIEW-RECONCILIATION.md). Earlier hardware rows refer to the original runtime; no actual Android receipt has been tested.
The N2 follow-up adds explicit synthetic dataset incarnation and protected revision floors, rejecting older grants across restart; see [REVIEW-REVISION-FLOOR.md](REVIEW-REVISION-FLOOR.md). Legacy records are retained without guessed identity. No private authentication or authoritative incarnation lifecycle is claimed.

The N4 follow-up adds one-use, preview-bound explicitly confirmed local discard for readable blocked pending edits, retaining revision floors and requiring a fresh grant/snapshot. See [REVIEW-PENDING-DISCARD.md](REVIEW-PENDING-DISCARD.md). This is a synthetic API; actual confirmation UI, export/rebase and unreadable-record recovery remain open.

## Local artifacts
Three successful synthetic files remain in the phone's dedicated EveryArticle-PoC folder for optional manual cleanup. Local ignored build directories contain synthetic encrypted journals, a deliberately damaged copy and logs; none were published. No test process is intentionally left running, and no further phone connection is required for document/contract review. E: was not used.
