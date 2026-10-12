package poc
import kotlin.jvm.JvmOverloads

/** Null incarnation means a preserved legacy record, never an inferred dataset identity. */
data class PendingEdit(val device: String, val baseRevision: Long, val operation: String,
    val datasetInstance: String? = null)

data class RecoveryEdit(val metadata: PendingEdit, val syntheticDelta: String) {
    init {
        requireWellFormedUtf16(metadata.device)
        requireWellFormedUtf16(metadata.operation)
        metadata.datasetInstance?.let { DatasetIdentity(metadata.device, it) }
        requireWellFormedUtf16(syntheticDelta)
    }
    /** Derived from the persisted identity, revision, operation and payload; not a signature. */
    val contentDigest: String get() = recoveryContentDigest(metadata, syntheticDelta)
}
internal expect fun recoveryContentDigest(metadata: PendingEdit, syntheticDelta: String): String

/** Reject replacement-prone strings before persistence or UTF-8 digest encoding. */
internal fun requireWellFormedUtf16(value: String) {
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        when (code) {
            in 0xD800..0xDBFF -> {
                require(index + 1 < value.length && value[index + 1].code in 0xDC00..0xDFFF) {
                    "Unpaired UTF-16 surrogate"
                }
                index += 2
            }
            in 0xDC00..0xDFFF -> throw IllegalArgumentException("Unpaired UTF-16 surrogate")
            else -> index++
        }
    }
}

enum class ReconciliationResult { CLEARED, NO_PENDING, NOT_THIS_OPERATION, CONFLICT }

/** Opaque, process-local, one-use capability. Not evidence of human confirmation by itself. */
class PendingDiscardConfirmation internal constructor()
data class PendingDiscardPreview(
    val metadata: PendingEdit,
    val contentDigest: String,
    val confirmation: PendingDiscardConfirmation
)
enum class PendingDiscardResult { DISCARDED, NO_PENDING, INVALID_CONFIRMATION, CHANGED }

/** Only pass a verified application applied receipt from a future authenticated channel.
 * Construction is NOT authentication. Device denotes the synthetic application/data identity.
 */
data class AppliedReceipt(
    val device: String,
    val baseRevision: Long,
    val operation: String,
    val contentDigest: String,
    val resultingRevision: Long,
    val datasetInstance: String
)
interface DeltaJournal {
    fun load(): RecoveryEdit?
    fun save(edit: RecoveryEdit)
    fun clear()
    fun revisionFloor(identity: DatasetIdentity): Long?
    /** Persist max(existing, verified revision) for a trusted grant or applied receipt.
     * Complete before reporting grant acceptance or deleting pending recovery.
     */
    fun recordVerifiedRevision(identity: DatasetIdentity, revision: Long)
    fun verifiedEpoch(identity: DatasetIdentity): Long?
    fun recordVerifiedEpoch(identity: DatasetIdentity, epoch: Long)
}

/** Serialized calls only. Grants/receipts must already be authenticated by a future private channel. */
class RecoveryController @JvmOverloads constructor(private val journal: DeltaJournal, private val storageClock: () -> Long = ::localMonotonicMillis) {
    private val gate = SessionGate()
    private var snapshot: ByteArray? = null
    private var discardPreview: PendingDiscardPreview? = null
    val hasSnapshot: Boolean get() = snapshot != null
    val pending: RecoveryEdit? get() = readJournal()

    fun observeTransport(available: Boolean) {
        gate.observeTransport(available)
        if (!available) releaseSnapshot()
    }
    fun beginGrantRequest(identity: DatasetIdentity, now: Long, maximumDuration: Long, safetyAllowance: Long,
        renewal: Boolean = false, rebaseline: Boolean = false): PendingGrantRequest = try {
        canEdit(now) // expire/release the controller snapshot before starting another request
        val epoch = journal.verifiedEpoch(identity)
        val minimum = if (renewal) {
            check(canEdit(now)); gate.grant!!.epoch
        } else {
            check(epoch != Long.MAX_VALUE) { "Epoch exhausted" }
            (epoch ?: -1) + 1
        }
        gate.prepare(identity, now, maximumDuration, safetyAllowance, minimum, renewal, rebaseline)
    } catch (failure: Exception) { suspendSession(); throw failure }
    fun cancelGrantRequest(request: PendingGrantRequest) { gate.cancel(request) }
    fun acceptGrant(grant: Grant, request: PendingGrantRequest, now: Long) {
        acceptGrantInternal(grant, request, now, false)
    }
    /** Future caller MUST authenticate a new request/current revision, never reuse a cached grant.
     * This explicit synthetic path is not authentication or evidence of current phone state.
     */
    fun acceptFreshHistoryBaseline(grant: Grant, request: PendingGrantRequest, now: Long) {
        acceptGrantInternal(grant, request, now, true)
    }
    private fun acceptGrantInternal(grant: Grant, request: PendingGrantRequest, now: Long, rebaseline: Boolean) {
        try {
            val started = storageClock()
            val expires = gate.validate(grant, request, now)
            check(request.rebaseline == rebaseline)
            val edit = readJournal()
            val identity = DatasetIdentity(grant.device, grant.datasetInstance)
            val recovery = journal as? RevisionHistoryRecoveryJournal
            val required = recovery?.historyRecoveryRequired(identity) == true
            check(required == rebaseline) { "History recovery requires an explicit fresh baseline" }
            val floor = journal.revisionFloor(identity)
            check(floor == null || grant.baseRevision >= floor) { "Grant revision below verified floor" }
            check(edit == null || (edit.metadata.device == grant.device && edit.metadata.baseRevision == grant.baseRevision &&
                edit.metadata.datasetInstance == grant.datasetInstance))
            val epoch = journal.verifiedEpoch(identity)
            check(epoch == null || (if (request.kind == GrantRequestKind.RENEW) grant.epoch >= epoch else grant.epoch > epoch))
            // Serialized calls: do not report the grant accepted until its verified revision is retained.
            journal.recordVerifiedEpoch(identity, grant.epoch)
            journal.recordVerifiedRevision(identity, grant.baseRevision)
            if (rebaseline) recovery!!.finishHistoryRebaseline(identity)
            val finished = storageClock()
            check(finished >= started)
            val elapsed = finished - started
            check(elapsed >= 0 && now <= Long.MAX_VALUE - elapsed)
            val completedAt = now + elapsed
            if (request.kind == GrantRequestKind.RENEW) check(gate.canEdit(completedAt))
            gate.activate(grant, expires, completedAt)
        } catch (failure: Exception) { suspendSession(); throw failure }
    }
    val revisionHistoryIncomplete: Boolean get() = historyCall { historyIsIncomplete() }
    fun revisionHistoryNeedsBaseline(identity: DatasetIdentity): Boolean = historyCall { historyRecoveryRequired(identity) }
    fun revisionHistoryEntries(): Map<DatasetIdentity, Long> = historyCall { revisionHistoryEntries() }
    fun prepareForgetRevisionHistory(identity: DatasetIdentity): HistoryRecoveryPreview? =
        historyCall { prepareForgetHistory(identity) }
    fun prepareQuarantineRevisionHistory(): HistoryRecoveryPreview = historyCall { prepareQuarantineHistory() }
    fun cancelRevisionHistoryRecovery(confirmation: HistoryRecoveryConfirmation) {
        historyCall { cancelHistoryRecovery(confirmation) }
    }
    /** Caller must obtain affirmative consent for the preview's loss of rollback protection. */
    fun recoverRevisionHistory(confirmation: HistoryRecoveryConfirmation): HistoryRecoveryResult {
        // Suspend even for an invalid recovery request: this is an explicit maintenance operation.
        suspendSession(); discardPreview = null
        return historyCall { recoverHistory(confirmation) }
    }
    private fun <T> historyCall(action: RevisionHistoryRecoveryJournal.() -> T): T = try {
        (journal as? RevisionHistoryRecoveryJournal ?: error("History recovery unsupported")).action()
    } catch (failure: Exception) { suspendSession(); throw failure }
    fun loadSyntheticSnapshot(bytes: ByteArray, now: Long) {
        check(canEdit(now))
        releaseSnapshot()
        gate.loadSnapshot(now)
        snapshot = bytes.copyOf()
    }
    fun canEdit(now: Long): Boolean {
        val allowed = gate.canEdit(now)
        if (!allowed) releaseSnapshot()
        return allowed
    }
    /** Call periodically from an event loop to expire leases even when the user is idle. */
    fun tick(now: Long) { canEdit(now) }
    fun stage(operation: String, syntheticDelta: String, now: Long) {
        check(canEdit(now) && hasSnapshot)
        check(readJournal() == null)
        require(operation.isNotBlank() && syntheticDelta.isNotEmpty())
        val grant = gate.grant!!
        val edit = RecoveryEdit(PendingEdit(grant.device, grant.baseRevision, operation, grant.datasetInstance), syntheticDelta)
        try {
            discardPreview = null
            journal.save(edit) // persist BEFORE reporting the synthetic edit as staged
        } catch (failure: Exception) {
            suspendSession()
            throw failure
        }
    }
    /** Recovery remains a pending proposal. Never automatically replay it to the phone. */
    fun recoveryCandidate(device: String, revision: Long, datasetInstance: String): RecoveryEdit? {
        val edit = readJournal() ?: return null
        check(edit.metadata.device == device && edit.metadata.baseRevision == revision &&
            edit.metadata.datasetInstance == datasetInstance)
        return edit
    }
    /** Read-only outcome reconciliation needs no transport or editing grant. Never grants editing.
     * Query/authenticate the outcome BEFORE acquiring a grant at the resulting revision.
     */
    fun reconcileApplied(receipt: AppliedReceipt): ReconciliationResult {
        val edit = readJournal() ?: return ReconciliationResult.NO_PENDING
        if (receipt.device != edit.metadata.device || receipt.operation != edit.metadata.operation ||
            receipt.datasetInstance != edit.metadata.datasetInstance)
            return ReconciliationResult.NOT_THIS_OPERATION
        if (receipt.baseRevision != edit.metadata.baseRevision || receipt.contentDigest != edit.contentDigest ||
            receipt.resultingRevision <= receipt.baseRevision) {
            suspendSession()
            return ReconciliationResult.CONFLICT
        }
        suspendSession() // a matching applied result makes the current snapshot obsolete
        discardPreview = null
        journal.recordVerifiedRevision(DatasetIdentity(receipt.device, receipt.datasetInstance), receipt.resultingRevision)
        journal.clear() // applied-path clearing requires this exact trusted receipt; discard is a separate decision
        return ReconciliationResult.CLEARED
    }

    /** Readable pending edits only. Caller must show metadata/data-loss consequences before confirming.
     * Preparing/cancelling a preview does not authorize deletion or suspend an active session.
     */
    fun preparePendingDiscard(): PendingDiscardPreview? {
        discardPreview = null
        val edit = readJournal() ?: return null
        return PendingDiscardPreview(edit.metadata, edit.contentDigest, PendingDiscardConfirmation())
            .also { discardPreview = it }
    }

    fun cancelPendingDiscard(confirmation: PendingDiscardConfirmation) {
        if (discardPreview?.confirmation === confirmation) discardPreview = null
    }

    /** Call ONLY after explicit user confirmation of this preview. No transport/lease is required.
     * Discard is local abandonment, never evidence of apply or cancellation on the phone.
     */
    fun discardPending(confirmation: PendingDiscardConfirmation): PendingDiscardResult {
        val preview = discardPreview
        if (preview == null || preview.confirmation !== confirmation)
            return PendingDiscardResult.INVALID_CONFIRMATION
        discardPreview = null // consume even on read/delete failure; retry requires a new preview
        val edit = readJournal() ?: return PendingDiscardResult.NO_PENDING
        if (preview.metadata != edit.metadata || preview.contentDigest != edit.contentDigest)
            return PendingDiscardResult.CHANGED
        suspendSession()
        journal.clear() // revision-floor history is retained; failure propagates with editing stopped
        return PendingDiscardResult.DISCARDED
    }
    fun suspendSession() {
        gate.suspendSession()
        releaseSnapshot()
    }
    private fun readJournal(): RecoveryEdit? = try {
        journal.load()
    } catch (failure: Exception) {
        suspendSession()
        throw failure
    }
    private fun releaseSnapshot() {
        snapshot?.fill(0)
        snapshot = null
    }
}
