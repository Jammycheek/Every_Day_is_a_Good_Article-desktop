# Re-review N2: retained verified revision

Synthetic public PoC only. After clearing an applied pending edit, the controller previously forgot the resulting revision and could accept a delayed older grant. It now retains a monotonic revision floor keyed by DatasetIdentity(application identity, dataset incarnation).

## Identity and accepted inputs
The existing device field denotes authenticated logical application identity, not a WPD selector. Grant, PendingEdit and AppliedReceipt now also carry datasetInstance. A valid incarnation is nonblank, well-formed Unicode, supplied by a future authenticated authoritative channel. No default, inference from handset/filename/revision, or automatic ID generation is used for grants/receipts. Fixtures explicitly use fake-dataset-v1.
The authoritative app must create a fresh incarnation when a dataset is newly created, restored or reinstalled so reused/reset revisions cannot be mistaken for the previous dataset. That private-side lifecycle and authentication are not implemented here. Application identity and incarnation both scope the floor; old floors are not transferred to another incarnation.
Pending candidates and applied receipts must match the incarnation. Version-2 content digests bind it along with application identity, base revision, operation ID and payload. An unrelated incarnation receipt leaves the active session and journal unchanged.

## Ordering and fail-closed behavior
1. Before accepting a grant, read the pending edit and stored floor. Reject a base revision below the floor or a pending identity/incarnation/revision mismatch. Validate transport and lease through the gate.
2. Persist max(previous floor, accepted grant base revision) before returning successful grant acceptance. Storage failure suspends permission/releases snapshot. Calls are serialized, so no caller observes intermediate permission.
3. For an exact verified applied receipt, suspend the session and persist max(previous floor, resulting revision) **before** deleting the pending journal. Failure retains the pending edit; a successful receipt never grants editing.
4. On a fresh process, read the same protected floor when accepting a grant. Equal/newer versions may pass the floor check, but still need a valid explicit grant; no snapshot or permission is restored automatically.

| Failure point | Persisted state | Behavior |
| --- | --- | --- |
| Floor read/parse/decryption fails | Existing files retained | New grant rejected; session suspended |
| Floor save fails before pending clear | Pending retained | Reconciliation fails; no editing |
| Floor saved, pending clear fails or process stops | New floor plus pending may remain | Matching applied outcome can be queried/reconciled again; floor never decreases with one cooperating locked owner |
| Pending clear succeeds | Floor remains after pending removal | Older grants rejected across process restart |

## Windows storage and compatibility
revision-floors.dpapi uses user-scope DPAPI, owner-only ACL, encrypted temporary-file force and atomic replacement. It holds at most 256 distinct identity/incarnation entries, bounded fields and bounded binary payload. Capacity failure is an error; old entries are not silently evicted. readEncrypted distinguishes a missing file from access denied/other IO errors; unreadable history is not interpreted as empty history. Clearing pending.dpapi does not remove revision-floors.dpapi.
New pending files use the EAP2 version with an explicit incarnation. EAP1 files remain readable with datasetInstance=null, retaining their original version-1 digest and bytes. Such unbound records block new grants and cannot be cleared by a receipt guessing an incarnation. They are not rewritten or silently assigned to the current phone. N4 now permits explicitly confirmed local discard of readable legacy records without guessing identity; export/migration remains deferred. See REVIEW-PENDING-DISCARD.md. Use a fresh synthetic build directory for revised hardware fixtures and keep old evidence intact.
Version-2 digest framing uses EveryArticle-Synthetic-Receipt-v2 followed by application identity, incarnation, base revision, operation ID and payload; strings are length-prefixed UTF-8 and revision is big-endian 64-bit. Version-1 framing remains for reading unbound legacy records only.

## Verification
Common tests cover restart rejection of older grants, equal/newer acceptance, separate incarnation/application keys, cross-incarnation receipts, digest binding, floor save/read failure, save-before-clear failure, monotonic max behavior, accepted grant persistence and unbound legacy retention.
Windows tests use actual DPAPI to check repeated floor updates/reload, independent keys, ACL/protected storage, grant rejection after reconstruction, corrupt history preservation, and readable-but-unbound EAP1 retention. runRecoveryRestartSmoke now runs three JVMs: seed edit at revision 5, reconcile applied revision 6, then independently verify that revision 5 is rejected and revision 6 accepted.
Windows CI passed all 47 Kotlin tests, native tests, the three-JVM recovery/floor smoke, Java 17 manual-driver compilation and CLI packaging for code commit e82e7e0c11bd9d6199e36a724b5827352ef876ab: [run 38241889671](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38241889671). This is synthetic identity/receipt validation with actual Windows protected storage, not private phone authentication or application integration.

## Remaining limits
R1 now provides explicitly confirmed identity-scoped forgetting or whole-file quarantine, with a persistent history-loss warning and explicit fresh baseline required for missing keys after recovery. The intentional reset is an exception to retaining the old floor; it is not automatic rollback acceptance. See REVIEW-HISTORY-RECOVERY.md for ordering, failure behavior and the unimplemented fresh authentication/confirmation UI requirements.
N4 now implements preview-bound confirmed local discard, including readable unbound legacy records. Its actual confirmation UI, encrypted export, rebase and unreadable-record recovery remain unimplemented; see REVIEW-PENDING-DISCARD.md. Generated unique operation IDs, submitted state/epoch, authoritative dataset lifecycle, authenticated status queries and request-bound duration leases remain open. The floor is a local defensive check, not a substitute for fresh authenticated grants or phone-side revision/fencing checks.
WindowsDeltaJournal now enforces one cooperating owner per recovery directory with a lifetime OS file lock; see REVIEW-DIRECTORY-OWNERSHIP.md. Controller callbacks remain serialized. Monotonic max requires this ownership protocol and does not cover external history replacement or old unlocked binaries. This is ordinary process persistence, not a power-loss or full anti-rollback guarantee. Deliberately deleting/restoring older protected history, account/key loss and malicious same-user access are outside this protection. No automatic floor pruning/reset is provided; retention must be designed with authoritative operation-status retention.
No Android source, real data, phone writes or main merge are included.
