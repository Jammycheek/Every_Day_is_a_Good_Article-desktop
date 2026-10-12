package poc

enum class TransportState { USB_MTP_READY, NONE, AMBIGUOUS, UNAVAILABLE, ERROR }
data class TransportObservation(val state: TransportState, val localSelector: String? = null) {
    init { require(state != TransportState.USB_MTP_READY || !localSelector.isNullOrBlank()) }
}

/** Calls and the controller are owned by one event loop. Transport NEVER creates a grant. */
class TransportCoordinator(private val controller: RecoveryController, private val freshnessMillis: Long = 3000) {
    init { require(freshnessMillis > 0) }
    var state = TransportState.ERROR
        private set
    private var sequence = -1L
    private var checkedAt: Long? = null
    private var selector: String? = null

    fun observe(request: Long, observation: TransportObservation, startedAt: Long, now: Long) {
        if (request <= sequence) return // a late old result must not re-enable transport
        sequence = request
        if (startedAt > now || now - startedAt >= freshnessMillis) {
            revoke(); return
        }
        if (observation.state != TransportState.USB_MTP_READY || selector != observation.localSelector)
            controller.observeTransport(false)
        state = observation.state
        selector = if (state == TransportState.USB_MTP_READY) observation.localSelector else null
        checkedAt = startedAt
        controller.observeTransport(state == TransportState.USB_MTP_READY)
    }
    fun tick(now: Long) {
        val time = checkedAt
        if (time == null || now < time || now - time >= freshnessMillis) revoke()
        controller.tick()
    }
    fun stop() { revoke() }
    private fun revoke() {
        controller.observeTransport(false)
        state = TransportState.ERROR
        checkedAt = null
        selector = null
    }
}
