package poc

/** Process-local one-use capability, not proof of user consent or peer authentication. */
class HistoryRecoveryConfirmation internal constructor()
enum class HistoryRecoveryKind { FORGET_IDENTITY, QUARANTINE_ALL }
data class HistoryRecoveryPreview(
    val kind: HistoryRecoveryKind,
    val identity: DatasetIdentity?,
    val previousFloor: Long?,
    val confirmation: HistoryRecoveryConfirmation
)
enum class HistoryRecoveryResult { RECOVERED, INVALID_CONFIRMATION, CHANGED }

/** Optional protected-storage recovery adapter. Calls remain serialized under directory ownership. */
interface RevisionHistoryRecoveryJournal : DeltaJournal {
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
