# Re-review R2: exclusive Windows recovery-directory ownership

R2 identified a lost update: two processes could both read floor X:5, then replace it with X:7 and X:6. max within each process did not prevent rollback between them. The same race affected pending save/clear and confirmation previews.

## Implemented boundary
WindowsDeltaJournal acquires a nonblocking exclusive FileChannel lock on journal.lock in its recovery directory during construction, before reading or changing pending edits or revision floors. It retains the channel/lock for the whole journal lifetime, not just individual writes. A second instance in the same JVM or another cooperating process fails construction with JournalInUseException. Other open/permission/locking errors propagate; there is no fallback to unlocked access, waiting, lock-file deletion or automatic takeover.

The lock covers both pending.dpapi and revision-floors.dpapi and protects read/modify/replace, save-before-clear and preview-to-discard against another cooperating journal owner. The journal's public operations and close are synchronized in the JVM and check that ownership remains valid. After close, load/save/clear/floor reads/writes reject access; a closed object cannot be reused after another owner starts. The controller/event loop must still serialize its callbacks and stop/suspend before closing the journal. Sharing one journal among multiple controllers is unsupported.

The application must retain this journal for its entire editing/recovery session. Close is idempotent and releases OS ownership; normal process exit and forced process termination also release the OS handle. The journal never unlinks journal.lock. Deleting/replacing it while owned could split the locking identity and defeat exclusion. A leftover lock file is expected and is not evidence that another process is alive. Do not remove it as startup cleanup. The directory and lock receive owner-only Windows ACLs.

Kotlin recovery/monitor fixtures and Java manual fixtures now explicitly scope journal ownership. The monitor stops its workers before closing storage. Existing storage reconstruction tests close the previous owner before opening another. New owner acquisition does not restore a grant or snapshot and does not replay pending data.

Under this cooperating-owner protocol, revision floors do not decrease through concurrent journal replacement: one owner reads the latest persisted value and records max(existing, verified revision). This does not protect deliberate direct filesystem edits, restoration/deletion of history, old binaries without the lock protocol, malicious same-user access or using separate copies of a dataset's storage directory. Use one dedicated local Windows recovery directory; network/removable filesystem lock semantics and unusual path aliases are not validated.

## Tests
- Same JVM, same directory through a dot-path alias: second owner rejected, pending/floor bytes unchanged. A separate directory remains independently usable.
- Idempotent close, all public access rejected after close, retained lock file and successful new ownership; lock ACL remains owner-only.
- Actual separate JVMs: one holds ownership with synthetic pending and floor 7; a contender is rejected without modifying either protected file. After graceful release, another JVM reads both and cannot lower the floor by recording 6.
- Forced termination of only the dedicated synthetic owner process: OS releases ownership, a fresh JVM opens the same directory, retains pending/floor and rejects a floor decrease. This terminates after completed saves; it is not a save-interruption or power-loss test.
- Existing DPAPI corruption, legacy, discard, separate-JVM recovery/floor and manual Java compilation checks remain required.

Windows CI succeeded for code commit 4755de5d3b4d03648cd1fb1027c122ac510ee5ac: [run 38245716650](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38245716650). It ran the 58-test Kotlin suite (including actual separate-JVM ownership/termination tests), two native tests, the three-JVM DPAPI recovery/floor smoke, Java 17 manual-driver compilation, native self-test and CLI packaging. Subsequent commit documents the result only. Manual hardware drivers were compiled without device access; ownership trials used disposable local synthetic directories.

## Still open
R1 now implements confirmed scoped history forgetting/whole-file quarantine, persistent warning and explicit fresh per-key baseline; see REVIEW-HISTORY-RECOVERY.md. Actual UI, fresh authenticated state acquisition, real key-loss and OS permission recovery remain unverified. R3 temporary-file prefix and R4 API naming/duplicate SessionGate model are separate work. No authenticated private channel, actual Compose UI, phone lock/apply changes, real data, E: access or main merge is included. Prior hardware results used the earlier runtime; this change requires no phone connection.
