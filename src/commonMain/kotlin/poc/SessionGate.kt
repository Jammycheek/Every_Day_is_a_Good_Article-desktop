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

/** A transport observation NEVER creates or renews an editing grant. */
class SessionGate {
    var transportAvailable = false
        private set
    var grant: Grant? = null
        private set
    var snapshotLoaded = false
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

    fun suspendSession() {
        grant = null
        snapshotLoaded = false
    }
}
