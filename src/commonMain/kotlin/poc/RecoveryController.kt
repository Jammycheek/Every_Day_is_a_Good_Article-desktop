package poc

data class RecoveryEdit(val metadata: PendingEdit, val syntheticDelta: String)
interface DeltaJournal {
    fun load(): RecoveryEdit?
    fun save(edit: RecoveryEdit)
    fun clear()
}

/** Serialized calls only. All grants/acks must already be authenticated by a future private channel. */
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
    fun acknowledge(device: String, session: String, operation: String, now: Long) {
        check(canEdit(now))
        val grant = gate.grant!!
        val edit = readJournal() ?: error("No pending edit")
        check(grant.device == device && grant.session == session && edit.metadata.operation == operation)
        try {
            journal.clear() // only after a validated applied ack; clear failure retains pending recovery
        } finally { suspendSession() }
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
