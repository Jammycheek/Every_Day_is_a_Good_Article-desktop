package poc

/** Old test timelines use an absolute LOCAL fixture deadline, never a peer clock. */
internal data class FixtureGrant(val device: String, val session: String, val baseRevision: Long,
    val deadline: Long, val datasetInstance: String)
internal fun testController(journal: DeltaJournal) = RecoveryController(journal) { 0 }
private fun FixtureGrant.response(request: PendingGrantRequest) = Grant(device, session, baseRevision,
    deadline - request.sentAt, datasetInstance, request.requestNonce, request.minimumEpoch)
internal fun RecoveryController.acceptFixtureGrant(value: FixtureGrant, now: Long) {
    val request = beginGrantRequest(DatasetIdentity(value.device, value.datasetInstance), now, value.deadline - now, 0)
    acceptGrant(value.response(request), request, now)
}
internal fun RecoveryController.acceptFixtureBaseline(value: FixtureGrant, now: Long) {
    val request = beginGrantRequest(DatasetIdentity(value.device, value.datasetInstance), now, value.deadline - now, 0,
        rebaseline = true)
    acceptFreshHistoryBaseline(value.response(request), request, now)
}
internal fun SessionGate.acceptFixtureGrant(value: FixtureGrant, now: Long) {
    val request = prepare(DatasetIdentity(value.device, value.datasetInstance), now, value.deadline - now, 0, 0, false, false)
    val response = value.response(request)
    activate(response, validate(response, request, now), now)
}
