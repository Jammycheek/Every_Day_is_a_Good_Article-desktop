# Synthetic hardware results and remaining checks

Recorded through 2026-10-16. These are partial validation results, not a production release sign-off.
Only the separately installed validation app, its synthetic articles and the isolated PC validation storage were used.
No screenshots, raw mailbox files, peer IDs, keys, private source or article bodies are included here.

## Versions and evidence

- Initial round trip: desktop `5c0c398`; the private validation build remained unchanged for the later PC fixes.
- Revised native implementation: `fcb3338`; UI recovery confirmation documented at `d546297`.
- Host: Windows 11 Pro, build 26300, JDK 17; phone: Pixel 7a. Android OS version remains unconfirmed.
- Six synthetic articles were actually present (two topics, three articles each), rather than the example total of three.
- User reports establish visible behavior. The separate Java driver establishes measured imports and pending-record equality.
  Neither establishes a complete fault-injection or exactly-once test by itself.

## Hardware checks

| Check / procedure step | Status | Evidence and limit |
| --- | --- | --- |
| Matching six digits and confirming on both sides (1-2) | Confirmed by user | Both peers listed. One-sided confirmation refusal was not tested. |
| Article import / phone lock display (3) | Confirmed by user | Six articles visible. Revised UI import and smooth transition confirmed on 2026-10-16. |
| Phone editing blocked (4) | Visible overlay confirmed | User reported the other-screen lock. Bypassing the UI to exercise the database refusal was not tested on hardware. |
| Normal single-article submission (5) | Visible round trip confirmed | User saw the appended text on the phone. Resulting revision and independent operation count were not recorded. |
| Reboot recovery of protected text | Confirmed by user | Revised editor restored the previously protected appended text after the reboot/connection failures. |
| Unplug before submit (6) | Visible protection and expiry confirmed | User reported the PC protection display and subsequent phone unlock without reconnecting. Unlock delay was not timed. |
| Reconnect after that unplug (7) | Pending | Restore the latest unplug-test text before any submission; then verify phone content and operation count separately. Earlier reboot recovery does not complete this case. |
| Reply lost during submission (8), changed phone base (9), phone process restart (10), backup replacement (11) | Not executed in this round | Automated coverage is not a hardware result. |
| Last keystroke / end-edit timing (12-13), unsaved/close/submit races (14, 14a, 14b) | Not confirmed on hardware | Restored text alone does not prove the requested sub-0.5-second timing. |
| Damaged pairing file (15), FAT32/exFAT export (16) | Not executed on hardware | No live validation journal or pairing file has been corrupted to claim these results. |
| Sleep, power loss, other devices | Not verified | Do not infer from process restart or unit tests. |

## Measured revised imports

The actual PC client performed initialize, status query, grant acquisition, snapshot import and release in three
separate JVMs: 8,294 / 5,423 / 5,626 ms. Each imported six articles and retained an equal pending RecoveryEdit before
and after. No saveDraft, submit, resubmit, discard or forget call was made. Grant acquisition updated normal verified
history/epochs. These are total driver connection times, not per-message MTP latency or UI stopwatch measurements.
See [CONNECTION-FEEDBACK.md](CONNECTION-FEEDBACK.md) for diagnosis and limits.

## Automated verification

At `d546297`, [Windows CI run 38874885041](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38874885041)
succeeded: native build/CTest, JVM recovery and session tests, protocol and desktop-core tests, Compose compilation,
restart smoke, manual-driver compilation and helper self-test. This CI run accesses no physical phone.
Local updated suites: desktop-core 37, protocol 19 and native CTest 2, all passed.

## Scope and next work

PR #6 remains a draft for the minimal synthetic round trip. Complete the interrupted reconnect check next; continue
the unexecuted data-preservation and failure cases before calling hardware validation complete. Review the expanded
native property/release changes as well as the PC worker changes. Merge remains an owner decision, not implied by CI.

The current phone PC-link screen must stay open. The five-minute requested lease and fifteen-second PC allowance
are provisional. A smooth switch within a few seconds is a product requirement, not a verified capability.
See [SESSION-HANDOFF-NEXT.md](SESSION-HANDOFF-NEXT.md) for the cross-repository follow-up. Distribution also needs
a friendly response to journal ownership failure on duplicate startup; the validation launcher guard is not that fix.
