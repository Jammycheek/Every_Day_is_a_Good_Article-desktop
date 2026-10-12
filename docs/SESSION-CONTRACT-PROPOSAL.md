# Application editing-session contract proposal

Status: review draft, not an implemented protocol or authorization to enable real data. This public document describes platform-neutral responsibilities only. Android implementation, app schemas, secrets and real data stay private. The verified baseline is [POC-RESULTS.md](POC-RESULTS.md).

## Required behavior
The phone is authoritative. A PC may edit only after the authoritative app authenticates it, acquires its application editing lock and explicitly grants a bounded session. MTP presence is transport evidence only. The lock applies to every application write path, including autosave, import and background changes, not just visible editor controls.
The PC releases its ordinary snapshot on transport loss, session revoke, timeout or expiry. Only protected unacknowledged edits survive. Returning transport never restores a session or replays an edit automatically.
The phone must eventually release an abandoned lock without requiring a PC release message. Physical cable presence cannot be its sole lock/unlock criterion.

## Independent boundaries
| Boundary | Responsibility | Cannot establish |
| --- | --- | --- |
| Transport adapter | Report USB/MTP/capability and failures; move bounded files | Authenticated application identity, editing permission or durable app commit |
| Session channel | Authenticate peer and message, bind fresh request/session, convey grants/renewals/revokes/status | MTP file receipt as application commit |
| Authoritative app | Serialize edits, enforce lock/expiry, validate revision and apply idempotently | PC snapshot release or local journal durability |
| PC recovery controller | Persist pending edit before accepting it; suspend safely; reconcile without blind replay | Phone-side lock correctness or operation status without a trusted reply |

The session channel may use a transport adapter, but channel authentication and lock semantics must remain independent of physical attachment. Its carrier, discovery and pairing mechanism are undecided. A filename, friendly name or WPD selector is never sufficient authentication.

## Logical messages (no wire encoding selected)
Every accepted message requires authenticated peer binding, protocol compatibility, bounded fields and replay protection. Correlate replies with a fresh request nonce and current session/epoch; reject duplicates that conflict, delayed replies to abandoned requests and unknown required fields/versions.

| Message | Required logical information | Authoritative behavior |
| --- | --- | --- |
| RequestSession | Fresh request nonce, paired PC identity, intended app/data identity | Serialize with all local writes; reject busy/incompatible/unauthorized requests |
| Grant | Request binding, app/data identity, session token, epoch, authoritative base revision, validity duration | Send only after lock acquisition succeeds and snapshot revision is stable |
| Renew / Renewed | Current session/epoch, fresh request binding, validity duration | Extend only a still-valid lock owned by this session; never resurrect an expired session |
| Release / Revoke | Session/epoch and reason | End matching permission; duplicate release is harmless; expiry remains independent |
| SubmitEdit | Session/epoch, operation ID, app/data identity, base revision, payload digest and bounded payload reference | Validate authenticity, active permission, revision and payload completeness before apply |
| QueryOperation / OperationStatus | Operation ID, authenticated identity, payload digest binding, status | Return durable status with resulting revision for an applied operation |

Statuses should distinguish applied, rejected and pending/in-progress. An unknown status is not sufficient permission to replay. Rejection reasons must distinguish identity mismatch, stale revision, expired permission, invalid payload and busy state without exposing private content in logs.
Operation IDs are unique per logical edit, immutable and not reused for different payloads. A repeated ID with different content must be rejected. Data identity includes the intended dataset/account, not merely a handset identity.

## Lock and lease sequence
1. Authenticate and negotiate compatibility. Establish application/data identity independent of the WPD selector. If there is a pending edit, query/reconcile its trusted operation outcome before acquiring a new editing grant; a newer revision must not prevent querying an already-applied operation.
2. The phone serializes RequestSession with local writes, acquires the application lock and fixes a snapshot/base revision. If any step fails, no grant is sent and no abandoned lock is left indefinitely.
3. The PC validates the fresh Grant, identity and base revision against any pending edit. It obtains the matching snapshot only under that grant. A mismatch keeps the pending edit and blocks editing.
4. The PC persists an edit durably before reporting it accepted. Transferred files remain staging input; the phone validates them before changing authoritative data.
5. Renew only while both peers agree the current session is valid. Missing/failed renewal suspends the PC; phone expiry releases the abandoned lock.
6. On suspension, stop new submissions, cancel pending transport best-effort, invalidate the PC grant and release its snapshot. A file already in transit must not bypass the phone's final session/revision check.

Use each peer's monotonic clock for its own deadlines; never compare raw PC and phone monotonic timestamps. A proposed conservative PC deadline is request-send time plus the returned validity duration minus a safety allowance, provided phone validity starts no earlier than receipt of that bound request. If the reply arrives after that conservative deadline, reject it. Renewal replies need the same fresh binding and must not revive a locally abandoned session. This timing rule needs private-channel validation; no duration, allowance or renewal interval is fixed here.
The phone must serialize final authorization/apply with lock expiry and local writers. Once a valid transaction is admitted, its atomic commit/rollback and lock handoff must prevent concurrent local editing from racing that transaction. Checking only at file-transfer start is insufficient.
After app restart or phone reboot, old session tokens/epochs are invalid. Recover committed/in-progress operation records before admitting conflicting new writes. Safe recovery must not rely on persisting a monotonic deadline across reboot.

## Durable apply and lost acknowledgements
The phone must atomically associate authoritative changes, resulting revision and operation outcome, or provide equivalent crash-safe recovery. It cannot report applied merely because an MTP write succeeded or a staging file exists.
The PC marks an edit applied and clears it only after a trusted applied outcome matches operation ID, data identity and payload digest. A separate explicitly confirmed local abandonment path may discard the local proposal without claiming it was applied or cancelled on the phone. If the acknowledgement is lost, retain the delta until reconciliation or that explicit decision. After reconnect, authenticate again and query the operation outcome before deciding whether a submission is needed.
- Applied with matching binding: reconcile the resulting revision and clear the local pending record using a verified receipt path.
- Rejected: retain the pending record and expose the reason; do not rewrite its base revision automatically.
- Pending/in-progress: wait/query under a bounded policy; do not duplicate an active operation.
- Unknown: determine whether authoritative revision and status-retention guarantees permit a retry. Reacquire a grant and require revision compatibility before any allowed retry; otherwise require reconciliation.

Define operation-status retention before real-data use. Forgotten outcomes plus changed revisions must not result in silent overwrite. A different dataset/phone or revision conflict requires explicit reconciliation; no blind replay.

## Explicit user resolution of blocked pending edits
Rejected, unknown or conflicting pending edits remain protected and block incompatible new grants until reconciliation or an explicitly confirmed local discard. The public synthetic controller supports only the discard choice: preview the current readable record, display its identity/incarnation/base revision/operation and loss consequences, require affirmative user confirmation, then re-read and compare before deletion. Cancel, a changed preview or an invalid/old token never deletes the record. Success suspends the PC session and preserves verified revision floors; it enables neither editing nor automatic replay. A new authenticated status/current-revision check and explicit fresh grant/snapshot are required before further editing. Local discard never cancels an in-progress remote operation or rolls back applied data. See REVIEW-PENDING-DISCARD.md for API results and tests.
No user-facing UI exists yet; the token alone does not prove user consent. Encrypted export, rebasing onto a new revision, unreadable-record quarantine, operation-status retention and handling of still-running remote operations require private integration design. Until implemented, those alternatives must retain/block rather than silently delete, guess an outcome or replay.
Late applied outcomes remain useful even after editing permission expires. Reading a verified receipt must not itself re-enable editing. RecoveryController now provides reconcileApplied for exact synthetic receipts without a live grant or MTP availability; it binds the persisted identity, operation, base revision and content digest and requires an advanced resulting revision. Authentication and status querying are still not implemented. See REVIEW-RECONCILIATION.md.

## Current PoC gaps
The next public stage now implements synthetic request/nonce/epoch/duration checks, protected epoch high-water marks and renewal/rebaseline validation; see REQUEST-BOUND-GRANTS.md. It removes the absolute Grant.expiresAt mock. This is not authenticated wire communication or phone epoch fencing; those gaps remain. Historical PR #1 review documents describe their original commits.
Grant/PendingEdit/AppliedReceipt now include explicit synthetic dataset incarnation. RecoveryEdit derives a versioned SHA-256 digest binding it, and AppliedReceipt binds the digest and resulting revision. The controller persists a revision floor by application identity/incarnation before returning accepted grants or clearing applied edits and rejects older grants after process restart. This is not a production canonical schema or signature. There is no authenticated carrier, negotiated version, epoch, authoritative snapshot acquisition, renew protocol, operation-status query or phone apply transaction. Authoritative incarnation lifecycle, generated unique operation IDs and submitted-state persistence remain missing. The one-operation journal is not a production document schema. Manual fixture grants/receipts are test inputs, not authentication. See REVIEW-REVISION-FLOOR.md.
The PoC rejects unpaired surrogates so distinct malformed strings cannot collapse to the same replacement bytes during hashing. Production canonicalization must still be defined over bytes. Reconciliation distinguishes no pending/unrelated receipts (leave editing unchanged) from same-operation conflict (suspend and retain) and a matching applied receipt (suspend and clear).
The polling bridge can miss rapid unplug/replug; private session validation and authoritative revision checks must still protect apply. Windows recovery-directory ownership now excludes a second cooperating process/instance; see REVIEW-DIRECTORY-OWNERSHIP.md. Persistence interrupted during save, history recovery/retention and power-loss durability need separate work before real data.

## Private integration acceptance tests
The R1 synthetic API now supports explicitly confirmed history forgetting/quarantine and rejects ordinary grants for missing keys after history loss. Private integration must query current authoritative state and bind a fresh replay-protected grant before calling acceptFreshHistoryBaseline; its name/input do not authenticate freshness. A history-loss warning remains after baseline establishment and after restart. See REVIEW-HISTORY-RECOVERY.md. Pending edits are not silently resolved by history recovery, and a reused authoritative incarnation remains a private-side bug.
| Scenario | Required outcome |
| --- | --- |
| Unauthorized peer / forged or stale grant | No lock grant, no PC editing |
| Local edit racing session acquisition | Serialized decision; stable snapshot/revision, no simultaneous writers |
| Two PCs requesting a session | At most one editing owner |
| USB loss, charging-only, missed renewal or app failure | PC suspends; phone unlocks safely after expiry/recovery without release delivery |
| Renewal response delayed past abandonment | Session stays invalid; fresh acquisition required |
| Lease expiry during transfer/apply | Stale input cannot apply; admitted transaction finishes/rolls back without concurrent writers |
| Duplicate submission / same ID with changed digest | Same valid operation applies once; changed content rejected |
| Commit succeeded but acknowledgement lost | Status query proves applied; no duplicate application |
| Wrong dataset or stale base revision | Pending edit retained; no overwrite |
| PC or phone crash before/after commit | Outcome reconciled with authoritative data; no grant/snapshot restored automatically |
| App restart / phone reboot | Old epoch invalid; lock and operation recovery permit safe local editing |
| Status retention expired / unreadable PC journal | No guessed outcome or blind replay; explicit recovery path |

## Decisions needed before implementation
Highest remaining local recovery gaps are T2 (unreadable pending journal/key loss) and T1 (environment/key failure versus ciphertext rejection; canary not implemented). S2 warning scope/clear policy stays conservative pending actual authentication. PR #2 review follow-up unifies floor and epoch history, applying confirmed forget/quarantine to both; see REQUEST-BOUND-GRANTS.md. Such recovery deliberately loses both rollback defences.
PR #1 closes its scope with S1 failure classification and S2/S3 documentation; see REVIEW-FAILURE-CLASSIFICATION.md. The next PR prioritizes nonce/epoch/duration binding and stale/late/other-request synthetic rejection tests. History loss currently makes all missing keys, including new datasets, require explicit baseline indefinitely. Normal/rebaseline mock grants differ only in local admission policy, not authenticated freshness. Warning scope/expiration/reset is a later protocol decision.
Select the authenticated session carrier and pairing model; define dataset identity and authoritative revision semantics; choose lease/renewal timing from measured behavior; define phone lock coverage and restart recovery; define atomic apply/status persistence and retention; choose conflict/receipt recovery UX and PC multi-process policy.
Next step: review these responsibilities against the private application's existing persistence/write paths and platform lifecycle. Changes to public mock APIs should follow that review, with synthetic tests only. No Android-specific implementation is proposed for this repository.
