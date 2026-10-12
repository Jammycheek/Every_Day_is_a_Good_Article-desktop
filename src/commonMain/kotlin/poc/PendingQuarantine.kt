package poc

/** Process-local one-use confirmation; the UI must obtain affirmative consent. */
class PendingQuarantineConfirmation internal constructor()
data class PendingQuarantinePreview(
    val ciphertextSize: Long,
    val reason: RecoveryFailureReason,
    val confirmation: PendingQuarantineConfirmation,
    val consequence: String = "This pending edit may never be recoverable. Quarantine preserves ciphertext but abandons local replay; it does not cancel or prove application on the phone.",
    val guidance: String = "Check the previous password and Windows account/key recovery options before confirming. You can export the quarantined ciphertext without decrypting it."
)
enum class PendingQuarantineResult { QUARANTINED, INVALID_CONFIRMATION, CHANGED, NO_PENDING, READABLE }
data class PendingQuarantineOutcome(val result: PendingQuarantineResult, val archiveId: String? = null)
interface PendingQuarantineJournal : DeltaJournal {
    fun preparePendingQuarantine(): PendingQuarantinePreview?
    fun cancelPendingQuarantine(confirmation: PendingQuarantineConfirmation)
    fun quarantinePending(confirmation: PendingQuarantineConfirmation): PendingQuarantineOutcome
    fun pendingHistoryIncomplete(): Boolean
}
