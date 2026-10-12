# T1: current DPAPI environment canary

Base: merged PR #2 main 3cde45042d52a56469987f1c6f9487a59487f7a0. Scope is current-environment validation before history quarantine; T2 (unreadable pending) and Q1 (legacy epoch resurrection) are unchanged. No private Android code, real data or hardware access.

## Admission rule

The existing two reads, separated by 100 ms, must still independently fail with DecryptionRejectedException or StorageFormatException. Only then does DpapiProtector.requireAvailableEnvironment generate a fresh 32-byte SecureRandom challenge, protect it using the same helper, unprotect the returned ciphertext and compare exact bytes. Failure to start, timeout, pipe/exit/output error, key rejection or a successful response with different bytes all become ProtectionUnavailableException. There is no cached success, persistent canary, file write or production test switch. Challenge, ciphertext and restored byte arrays are cleared in finally.

A history-quarantine preview is created only after this round trip succeeds. The environment is checked again during confirmed QUARANTINE_ALL recovery, after token consumption/source comparison and before writing the warning or moving history. If availability changed while the user reviewed the preview, the action stops without a new warning or archive; the old confirmation is consumed, so repair and a new preview are required. FORGET_IDENTITY remains unchanged.

## What success means

It demonstrates that the **current** context and configured helper can protect and unprotect fresh data at that moment. It does not prove integrity of a historical file, prove the availability of an old DPAPI key, authenticate a phone, guarantee the helper stays usable or eliminate the interval between final check and rename. A lost old key can coexist with a working current key; quarantine may still be offered in that case, with encrypted original bytes retained and explicit confirmation required. Existing helper error-code classification remains an underlying signal, not proof of corruption.

The check is synchronous in the existing serialized recovery operation, never the normal grant acceptance path. Each round trip adds two bounded helper calls (up to 15 seconds each); preview still includes two history reads and 100 ms retry delay, and confirmation checks again. Event-loop blocking/helper-induced expiry latency remains unverified; UI integration should put maintenance work on its serialized worker and must not claim an instantaneous check. Existing cancellation/interruption behavior comes from the helper runner. No power-loss guarantee is added.

## Verification

Five new tests cover real-DPAPI repeated canaries with no storage changes; helper rejection for normal history; canary startup/encrypt/decrypt/timeout/empty/oversize/mismatch failures; loss after preview with consumed confirmation and unchanged warning/history/pending; and invalid schema blocked by environment failure, then recoverable when repaired. Existing real corruption, schema, retry, timeout and archive-preservation tests remain in the full suite. Tests use synthetic disposable directories and test-only child processes.

Local verification passed all 106 Kotlin tests (101 prior plus 5 environment tests), Java driver compilation with --release 17 and three separate-JVM recovery modes. Kotlin 2.1.20 ran with local JDK 25 and JVM target 17. Windows Gradle 8.12.1/JDK 17 CI remains pending after publication. Historical PR #2 CI does not validate this branch.

## Remaining

T2 unreadable pending/key loss has no confirmation/quarantine exit. Q1 inactive legacy epoch files can be imported again if the combined file disappears. Actual authentication, private all-writer fencing/incarnation lifecycle, OS sleep/resume, real power loss and warning/retention policy remain separate work.
