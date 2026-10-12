package poc
import kotlin.jvm.JvmOverloads

/** Null incarnation means a preserved legacy record, never an inferred dataset identity. */
data class PendingEdit(val device: String, val baseRevision: Long, val operation: String,
    val datasetInstance: String? = null)

/** [submitted] is persisted before the edit is sent: a submitted edit is never edited again, only reconciled. */
data class RecoveryEdit(val metadata: PendingEdit, val syntheticDelta: String, val submitted: Boolean = false) {
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
    /** Atomically retain both maxima before activating a grant. */
    fun recordVerifiedGrant(identity: DatasetIdentity, revision: Long, epoch: Long)
    /** Replace the pending record of the same operation. Implementations should make this atomic. */
    fun replace(edit: RecoveryEdit) { clear(); save(edit) }
}

/** Serialized calls only. Grants/receipts must already be authenticated by a future private channel. */
class RecoveryController @JvmOverloads constructor(private val journal: DeltaJournal, private val clock: () -> Long = ::localMonotonicMillis) {
    private val gate = SessionGate()
    private var snapshot: ByteArray? = null
    private var discardPreview: PendingDiscardPreview? = null
    val hasSnapshot: Boolean get() = snapshot != null
    val pending: RecoveryEdit? get() = readJournal()

    fun observeTransport(available: Boolean) {
        gate.observeTransport(available)
        if (!available) releaseSnapshot()
    }
    fun beginGrantRequest(identity: DatasetIdentity, maximumDuration: Long, safetyAllowance: Long,
        renewal: Boolean = false, rebaseline: Boolean = false): PendingGrantRequest {
        val now = clock()
        val live = canEditAt(now)
        // A mistaken acquisition must not abandon a valid session or its pending renewal.
        check(renewal || !live) { "Suspend before acquiring a new session" }
        return try {
            val epoch = journal.verifiedEpoch(identity)
            val minimum = if (renewal) {
                check(live); gate.grant!!.epoch
            } else {
                check(epoch != Long.MAX_VALUE) { "Epoch exhausted" }
                (epoch ?: -1) + 1
            }
            gate.prepare(identity, now, maximumDuration, safetyAllowance, minimum, renewal, rebaseline)
        } catch (failure: Exception) { suspendSession(); throw failure }
    }
    fun cancelGrantRequest(request: PendingGrantRequest) { gate.cancel(request) }
    fun acceptGrant(grant: Grant, request: PendingGrantRequest): GrantAcceptanceResult =
        acceptGrantInternal(grant, request, false)
    /** An explicit local admission policy; authentication is still the carrier's responsibility. */
    fun acceptFreshHistoryBaseline(grant: Grant, request: PendingGrantRequest): GrantAcceptanceResult =
        acceptGrantInternal(grant, request, true)
    private fun acceptGrantInternal(grant: Grant, request: PendingGrantRequest, rebaseline: Boolean): GrantAcceptanceResult {
        // Do not consume the current request, read storage, or suspend on a delayed/unrelated reply.
        if (!gate.matches(grant, request)) return GrantAcceptanceResult.IGNORED_UNRELATED
        try {
            val now = clock()
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
            journal.recordVerifiedGrant(identity, grant.baseRevision, grant.epoch)
            if (rebaseline) recovery!!.finishHistoryRebaseline(identity)
            val completedAt = clock()
            check(completedAt >= now)
            if (request.kind == GrantRequestKind.RENEW) check(gate.canEdit(completedAt))
            gate.activate(grant, expires, completedAt)
            return GrantAcceptanceResult.ACCEPTED
        } catch (failure: Exception) { suspendSession(); throw failure }
    }
    val revisionHistoryIncomplete: Boolean get() = historyCall { historyIsIncomplete() }
    fun revisionHistoryNeedsBaseline(identity: DatasetIdentity): Boolean = historyCall { historyRecoveryRequired(identity) }
    fun verifiedHistoryEntries(): Map<DatasetIdentity, VerifiedHistoryEntry> = historyCall { verifiedHistoryEntries() }
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
    val pendingHistoryIncomplete: Boolean get() = pendingQuarantineCall { pendingHistoryIncomplete() }
    fun preparePendingQuarantine(): PendingQuarantinePreview? = pendingQuarantineCall { preparePendingQuarantine() }
    fun cancelPendingQuarantine(confirmation: PendingQuarantineConfirmation) {
        pendingQuarantineCall { cancelPendingQuarantine(confirmation) }
    }
    /** Explicit maintenance: no transport/grant required and never enables or replays editing. */
    fun quarantinePending(confirmation: PendingQuarantineConfirmation): PendingQuarantineOutcome {
        suspendSession(); discardPreview = null
        return pendingQuarantineCall { quarantinePending(confirmation) }
    }
    private fun <T> pendingQuarantineCall(action: PendingQuarantineJournal.() -> T): T = try {
        (journal as? PendingQuarantineJournal ?: error("Pending quarantine unsupported")).action()
    } catch (failure: Exception) { suspendSession(); throw failure }
    private fun <T> historyCall(action: RevisionHistoryRecoveryJournal.() -> T): T = try {
        (journal as? RevisionHistoryRecoveryJournal ?: error("History recovery unsupported")).action()
    } catch (failure: Exception) { suspendSession(); throw failure }
    fun loadSyntheticSnapshot(bytes: ByteArray) {
        val now = clock()
        check(canEditAt(now))
        releaseSnapshot()
        gate.loadSnapshot(now)
        snapshot = bytes.copyOf()
    }
    fun canEdit(): Boolean = canEditAt(clock())
    private fun canEditAt(now: Long): Boolean {
        val allowed = gate.canEdit(now)
        if (!allowed) releaseSnapshot()
        return allowed
    }
    /** Call periodically from an event loop to expire leases even when the user is idle. */
    fun tick() { canEdit() }
    fun stage(operation: String, syntheticDelta: String) {
        check(canEdit() && hasSnapshot)
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
    /** The live grant, or null when editing is not currently permitted. */
    val activeGrant: Grant? get() = if (canEdit()) gate.grant else null
    /** Milliseconds until the conservative local deadline, or null without a live grant. */
    fun leaseRemainingMillis(): Long? { val now = clock(); return if (canEditAt(now)) gate.deadlineAt - now else null }

    /**
     * Autosave: create the staged edit, or replace the text of the same not-yet-submitted operation.
     * Requires a live grant and snapshot, exactly like [stage].
     */
    fun restage(operation: String, syntheticDelta: String) {
        check(canEdit() && hasSnapshot)
        val current = readJournal() ?: return stage(operation, syntheticDelta)
        val grant = gate.grant!!
        check(!current.submitted && current.metadata == PendingEdit(grant.device, grant.baseRevision, operation, grant.datasetInstance))
        require(syntheticDelta.isNotEmpty())
        try { journal.replace(current.copy(syntheticDelta = syntheticDelta)) }
        catch (failure: Exception) { suspendSession(); throw failure }
    }

    /** Persist the submitted marker BEFORE the edit leaves the PC; afterwards only reconciliation may clear it. */
    fun markSubmitted(): RecoveryEdit {
        check(canEdit() && hasSnapshot)
        val current = readJournal() ?: error("No pending edit")
        val grant = gate.grant!!
        check(current.metadata.baseRevision == grant.baseRevision && current.metadata.datasetInstance == grant.datasetInstance)
        if (current.submitted) return current
        val submitted = current.copy(submitted = true)
        try { journal.replace(submitted) } catch (failure: Exception) { suspendSession(); throw failure }
        return submitted
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
