package poc

/** Process-local one-use capability, not proof of user consent or peer authentication. */
class HistoryRecoveryConfirmation internal constructor()
enum class RecoveryFailureReason { DATA_REJECTED, KEY_STATE, INVALID_FORMAT, LEGACY_REIMPORT_BLOCKED, MIXED }

enum class HistoryRecoveryKind { FORGET_IDENTITY, QUARANTINE_ALL }
data class HistoryRecoveryPreview(
    val kind: HistoryRecoveryKind,
    val identity: DatasetIdentity?,
    val previousFloor: Long?,
    val confirmation: HistoryRecoveryConfirmation,
    val previousEpoch: Long? = null,
    val consequence: String = "Forget revision and epoch history: older revisions and generations may be accepted after explicit rebaseline.",
    val reason: RecoveryFailureReason? = null,
    val guidance: String = "Before quarantining, check the previous password and Windows account/key recovery options; the original ciphertext is retained."
)
data class VerifiedHistoryEntry(val revisionFloor: Long?, val epoch: Long?)

enum class HistoryRecoveryResult { RECOVERED, INVALID_CONFIRMATION, CHANGED }

/** Optional protected-storage recovery adapter. Calls remain serialized under directory ownership. */
interface RevisionHistoryRecoveryJournal : DeltaJournal {
    /** Includes epoch-only legacy identities so the user can choose them for recovery. */
    fun verifiedHistoryEntries(): Map<DatasetIdentity, VerifiedHistoryEntry>
    fun revisionHistoryEntries(): Map<DatasetIdentity, Long>
    fun prepareForgetHistory(identity: DatasetIdentity): HistoryRecoveryPreview?
    fun prepareQuarantineHistory(): HistoryRecoveryPreview
    fun cancelHistoryRecovery(confirmation: HistoryRecoveryConfirmation)
    fun recoverHistory(confirmation: HistoryRecoveryConfirmation): HistoryRecoveryResult
    fun historyRecoveryRequired(identity: DatasetIdentity): Boolean
    fun historyIsIncomplete(): Boolean
    /** Called only after a freshly authenticated current grant has been validated and persisted. */
    fun finishHistoryRebaseline(identity: DatasetIdentity)
}
