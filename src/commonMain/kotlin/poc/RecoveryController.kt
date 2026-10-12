package poc

data class RecoveryEdit(val metadata: PendingEdit, val syntheticDelta: String) {
    /** Derived from the persisted identity, revision, operation and payload; not a signature. */
    val contentDigest: String get() = recoveryContentDigest(metadata, syntheticDelta)
}
internal expect fun recoveryContentDigest(metadata: PendingEdit, syntheticDelta: String): String

/** Only pass a verified application applied receipt from a future authenticated channel.
 * Construction is NOT authentication. Device denotes the synthetic application/data identity.
 */
data class AppliedReceipt(
    val device: String,
    val baseRevision: Long,
    val operation: String,
    val contentDigest: String,
    val resultingRevision: Long
)
interface DeltaJournal {
    fun load(): RecoveryEdit?
    fun save(edit: RecoveryEdit)
    fun clear()
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
        check(edit == null || (edit.metadata.device == grant.device && edit.metadata.baseRevision == grant.baseRevision))
        gate.acceptGrant(grant, now)
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
        val edit = RecoveryEdit(PendingEdit(grant.device, grant.baseRevision, operation), syntheticDelta)
        try {
            journal.save(edit) // persist BEFORE reporting the synthetic edit as staged
        } catch (failure: Exception) {
            suspendSession()
            throw failure
        }
    }
    /** Recovery remains a pending proposal. Never automatically replay it to the phone. */
    fun recoveryCandidate(device: String, revision: Long): RecoveryEdit? {
        val edit = readJournal() ?: return null
        check(edit.metadata.device == device && edit.metadata.baseRevision == revision)
        return edit
    }
    /** Read-only outcome reconciliation needs no transport or editing grant. Never grants editing.
     * Query/authenticate the outcome BEFORE acquiring a grant at the resulting revision.
     */
    fun reconcileApplied(receipt: AppliedReceipt) {
        suspendSession() // even a failed/mismatched reconciliation cannot leave editing enabled
        val edit = readJournal() ?: error("No pending edit")
        check(receipt.device == edit.metadata.device && receipt.baseRevision == edit.metadata.baseRevision)
        check(receipt.operation == edit.metadata.operation && receipt.contentDigest == edit.contentDigest)
        check(receipt.resultingRevision > receipt.baseRevision)
        journal.clear() // only the exact persisted operation's trusted applied receipt permits clearing
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
