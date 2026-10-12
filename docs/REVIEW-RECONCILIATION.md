# Review follow-up: lost applied acknowledgement

Scope: synthetic public PoC only. Review item 3 identified a dead end: after authoritative apply N -> N+1 and lost acknowledgement/expiry, the old API rejected the N+1 grant because the pending edit used N, while deleting the pending edit required a live grant. The old CI-built jar reproduced both failures with an in-memory synthetic journal.

## Implemented boundary
RecoveryController.reconcileApplied(AppliedReceipt) replaces the controller's lease-dependent acknowledge API. A future channel must authenticate the applied outcome **before** calling it; constructing this data class proves nothing. It is not a network message parser and performs no phone query.

The receipt must match the persisted synthetic application identity, dataset incarnation, base revision, operation ID and content digest. Its resulting revision must be strictly greater than the base revision. Reconciliation returns CLEARED, NO_PENDING, NOT_THIS_OPERATION or CONFLICT. No pending edit, a different application identity, or a different operation returns without suspending the current session or changing the journal. For the same identity/operation, a base-revision/digest/non-advanced-result mismatch returns CONFLICT, suspends editing and retains the journal. Read failure also suspends and propagates; clear failure propagates after suspension. A valid receipt suspends, clears pending recovery and returns CLEARED without granting editing, loading a snapshot or automatically replaying data. The current model assumes a successful operation advances the revision.
No transport or editing grant is needed for reconciliation. The caller authenticates, queries the pending operation, reconciles a verified applied outcome, and only then requests a new grant/snapshot at the current authoritative revision. Pending edits that have not been proven applied continue to block incompatible revision grants.

## Digest and storage compatibility
RecoveryEdit.contentDigest is derived from persisted fields. New EAP2 journals and v2 digests bind dataset incarnation; EAP1 files remain readable with a null incarnation and their original v1 digest, but cannot be automatically reconciled or reused for editing. No plaintext file or automatic legacy migration is introduced. The historical v1 SHA-256 input is:
1. Length-prefixed UTF-8 domain EveryArticle-Synthetic-Receipt-v1.
2. Length-prefixed UTF-8 synthetic application identity.
3. Signed 64-bit big-endian base revision.
4. Length-prefixed UTF-8 operation ID.
5. Length-prefixed UTF-8 exact synthetic payload.

Lengths are 32-bit big-endian byte counts; output is lowercase 64-character hexadecimal. This framing avoids ambiguous concatenation and binds identity/revision/operation/payload. All persisted identity/incarnation/operation/payload strings must be well-formed UTF-16; unpaired surrogates are rejected at RecoveryEdit construction and at digest entry before UTF-8 conversion, preventing replacement-character collisions. Existing malformed version-1 journals are rejected on load and preserved. Valid surrogate pairs (including emoji), Japanese and NUL remain supported. Historical v1 digests remain unchanged for read-only legacy records; new incarnation-bound records use v2 framing. Production canonicalization must be defined over bytes; this synthetic string representation must not be reused as its definition. The digest does not authenticate a sender, normalize production data, or solve operation-ID reuse with identical content. The encrypted payload remains the source of truth; the digest is recalculated after reading it.

## Regression coverage
- Applied outcome lost, lease expires, new N+1 grant initially rejected; exact receipt reconciles without MTP/lease; only an explicit later N+1 grant enables editing, with no automatic snapshot.
- Unrelated identity/operation returns NOT_THIS_OPERATION and retains the exact pending edit and active session. For the same identity/operation, conflicting base revision/digest/result suspends and retains the edit.
- An old receipt with a reused operation name cannot clear different payload content.
- Duplicate receipt cannot clear or interrupt a new operation; NO_PENDING also leaves an active unsaved snapshot intact.
- Read/clear failure retains the record and leaves editing disabled.
- Digest changes with each bound field, remains deterministic and distinguishes ambiguous concatenation.
- Malformed surrogates in identity/operation/payload are rejected before persistence/digest; normal emoji/Japanese/NUL are accepted. A DPAPI-protected legacy malformed record stops editing on read without altering encrypted bytes.
- Actual Windows DPAPI persistence/restart test reconciles without a grant and then accepts a higher revision; separate-JVM smoke uses revision 5 -> 6 rather than pretending the applied revision is unchanged.
- CI compiles all manual Java hardware drivers against the revised jar without running them against devices.

Windows CI passed all 31 Kotlin tests, native tests, separate-JVM recovery smoke and manual-driver Java 17 compilation for code commit 09e54b79af14b98168c9c9908f7850a1353cd157: [run 38236200127](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38236200127). This is synthetic receipt validation, not private-channel authentication or hardware application commit.

## Re-review N1 / N3 follow-up
N1 is addressed by the typed result and non-interrupting NO_PENDING / NOT_THIS_OPERATION behavior described above. Receipts are scoped by the current synthetic application identity and operation ID; same-operation binding conflicts suspend and retain, while matching clear attempts suspend before touching storage. Read/clear errors still propagate. Callers must inspect the returned result rather than assume a non-throwing call cleared recovery.
N3 is addressed by rejecting unpaired surrogates before hashing/persistence, including legacy journal load. Production byte canonicalization remains undecided; no malformed string is silently repaired.
Windows CI passed 36 Kotlin tests, native tests, separate-JVM DPAPI recovery, CLI packaging and Java 17 manual-driver compilation for code commit 783cc8e09e713c5c911114dfcb40a6e328058d48: [run 38240256984](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38240256984).
N2 is now implemented for synthetic inputs; see REVIEW-REVISION-FLOOR.md for the incarnation-bound v2 digest, encrypted revision floor and legacy policy. N4 (explicit user resolution/export/discard of rejected/unknown/conflicting pending edits) remains unresolved. No automatic discard or new user confirmation flow is introduced here.

## Deliberately unresolved review work
This fixes the controller dead end (item 3) and strengthens receipt content binding (part of item 4). It does not complete item 4: cryptographically generated operation IDs, authoritative dataset incarnation lifecycle, submitted session/epoch and submitted-state persistence remain to be designed. Incarnation fields and persistent revision floors now exist in the synthetic controller. SessionGate still has its older isolated synthetic pending/ack model; RecoveryController does not use that model to delete persisted records. Removing that duplicate model is separate follow-up work.
Grant.expiresAt remains a PC-local mock deadline; it must never be populated directly from a phone clock. Duration-based request-bound leases, clock drift/resume behavior, authoritative fencing, renewal, unknown-status retry policy, UI draft protection, multi-process exclusion, retention and save-interruption durability remain open. Private authentication and trusted operation-status query remain unimplemented.

No Android source, real data, changes to phone files, main commit or merge are part of this follow-up. Previous hardware observations describe the original runtime; this revision's receipt path is validated with synthetic tests, not an actual Android applied acknowledgement.
