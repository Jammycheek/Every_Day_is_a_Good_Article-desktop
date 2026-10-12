package poc

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
    /** Persist max(existing, revision) before pending deletion; fail rather than forget history. */
    fun recordAppliedRevision(identity: DatasetIdentity, revision: Long)
}

/** Serialized calls only. Grants/receipts must already be authenticated by a future private channel. */
class RecoveryController(private val journal: DeltaJournal) {
    private val gate = SessionGate()
    private var snapshot: ByteArray? = null
    val hasSnapshot: Boolean get() = snapshot != null
    val pending: RecoveryEdit? get() = readJournal()

    fun observeTransport(available: Boolean) {
        gate.observeTransport(available)
        if (!available) releaseSnapshot()
    }
    fun acceptGrant(grant: Grant, now: Long) {
        val edit = readJournal()
        val identity = DatasetIdentity(grant.device, grant.datasetInstance)
        val floor = try { journal.revisionFloor(identity) } catch (failure: Exception) {
            suspendSession(); throw failure
        }
        check(floor == null || grant.baseRevision >= floor) { "Grant revision below verified floor" }
        check(edit == null || (edit.metadata.device == grant.device && edit.metadata.baseRevision == grant.baseRevision &&
            edit.metadata.datasetInstance == grant.datasetInstance))
        gate.acceptGrant(grant, now)
        try {
            // Serialized calls: do not report the grant accepted until its verified revision is retained.
            journal.recordAppliedRevision(identity, grant.baseRevision)
        } catch (failure: Exception) {
            suspendSession(); throw failure
        }
    }
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
        journal.recordAppliedRevision(DatasetIdentity(receipt.device, receipt.datasetInstance), receipt.resultingRevision)
        journal.clear() // only the exact persisted operation's trusted applied receipt permits clearing
        return ReconciliationResult.CLEARED
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
