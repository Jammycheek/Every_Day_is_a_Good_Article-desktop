# Review follow-up: lost applied acknowledgement

Scope: synthetic public PoC only. Review item 3 identified a dead end: after authoritative apply N -> N+1 and lost acknowledgement/expiry, the old API rejected the N+1 grant because the pending edit used N, while deleting the pending edit required a live grant. The old CI-built jar reproduced both failures with an in-memory synthetic journal.

## Implemented boundary
RecoveryController.reconcileApplied(AppliedReceipt) replaces the controller's lease-dependent acknowledge API. A future channel must authenticate the applied outcome **before** calling it; constructing this data class proves nothing. It is not a network message parser and performs no phone query.

The receipt must match the persisted synthetic application identity, base revision, operation ID and content digest. Its resulting revision must be strictly greater than the base revision. Any attempt suspends editing and releases the snapshot; mismatch/read error does not clear the journal, and clear failure propagates with editing disabled. A valid receipt clears pending recovery without granting editing, loading a snapshot or automatically replaying data. The current model assumes a successful operation advances the revision.
No transport or editing grant is needed for reconciliation. The caller authenticates, queries the pending operation, reconciles a verified applied outcome, and only then requests a new grant/snapshot at the current authoritative revision. Pending edits that have not been proven applied continue to block incompatible revision grants.

## Digest and storage compatibility
RecoveryEdit.contentDigest is derived from the fields already persisted in the DPAPI-protected version-1 journal; no journal migration or plaintext file is introduced. SHA-256 input is:
1. Length-prefixed UTF-8 domain EveryArticle-Synthetic-Receipt-v1.
2. Length-prefixed UTF-8 synthetic application identity.
3. Signed 64-bit big-endian base revision.
4. Length-prefixed UTF-8 operation ID.
5. Length-prefixed UTF-8 exact synthetic payload.

Lengths are 32-bit big-endian byte counts; output is lowercase 64-character hexadecimal. This framing avoids ambiguous concatenation and binds identity/revision/operation/payload. It does not authenticate a sender, normalize production application data, or solve operation-ID reuse with identical content. The encrypted payload remains the source of truth; the digest is recalculated after reading it.

## Regression coverage
- Applied outcome lost, lease expires, new N+1 grant initially rejected; exact receipt reconciles without MTP/lease; only an explicit later N+1 grant enables editing, with no automatic snapshot.
- Wrong identity, base revision, operation, digest or non-advanced result retains the exact pending edit.
- An old receipt with a reused operation name cannot clear different payload content.
- Duplicate receipt cannot clear a new operation.
- Read/clear failure retains the record and leaves editing disabled.
- Digest changes with each bound field, remains deterministic and distinguishes ambiguous concatenation.
- Actual Windows DPAPI persistence/restart test reconciles without a grant and then accepts a higher revision; separate-JVM smoke uses revision 5 -> 6 rather than pretending the applied revision is unchanged.
- CI compiles all manual Java hardware drivers against the revised jar without running them against devices.

Windows CI passed all 31 Kotlin tests, native tests, separate-JVM recovery smoke and manual-driver Java 17 compilation for code commit 09e54b79af14b98168c9c9908f7850a1353cd157: [run 38236200127](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38236200127). This is synthetic receipt validation, not private-channel authentication or hardware application commit.

## Deliberately unresolved review work
This fixes the controller dead end (item 3) and strengthens receipt content binding (part of item 4). It does not complete item 4: cryptographically generated operation IDs, dataset incarnation, submitted session/epoch and submitted-state persistence remain to be designed. SessionGate still has its older isolated synthetic pending/ack model; RecoveryController does not use that model to delete persisted records. Removing that duplicate model is separate follow-up work.
Grant.expiresAt remains a PC-local mock deadline; it must never be populated directly from a phone clock. Duration-based request-bound leases, clock drift/resume behavior, authoritative fencing, renewal, unknown-status retry policy, UI draft protection, multi-process exclusion, retention and save-interruption durability remain open. Private authentication and trusted operation-status query remain unimplemented.

No Android source, real data, changes to phone files, main commit or merge are part of this follow-up. Previous hardware observations describe the original runtime; this revision's receipt path is validated with synthetic tests, not an actual Android applied acknowledgement.
