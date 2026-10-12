# Sync protocol v1: phone ⇄ PC one-edit round trip

Status: implemented for synthetic data (public `protocol/`, `desktop-core/`, `desktop/`), with a private Android
adapter in a separate repository. Not validated on hardware yet; see [SYNC-HARDWARE-VALIDATION.md](SYNC-HARDWARE-VALIDATION.md).
This document is the public, platform-neutral contract. It contains no Android source or application data.

## 1. Principles

- The phone is the source of truth. The PC edits only inside a request-bound, phone-granted session.
- USB-MTP is a file carrier only. Seeing the phone over MTP never grants, renews or proves anything.
- Every non-pairing message is authenticated and encrypted with a key established by user-confirmed pairing.
- The phone's edit lock does not depend on the cable: it ends by explicit release or by its own expiry clock.
- The PC releases imported data when permission ends and keeps only its own unacknowledged edit, encrypted (DPAPI).
- Nothing is resent unless the phone proves the edit was not applied and the base is unchanged.

## 2. Responsibilities

| Component | Owns | Never does |
| --- | --- | --- |
| MTP transport (WPD helper / Android SAF) | Moving `*.eam` files in `EveryArticleSync/to-phone` and `to-pc` | Authenticate, authorize or interpret content |
| Envelope + pairing (`protocol/`) | Peer authentication, confidentiality, direction binding, replay detection | Decide whether an edit may apply |
| `PhoneAuthority` (reference rules, run on the phone) | Edit lock, epoch fence, expiry, base revision, idempotent apply, operation status | Trust MTP presence or PC clocks |
| Phone app adapter (private) | Room transactions, revision bump on every local write, Keystore-wrapped keys, UI lock overlay | Write content outside the `WriteGate` |
| `PcSyncClient` + `RecoveryController` (PC) | Request-bound grants, conservative local deadline, encrypted draft/pending edit, reconciliation by query | Clear a pending edit without a matching applied report or explicit user discard |

## 3. Transport and envelope

Shared folder at the top of the phone's internal storage: `EveryArticleSync/{to-phone,to-pc}/`. File names are
`<13-digit wall millis>-<16 hex>.eam`; anything else is ignored by both sides. The phone writes `name.part` and
renames it; the PC helper only creates objects with the final name (a partially transferred object fails
authentication and is discarded by the phone after 30 s). The native helper refuses to read/delete objects that are
not message files directly inside these folders.

Envelope: `"EASY" | u8 version=1 | u8 kind | sender | recipient | messageId(16)` then either the plaintext pairing
body (kind 0) or `iv(12) | AES-256-GCM(body)` with the header as associated data (kind 1). The message key is
`HKDF(pairingKey, "EA-message-key-v1|<writer role>")`, so a file can never be replayed in the other direction.
Recipient must equal the reader's ID; only a pairing start may be addressed to `*`. Integers are big-endian;
strings are strict UTF-8 (malformed or unpaired surrogates are rejected); trailing bytes are rejected.

## 4. Pairing (numeric comparison)

1. The user opens the phone's pairing screen, then starts pairing on the PC.
2. PC → `PairStart(pcId, pcName, commitment = SHA256(pcId, pcPublicKey, pcNonce))`.
3. Phone → `PairAccept(phoneId, phoneName, phonePublicKey, phoneNonce)` (ECDH P-256).
4. PC → `PairReveal(pcPublicKey, pcNonce)`; the phone checks the commitment.
5. Both derive `SAS = HKDF(ECDH, transcript, "EA-sas-v1") mod 10^6` and show 6 digits.
6. Each user confirms on their own screen; each side then sends `PairConfirm(HMAC(pairingKey, role, transcript))`.
   A side stores the peer only after its own user confirmed **and** the peer's confirmation verifies.

The commitment prevents a man in the middle from searching for keys that produce equal codes. Pairing is accepted
by the phone only while its pairing screen is open. Keys: PC – DPAPI (current Windows user); phone – AES-GCM wrapped
by an Android Keystore key. Unpairing deletes the local key; a held lock still expires on its own.

## 5. Identity and counters (phone)

| Value | Created | Changes | Never |
| --- | --- | --- | --- |
| `phoneId` (app identity) | random at first use | — | derived from hardware IDs |
| `incarnation` | random at first use | **regenerated on every backup restore** (dataset replaced) | reused |
| `revision` | 0 | +1 in the same DB transaction as every content write, local or PC | decreases (not even on restore) |
| `epoch` | 0 | +1 at every process start and every new session, persisted before granting | decreases |
| `prunedThroughRevision` | 0 | highest resulting revision whose operation record was deleted | decreases |

The counters live in `sync_state`, which is excluded from backups and Android cloud/device transfer, so a restore
cannot roll them back. Restore runs under the write gate (refused while a PC session holds the lock), replaces the
data, regenerates the incarnation and advances revision and epoch in the same transaction. Any PC pending edit or
grant from before the restore is then rejected with `INCARNATION_MISMATCH`; the PC asks the user to export/discard.

## 6. Session lifecycle

```
                 StatusRequest/Report (any time, authenticated)
 PC idle ──beginGrantRequest(nonce, sentAt)──► SessionRequest(ACQUIRE, incarnation, maxDuration)
                                               │ phone: gate busy? → BUSY
                                               │ incarnation differs? → INCARNATION_MISMATCH
                                               │ epoch+1 persisted, lock(deadline = now_phone + validity)
 PC ◄── SessionGrant(nonce, sessionId, epoch, baseRevision, validity) ──┘
 PC: reply matches pending request object + nonce? else IGNORED_UNRELATED (session untouched)
     deadline_pc = sentAt + validity − safetyAllowance;  reject if arrival ≥ deadline_pc
     persist max(floor, baseRevision) and max(epoch) atomically, then activate
 PC ── SnapshotRequest ──► Snapshot(revision == baseRevision) → editor; snapshot owned by RecoveryController
 renew at < 1/3 validity: SessionRequest(RENEW, sessionId, epoch) → same session/epoch/revision, longer deadline
 end: SessionRelease (best effort) | phone expiry | PC deadline | transport loss | app/process restart
```

Clocks: each side uses only its own monotonic clock (phone `elapsedRealtime`, which keeps running in deep sleep;
PC `System.nanoTime` read inside the controller). They are never compared. The phone starts the lease after the
epoch is durable, i.e. no earlier than receipt of the request, so `sentAt + validity − allowance` on the PC is
always earlier than the phone's deadline (allowance covers clock-rate drift). Durations are policy, not protocol:
the current synthetic defaults are 5 min requested, 15 s allowance and a 10 min phone maximum, pending measurement.

## 7. One write gate on the phone

`WriteGate` is a single fair semaphore. Every content write path takes it: all `RoomNoteRepository` mutations
(topics, tags, articles, images), backup restore, and the authority's acquire/renew/apply/expire/release. Local
writes fail immediately with `EditLockedException` while a live PC lock exists; an expired lock is cleared under the
same gate before the next decision, without any PC message. Inside the gate, PC apply checks in one transaction:
session matches peer/sessionId/epoch and is unexpired, `state.epoch == epoch` (epoch fence), incarnation, base
revision, article exists, and — as the last step before writing, after any wait for the database — that the lock's
deadline has not passed on the phone's clock; then updates title/body, `revision+1` and inserts the operation record.
Renewal and snapshot also re-check the deadline after their database read, so an expired lock is never revived and
no data leaves after expiry. (The commit itself completes a few milliseconds after that check; the PC's deadline is
earlier by the safety allowance, so this never extends what the PC believes it may do.) Drafts and
"last opened" metadata are not content and are not blocked, so typing on the phone is never lost; instead a local
save compares the article's `updatedAt` captured when editing began and refuses to overwrite a PC-applied change.

## 8. Edit unit and operation identity

v1 edits exactly one existing article's **title and body** (`ArticleTextEdit`): the smallest unit in the app's
model that users edit as text, applied as a single-row update that never touches photos, dates or layout.
- Operation ID: 128-bit random, generated by the PC when the first draft of the edit is saved, never regenerated.
- Canonical bytes: `"EA-article-text-edit-v1" | articleId | optional title | body`.
- Digest: `SHA256("EA-operation-v1" | operationId | incarnation | baseRevision | canonical edit)`.
- Submission carries sessionId + epoch (the fence) and the digest; the phone recomputes it.
- Duplicate submission of the same ID and digest returns the original `APPLIED` result; the same ID with different
  content is `REJECTED(OPERATION_ID_REUSED)`. Nothing is applied twice.

## 9. Operation status and resend rule

| Status | Meaning (phone) | PC action |
| --- | --- | --- |
| `APPLIED(resultingRevision)` | record with this ID and digest exists | reconcile receipt, clear pending, release |
| `NOT_APPLIED` | no record, same incarnation, `revision == base`: provably never committed | **only status that allows resubmission** (same operation ID, after a fresh grant at the same base, user-initiated) |
| `IN_PROGRESS` | being applied right now | wait and query again; never resend |
| `CONFLICT` | no record and the dataset moved on (or apply refused for base mismatch) | keep pending; user exports/discards; never resend |
| `REJECTED(reason)` | will never apply (ID reuse, digest mismatch, incarnation changed, unknown article) | keep pending; user decides |
| `UNKNOWN` | the record that could prove the outcome may have been pruned (`base+1 ≤ prunedThroughRevision`) | keep pending; never resend; user checks the phone |

Queries need an authenticated peer but no session, so a lost reply is resolved after expiry, reconnect or app
restart. The PC persists `submitted = true` before sending; a submitted edit is never edited again.

## 10. Retention

Phone: applied-operation records are kept while among the newest 256 **or** younger than 30 days, and are deleted
strictly in ascending resulting-revision order with a watermark (`prunedThroughRevision`). A query whose answer
could depend on a deleted record returns `UNKNOWN` instead of guessing, so retention can never produce a wrong
`NOT_APPLIED`. PC: a pending edit is never deleted automatically; only a matching applied report or an explicitly
confirmed discard/quarantine clears it. Seen message IDs: newest 4096 per phone.

## 11. Unsaved editor text

The PC editor autosaves ~0.7 s after typing stops into the DPAPI journal (one staged operation, same operation ID
until submitted). The newest editor text is held in a single slot handed to the sync worker, and **every** worker
task — end editing, connect, article switch, the periodic tick, window close — saves it first, so none of them can
overtake the debounced autosave. Submitting saves exactly the on-screen text and sends nothing if that save fails.

If the text can no longer be saved (permission already ended, journal write failed, another article holds the
pending edit), it is kept in memory as *unsaved input*, shown outside the editor with "save as text" and a confirmed
"discard". It is put back into the journal automatically only in a new session at the same incarnation and base
revision (nothing moved on the phone, nothing is sent); otherwise it stays aside for export. Closing the window
saves and releases first; if unsaved input remains, the app asks to export it, discard it, or stay open.

Disconnect, expiry or a failed renewal releases the snapshot and closes editing; the protected draft is offered
again after reconnect if the base revision is unchanged, or as a `CONFLICT` decision otherwise. On the phone,
drafts keep autosaving.

## 12. Failure handling summary

| Event | Result |
| --- | --- |
| Unplug / charging-only / app restart on either side | PC snapshot released; phone lock expires or vanishes with the process (epoch advanced at start) |
| Late, duplicate, foreign or replaced grant reply | `IGNORED_UNRELATED`, session and outstanding request untouched |
| Grant reply after the conservative deadline, wrong identity/epoch | rejected, session suspended |
| Renewal after expiry | refused (`SESSION_EXPIRED`); never revives |
| Reply lost after apply | PC times out without resending; next connect queries → `APPLIED` → cleared |
| PC journal write failure | editing stops, last saved text kept |
| Phone write failure | transaction rolled back; status stays `NOT_APPLIED` |
| Backup restore on phone | `INCARNATION_MISMATCH` for old pending/grants |
| Unreadable PC pairing file at start | shown on screen (temporary / key state / rejected / format); retry, or a confirmed move-aside (file renamed, never deleted) and pairing again as a new PC. Pending edit and history are separate files and untouched |
| Unreadable PC journal/history | existing confirmed quarantine/export flows (PR #1–#5), reason-specific guidance (V3) |

## 13. Decisions taken for v1 and open items

Decided with the owner: MTP file mailbox (no network permission on the phone), 6-digit numeric comparison pairing,
public pure-Kotlin protocol module vendored by the app at a pinned commit with checksums.
Open: session/allowance durations from measurement; MTP visibility latency of SAF-written files; multi-article or
photo edits; macOS/Linux mailbox and key-storage adapters; Android instrumentation tests of the Room adapter.
