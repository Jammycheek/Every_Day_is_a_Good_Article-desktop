# U1, T2 and Q1: protected pending recovery

Base: user-merged PR #4 main c69584b. Synthetic Windows PoC only; no private Android source, real dataset, actual authentication or UI. Existing controller serialization and lifetime directory ownership apply.

## U1: failure hints for confirmation UI

The helper's binary unprotect protocol now returns 3 for ERROR_INVALID_DATA/ERROR_INVALID_PARAMETER/NTE_BAD_DATA, 4 for NTE_BAD_KEY_STATE/NTE_BAD_KEYSET, and 1 for other operational/unknown failures. ERROR_INVALID_PARAMETER retains the previous classification because this Windows DPAPI path also reports it for short invalid ciphertext; it is an ambiguous hint, never proof of corruption. Codes 3/4 require complete input and empty output before JVM accepts them as DecryptionRejectedException(reason). Encryption errors always remain operational. Native self-test exercises the mapping; test-only pipe peers exercise status 4 and incomplete-input rejection. These are Windows error hints, not proof of corruption or guaranteed recoverability.

History and pending quarantine previews carry DATA_REJECTED, KEY_STATE, INVALID_FORMAT, LEGACY_REIMPORT_BLOCKED or MIXED, plus advice to check prior password/account/key-recovery options before isolating ciphertext. No actual password reset/restoration is performed or prescribed by the PoC. A working fresh DPAPI canary does not prove old keys exist. JVM jar and helper must be upgraded together; an old helper may classify key failures as status 3.

## T2: unreadable pending quarantine

`preparePendingQuarantine()` requires two data/key/format failures 100 ms apart and a successful fresh environment canary. Missing pending returns null; readable pending, temporary helper failures, IO/access failures and unavailable environment cannot offer quarantine. It returns ciphertext size, failure hint, guidance, loss consequence and a process-local one-use confirmation. Identity/payload are unknown; the preview never guesses them or displays plaintext.

The consequence states: the edit may never be recoverable; keeping ciphertext does not guarantee restoration; local abandonment does not cancel or prove application on the phone. Future UI must obtain affirmative consent; a token is not evidence of human confirmation. Actual screen/default choice and consent audit remain future work.

`quarantinePending(confirmation)` consumes the matching token, checks a streaming SHA-256 fingerprint and regular-file identity/size/mtime, rechecks the current environment and reads again. Key repair making unchanged bytes readable returns READABLE without moving data. Changed source/reason returns CHANGED; missing source returns NO_PENDING; foreign/cancelled/replaced/reused confirmation returns INVALID_CONFIRMATION. Operational failure throws with token consumed and editing suspended. Close invalidates preview state. Hashing includes oversized ciphertext without loading it all into memory; final metadata is checked to detect changes during hashing. Cooperating ownership remains required; malicious/external writes during the final check-to-move interval are outside this PoC.

After checks, write `pending-quarantined.warning`, then atomically rename the original to `pending-quarantine-<UUID>.dpapi` with original bytes/ACL. No deletion, decryption, automatic replay or automatic editing grant. History floors/epochs are untouched. Success returns archiveId, and fresh authenticated/status-checked acquisition can proceed if normal history/identity/revision/epoch requirements hold. Local mock tests demonstrate this exit, not real phone consistency. The persistent pending-warning records the abandoned local proposal; clearing/retention policy is not implemented.

A marker or move failure retains original ciphertext (or, on successful move, its archive); marker-first ordering can leave a conservative warning if rename fails. This is not a multi-file transaction or power-loss guarantee. The operation is synchronous maintenance on the serialized owner; fingerprinting large files and helper calls can delay the loop. Run future UI maintenance on its designated serialized worker and retain expiry checks.

### Encrypted export

Windows adapter `exportPendingArchive(archiveId, destination)` copies only retained ciphertext to a caller-selected separate existing local directory. Strict UUID filename validation keeps the source inside the owned recovery directory; regular-file/no-follow reads and streamed hashes validate the copy. CREATE_NEW prevents overwrite; owner-only ACL is applied, data is forced, and a failed new export is cleaned up where possible. Existing destination bytes are never removed. Export needs no working DPAPI key; the original archive remains. No plaintext export, promotion, auto restore or archive deletion is provided. It is an API for a future save dialog, not a completed UI.

## Q1: prevent inactive legacy resurrection

Before the first EAF2 replacement, persist `history-combined.marker`. After successful replacement, rename active `grant-epochs.dpapi` to `grant-epochs-migrated-<UUID>.dpapi`, retaining bytes and ACL outside automatic read candidates. Marker remains forever. If replacement/retirement fails, the marker prevents fallback to active EAF1/EAE1 history: existing ciphertext remains and a LEGACY_REIMPORT_BLOCKED quarantine preview is required. No old value is silently accepted. This deliberately chooses explicit maintenance after interrupted migration over automatic fallback.

If combined history disappears with active legacy still present, it is blocked for confirmed isolation. If no active legacy remains, missing history is accepted only as unknown with a durable incomplete warning and explicit fresh baseline. One rebaseline cannot clear warning for other missing identities. Corrupt/oversized marker content still counts as present; IO/invalid-file errors fail closed. Direct deletion of marker/restoration of files by outside code remains out of scope.

Tests simulate combined-file loss after forgetting, leftover active legacy after retirement interruption, failed migration with bytes retained, restart and lower-epoch fresh baseline after explicit recovery. No real power cut is performed.

## Validation and remaining work

New tests cover pending key/data/schema failures, canary failure, repair before confirmation, one-use/change/cancel/missing cases, streaming oversized data, marker failure, ciphertext/ACL export without keys, no overwrite/path escape, reopening and fresh acquisition, and Q1 failure boundaries. Local verification passed all 119 Kotlin tests (108 prior plus 8 pending recovery and 3 migration-boundary tests), two native tests including the status mapping, Java 17 manual-driver compilation and three separate-JVM recovery modes. Kotlin 2.1.20 used the local JDK 25 with JVM target 17; Windows Gradle/JDK 17 CI is pending after publication. Actual historical-key loss is represented by test-only helper status 4, not a password/account change. No real power cut or device operation was performed.

Actual authentication/all-writer fencing, authoritative incarnation and apply/status rules, unsaved UI edits, actual confirmation screens, OS suspend/resume, real power loss, retention/warning clearing and macOS/Linux adapters remain unfinished. Pending quarantine is a recoverable local exit by preserving ciphertext, not restoration of the edit itself. Never use this synthetic PoC with real app data.
