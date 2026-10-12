# PC recovery PoC
Synthetic data only; no private Android protocol or real application schema.
RecoveryController receives explicit transport observations and mock grants/applied receipts representing a future authenticated channel. The TransportCoordinator/WindowsTransportReader polling bridge connects WPD observations to a serialized event loop; no Compose UI or private Android channel is implemented. See TRANSPORT-SESSION-BRIDGE.md, POC-RESULTS.md and REVIEW-RECONCILIATION.md.

## Behavior
- Save one synthetic delta before reporting it staged.
- Disconnect, explicit suspension, or lease expiry tick zeroes/releases the controller's owned snapshot byte array and invalidates the session.
- Reconnection alone cannot grant editing rights.
- Restart reads only the encrypted delta, never a full snapshot or editing grant.
- Recovery candidate must match application identity and base revision; no automatic retransmission.
- reconcileApplied returns CLEARED / NO_PENDING / NOT_THIS_OPERATION / CONFLICT. It clears only for a pre-authenticated receipt matching persisted identity, base revision, operation and digest, with an advanced result revision. No pending edit or unrelated identity/operation leaves the active session unchanged. A same-operation conflict, read failure or matching clear attempt suspends editing. It requires neither transport nor an editing lease and never enables editing.
- Corrupted/unreadable journals raise errors and remain on disk.

WindowsDeltaJournal uses the helper's binary protect/unprotect pipe with user-scope DPAPI (no machine scope), an owner-only ACL and a forced encrypted temporary file followed by atomic move. No plaintext file, console payload or command argument is used.
Calls are serialized and there is one writer. Each string is bounded to 4096 UTF-8 bytes; this is a one-operation synthetic record, not a journal of application documents.
Unpaired UTF-16 surrogates are rejected before persistence or digest encoding, including on legacy journal load. The modified-UTF-8 journal format is unchanged for valid strings; production digest canonicalization must be specified over bytes rather than copied from this string fixture.
The same Windows user typically restores data; Windows account/profile loss is outside this PoC. Admin/same-user malware is not addressed by these measures.

## Verification
Common tests cover detach, expiry, restart, wrong identity/revision, persistence failure, mismatched/duplicate applied receipts, clear/read failure and lost acknowledgement after revision advance.
Windows JVM integration test uses the actual native DPAPI helper, writes a synthetic delta, reconstructs the controller/store, checks identity, clears via a matching mock applied receipt without a lease and tests corruption rejection. The separate-JVM smoke reconciles revision 5 -> 6 before acquiring a new revision-6 grant.
Build the native helper, set POC_HELPER to its absolute path, then run gradle jvmTest. CI does this in Windows. All test data is synthetic.

## Limits
Normal process restart and forced termination after completed save were locally verified with synthetic data. Interruption during save, Windows reboot and power loss remain untested. No directory fsync guarantee, multi-process locking, retention policy, secure erasure of JVM strings, Android identity authentication or idempotent apply-status query is implemented.
Zeroing only affects the controller-owned byte array; JVM/native copies may remain.
Crash after phone application but before journal deletion can leave an already-applied pending operation; private Android integration MUST query operation status before replay.
A crash can leave an encrypted temporary file. It is not silently promoted or deleted at startup.
UI must drive lease ticks and dispatch all controller callbacks on one serialized event loop. Native transport loss does not automatically imply the phone released its editing lock.
This public PoC is not enabled for real data.

Reference: https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata
