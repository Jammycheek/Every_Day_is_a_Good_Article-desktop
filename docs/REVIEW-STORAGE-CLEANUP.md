# Re-review R3 / R4: storage names and one recovery model

Synthetic public PoC cleanup under the existing ownership/recovery contracts.

## R3 temporary-file names
WindowsDeltaJournal.writeProtected now selects pending-*.tmp for pending.dpapi and floor-*.tmp for revision-floors.dpapi. The generic history warning already uses history-marker-*.tmp. Unknown protected targets are rejected. Prefixes identify the intended destination of an orphan temporary file; they do not prove its completeness or authorize promotion/deletion. No startup cleanup, retention policy or power-loss guarantee is introduced.

The new prefixes apply to new writes. Historical pending-*.tmp files may have come from either target and cannot be classified retroactively by name alone.

## R4 verified-revision API
DeltaJournal.recordAppliedRevision is renamed recordVerifiedRevision throughout implementations, controller calls, synthetic fixtures and tests. Both a trusted accepted grant's base revision and a trusted applied receipt's resulting revision are verified inputs. The max/persistence ordering and failure behavior remain unchanged: persist before returning accepted permission or clearing applied recovery. No deprecated alias remains in this prototype; external/custom DeltaJournal implementations must update their method name and rebuild. Storage formats/digests are unchanged.

## R4 single recovery model
SessionGate no longer has pending, stage or acknowledge, nor a pending compatibility check. It only tracks transport, an explicit grant, expiry and snapshot-loaded state. PendingEdit's declaration moves beside RecoveryEdit/RecoveryController; its package, fields and persisted encoding are unchanged. RecoveryController remains the single owner of pending compatibility, protected staging, exact receipt reconciliation and explicitly confirmed discard/history recovery.

The old synthetic gate acknowledgement test is removed with that API. Its weaker session/name matching cannot be mistaken for the persisted receipt path. Gate tests now focus on reconnect without renewal, expiry, explicit suspension/fresh grant/snapshot and rejecting expired/replacement grants. Existing RecoveryController tests still check pending retention, identity/incarnation/revision mismatch, exact receipt binding, duplicate/unrelated replies, failure paths and confirmed discard. Windows DPAPI/history/quarantine/ownership and separate-process recovery coverage remains required.

## Remaining scope
Windows CI passed for implementation commit 3b18d1f35d4480eef4d17ed404e3ca11de644343: [run 38248570839](https://github.com/Jammycheek/Every_Day_is_a_Good_Article-desktop/actions/runs/38248570839). The 64-test Kotlin suite, two native tests, three-JVM DPAPI recovery/floor smoke, Java 17 manual-driver compilation, native self-test and CLI packaging succeeded. The prior 65-test suite loses only the retired gate-acknowledgement test; its recovery compatibility test becomes an explicit session/snapshot lifecycle test. Persisted controller recovery coverage remains intact. Prefix selection was inspected in code; no new power-loss/orphan cleanup trial is claimed. Subsequent commit updates documentation only.

Actual confirmation UI, authenticated fresh request/status channel, authoritative incarnation/epoch/operation lifecycle, phone lock/atomic apply, retention and power-loss durability remain open. This cleanup does not enable real data or establish private Android safety. No phone connection, phone file change, Android source, E: access or main merge is part of it.
