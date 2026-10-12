package poc

/** Old test timelines use an absolute LOCAL fixture deadline, never a peer clock. */
internal data class FixtureGrant(val device: String, val session: String, val baseRevision: Long,
    val deadline: Long, val datasetInstance: String)
internal class TestClock(var now: Long = 0)
private val clocks = mutableMapOf<RecoveryController, TestClock>()
internal fun testController(journal: DeltaJournal): RecoveryController {
    val clock = TestClock()
    return RecoveryController(journal) { clock.now }.also { clocks[it] = clock }
}
internal fun RecoveryController.setTestTime(now: Long) { clocks.getValue(this).now = now }
internal fun RecoveryController.beginGrantRequest(identity: DatasetIdentity, now: Long, maximumDuration: Long,
    safetyAllowance: Long, renewal: Boolean = false, rebaseline: Boolean = false): PendingGrantRequest {
    setTestTime(now); return beginGrantRequest(identity, maximumDuration, safetyAllowance, renewal, rebaseline)
}
internal fun RecoveryController.acceptGrant(grant: Grant, request: PendingGrantRequest, now: Long): GrantAcceptanceResult {
    setTestTime(now); return acceptGrant(grant, request)
}
internal fun RecoveryController.acceptFreshHistoryBaseline(grant: Grant, request: PendingGrantRequest, now: Long): GrantAcceptanceResult {
    setTestTime(now); return acceptFreshHistoryBaseline(grant, request)
}
internal fun RecoveryController.canEdit(now: Long): Boolean { setTestTime(now); return canEdit() }
internal fun RecoveryController.tick(now: Long) { setTestTime(now); tick() }
internal fun RecoveryController.loadSyntheticSnapshot(bytes: ByteArray, now: Long) { setTestTime(now); loadSyntheticSnapshot(bytes) }
internal fun RecoveryController.stage(operation: String, delta: String, now: Long) { setTestTime(now); stage(operation, delta) }
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
