package poc

data class DatasetIdentity(val device: String, val incarnation: String) {
    init {
        require(device.isNotBlank() && incarnation.isNotBlank())
        requireWellFormedUtf16(device); requireWellFormedUtf16(incarnation)
    }
}
/** Synthetic wire model, not authentication. Duration starts at phone receipt of the bound request. */
data class Grant(val device: String, val session: String, val baseRevision: Long, val validityDuration: Long,
    val datasetInstance: String, val requestNonce: String, val epoch: Long)

enum class GrantRequestKind { ACQUIRE, RENEW }
enum class GrantAcceptanceResult { ACCEPTED, IGNORED_UNRELATED }
/** Local capability: never reconstructed from a peer message or persisted across restart. */
class PendingGrantRequest internal constructor(val identity: DatasetIdentity, val requestNonce: String,
    val sentAt: Long, val maximumDuration: Long, val safetyAllowance: Long, val minimumEpoch: Long,
    val kind: GrantRequestKind, val session: String?, val rebaseline: Boolean)
internal expect fun freshRequestNonce(): String
internal expect fun localMonotonicMillis(): Long

/** A transport observation NEVER creates or renews an editing grant. */
internal class SessionGate {
    var transportAvailable = false
        private set
    var grant: Grant? = null
        private set
    var snapshotLoaded = false
        private set
    private var pending: PendingGrantRequest? = null
    private var deadline = 0L
    private var lastTime: Long? = null
    private fun observeTime(now: Long) {
        val previous = lastTime
        if (previous != null && now < previous) {
            suspendSession(); throw IllegalStateException("Local monotonic clock moved backwards")
        }
        lastTime = now
    }

    fun observeTransport(available: Boolean) {
        transportAvailable = available
        if (!available) suspendSession()
    }

    fun prepare(identity: DatasetIdentity, now: Long, maximumDuration: Long, allowance: Long,
        minimumEpoch: Long, renewal: Boolean, rebaseline: Boolean): PendingGrantRequest {
        observeTime(now)
        require(transportAvailable && maximumDuration > 0 && allowance >= 0 && allowance < maximumDuration)
        require(now <= Long.MAX_VALUE - maximumDuration && minimumEpoch >= 0)
        if (renewal) check(canEdit(now) && !rebaseline) { "Cannot renew an abandoned session" }
        else { canEdit(now); check(grant == null) { "Suspend before acquiring a new session" } }
        if (renewal) check(identity == DatasetIdentity(grant!!.device, grant!!.datasetInstance))
        return PendingGrantRequest(identity, freshRequestNonce(), now, maximumDuration, allowance,
            minimumEpoch, if (renewal) GrantRequestKind.RENEW else GrantRequestKind.ACQUIRE,
            if (renewal) grant!!.session else null, rebaseline).also { pending = it }
    }
    fun validate(value: Grant, request: PendingGrantRequest, now: Long): Long {
        observeTime(now)
        check(pending === request) { "Request was replaced, consumed or abandoned" }
        pending = null // a local attempt is one use, including a rejected reply
        require(transportAvailable && value.requestNonce == request.requestNonce)
        require(DatasetIdentity(value.device, value.datasetInstance) == request.identity)
        require(value.session.isNotBlank() && value.baseRevision >= 0 && value.epoch >= request.minimumEpoch)
        requireWellFormedUtf16(value.session)
        require(value.validityDuration > request.safetyAllowance && value.validityDuration <= request.maximumDuration)
        val expires = request.sentAt + value.validityDuration - request.safetyAllowance
        require(now >= request.sentAt && now < expires) { "Reply arrived beyond the conservative deadline" }
        if (request.kind == GrantRequestKind.RENEW) {
            check(canEdit(now)) { "Renewal cannot revive an expired session" }
            val active = grant!!
            require(value.session == request.session && value.epoch == active.epoch &&
                value.baseRevision == active.baseRevision) { "Renewal must retain session/epoch/snapshot revision" }
            require(expires > deadline) { "Renewal must extend the deadline" }
        } else check(grant == null)
        return expires
    }
    /** Correlation only; the carrier must authenticate replies before dispatch. */
    fun matches(value: Grant, request: PendingGrantRequest): Boolean =
        pending === request && value.requestNonce == request.requestNonce
    fun activate(value: Grant, expires: Long, now: Long) {
        observeTime(now)
        require(transportAvailable && now < expires)
        grant = value; deadline = expires
    }
    fun cancel(request: PendingGrantRequest) { if (pending === request) pending = null }

    fun loadSnapshot(now: Long) {
        check(canEdit(now))
        snapshotLoaded = true
    }

    fun canEdit(now: Long): Boolean {
        try { observeTime(now) } catch (_: IllegalStateException) { return false }
        if (grant == null) return false
        if (now >= deadline) {
            suspendSession()
            return false
        }
        return transportAvailable
    }

    fun suspendSession() {
        grant = null
        pending = null
        snapshotLoaded = false
    }
}
