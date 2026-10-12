# Request-bound synthetic grants (PR #2)

Based on merged PR #1 main d098f17. Scope: synthetic request correlation, duration leases, renewal and protected verified history. No Android code, real data, authentication carrier, UI or power-loss guarantee is added.

## API and request lifecycle

`Grant` carries identity/incarnation, session, baseRevision, requestNonce, epoch and validityDuration (milliseconds). `beginGrantRequest(identity, maximumDuration, safetyAllowance, renewal, rebaseline)` records local time before returning the locally owned capability to the future carrier. At most one request is outstanding. A generated 256-bit [SecureRandom](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/SecureRandom.html) nonce correlates the response; **nonce equality is not authentication**. The carrier must authenticate before dispatching any grant or receipt.

`acceptGrant(grant, request)` and `acceptFreshHistoryBaseline(grant, request)` return `ACCEPTED` or `IGNORED_UNRELATED`. A missing/replaced/cancelled/consumed/foreign capability or a different nonce is ignored before reading the clock or storage: it does not consume the current request, interrupt the session or release its snapshot. A matching capability and nonce consume one attempt. Invalid identity, epoch, duration, revision, baseline mode, deadline or storage then suspends editing. Replacement, cancellation, disconnect, suspension, live-session expiry and restart invalidate capabilities. They are never reconstructed from wire data or persisted.

A mistaken acquisition during a live session is rejected without replacing an outstanding renewal or stopping the session. Expired sessions still release their snapshot before a fresh acquisition. Receipt reconciliation keeps its existing unrelated-receipt behavior. Future callers must inspect the returned result; acceptance is not inferred merely from absence of an exception.

## Conservative deadline and one local clock

`deadline = request.sentAt + returned validityDuration - local safetyAllowance`.

The authoritative app must begin that bound duration no earlier than receipt of the same request. A duration measured before receipt is not compatible. Peer timing, drift allowance and OS sleep/resume remain undecided. The returned duration must exceed the allowance and not exceed the requested maximum. Arrival exactly at the deadline is rejected; arrival never restarts the duration.

The controller owns one injected trusted monotonic clock, defaulting to JVM [System.nanoTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#nanoTime()) in milliseconds. Public request, acceptance, snapshot, stage, tick and canEdit methods have **no time argument**. It reads the clock at processing time, so timestamps captured when queuing UI events cannot cause artificial rollback. Tests inject a virtual clock; timestamp convenience extensions exist only in commonTest. Clock origin is arbitrary and never persisted or compared to the phone. Actual injected clock regression fails closed; arithmetic overflow is rejected.

After protected storage completes, the controller reads that same clock again and rechecks expiry before enabling editing. A delayed write cannot activate an expired grant. A renewal must also remain before the previous live deadline at completion. Event-loop blocking and OS suspend behavior are not proved; periodic ticking remains necessary.

## Unified verified history (P1)

`revision-floors.dpapi` now uses EAF2: bounded count <=256, identity/incarnation UTF strings, optional nonnegative floor and epoch (encoded absent as -1). At least one value is required per identity. Duplicate keys, invalid fields and trailing bytes fail closed. `recordVerifiedGrant` retains both maxima in **one** forced encrypted temporary file and atomic replacement, under existing owner ACL, DPAPI limits and lifetime `journal.lock`. Failure before replacement changes neither maximum; failure after replacement/final deadline check can retain both maxima without enabling editing. A fresh higher-epoch request then proceeds. Individual revision-only receipt updates and epoch-only lock fixtures retain the other maximum.

Acquisition requires an epoch strictly greater than the saved maximum; renewal retains exactly the live epoch. The phone may choose an epoch far above the PC minimum: the PC does not assign the authoritative epoch.

### Legacy migration and compatibility

EAF1 floors and EAE1 `grant-epochs.dpapi` are read together until the first successful EAF2 write. Missing legacy values remain absent; epoch-only identities are preserved. Both legacy files must validate before migration, and the union must fit the shared bound. The EAF2 replacement becomes authoritative, and retained legacy epoch ciphertext is never imported again. It is retained for manual inspection rather than silently deleted. Older binaries cannot read EAF2; do not run them against this directory. Deliberate file restoration/deletion and malicious same-user code remain outside the protection.

### Confirmed recovery

`verifiedHistoryEntries()` includes both values, including epoch-only identities; the compatibility `revisionHistoryEntries()` lists only floors. A forget preview includes previousFloor, previousEpoch and a consequence explaining that older **revisions and generations** may be accepted after explicit rebaseline. One-use confirmation and byte comparison cover all present history source files. After confirmation, write the persistent warning first, then remove the entire selected record atomically. This frees one shared capacity slot. Unrelated records and pending edits remain intact.

If an authoritative restore accidentally reuses the incarnation and rolls epoch/revision back, confirmed forgetting followed by explicit fresh baseline now permits recovery. This is a deliberate loss of both rollback defences, not authentication or proof that the restore is correct. Pending compatibility still applies.

Unreadable unified/legacy history uses the existing two-read classification and quarantine preview. Operational/helper/IO failures cannot offer quarantine. Confirmed quarantine renames each present history source with original ciphertext and ACL retained; it never decrypts or deletes archives. Unified history is one file; archiving two *legacy* sources is not an atomic multi-file transaction. Partial failure retains the warning and remaining files, stops editing, and requires explicit recovery of remaining readable records by forgetting, or a new quarantine preview if still unreadable. No automatic archive restoration occurs. Pending data is untouched. Oversized unreadable files retain the documented weak metadata comparison.

## Renewal and explicit baseline

Renewal binds identity, session, epoch and base revision. The response must retain them, extend the conservative deadline, and arrive and complete storage before the old deadline. Valid renewal preserves the snapshot. Matched invalid renewal still suspends editing, including a non-extending duration. Duplicate/unrelated renewal responses are ignored; expiration is still enforced on later editing/tick operations.

Normal and explicit baseline acceptance share all protocol checks; only local admission differs. After forgetting/quarantine there is no saved epoch for the removed identity; a fresh bound baseline may start at a lower epoch. The persistent S2 warning still makes every missing/new identity require explicit baseline indefinitely. This PR does not narrow warning scope or clear it: real authentication and warning policy remain separate work.

## Remaining issues

1. T2: unreadable pending journals/key loss still have no confirmed quarantine/export exit. Pending discard cannot preview unreadable content. Resolve before real-data use.
2. T1: environment/key failure may still be classified as ciphertext/key rejection for history quarantine; the proposed encrypt/decrypt canary is not implemented.
3. Private peer authentication, all-writer epoch fencing, incarnation lifecycle, authoritative duration semantics, apply/status crash consistency and operation submission state remain requirements.
4. OS sleep/resume, helper-induced tick delay, actual power loss, long-term retention and S2 warning scope/clear policy remain unverified or undecided. Shared capacity is 256; there is no automatic eviction. Long.MAX_VALUE epoch exhaustion requires confirmed recovery or a corrected authoritative incarnation.

## Verification

PR #2 review follow-up tests cover duplicate/foreign/replaced/wrong-nonce replies without interruption, matching malformed replies, mistaken acquisition preserving renewal, execution-time clock ownership, authoritative epoch jumps, unified failure without partial maxima, legacy union migration/epoch-only enumeration, non-resurrection after forgetting/restart, capacity recovery, source-change/one-use confirmation, real DPAPI quarantine of both legacy files and pending preservation. Prior session/recovery/transport tests and manual drivers use the new public API. No hardware driver was run against a device.

Historical pre-review validation: 93 Kotlin tests, two native tests, three-JVM recovery and Java 17 driver compilation passed at implementation 4ff624a (Windows Gradle 8.12.1/JDK 17 [CI run](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38257556327)). Follow-up results are recorded in REVIEW-REQUEST-BOUND-FOLLOWUP.md after execution.
