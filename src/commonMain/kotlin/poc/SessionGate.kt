package poc

/** Synthetic PoC only: callers must authenticate grants outside this state machine. */
data class DatasetIdentity(val device: String, val incarnation: String) {
    init {
        require(device.isNotBlank() && incarnation.isNotBlank())
        requireWellFormedUtf16(device); requireWellFormedUtf16(incarnation)
    }
}
data class Grant(val device: String, val session: String, val baseRevision: Long, val expiresAt: Long,
    val datasetInstance: String)
/** Null incarnation means a preserved legacy record, never an inferred dataset identity. */
data class PendingEdit(val device: String, val baseRevision: Long, val operation: String,
    val datasetInstance: String? = null)

/** A transport observation NEVER creates or renews an editing grant. */
class SessionGate {
    var transportAvailable = false
        private set
    var grant: Grant? = null
        private set
    var snapshotLoaded = false
        private set
    var pending: PendingEdit? = null
        private set

    fun observeTransport(available: Boolean) {
        transportAvailable = available
        if (!available) suspendSession()
    }

    fun acceptGrant(value: Grant, now: Long) {
        require(transportAvailable && value.expiresAt > now)
        require(value.device.isNotBlank() && value.session.isNotBlank() && value.baseRevision >= 0)
        DatasetIdentity(value.device, value.datasetInstance)
        check(grant == null)
        check(pending == null || (pending!!.device == value.device && pending!!.baseRevision == value.baseRevision &&
            pending!!.datasetInstance == value.datasetInstance))
        grant = value
    }

    fun loadSnapshot(now: Long) {
        check(canEdit(now))
        snapshotLoaded = true
    }

    fun canEdit(now: Long): Boolean {
        val current = grant ?: return false
        if (now >= current.expiresAt) {
            suspendSession()
            return false
        }
        return transportAvailable
    }

    fun stage(operation: String, now: Long) {
        check(canEdit(now) && snapshotLoaded)
        require(operation.isNotBlank())
        check(pending == null) // one synthetic operation at a time
        val current = grant!!
        pending = PendingEdit(current.device, current.baseRevision, operation, current.datasetInstance)
    }

    /** Call only for an authenticated application-level applied acknowledgement. */
    fun acknowledge(device: String, session: String, operation: String, now: Long) {
        check(canEdit(now))
        val current = grant!!
        val edit = pending ?: error("No pending edit")
        check(current.device == device && current.session == session && edit.operation == operation)
        pending = null
        suspendSession() // obtain a fresh snapshot and revision before the next edit
    }

    fun suspendSession() {
        grant = null
        snapshotLoaded = false
        // Only the synthetic unacknowledged delta survives. Durable protection is not implemented.
    }
}
