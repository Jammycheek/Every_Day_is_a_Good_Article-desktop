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

/**
 * V3: guidance depends on why protected data could not be read. These are hints from Windows error codes,
 * never proof of corruption; the UI translates them, and every option keeps the original ciphertext.
 */
fun recoveryGuidance(reason: RecoveryFailureReason?): String = when (reason) {
    RecoveryFailureReason.KEY_STATE -> "Windows reported a key-state problem. If the password was reset, sign in with or restore the previous password/account key first; the ciphertext may become readable again."
    RecoveryFailureReason.DATA_REJECTED -> "Windows rejected the protected bytes. They may be damaged or protected by another account/PC; the original ciphertext is retained."
    RecoveryFailureReason.INVALID_FORMAT -> "The data decrypted but its format is invalid (damage or an incompatible app version). Updating the app does not require quarantine."
    RecoveryFailureReason.LEGACY_REIMPORT_BLOCKED -> "A history migration was interrupted. Old history is kept but not imported automatically; quarantine only after reviewing."
    RecoveryFailureReason.MIXED, null -> "Repeated reads failed for different reasons. Check the Windows account and try again before quarantining."
}

/** V1: shown before any encrypted export. */
const val EXPORT_GUIDANCE = "The exported file stays DPAPI-encrypted. It is not readable on another PC or account; restoration needs this Windows account and its original key."
